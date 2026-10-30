package com.example.autobook.ui

import com.example.billkit.Packages
import com.example.autobook.data.CaptureScope

/**
 * 应用清单的显示名与顺序（纯数据，无 Compose 依赖）。
 *
 * 同一个 App 常有多个包名（京东金融、美团外卖、抖音极速版……），清单要一个包名一行，
 * 但标题必须短——真机上「京东（com.jd.waimai）」这种拼法会把标题折成三行，
 * 副标题里本来就写着包名。所以这里显式给每个包名一个短名，顺序也固定，
 * 不靠 `Set.sorted()` 决定谁是主包（那会把 com.dianping.v1 排成「美团」的第一行）。
 */
private val appEntries: List<Pair<String, String>> = listOf(
    "微信" to Packages.WECHAT,
    "支付宝" to Packages.ALIPAY,
    "云闪付" to Packages.UNIONPAY,
    "京东" to Packages.JD,
    "京东金融" to Packages.JD_FINANCE,
    "京东外卖" to Packages.JD_WAIMAI,
    "美团" to Packages.MEITUAN,
    "美团外卖" to Packages.MEITUAN_WAIMAI,
    "美团优选" to Packages.MEITUAN_YOUXUAN,
    "大众点评" to Packages.DIANPING,
    "淘宝" to Packages.TAOBAO,
    "天猫" to Packages.TMALL,
    "拼多多" to Packages.PDD,
    "抖音" to Packages.DOUYIN,
    "抖音极速版" to Packages.DOUYIN_LITE,
    "数字人民币" to Packages.DCEP,
    "网商银行" to Packages.MYBANK,
)

/** 预设包名，顺序即清单顺序。 */
internal val knownPackages: List<String> = appEntries.map { it.second }.filter(CaptureScope::supports)

// 注意方向：entries 是「显示名 to 包名」，查表要反过来按包名取显示名。
// 直接 toMap() 会得到「显示名 → 包名」，查不到就退化成裸包名。
private val packageLabels: Map<String, String> = appEntries.associate { (label, pkg) -> pkg to label }

/**
 * self应用清单只展示微信与支付宝；旧配置中的其他包名不形成新入口。
 * 历史应用显示名仍保留，不查询安装应用或运行进程。
 */
internal fun appPackages(whitelist: Set<String>): List<String> =
    knownPackages

internal fun appLabel(pkg: String): String = packageLabels[pkg] ?: pkg
