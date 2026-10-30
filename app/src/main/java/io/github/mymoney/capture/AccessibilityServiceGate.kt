package io.github.mymoney.capture

/**
 * 「本应用的无障碍服务是否被系统勾上」的唯一判据。
 *
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 是冒号分隔的组件名列表，而同一个组件有
 * **两种合法写法**：短类名 `pkg/.capture.BillAccessibilityService` 与全类名
 * `pkg/pkg.capture.BillAccessibilityService`。写哪一种取决于系统设置面板实现
 * （AOSP `flattenToShortString` / `flattenToString` 都有人用），也会被系统在回写时改写。
 *
 * 过去这里直接拿「包名/全类名」做字符串全等，真机上系统存的是短类名，于是服务其实已经绑定
 * （`dumpsys accessibility` 的 Bound services 里有它），界面上却一直报「还差无障碍授权」。
 * 所以按组件语义解析，而不是比较字面量。
 */
internal fun accessibilityServiceEnabled(
    entries: String?,
    packageName: String,
    className: String,
): Boolean = entries.orEmpty().split(':').any { raw ->
    val slash = raw.indexOf('/')
    if (slash <= 0 || slash == raw.length - 1) return@any false
    if (!raw.substring(0, slash).equals(packageName, ignoreCase = true)) return@any false
    val listed = raw.substring(slash + 1)
    val full = if (listed.startsWith(".")) packageName + listed else listed
    full.equals(className, ignoreCase = true)
}
