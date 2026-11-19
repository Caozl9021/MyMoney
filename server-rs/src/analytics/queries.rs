use std::collections::{BTreeMap, HashMap};

use chrono::{DateTime, Datelike, Days, Duration, NaiveDate, Timelike, Utc};
use chrono_tz::Tz;
use serde::Serialize;
use serde_json::{json, Value};

use crate::analytics::accounting::{
    compute_asset_summary, compute_asset_timeline, compute_totals, local_midnight_millis,
    make_delta, parse_tz, resolve_range, LedgerPeriod,
};
use crate::analytics::colors::swatch_for;
use crate::analytics::snapshot::{ParsedBill, ParsedSnapshot, SnapshotMeta};
use crate::error::AppError;

pub fn snapshot_meta_json(meta: &SnapshotMeta, snap: Option<&ParsedSnapshot>) -> Value {
    json!({
        "backupId": meta.backup_id.to_string(),
        "deviceId": meta.device_id.to_string(),
        "deviceName": meta.device_name,
        "appVersion": meta.app_version,
        "backupSchema": meta.backup_schema,
        "sha256": meta.sha256,
        "sizeBytes": meta.size_bytes,
        "billCount": meta.bill_count,
        "snapshotAt": meta.snapshot_at.to_rfc3339(),
        "uploadedAt": meta.uploaded_at.to_rfc3339(),
        "monthStartDay": snap.map(|s| s.month_start_day).unwrap_or(1),
        "overlayRevision": meta.overlay_revision,
        "overlayUpdatedAt": meta.overlay_updated_at.map(|t| t.to_rfc3339()),
        "overlayCount": meta.overlay_count,
        "accountCount": snap.map(|s| s.accounts.len()).unwrap_or(0),
        "entryCount": snap.map(|s| s.entries.len()).unwrap_or(0),
        "deletionCount": snap.map(|s| s.deletions.len()).unwrap_or(0),
    })
}

pub fn bill_to_json(b: &ParsedBill, tz: Tz) -> Value {
    let dt = DateTime::from_timestamp_millis(b.time_millis)
        .unwrap_or_else(Utc::now)
        .with_timezone(&tz);
    json!({
        "index": b.index,
        "localId": b.local_id,
        "syncId": b.sync_id,
        "priceCents": b.price_cents,
        "type": b.bill_type,
        "timeMillis": b.time_millis,
        "timeIso": dt.to_rfc3339(),
        "date": dt.date_naive().to_string(),
        "timeText": dt.format("%H:%M").to_string(),
        "createdAt": b.created_at,
        "category": b.resolved_category,
        "rawCategory": b.category,
        "subCategory": b.sub_category,
        "categoryColor": swatch_for(&b.resolved_category, 0),
        "account": b.resolved_account,
        "accountId": b.account_id,
        "transferAccount": b.resolved_transfer_account,
        "transferAccountId": b.transfer_account_id,
        "merchant": b.merchant,
        "productName": b.product_name,
        "productSource": b.product_source,
        "note": b.note,
        "noteOrigin": b.note_origin,
        "noteTemplateSnapshot": b.note_template_snapshot,
        "method": b.method,
        "orderTail": b.order_tail,
        "sourcePkg": b.source_pkg,
        "strategy": b.strategy,
        "channel": b.channel_label,
        "recognitionId": b.recognition_id,
        "assetLinked": b.asset_linked,
        "assetKey": b.asset_key,
        "transferFeeCents": b.transfer_fee_cents,
        "feeParentKey": b.fee_parent_key,
        "reimburseStatus": b.reimburse_status,
        "reimbursedAt": b.reimbursed_at,
        "reimburseAccountId": b.reimburse_account_id,
        "countsInExpense": b.counts_in_expense,
    })
}

pub fn build_overview(
    snap: &ParsedSnapshot,
    period_mode: &str,
    start_param: Option<&str>,
    end_param: Option<&str>,
    tz_name: Option<&str>,
) -> Result<Value, AppError> {
    let tz = parse_tz(tz_name)?;
    let range = resolve_range(
        period_mode,
        snap.month_start_day,
        start_param,
        end_param,
        tz,
    )?;

    let curr = compute_totals(&snap.bills, range.from_millis, range.to_millis);
    let prev = compute_totals(
        &snap.bills,
        range.compare_from_millis,
        range.compare_to_millis,
    );

    let comp_start_date = NaiveDate::parse_from_str(&range.compare_start, "%Y-%m-%d").unwrap();
    let comp_end_date = NaiveDate::parse_from_str(&range.compare_end, "%Y-%m-%d").unwrap();
    let comp_days = ((comp_end_date - comp_start_date).num_days() + 1).max(1);

    let daily_avg_cents = curr.expense_cents / range.elapsed_days.max(1);
    let prev_daily_avg_cents = prev.expense_cents / comp_days;
    let baseline_label = if range.is_partial {
        format!("vs 上期前{}天", range.elapsed_days)
    } else {
        "vs 上期".to_string()
    };

    let (asset_summary, _, _) = compute_asset_summary(&snap.accounts, &snap.entries);
    let savings_rate = if curr.income_cents > 0 {
        Some(((curr.balance_cents as f64 / curr.income_cents as f64) * 1000.0).round() / 10.0)
    } else {
        None
    };

    // 主趋势序列
    let start_date = NaiveDate::parse_from_str(&range.start, "%Y-%m-%d").unwrap();
    let end_date = NaiveDate::parse_from_str(&range.end, "%Y-%m-%d").unwrap();
    let today = Utc::now().with_timezone(&tz).date_naive();

    let mut trend_series = Vec::new();
    if range.days <= 45 {
        let mut day_map: HashMap<NaiveDate, (i64, i64, i64, i64)> = HashMap::new();
        for b in &snap.bills {
            let in_curr = b.time_millis >= range.from_millis && b.time_millis < range.to_millis;
            let in_prev = b.time_millis >= range.compare_from_millis
                && b.time_millis < range.compare_to_millis;
            if !in_curr && !in_prev {
                continue;
            }
            let d = DateTime::from_timestamp_millis(b.time_millis)
                .unwrap_or_else(Utc::now)
                .with_timezone(&tz)
                .date_naive();
            let entry = day_map.entry(d).or_insert((0, 0, 0, 0));
            if b.counts_in_expense {
                entry.0 += b.price_cents;
                entry.3 += 1;
            } else if b.bill_type == "INCOME" {
                entry.1 += b.price_cents;
            } else if b.bill_type == "REFUND" {
                entry.2 += b.price_cents;
            }
        }
        for i in 0..range.days {
            let d = start_date + Duration::days(i);
            let prev_d = comp_start_date + Duration::days(i);
            let (exp, inc, ref_, cnt) = day_map.get(&d).copied().unwrap_or((0, 0, 0, 0));
            let (p_exp, p_inc, _, _) = if prev_d <= comp_end_date {
                day_map.get(&prev_d).copied().unwrap_or((0, 0, 0, 0))
            } else {
                (0, 0, 0, 0)
            };
            trend_series.push(json!({
                "key": d.to_string(),
                "label": format!("{}/{}", d.month(), d.day()),
                "start": d.to_string(),
                "end": d.to_string(),
                "expenseCents": exp,
                "incomeCents": inc,
                "refundCents": ref_,
                "balanceCents": inc - exp + ref_,
                "prevExpenseCents": p_exp,
                "prevIncomeCents": p_inc,
                "count": cnt,
                "isPartial": d == today && range.is_partial,
                "isFuture": d > today,
            }));
        }
    } else if range.days <= 366 {
        // 按7天桶聚合
        let mut idx = 0i64;
        while idx < range.days {
            let b_start = start_date + Duration::days(idx);
            let b_end = (b_start + Duration::days(6)).min(end_date);
            let b_end_excl = b_end.checked_add_days(Days::new(1)).unwrap();
            let f = local_midnight_millis(b_start, tz);
            let t = local_midnight_millis(b_end_excl, tz);
            let tot = compute_totals(&snap.bills, f, t);

            let p_start = comp_start_date + Duration::days(idx);
            let p_end = (p_start + Duration::days(6)).min(comp_end_date);
            let p_tot = if p_start <= comp_end_date {
                let pf = local_midnight_millis(p_start, tz);
                let pt = local_midnight_millis(p_end.checked_add_days(Days::new(1)).unwrap(), tz);
                compute_totals(&snap.bills, pf, pt)
            } else {
                Default::default()
            };

            trend_series.push(json!({
                "key": b_start.to_string(),
                "label": format!("{}/{}", b_start.month(), b_start.day()),
                "start": b_start.to_string(),
                "end": b_end.to_string(),
                "expenseCents": tot.expense_cents,
                "incomeCents": tot.income_cents,
                "refundCents": tot.refund_cents,
                "balanceCents": tot.balance_cents,
                "prevExpenseCents": p_tot.expense_cents,
                "prevIncomeCents": p_tot.income_cents,
                "count": tot.expense_count,
                "isPartial": today >= b_start && today <= b_end,
                "isFuture": b_start > today,
            }));
            idx += 7;
        }
    } else {
        // 按月聚合
        let mut cursor = crate::analytics::accounting::YearMonth::from_date(start_date);
        let end_ym = crate::analytics::accounting::YearMonth::from_date(end_date);
        while cursor.year < end_ym.year
            || (cursor.year == end_ym.year && cursor.month <= end_ym.month)
        {
            let m_start = NaiveDate::from_ymd_opt(cursor.year, cursor.month, 1)
                .unwrap()
                .max(start_date);
            let next_ym = cursor.shift(1);
            let m_end = NaiveDate::from_ymd_opt(next_ym.year, next_ym.month, 1)
                .unwrap()
                .pred_opt()
                .unwrap()
                .min(end_date);
            let f = local_midnight_millis(m_start, tz);
            let t = local_midnight_millis(m_end.checked_add_days(Days::new(1)).unwrap(), tz);
            let tot = compute_totals(&snap.bills, f, t);
            trend_series.push(json!({
                "key": format!("{:04}-{:02}", cursor.year, cursor.month),
                "label": format!("{:02}月", cursor.month),
                "start": m_start.to_string(),
                "end": m_end.to_string(),
                "expenseCents": tot.expense_cents,
                "incomeCents": tot.income_cents,
                "refundCents": tot.refund_cents,
                "balanceCents": tot.balance_cents,
                "prevExpenseCents": 0,
                "prevIncomeCents": 0,
                "count": tot.expense_count,
                "isPartial": today >= m_start && today <= m_end,
                "isFuture": m_start > today,
            }));
            cursor = next_ym;
        }
    }

    // 近 12 个周期收支序列
    let anchor_period = LedgerPeriod::containing(end_date, range.effective_start_day);
    let mut annual_flow = Vec::with_capacity(12);
    for offset in -11..=0 {
        let p = anchor_period.shift(offset);
        let ps = p.start_date();
        let pe_excl = p.end_exclusive_date();
        let pe_incl = p.end_inclusive_date();
        let tot = compute_totals(
            &snap.bills,
            local_midnight_millis(ps, tz),
            local_midnight_millis(pe_excl, tz),
        );
        annual_flow.push(json!({
            "key": format!("{:04}-{:02}", p.month.year, p.month.month),
            "label": if p.month.month == 1 || offset == -11 {
                format!("{}/{:02}", p.month.year % 100, p.month.month)
            } else {
                format!("{}月", p.month.month)
            },
            "start": ps.to_string(),
            "end": pe_incl.to_string(),
            "expenseCents": tot.expense_cents,
            "incomeCents": tot.income_cents,
            "refundCents": tot.refund_cents,
            "balanceCents": tot.balance_cents,
            "isPartial": today >= ps && today < pe_excl,
        }));
    }

    // 分类 & 二级分类 & 账户排行（当前周期净支出）
    let mut cat_curr: HashMap<String, (i64, i64)> = HashMap::new();
    let mut cat_prev: HashMap<String, (i64, i64)> = HashMap::new();
    let mut sub_curr: HashMap<(String, String), (i64, i64)> = HashMap::new();
    let mut acc_curr: HashMap<String, (i64, i64)> = HashMap::new();
    let mut period_uncategorized_count: i64 = 0;
    let mut period_large_bills: Vec<&ParsedBill> = Vec::new();
    let mut period_recent_bills: Vec<&ParsedBill> = Vec::new();

    for b in &snap.bills {
        let in_curr = b.time_millis >= range.from_millis && b.time_millis < range.to_millis;
        let in_prev =
            b.time_millis >= range.compare_from_millis && b.time_millis < range.compare_to_millis;

        if in_curr {
            period_recent_bills.push(b);
            if b.category.is_none() && b.bill_type != "TRANSFER" {
                period_uncategorized_count += 1;
            }
            if b.counts_in_expense {
                period_large_bills.push(b);
                let e = cat_curr
                    .entry(b.resolved_category.clone())
                    .or_insert((0, 0));
                e.0 += b.price_cents;
                e.1 += 1;

                if let Some(ref sc) = b.sub_category {
                    let se = sub_curr
                        .entry((b.resolved_category.clone(), sc.clone()))
                        .or_insert((0, 0));
                    se.0 += b.price_cents;
                    se.1 += 1;
                }

                let ae = acc_curr.entry(b.resolved_account.clone()).or_insert((0, 0));
                ae.0 += b.price_cents;
                ae.1 += 1;
            }
        } else if in_prev && b.counts_in_expense {
            let e = cat_prev
                .entry(b.resolved_category.clone())
                .or_insert((0, 0));
            e.0 += b.price_cents;
            e.1 += 1;
        }
    }

    let mut top_categories: Vec<Value> = cat_curr
        .iter()
        .map(|(name, &(cents, count))| {
            let (p_cents, p_count) = cat_prev.get(name).copied().unwrap_or((0, 0));
            let ratio = if curr.expense_cents > 0 {
                cents as f64 / curr.expense_cents as f64
            } else {
                0.0
            };
            json!({
                "name": name,
                "color": swatch_for(name, 0),
                "cents": cents,
                "count": count,
                "avgCents": if count > 0 { cents / count } else { 0 },
                "ratio": ratio,
                "delta": make_delta(cents, p_cents, p_count, true, &baseline_label),
            })
        })
        .collect();
    top_categories.sort_by(|a, b| {
        b["cents"]
            .as_i64()
            .cmp(&a["cents"].as_i64())
            .then_with(|| a["name"].as_str().cmp(&b["name"].as_str()))
    });

    let mut top_sub_categories: Vec<Value> = sub_curr
        .iter()
        .map(|((cat, sub), &(cents, count))| {
            let ratio = if curr.expense_cents > 0 {
                cents as f64 / curr.expense_cents as f64
            } else {
                0.0
            };
            json!({
                "category": cat,
                "name": sub,
                "color": swatch_for(cat, 0),
                "cents": cents,
                "count": count,
                "avgCents": if count > 0 { cents / count } else { 0 },
                "ratio": ratio,
            })
        })
        .collect();
    top_sub_categories.sort_by(|a, b| b["cents"].as_i64().cmp(&a["cents"].as_i64()));
    top_sub_categories.truncate(8);

    let mut top_accounts: Vec<Value> = acc_curr
        .iter()
        .map(|(name, &(cents, count))| {
            let ratio = if curr.expense_cents > 0 {
                cents as f64 / curr.expense_cents as f64
            } else {
                0.0
            };
            json!({
                "name": name,
                "cents": cents,
                "count": count,
                "avgCents": if count > 0 { cents / count } else { 0 },
                "ratio": ratio,
            })
        })
        .collect();
    top_accounts.sort_by(|a, b| {
        b["cents"]
            .as_i64()
            .cmp(&a["cents"].as_i64())
            .then_with(|| a["name"].as_str().cmp(&b["name"].as_str()))
    });

    period_large_bills.sort_by(|a, b| {
        b.price_cents
            .cmp(&a.price_cents)
            .then_with(|| b.time_millis.cmp(&a.time_millis))
    });
    let top_large: Vec<Value> = period_large_bills
        .into_iter()
        .take(3)
        .map(|b| bill_to_json(b, tz))
        .collect();

    period_recent_bills.sort_by(|a, b| {
        b.time_millis
            .cmp(&a.time_millis)
            .then_with(|| b.index.cmp(&a.index))
    });
    let recent_bills: Vec<Value> = period_recent_bills
        .into_iter()
        .take(8)
        .map(|b| bill_to_json(b, tz))
        .collect();

    let total_pending_reimburse_cents: i64 = snap
        .bills
        .iter()
        .filter(|b| b.bill_type == "EXPENSE" && b.reimburse_status == "PENDING")
        .map(|b| b.price_cents)
        .sum();
    let total_pending_reimburse_count = snap
        .bills
        .iter()
        .filter(|b| b.bill_type == "EXPENSE" && b.reimburse_status == "PENDING")
        .count();
    let total_uncategorized_count = snap
        .bills
        .iter()
        .filter(|b| b.category.is_none() && b.bill_type != "TRANSFER")
        .count();
    let backup_age_days = (Utc::now() - snap.meta.snapshot_at).num_days().max(0);

    Ok(json!({
        "snapshot": snapshot_meta_json(&snap.meta, Some(snap)),
        "range": range,
        "totals": curr,
        "kpis": {
            "netExpense": {
                "cents": curr.expense_cents,
                "count": curr.expense_count,
                "delta": make_delta(curr.expense_cents, prev.expense_cents, prev.expense_count, true, &baseline_label),
            },
            "income": {
                "cents": curr.income_cents,
                "count": curr.income_count,
                "delta": make_delta(curr.income_cents, prev.income_cents, prev.income_count, false, &baseline_label),
            },
            "balance": {
                "cents": curr.balance_cents,
                "refundCents": curr.refund_cents,
                "savingsRate": savings_rate,
                "delta": make_delta(curr.balance_cents, prev.balance_cents, prev.income_count + prev.expense_count, false, &baseline_label),
            },
            "dailyAvgExpense": {
                "cents": daily_avg_cents,
                "elapsedDays": range.elapsed_days,
                "delta": make_delta(daily_avg_cents, prev_daily_avg_cents, prev.expense_count, true, &baseline_label),
            },
            "netWorth": asset_summary,
        },
        "trendSeries": trend_series,
        "annualFlow": annual_flow,
        "topCategories": top_categories,
        "topSubCategories": top_sub_categories,
        "topAccounts": top_accounts,
        "signals": {
            "pendingReimburseCents": total_pending_reimburse_cents,
            "pendingReimburseCount": total_pending_reimburse_count,
            "periodUncategorizedCount": period_uncategorized_count,
            "totalUncategorizedCount": total_uncategorized_count,
            "backupAgeDays": backup_age_days,
            "topLargeBills": top_large,
        },
        "recentBills": recent_bills,
    }))
}

pub fn build_analytics(
    snap: &ParsedSnapshot,
    period_mode: &str,
    start_param: Option<&str>,
    end_param: Option<&str>,
    category_filter: Option<&str>,
    tz_name: Option<&str>,
) -> Result<Value, AppError> {
    let tz = parse_tz(tz_name)?;
    let range = resolve_range(
        period_mode,
        snap.month_start_day,
        start_param,
        end_param,
        tz,
    )?;

    let start_date = NaiveDate::parse_from_str(&range.start, "%Y-%m-%d").unwrap();
    let end_date = NaiveDate::parse_from_str(&range.end, "%Y-%m-%d").unwrap();
    let heatmap_start = if range.days > 90 {
        start_date
    } else {
        end_date - Duration::days(364)
    };
    let hm_from_ms = local_midnight_millis(heatmap_start, tz);
    let hm_to_ms = range.to_millis;

    let mut day_agg: HashMap<NaiveDate, (i64, i64, HashMap<String, i64>)> = HashMap::new();
    let mut matrix_cents = [[0i64; 8]; 7];
    let mut matrix_counts = [[0i64; 8]; 7];
    let mut cat_map: HashMap<String, (i64, i64)> = HashMap::new();
    let mut sub_by_cat: HashMap<String, HashMap<String, (i64, i64)>> = HashMap::new();
    let mut merchant_map: HashMap<String, (i64, i64, HashMap<String, i64>)> = HashMap::new();

    let bucket_defs: [(&str, i64, i64); 6] = [
        ("<¥20", 0, 2000),
        ("¥20–50", 2000, 5000),
        ("¥50–100", 5000, 10_000),
        ("¥100–300", 10_000, 30_000),
        ("¥300–1k", 30_000, 100_000),
        (">¥1k", 100_000, i64::MAX),
    ];
    let mut hist_counts = [0i64; 6];
    let mut hist_cents = [0i64; 6];

    let mut total_range_expense: i64 = 0;
    let mut total_range_exp_count: i64 = 0;

    for b in &snap.bills {
        if b.counts_in_expense && b.time_millis >= hm_from_ms && b.time_millis < hm_to_ms {
            let dt = DateTime::from_timestamp_millis(b.time_millis)
                .unwrap_or_else(Utc::now)
                .with_timezone(&tz);
            let d = dt.date_naive();
            let entry = day_agg.entry(d).or_insert_with(|| (0, 0, HashMap::new()));
            entry.0 += b.price_cents;
            entry.1 += 1;
            *entry.2.entry(b.resolved_category.clone()).or_insert(0) += b.price_cents;
        }

        if b.time_millis < range.from_millis || b.time_millis >= range.to_millis {
            continue;
        }
        if !b.counts_in_expense {
            continue;
        }

        total_range_expense += b.price_cents;
        total_range_exp_count += 1;

        let dt = DateTime::from_timestamp_millis(b.time_millis)
            .unwrap_or_else(Utc::now)
            .with_timezone(&tz);
        let wd = dt.weekday().num_days_from_monday() as usize;
        let hour_slot = (dt.hour() / 3) as usize;
        matrix_cents[wd][hour_slot] += b.price_cents;
        matrix_counts[wd][hour_slot] += 1;

        let ce = cat_map.entry(b.resolved_category.clone()).or_insert((0, 0));
        ce.0 += b.price_cents;
        ce.1 += 1;

        let sub_name = b
            .sub_category
            .clone()
            .unwrap_or_else(|| "无二级分类".to_string());
        let se = sub_by_cat
            .entry(b.resolved_category.clone())
            .or_default()
            .entry(sub_name)
            .or_insert((0, 0));
        se.0 += b.price_cents;
        se.1 += 1;

        let m_label = b
            .merchant
            .as_deref()
            .or(b.product_name.as_deref())
            .or(b.note.as_deref())
            .unwrap_or("未记商户/备注")
            .to_string();
        let me = merchant_map
            .entry(m_label)
            .or_insert_with(|| (0, 0, HashMap::new()));
        me.0 += b.price_cents;
        me.1 += 1;
        *me.2.entry(b.resolved_category.clone()).or_insert(0) += b.price_cents;

        for (i, &(_, lo, hi)) in bucket_defs.iter().enumerate() {
            if b.price_cents >= lo && b.price_cents < hi {
                hist_counts[i] += 1;
                hist_cents[i] += b.price_cents;
                break;
            }
        }
    }

    // 计算日历热力图动态分位阈值
    let mut non_zero: Vec<i64> = day_agg.values().map(|v| v.0).filter(|&c| c > 0).collect();
    non_zero.sort_unstable();
    let pct = |p: usize| -> i64 {
        if non_zero.is_empty() {
            0
        } else {
            let idx = ((non_zero.len() - 1) * p) / 100;
            non_zero[idx]
        }
    };
    let t1 = pct(35).max(1);
    let t2 = pct(65).max(t1 + 1);
    let t3 = pct(85).max(t2 + 1);
    let t4 = pct(95).max(t3 + 1);

    let empty_cats: HashMap<String, i64> = HashMap::new();
    let mut heatmap_days = Vec::new();
    let mut cur_d = heatmap_start;
    while cur_d <= end_date {
        let (cents, count, cats) = match day_agg.get(&cur_d) {
            Some((c, n, m)) => (*c, *n, m),
            None => (0, 0, &empty_cats),
        };
        let mut top_c: Vec<(&String, &i64)> = cats.iter().collect();
        top_c.sort_by(|a, b| b.1.cmp(a.1));
        let tier = if cents == 0 {
            0
        } else if cents >= t4 {
            4
        } else if cents >= t3 {
            3
        } else if cents >= t2 {
            2
        } else {
            1
        };
        heatmap_days.push(json!({
            "date": cur_d.to_string(),
            "weekday": cur_d.weekday().num_days_from_monday(),
            "cents": cents,
            "count": count,
            "tier": tier,
            "inRange": cur_d >= start_date && cur_d <= end_date,
            "topCategories": top_c.into_iter().take(2).map(|(k, v)| json!({"name": k, "cents": v})).collect::<Vec<_>>(),
        }));
        cur_d = cur_d.checked_add_days(Days::new(1)).unwrap();
    }

    // 星期 × 时段矩阵
    let weekday_names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
    let slot_labels = [
        "00–03", "03–06", "06–09", "09–12", "12–15", "15–18", "18–21", "21–24",
    ];
    let mut matrix_cells = Vec::new();
    let max_slot_cents = matrix_cents
        .iter()
        .flat_map(|r| r.iter().copied())
        .max()
        .unwrap_or(0);
    for (w, wname) in weekday_names.iter().enumerate() {
        for (h, slabel) in slot_labels.iter().enumerate() {
            matrix_cells.push(json!({
                "weekday": w,
                "weekdayLabel": wname,
                "slot": h,
                "slotLabel": slabel,
                "cents": matrix_cents[w][h],
                "count": matrix_counts[w][h],
                "intensity": if max_slot_cents > 0 { matrix_cents[w][h] as f64 / max_slot_cents as f64 } else { 0.0 },
            }));
        }
    }

    // 分类列表与选中分类下钻
    let mut categories: Vec<Value> = cat_map
        .iter()
        .map(|(name, &(cents, count))| {
            json!({
                "name": name,
                "color": swatch_for(name, 0),
                "cents": cents,
                "count": count,
                "avgCents": if count > 0 { cents / count } else { 0 },
                "ratio": if total_range_expense > 0 { cents as f64 / total_range_expense as f64 } else { 0.0 },
            })
        })
        .collect();
    categories.sort_by(|a, b| b["cents"].as_i64().cmp(&a["cents"].as_i64()));

    let active_category = category_filter
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .or_else(|| {
            categories
                .first()
                .and_then(|c| c["name"].as_str())
                .map(|s| s.to_string())
        });

    let mut sub_categories = Vec::new();
    let mut category_monthly_trend = Vec::new();
    if let Some(ref active_cat) = active_category {
        let cat_total = cat_map.get(active_cat).map(|v| v.0).unwrap_or(0);
        if let Some(subs) = sub_by_cat.get(active_cat) {
            for (sname, &(cents, count)) in subs {
                sub_categories.push(json!({
                    "name": sname,
                    "cents": cents,
                    "count": count,
                    "avgCents": if count > 0 { cents / count } else { 0 },
                    "ratioInCategory": if cat_total > 0 { cents as f64 / cat_total as f64 } else { 0.0 },
                }));
            }
            sub_categories.sort_by(|a, b| b["cents"].as_i64().cmp(&a["cents"].as_i64()));
        }

        let anchor = LedgerPeriod::containing(end_date, range.effective_start_day);
        for offset in -5..=0 {
            let p = anchor.shift(offset);
            let f = local_midnight_millis(p.start_date(), tz);
            let t = local_midnight_millis(p.end_exclusive_date(), tz);
            let mut sum_c = 0i64;
            let mut cnt_c = 0i64;
            for b in &snap.bills {
                if b.counts_in_expense
                    && b.time_millis >= f
                    && b.time_millis < t
                    && &b.resolved_category == active_cat
                {
                    sum_c += b.price_cents;
                    cnt_c += 1;
                }
            }
            category_monthly_trend.push(json!({
                "label": format!("{}月", p.month.month),
                "start": p.start_date().to_string(),
                "end": p.end_inclusive_date().to_string(),
                "cents": sum_c,
                "count": cnt_c,
            }));
        }
    }

    // 高频商户 / 消费去向榜
    let mut merchants: Vec<Value> = merchant_map
        .into_iter()
        .map(|(name, (cents, count, cats))| {
            let top_cat = cats
                .into_iter()
                .max_by_key(|(_, c)| *c)
                .map(|(k, _)| k)
                .unwrap_or_else(|| "未分类".to_string());
            json!({
                "name": name,
                "cents": cents,
                "count": count,
                "avgCents": if count > 0 { cents / count } else { 0 },
                "topCategory": top_cat,
                "categoryColor": swatch_for(&top_cat, 0),
                "ratio": if total_range_expense > 0 { cents as f64 / total_range_expense as f64 } else { 0.0 },
            })
        })
        .collect();
    merchants.sort_by(|a, b| b["cents"].as_i64().cmp(&a["cents"].as_i64()));
    merchants.truncate(20);

    // 金额分档直方图
    let amount_buckets: Vec<Value> = bucket_defs
        .iter()
        .enumerate()
        .map(|(i, (label, _, _))| {
            json!({
                "label": label,
                "count": hist_counts[i],
                "cents": hist_cents[i],
                "countRatio": if total_range_exp_count > 0 { hist_counts[i] as f64 / total_range_exp_count as f64 } else { 0.0 },
                "centsRatio": if total_range_expense > 0 { hist_cents[i] as f64 / total_range_expense as f64 } else { 0.0 },
            })
        })
        .collect();

    // 近 12 周期横向对比表
    let anchor = LedgerPeriod::containing(end_date, range.effective_start_day);
    let mut period_comparison = Vec::with_capacity(12);
    for offset in -11..=0 {
        let p = anchor.shift(offset);
        let ps = p.start_date();
        let pe_excl = p.end_exclusive_date();
        let pe_incl = p.end_inclusive_date();
        let tot = compute_totals(
            &snap.bills,
            local_midnight_millis(ps, tz),
            local_midnight_millis(pe_excl, tz),
        );
        let days = (pe_excl - ps).num_days().max(1);
        let savings_rate = if tot.income_cents > 0 {
            Some(((tot.balance_cents as f64 / tot.income_cents as f64) * 1000.0).round() / 10.0)
        } else {
            None
        };
        period_comparison.push(json!({
            "label": format!("{:04}-{:02}", p.month.year, p.month.month),
            "start": ps.to_string(),
            "end": pe_incl.to_string(),
            "expenseCents": tot.expense_cents,
            "incomeCents": tot.income_cents,
            "refundCents": tot.refund_cents,
            "balanceCents": tot.balance_cents,
            "savingsRate": savings_rate,
            "billCount": tot.total_count,
            "expenseCount": tot.expense_count,
            "dailyAvgExpenseCents": tot.expense_cents / days,
        }));
    }

    Ok(json!({
        "snapshot": snapshot_meta_json(&snap.meta, Some(snap)),
        "range": range,
        "calendarHeatmap": {
            "start": heatmap_start.to_string(),
            "end": end_date.to_string(),
            "thresholds": [t1, t2, t3, t4],
            "days": heatmap_days,
        },
        "weekdayHourMatrix": matrix_cells,
        "categories": categories,
        "activeCategory": active_category,
        "subCategories": sub_categories,
        "categoryMonthlyTrend": category_monthly_trend,
        "merchants": merchants,
        "amountBuckets": amount_buckets,
        "periodComparison": period_comparison,
    }))
}

#[derive(Clone, Debug, Default)]
pub struct BillFilter {
    pub q: Option<String>,
    pub bill_type: Option<String>,
    pub category: Option<String>,
    pub sub_category: Option<String>,
    pub account: Option<String>,
    pub reimburse_status: Option<String>,
    pub strategy: Option<String>,
    pub channel: Option<String>,
    pub min_cents: Option<i64>,
    pub max_cents: Option<i64>,
    pub from_ms: Option<i64>,
    pub to_ms: Option<i64>,
    pub sort: String,
    pub page: usize,
    pub page_size: usize,
}

fn filter_bills<'a>(snap: &'a ParsedSnapshot, filter: &BillFilter) -> Vec<&'a ParsedBill> {
    let q_lower = filter
        .q
        .as_deref()
        .map(|s| s.trim().to_lowercase())
        .filter(|s| !s.is_empty());

    let mut matched: Vec<&'a ParsedBill> = snap
        .bills
        .iter()
        .filter(|b| {
            if let Some(f) = filter.from_ms {
                if b.time_millis < f {
                    return false;
                }
            }
            if let Some(t) = filter.to_ms {
                if b.time_millis >= t {
                    return false;
                }
            }
            if let Some(ref ty) = filter.bill_type {
                if !ty.is_empty() && &b.bill_type != ty {
                    return false;
                }
            }
            if let Some(ref cat) = filter.category {
                if !cat.is_empty() && &b.resolved_category != cat {
                    return false;
                }
            }
            if let Some(ref sub) = filter.sub_category {
                if !sub.is_empty() && b.sub_category.as_deref() != Some(sub.as_str()) {
                    return false;
                }
            }
            if let Some(ref acc) = filter.account {
                if !acc.is_empty()
                    && &b.resolved_account != acc
                    && b.resolved_transfer_account.as_deref() != Some(acc.as_str())
                {
                    return false;
                }
            }
            if let Some(ref rs) = filter.reimburse_status {
                if !rs.is_empty() && &b.reimburse_status != rs {
                    return false;
                }
            }
            if let Some(ref st) = filter.strategy {
                if !st.is_empty() && &b.strategy != st {
                    return false;
                }
            }
            if let Some(ref ch) = filter.channel {
                if !ch.is_empty() && &b.channel_label != ch {
                    return false;
                }
            }
            if let Some(min_c) = filter.min_cents {
                if b.price_cents < min_c {
                    return false;
                }
            }
            if let Some(max_c) = filter.max_cents {
                if b.price_cents > max_c {
                    return false;
                }
            }
            if let Some(ref q) = q_lower {
                let hit = b
                    .merchant
                    .as_deref()
                    .map(|s| s.to_lowercase().contains(q))
                    .unwrap_or(false)
                    || b.product_name
                        .as_deref()
                        .map(|s| s.to_lowercase().contains(q))
                        .unwrap_or(false)
                    || b.note
                        .as_deref()
                        .map(|s| s.to_lowercase().contains(q))
                        .unwrap_or(false)
                    || b.resolved_category.to_lowercase().contains(q)
                    || b.sub_category
                        .as_deref()
                        .map(|s| s.to_lowercase().contains(q))
                        .unwrap_or(false)
                    || b.order_tail
                        .as_deref()
                        .map(|s| s.to_lowercase().contains(q))
                        .unwrap_or(false)
                    || b.resolved_account.to_lowercase().contains(q);
                if !hit {
                    return false;
                }
            }
            true
        })
        .collect();

    match filter.sort.as_str() {
        "time_asc" => matched.sort_by(|a, b| {
            a.time_millis
                .cmp(&b.time_millis)
                .then_with(|| a.index.cmp(&b.index))
        }),
        "amount_desc" => matched.sort_by(|a, b| {
            b.price_cents
                .cmp(&a.price_cents)
                .then_with(|| b.time_millis.cmp(&a.time_millis))
        }),
        "amount_asc" => matched.sort_by(|a, b| {
            a.price_cents
                .cmp(&b.price_cents)
                .then_with(|| b.time_millis.cmp(&a.time_millis))
        }),
        _ => matched.sort_by(|a, b| {
            b.time_millis
                .cmp(&a.time_millis)
                .then_with(|| b.index.cmp(&a.index))
        }),
    }
    matched
}

pub fn query_bills(snap: &ParsedSnapshot, filter: &BillFilter, tz: Tz) -> Value {
    let matched = filter_bills(snap, filter);
    let total = matched.len();

    let mut expense_cents = 0i64;
    let mut expense_count = 0i64;
    let mut income_cents = 0i64;
    let mut refund_cents = 0i64;
    let mut transfer_cents = 0i64;
    for b in &matched {
        match b.bill_type.as_str() {
            "EXPENSE" if b.counts_in_expense => {
                expense_cents += b.price_cents;
                expense_count += 1;
            }
            "INCOME" => income_cents += b.price_cents,
            "REFUND" => refund_cents += b.price_cents,
            "TRANSFER" => transfer_cents += b.price_cents,
            _ => {}
        }
    }
    let balance_cents = income_cents - expense_cents + refund_cents;

    // 选项字典（基于全快照）
    let mut cats_set: BTreeMap<String, BTreeMap<String, ()>> = BTreeMap::new();
    let mut accs_set: BTreeMap<String, ()> = BTreeMap::new();
    let mut channels_set: BTreeMap<String, ()> = BTreeMap::new();
    let mut strategies_set: BTreeMap<String, ()> = BTreeMap::new();
    for b in &snap.bills {
        let sub_map = cats_set.entry(b.resolved_category.clone()).or_default();
        if let Some(ref sc) = b.sub_category {
            sub_map.insert(sc.clone(), ());
        }
        accs_set.insert(b.resolved_account.clone(), ());
        channels_set.insert(b.channel_label.clone(), ());
        strategies_set.insert(b.strategy.clone(), ());
    }

    let page_size = filter.page_size.clamp(1, 200);
    let page = filter.page.max(1);
    let start_idx = (page - 1) * page_size;
    let items: Vec<Value> = matched
        .into_iter()
        .skip(start_idx)
        .take(page_size)
        .map(|b| bill_to_json(b, tz))
        .collect();

    json!({
        "snapshot": snapshot_meta_json(&snap.meta, Some(snap)),
        "summary": {
            "totalCount": total,
            "expenseCount": expense_count,
            "expenseCents": expense_cents,
            "incomeCents": income_cents,
            "refundCents": refund_cents,
            "transferCents": transfer_cents,
            "balanceCents": balance_cents,
            "avgExpenseCents": if expense_count > 0 { expense_cents / expense_count } else { 0 },
        },
        "facets": {
            "categories": cats_set.iter().map(|(c, subs)| json!({
                "name": c,
                "color": swatch_for(c, 0),
                "subCategories": subs.keys().collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
            "accounts": accs_set.keys().collect::<Vec<_>>(),
            "channels": channels_set.keys().collect::<Vec<_>>(),
            "strategies": strategies_set.keys().collect::<Vec<_>>(),
        },
        "total": total,
        "page": page,
        "pageSize": page_size,
        "items": items,
    })
}

fn csv_safe_cell(s: &str) -> String {
    let trimmed = s.trim_start();
    if trimmed.starts_with('=')
        || trimmed.starts_with('+')
        || trimmed.starts_with('-')
        || trimmed.starts_with('@')
        || s.starts_with('\t')
        || s.starts_with('\r')
    {
        format!("'{}", s)
    } else {
        s.to_string()
    }
}

pub fn export_bills_bytes(
    snap: &ParsedSnapshot,
    filter: &BillFilter,
    format: &str,
    tz: Tz,
) -> Result<(Vec<u8>, &'static str, String), AppError> {
    let matched = filter_bills(snap, filter);
    if format.eq_ignore_ascii_case("json") {
        let rows: Vec<Value> = matched.into_iter().map(|b| bill_to_json(b, tz)).collect();
        let payload = json!({
            "snapshot": snapshot_meta_json(&snap.meta, Some(snap)),
            "count": rows.len(),
            "items": rows,
        });
        let bytes = serde_json::to_vec_pretty(&payload)
            .map_err(|_| AppError::internal("导出 JSON 失败"))?;
        return Ok((
            bytes,
            "application/json; charset=utf-8",
            format!("mymoney-bills-{}.json", snap.meta.backup_id),
        ));
    }

    let mut out = Vec::new();
    // UTF-8 BOM
    out.extend_from_slice(&[0xEF, 0xBB, 0xBF]);
    {
        let mut wtr = csv::Writer::from_writer(&mut out);
        wtr.write_record([
            "时间",
            "类型",
            "金额(元)",
            "一级分类",
            "二级分类",
            "账户",
            "转入账户",
            "商户",
            "商品",
            "备注",
            "报销状态",
            "支付方式",
            "订单尾号",
            "识别渠道",
            "策略",
            "计入余额",
        ])
        .map_err(|_| AppError::internal("写入 CSV 表头失败"))?;

        for b in matched {
            let dt = DateTime::from_timestamp_millis(b.time_millis)
                .unwrap_or_else(Utc::now)
                .with_timezone(&tz);
            let ty_label = match b.bill_type.as_str() {
                "EXPENSE" => "支出",
                "INCOME" => "收入",
                "REFUND" => "退款",
                "TRANSFER" => "转账",
                other => other,
            };
            let rs_label = match b.reimburse_status.as_str() {
                "PENDING" => "待报销",
                "REIMBURSED" => "已报销",
                _ => "无",
            };
            let amount_yuan = format!("{:.2}", b.price_cents as f64 / 100.0);
            wtr.write_record([
                dt.format("%Y-%m-%d %H:%M:%S").to_string(),
                ty_label.to_string(),
                amount_yuan,
                csv_safe_cell(&b.resolved_category),
                csv_safe_cell(b.sub_category.as_deref().unwrap_or("")),
                csv_safe_cell(&b.resolved_account),
                csv_safe_cell(b.resolved_transfer_account.as_deref().unwrap_or("")),
                csv_safe_cell(b.merchant.as_deref().unwrap_or("")),
                csv_safe_cell(b.product_name.as_deref().unwrap_or("")),
                csv_safe_cell(b.note.as_deref().unwrap_or("")),
                rs_label.to_string(),
                csv_safe_cell(b.method.as_deref().unwrap_or("")),
                csv_safe_cell(b.order_tail.as_deref().unwrap_or("")),
                csv_safe_cell(&b.channel_label),
                csv_safe_cell(&b.strategy),
                if b.asset_linked { "是" } else { "否" }.to_string(),
            ])
            .map_err(|_| AppError::internal("写入 CSV 行失败"))?;
        }
        wtr.flush()
            .map_err(|_| AppError::internal("导出 CSV 缓冲失败"))?;
    }

    Ok((
        out,
        "text/csv; charset=utf-8",
        format!("mymoney-bills-{}.csv", snap.meta.backup_id),
    ))
}

fn account_kind_order(kind: &str) -> usize {
    match kind {
        "PAYMENT" => 0,
        "SAVINGS" => 1,
        "CREDIT" => 2,
        "CASH" => 3,
        "OTHER_ASSET" => 4,
        "OTHER_DEBT" => 5,
        _ => 6,
    }
}

pub fn build_assets(
    snap: &ParsedSnapshot,
    start_param: Option<&str>,
    end_param: Option<&str>,
    account_id_param: Option<&str>,
    tz: Tz,
) -> Result<Value, AppError> {
    let (summary, balances, entry_counts) = compute_asset_summary(&snap.accounts, &snap.entries);

    let mut accounts_sorted: Vec<_> = snap.accounts.iter().collect();
    accounts_sorted.sort_by(|a, b| {
        a.archived
            .cmp(&b.archived)
            .then_with(|| account_kind_order(&a.kind).cmp(&account_kind_order(&b.kind)))
            .then_with(|| a.sort_order.cmp(&b.sort_order))
            .then_with(|| a.created_at.cmp(&b.created_at))
            .then_with(|| a.id.cmp(&b.id))
    });

    let accounts_json: Vec<Value> = accounts_sorted
        .iter()
        .map(|a| {
            json!({
                "id": a.id,
                "name": a.name,
                "kind": a.kind,
                "aliases": a.aliases,
                "sortOrder": a.sort_order,
                "archived": a.archived,
                "included": a.included,
                "initialized": a.initialized,
                "balanceCents": *balances.get(&a.id).unwrap_or(&0),
                "entryCount": *entry_counts.get(&a.id).unwrap_or(&0),
                "createdAt": a.created_at,
                "updatedAt": a.updated_at,
            })
        })
        .collect();

    let from_ms = match start_param {
        Some(s) if !s.trim().is_empty() => {
            let sd = NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d")
                .map_err(|_| AppError::unprocessable("开始日期格式无效"))?;
            Some(local_midnight_millis(sd, tz))
        }
        _ => None,
    };
    let to_ms = match end_param {
        Some(e) if !e.trim().is_empty() => {
            let ed = NaiveDate::parse_from_str(e.trim(), "%Y-%m-%d")
                .map_err(|_| AppError::unprocessable("结束日期格式无效"))?;
            let excl = ed.checked_add_days(Days::new(1)).unwrap();
            Some(local_midnight_millis(excl, tz))
        }
        _ => None,
    };
    let timeline = compute_asset_timeline(snap, from_ms, to_ms, tz);

    let selected_account_id = account_id_param
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .or_else(|| {
            accounts_sorted
                .iter()
                .find(|a| a.initialized && !a.archived)
                .map(|a| a.id.clone())
        });

    let mut account_entries = Vec::new();
    if let Some(ref target_id) = selected_account_id {
        let mut ents: Vec<_> = snap
            .entries
            .iter()
            .filter(|e| &e.account_id == target_id)
            .collect();
        ents.sort_by(|a, b| {
            a.created_at
                .cmp(&b.created_at)
                .then_with(|| a.id.cmp(&b.id))
        });
        let mut running = 0i64;
        for e in ents {
            running += e.delta_cents;
            let dt = DateTime::from_timestamp_millis(e.created_at)
                .unwrap_or_else(Utc::now)
                .with_timezone(&tz);
            account_entries.push(json!({
                "id": e.id,
                "operationId": e.operation_id,
                "accountId": e.account_id,
                "deltaCents": e.delta_cents,
                "runningBalanceCents": running,
                "kind": e.kind,
                "note": e.note,
                "createdAt": e.created_at,
                "timeIso": dt.to_rfc3339(),
                "billKey": e.bill_key,
                "reversalOf": e.reversal_of,
            }));
        }
        account_entries.reverse();
    }

    Ok(json!({
        "snapshot": snapshot_meta_json(&snap.meta, Some(snap)),
        "summary": summary,
        "accounts": accounts_json,
        "timeline": timeline,
        "selectedAccountId": selected_account_id,
        "accountEntries": account_entries,
    }))
}

pub fn build_audit(snap: &ParsedSnapshot, tz: Tz) -> Value {
    let mut pending_bills = Vec::new();
    let mut reimbursed_bills = Vec::new();
    let mut uncategorized_bills = Vec::new();
    let mut pending_cents = 0i64;
    let mut reimbursed_cents = 0i64;
    let mut uncategorized_expense_cents = 0i64;
    let mut reimburse_durations_days: Vec<f64> = Vec::new();

    let mut channel_map: HashMap<String, (i64, i64)> = HashMap::new();
    let mut strategy_map: HashMap<String, (i64, i64)> = HashMap::new();

    for b in &snap.bills {
        let ce = channel_map.entry(b.channel_label.clone()).or_insert((0, 0));
        ce.0 += 1;
        if b.counts_in_expense {
            ce.1 += b.price_cents;
        }
        let se = strategy_map.entry(b.strategy.clone()).or_insert((0, 0));
        se.0 += 1;
        if b.counts_in_expense {
            se.1 += b.price_cents;
        }

        if b.bill_type == "EXPENSE" && b.reimburse_status == "PENDING" {
            pending_cents += b.price_cents;
            pending_bills.push(bill_to_json(b, tz));
        } else if b.bill_type == "EXPENSE" && b.reimburse_status == "REIMBURSED" {
            reimbursed_cents += b.price_cents;
            if let Some(rat) = b.reimbursed_at {
                if rat >= b.time_millis {
                    reimburse_durations_days.push((rat - b.time_millis) as f64 / 86_400_000.0);
                }
            }
            reimbursed_bills.push(bill_to_json(b, tz));
        }

        if b.category.is_none() && b.bill_type != "TRANSFER" {
            if b.counts_in_expense {
                uncategorized_expense_cents += b.price_cents;
            }
            uncategorized_bills.push(bill_to_json(b, tz));
        }
    }

    pending_bills.sort_by(|a, b| b["timeMillis"].as_i64().cmp(&a["timeMillis"].as_i64()));
    reimbursed_bills.sort_by(|a, b| b["timeMillis"].as_i64().cmp(&a["timeMillis"].as_i64()));
    uncategorized_bills.sort_by(|a, b| b["timeMillis"].as_i64().cmp(&a["timeMillis"].as_i64()));

    let total_bills = snap.bills.len().max(1) as f64;
    let mut channels: Vec<Value> = channel_map
        .into_iter()
        .map(|(name, (count, expense_cents))| {
            json!({
                "name": name,
                "count": count,
                "expenseCents": expense_cents,
                "ratio": count as f64 / total_bills,
            })
        })
        .collect();
    channels.sort_by(|a, b| b["count"].as_i64().cmp(&a["count"].as_i64()));

    let mut strategies: Vec<Value> = strategy_map
        .into_iter()
        .map(|(name, (count, expense_cents))| {
            json!({
                "name": name,
                "count": count,
                "expenseCents": expense_cents,
                "ratio": count as f64 / total_bills,
            })
        })
        .collect();
    strategies.sort_by(|a, b| b["count"].as_i64().cmp(&a["count"].as_i64()));
    strategies.truncate(20);

    let avg_reimburse_days = if reimburse_durations_days.is_empty() {
        None
    } else {
        let sum: f64 = reimburse_durations_days.iter().sum();
        Some(((sum / reimburse_durations_days.len() as f64) * 10.0).round() / 10.0)
    };

    let mut deletions = snap.deletions.clone();
    deletions.sort_by_key(|a| std::cmp::Reverse(a.deleted_at));
    let deletions_json: Vec<Value> = deletions
        .into_iter()
        .map(|d| {
            let dt = DateTime::from_timestamp_millis(d.deleted_at)
                .unwrap_or_else(Utc::now)
                .with_timezone(&tz);
            json!({
                "eventId": d.event_id,
                "deletedAt": d.deleted_at,
                "deletedAtIso": dt.to_rfc3339(),
                "origin": d.origin,
                "principalId": d.principal_id,
                "recognitionId": d.recognition_id,
                "groupId": d.group_id,
                "bills": d.bills,
            })
        })
        .collect();

    json!({
        "snapshot": snapshot_meta_json(&snap.meta, Some(snap)),
        "reimburse": {
            "pendingCents": pending_cents,
            "pendingCount": pending_bills.len(),
            "reimbursedCents": reimbursed_cents,
            "reimbursedCount": reimbursed_bills.len(),
            "avgReimburseDays": avg_reimburse_days,
            "pendingBills": pending_bills,
            "reimbursedBills": reimbursed_bills,
        },
        "channels": channels,
        "strategies": strategies,
        "uncategorized": {
            "count": uncategorized_bills.len(),
            "expenseCents": uncategorized_expense_cents,
            "bills": uncategorized_bills,
        },
        "deletions": deletions_json,
    })
}

fn stable_bill_key(b: &ParsedBill) -> String {
    if !b.sync_id.is_empty() {
        format!("sid:{}", b.sync_id)
    } else if !b.asset_key.is_empty() {
        format!("ak:{}", b.asset_key)
    } else if let Some(ref r) = b.recognition_id {
        format!("rid:{}", r)
    } else {
        format!("fp:{}:{}:{}", b.time_millis, b.price_cents, b.fingerprint)
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BillDiffRow {
    key: String,
    left: Option<Value>,
    right: Option<Value>,
    changed_fields: Vec<String>,
}

pub fn build_diff(left: &ParsedSnapshot, right: &ParsedSnapshot, tz: Tz) -> Value {
    let left_tot = compute_totals(&left.bills, i64::MIN, i64::MAX);
    let right_tot = compute_totals(&right.bills, i64::MIN, i64::MAX);
    let (left_assets, left_acc_bal, _) = compute_asset_summary(&left.accounts, &left.entries);
    let (right_assets, right_acc_bal, _) = compute_asset_summary(&right.accounts, &right.entries);

    let mut left_map: HashMap<String, &ParsedBill> = HashMap::with_capacity(left.bills.len());
    for b in &left.bills {
        let base = stable_bill_key(b);
        let mut key = base.clone();
        let mut seq = 1usize;
        while left_map.contains_key(&key) {
            key = format!("{}#{}", base, seq);
            seq += 1;
        }
        left_map.insert(key, b);
    }

    let mut right_map: HashMap<String, &ParsedBill> = HashMap::with_capacity(right.bills.len());
    for b in &right.bills {
        let base = stable_bill_key(b);
        let mut key = base.clone();
        let mut seq = 1usize;
        while right_map.contains_key(&key) {
            key = format!("{}#{}", base, seq);
            seq += 1;
        }
        right_map.insert(key, b);
    }

    let mut added = Vec::new();
    let mut removed = Vec::new();
    let mut modified = Vec::new();

    for (k, rb) in &right_map {
        match left_map.get(k) {
            None => added.push(BillDiffRow {
                key: k.clone(),
                left: None,
                right: Some(bill_to_json(rb, tz)),
                changed_fields: Vec::new(),
            }),
            Some(lb) => {
                let mut changed = Vec::new();
                if lb.price_cents != rb.price_cents {
                    changed.push("金额".to_string());
                }
                if lb.bill_type != rb.bill_type {
                    changed.push("类型".to_string());
                }
                if lb.resolved_category != rb.resolved_category {
                    changed.push("分类".to_string());
                }
                if lb.sub_category != rb.sub_category {
                    changed.push("二级分类".to_string());
                }
                if lb.resolved_account != rb.resolved_account {
                    changed.push("账户".to_string());
                }
                if lb.note != rb.note {
                    changed.push("备注".to_string());
                }
                if lb.reimburse_status != rb.reimburse_status {
                    changed.push("报销状态".to_string());
                }
                if !changed.is_empty() {
                    modified.push(BillDiffRow {
                        key: k.clone(),
                        left: Some(bill_to_json(lb, tz)),
                        right: Some(bill_to_json(rb, tz)),
                        changed_fields: changed,
                    });
                }
            }
        }
    }

    for (k, lb) in &left_map {
        if !right_map.contains_key(k) {
            removed.push(BillDiffRow {
                key: k.clone(),
                left: Some(bill_to_json(lb, tz)),
                right: None,
                changed_fields: Vec::new(),
            });
        }
    }

    let mut acc_ids: BTreeMap<String, String> = BTreeMap::new();
    for a in &left.accounts {
        acc_ids.insert(a.id.clone(), a.name.clone());
    }
    for a in &right.accounts {
        acc_ids.insert(a.id.clone(), a.name.clone());
    }
    let account_diffs: Vec<Value> = acc_ids
        .into_iter()
        .filter_map(|(id, name)| {
            let l_bal = *left_acc_bal.get(&id).unwrap_or(&0);
            let r_bal = *right_acc_bal.get(&id).unwrap_or(&0);
            if l_bal == r_bal && left_acc_bal.contains_key(&id) == right_acc_bal.contains_key(&id) {
                return None;
            }
            Some(json!({
                "accountId": id,
                "name": name,
                "leftBalanceCents": l_bal,
                "rightBalanceCents": r_bal,
                "diffCents": r_bal - l_bal,
            }))
        })
        .collect();

    json!({
        "left": snapshot_meta_json(&left.meta, Some(left)),
        "right": snapshot_meta_json(&right.meta, Some(right)),
        "summaryDelta": {
            "billCountDiff": right.bills.len() as i64 - left.bills.len() as i64,
            "expenseDiffCents": right_tot.expense_cents - left_tot.expense_cents,
            "incomeDiffCents": right_tot.income_cents - left_tot.income_cents,
            "refundDiffCents": right_tot.refund_cents - left_tot.refund_cents,
            "balanceDiffCents": right_tot.balance_cents - left_tot.balance_cents,
            "netWorthDiffCents": right_assets.net_cents - left_assets.net_cents,
            "addedCount": added.len(),
            "removedCount": removed.len(),
            "modifiedCount": modified.len(),
        },
        "addedBills": added.into_iter().take(100).collect::<Vec<_>>(),
        "removedBills": removed.into_iter().take(100).collect::<Vec<_>>(),
        "modifiedBills": modified.into_iter().take(100).collect::<Vec<_>>(),
        "accountDiffs": account_diffs,
    })
}
