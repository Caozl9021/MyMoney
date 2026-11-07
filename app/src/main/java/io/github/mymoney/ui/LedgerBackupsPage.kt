package io.github.mymoney.ui

import androidx.compose.runtime.Composable

/**
 * 历史备份：某一刻的完整账本副本，用于误删 / 换机恢复。
 * 内容复用 [FamilyBackupsPanel]（账本快照备份 + 家庭备份列表 + 恢复）。
 */
@Composable
fun LedgerBackupsPage(onBack: () -> Unit) {
    SettingsContentPage("历史备份", onBack) {
        FamilyBackupsPanel()
    }
}
