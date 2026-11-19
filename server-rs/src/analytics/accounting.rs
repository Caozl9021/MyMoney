use std::collections::HashMap;

use chrono::{DateTime, Datelike, Days, Duration, Months, NaiveDate, TimeZone, Utc};
use chrono_tz::Tz;
use serde::Serialize;

use crate::analytics::snapshot::{ParsedAccount, ParsedBill, ParsedEntry, ParsedSnapshot};
use crate::error::AppError;

pub fn parse_tz(tz_name: Option<&str>) -> Result<Tz, AppError> {
    let raw = tz_name.unwrap_or("Asia/Shanghai").trim();
    if raw.is_empty() {
        return Ok(chrono_tz::Asia::Shanghai);
    }
    raw.parse::<Tz>()
        .map_err(|_| AppError::unprocessable("时区参数无效"))
}

pub fn days_in_month(year: i32, month: u32) -> u32 {
    let next = if month == 12 {
        NaiveDate::from_ymd_opt(year + 1, 1, 1)
    } else {
        NaiveDate::from_ymd_opt(year, month + 1, 1)
    };
    next.expect("valid next month")
        .pred_opt()
        .expect("valid pred")
        .day()
}

/// 对齐 App `LedgerMonthPeriod.kt`：短月自动夹紧到月末。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct YearMonth {
    pub year: i32,
    pub month: u32,
}

impl YearMonth {
    pub fn from_date(d: NaiveDate) -> Self {
        Self {
            year: d.year(),
            month: d.month(),
        }
    }

    pub fn shift(self, delta_months: i32) -> Self {
        let total = self.year * 12 + (self.month as i32 - 1) + delta_months;
        let y = total.div_euclid(12);
        let m = (total.rem_euclid(12) + 1) as u32;
        Self { year: y, month: m }
    }

    pub fn at_clamped_day(self, day: u32) -> NaiveDate {
        let clamped = day.min(days_in_month(self.year, self.month));
        NaiveDate::from_ymd_opt(self.year, self.month, clamped).expect("valid date")
    }
}

#[derive(Clone, Copy, Debug)]
pub struct LedgerPeriod {
    pub month: YearMonth,
    pub day: u32,
}

impl LedgerPeriod {
    pub fn containing(date: NaiveDate, day: u32) -> Self {
        let ym = YearMonth::from_date(date);
        let start = ym.at_clamped_day(day);
        if date < start {
            Self {
                month: ym.shift(-1),
                day,
            }
        } else {
            Self { month: ym, day }
        }
    }

    pub fn shift(self, amount: i32) -> Self {
        Self {
            month: self.month.shift(amount),
            day: self.day,
        }
    }

    pub fn start_date(self) -> NaiveDate {
        self.month.at_clamped_day(self.day)
    }

    pub fn end_exclusive_date(self) -> NaiveDate {
        self.month.shift(1).at_clamped_day(self.day)
    }

    pub fn end_inclusive_date(self) -> NaiveDate {
        self.end_exclusive_date()
            .checked_sub_days(Days::new(1))
            .expect("valid end inclusive")
    }
}

pub fn local_midnight_millis(date: NaiveDate, tz: Tz) -> i64 {
    let naive = date.and_hms_opt(0, 0, 0).expect("midnight");
    tz.from_local_datetime(&naive)
        .earliest()
        .or_else(|| tz.from_local_datetime(&naive).latest())
        .expect("local midnight")
        .timestamp_millis()
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DateRangeInfo {
    pub start: String,
    pub end: String,
    pub from_millis: i64,
    pub to_millis: i64,
    pub days: i64,
    pub is_partial: bool,
    pub elapsed_days: i64,
    pub compare_start: String,
    pub compare_end: String,
    pub compare_from_millis: i64,
    pub compare_to_millis: i64,
    pub period_mode: String,
    pub effective_start_day: u32,
    pub label: String,
}

pub fn resolve_range(
    period_mode: &str,
    snapshot_month_start_day: u32,
    start_param: Option<&str>,
    end_param: Option<&str>,
    tz: Tz,
) -> Result<DateRangeInfo, AppError> {
    let mode = match period_mode {
        "calendar" => "calendar",
        _ => "ledger",
    };
    let effective_day = if mode == "calendar" {
        1
    } else {
        snapshot_month_start_day.clamp(1, 31)
    };

    let today = Utc::now().with_timezone(&tz).date_naive();

    let (start_date, end_inclusive, prev_start, prev_end_exclusive) = match (start_param, end_param)
    {
        (Some(s), Some(e)) => {
            let sd = NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d")
                .map_err(|_| AppError::unprocessable("开始日期格式必须为 YYYY-MM-DD"))?;
            let ed = NaiveDate::parse_from_str(e.trim(), "%Y-%m-%d")
                .map_err(|_| AppError::unprocessable("结束日期格式必须为 YYYY-MM-DD"))?;
            if sd > ed {
                return Err(AppError::unprocessable("开始日期不能晚于结束日期"));
            }
            let span = (ed - sd).num_days() + 1;
            if span > 3660 {
                return Err(AppError::unprocessable("查询时间跨度不能超过10年"));
            }
            // 如果刚好匹配某个周期，则上期按同口径移位1月；否则按等长前移
            let containing = LedgerPeriod::containing(sd, effective_day);
            if containing.start_date() == sd && containing.end_inclusive_date() == ed {
                let prev = containing.shift(-1);
                (sd, ed, prev.start_date(), prev.end_exclusive_date())
            } else {
                let prev_ed_excl = sd;
                let prev_sd = sd - Duration::days(span);
                (sd, ed, prev_sd, prev_ed_excl)
            }
        }
        (None, None) => {
            let current = LedgerPeriod::containing(today, effective_day);
            let prev = current.shift(-1);
            (
                current.start_date(),
                current.end_inclusive_date(),
                prev.start_date(),
                prev.end_exclusive_date(),
            )
        }
        _ => {
            return Err(AppError::unprocessable("start 和 end 必须成对传入"));
        }
    };

    let end_exclusive = end_inclusive
        .checked_add_days(Days::new(1))
        .ok_or_else(|| AppError::unprocessable("结束日期超出范围"))?;
    let total_days = (end_exclusive - start_date).num_days();

    let is_partial = today >= start_date && today < end_exclusive;
    let elapsed_days = if is_partial {
        ((today - start_date).num_days() + 1).clamp(1, total_days)
    } else {
        total_days
    };

    let compare_end_exclusive = if is_partial {
        let candidate = prev_start + Duration::days(elapsed_days);
        candidate.min(prev_end_exclusive)
    } else {
        prev_end_exclusive
    };
    let compare_end_inclusive = compare_end_exclusive
        .checked_sub_days(Days::new(1))
        .unwrap_or(prev_start);

    let from_millis = local_midnight_millis(start_date, tz);
    let to_millis = local_midnight_millis(end_exclusive, tz);
    let compare_from_millis = local_midnight_millis(prev_start, tz);
    let compare_to_millis = local_midnight_millis(compare_end_exclusive, tz);

    let label = if effective_day == 1
        && start_date.day() == 1
        && end_inclusive == YearMonth::from_date(start_date).at_clamped_day(31)
    {
        format!("{}年{}月", start_date.year(), start_date.month())
    } else {
        format!("{} – {}", start_date, end_inclusive)
    };

    Ok(DateRangeInfo {
        start: start_date.to_string(),
        end: end_inclusive.to_string(),
        from_millis,
        to_millis,
        days: total_days,
        is_partial,
        elapsed_days,
        compare_start: prev_start.to_string(),
        compare_end: compare_end_inclusive.to_string(),
        compare_from_millis,
        compare_to_millis,
        period_mode: mode.to_string(),
        effective_start_day: effective_day,
        label,
    })
}

#[derive(Clone, Copy, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeriodTotals {
    pub expense_cents: i64,
    pub income_cents: i64,
    pub refund_cents: i64,
    pub transfer_cents: i64,
    pub balance_cents: i64,
    pub expense_count: i64,
    pub income_count: i64,
    pub refund_count: i64,
    pub transfer_count: i64,
    pub total_count: i64,
    pub pending_reimburse_cents: i64,
    pub pending_reimburse_count: i64,
    pub reimbursed_cents: i64,
    pub reimbursed_count: i64,
}

/// 严格对齐 App `Reimburse.countsInExpense` 与 `LedgerTotals.balance`
pub fn compute_totals(bills: &[ParsedBill], from_ms: i64, to_ms: i64) -> PeriodTotals {
    let mut t = PeriodTotals::default();
    for b in bills {
        if b.time_millis < from_ms || b.time_millis >= to_ms {
            continue;
        }
        t.total_count += 1;
        match b.bill_type.as_str() {
            "EXPENSE" => {
                if b.counts_in_expense {
                    t.expense_cents += b.price_cents;
                    t.expense_count += 1;
                }
                if b.reimburse_status == "PENDING" {
                    t.pending_reimburse_cents += b.price_cents;
                    t.pending_reimburse_count += 1;
                } else if b.reimburse_status == "REIMBURSED" {
                    t.reimbursed_cents += b.price_cents;
                    t.reimbursed_count += 1;
                }
            }
            "INCOME" => {
                t.income_cents += b.price_cents;
                t.income_count += 1;
            }
            "REFUND" => {
                t.refund_cents += b.price_cents;
                t.refund_count += 1;
            }
            "TRANSFER" => {
                t.transfer_cents += b.price_cents;
                t.transfer_count += 1;
            }
            _ => {}
        }
    }
    t.balance_cents = t.income_cents - t.expense_cents + t.refund_cents;
    t
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeltaInfo {
    pub previous_cents: i64,
    pub previous_count: i64,
    pub diff_cents: i64,
    pub percent: Option<f64>,
    pub direction: String,
    pub tone: String,
    pub small_base: bool,
    pub baseline_label: String,
}

pub fn make_delta(
    current_cents: i64,
    prev_cents: i64,
    prev_count: i64,
    lower_is_better: bool,
    baseline_label: &str,
) -> DeltaInfo {
    let diff = current_cents - prev_cents;
    let small_base = prev_count < 5 || prev_cents == 0;
    let direction = if diff > 0 {
        "up"
    } else if diff < 0 {
        "down"
    } else {
        "flat"
    };

    if small_base {
        return DeltaInfo {
            previous_cents: prev_cents,
            previous_count: prev_count,
            diff_cents: diff,
            percent: None,
            direction: direction.to_string(),
            tone: "neutral".to_string(),
            small_base: true,
            baseline_label: baseline_label.to_string(),
        };
    }

    let raw_pct = (diff as f64 / prev_cents.abs() as f64) * 100.0;
    let pct = (raw_pct * 10.0).round() / 10.0;
    let tone = if diff == 0 {
        "neutral"
    } else if lower_is_better {
        if diff > 0 {
            "bad"
        } else {
            "good"
        }
    } else if diff > 0 {
        "good"
    } else {
        "bad"
    };

    DeltaInfo {
        previous_cents: prev_cents,
        previous_count: prev_count,
        diff_cents: diff,
        percent: Some(pct),
        direction: direction.to_string(),
        tone: tone.to_string(),
        small_base: false,
        baseline_label: baseline_label.to_string(),
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetSummaryDto {
    pub assets_cents: i64,
    pub debts_cents: i64,
    pub net_cents: i64,
    pub unknown_count: i32,
}

/// 严格对齐 App `Assets.kt` `AssetMath.summary`
pub fn compute_asset_summary(
    accounts: &[ParsedAccount],
    entries: &[ParsedEntry],
) -> (
    AssetSummaryDto,
    HashMap<String, i64>,
    HashMap<String, usize>,
) {
    let mut balances: HashMap<String, i64> = HashMap::new();
    let mut entry_counts: HashMap<String, usize> = HashMap::new();
    for e in entries {
        *balances.entry(e.account_id.clone()).or_insert(0) += e.delta_cents;
        *entry_counts.entry(e.account_id.clone()).or_insert(0) += 1;
    }

    let mut assets: i64 = 0;
    let mut debts: i64 = 0;
    let mut unknown: i32 = 0;

    for a in accounts {
        if !a.included {
            continue;
        }
        if !a.initialized {
            unknown += 1;
            continue;
        }
        let pos = *balances.get(&a.id).unwrap_or(&0);
        if pos >= 0 {
            assets += pos;
        } else {
            debts += -pos;
        }
    }

    (
        AssetSummaryDto {
            assets_cents: assets,
            debts_cents: debts,
            net_cents: assets - debts,
            unknown_count: unknown,
        },
        balances,
        entry_counts,
    )
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetTimelinePoint {
    pub date: String,
    pub timestamp_millis: i64,
    pub assets_cents: i64,
    pub debts_cents: i64,
    pub net_cents: i64,
    pub account_balances: HashMap<String, i64>,
}

/// 基于 `assets.entries` 时间轴回放历史净资产与各账户余额走势；最后一点保证等于当前快照实际余额。
pub fn compute_asset_timeline(
    snapshot: &ParsedSnapshot,
    from_ms: Option<i64>,
    to_ms: Option<i64>,
    tz: Tz,
) -> Vec<AssetTimelinePoint> {
    let initialized_accounts: Vec<&ParsedAccount> =
        snapshot.accounts.iter().filter(|a| a.initialized).collect();
    if initialized_accounts.is_empty() {
        return Vec::new();
    }

    let mut sorted_entries: Vec<&ParsedEntry> = snapshot.entries.iter().collect();
    sorted_entries.sort_by(|a, b| {
        a.created_at
            .cmp(&b.created_at)
            .then_with(|| a.id.cmp(&b.id))
    });

    let min_entry_ms = sorted_entries
        .first()
        .map(|e| e.created_at)
        .unwrap_or(snapshot.exported_at);
    let max_ms = to_ms.unwrap_or(snapshot.exported_at.max(min_entry_ms));
    let min_ms = from_ms.unwrap_or(min_entry_ms).min(max_ms);

    let start_date = DateTime::from_timestamp_millis(min_ms)
        .unwrap_or_else(Utc::now)
        .with_timezone(&tz)
        .date_naive();
    let end_date = DateTime::from_timestamp_millis(max_ms)
        .unwrap_or_else(Utc::now)
        .with_timezone(&tz)
        .date_naive();

    let total_days = (end_date - start_date).num_days().max(0);
    let mut sample_dates: Vec<(String, i64)> = Vec::new();
    if total_days <= 90 {
        let mut d = start_date;
        while d < end_date {
            let cutoff = local_midnight_millis(d.checked_add_days(Days::new(1)).unwrap_or(d), tz);
            sample_dates.push((d.to_string(), cutoff));
            d = d.checked_add_days(Days::new(1)).unwrap();
        }
    } else if total_days <= 400 {
        let mut d = start_date;
        while d < end_date {
            let next_d = (d + Duration::days(7)).min(end_date);
            let cutoff = local_midnight_millis(next_d, tz);
            sample_dates.push((d.to_string(), cutoff));
            d = next_d;
        }
    } else {
        let mut ym = YearMonth::from_date(start_date);
        let end_ym = YearMonth::from_date(end_date);
        while ym.year < end_ym.year || (ym.year == end_ym.year && ym.month < end_ym.month) {
            let next_ym = ym.shift(1);
            let next_start = NaiveDate::from_ymd_opt(next_ym.year, next_ym.month, 1).unwrap();
            let cutoff = local_midnight_millis(next_start, tz);
            let label = format!("{:04}-{:02}", ym.year, ym.month);
            sample_dates.push((label, cutoff));
            ym = next_ym;
        }
    }
    // 终结采样点精确覆盖到当前快照截止时刻
    sample_dates.push((end_date.to_string(), i64::MAX));

    let mut running: HashMap<String, i64> = initialized_accounts
        .iter()
        .map(|a| (a.id.clone(), 0i64))
        .collect();
    let mut entry_idx = 0usize;
    let mut points = Vec::with_capacity(sample_dates.len());

    for (label, cutoff) in sample_dates {
        while entry_idx < sorted_entries.len() && sorted_entries[entry_idx].created_at < cutoff {
            let e = sorted_entries[entry_idx];
            if let Some(bal) = running.get_mut(&e.account_id) {
                *bal += e.delta_cents;
            }
            entry_idx += 1;
        }

        let mut assets = 0i64;
        let mut debts = 0i64;
        for a in &initialized_accounts {
            if !a.included {
                continue;
            }
            let bal = *running.get(&a.id).unwrap_or(&0);
            if bal >= 0 {
                assets += bal;
            } else {
                debts += -bal;
            }
        }
        let ts = if cutoff == i64::MAX {
            snapshot.exported_at
        } else {
            cutoff - 1
        };
        points.push(AssetTimelinePoint {
            date: label,
            timestamp_millis: ts,
            assets_cents: assets,
            debts_cents: debts,
            net_cents: assets - debts,
            account_balances: running.clone(),
        });
    }

    points
}

#[allow(dead_code)]
fn _keep_months_import(_: Option<Months>) {}
