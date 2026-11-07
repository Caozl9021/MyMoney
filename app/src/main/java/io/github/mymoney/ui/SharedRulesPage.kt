package io.github.mymoney.ui

import androidx.compose.runtime.Composable

/**
 * 共享规则：个人规则、备注规则与官方规则启停，双向同步到家庭服务。
 * 由 [FamilySyncPage]（规则配对 / 同步 / 冲突）承载；本页只做语义封装，
 * 把「共享规则」从「本机账本同步」「历史备份」中独立出来。
 */
@Composable
fun SharedRulesPage(onBack: () -> Unit) {
    FamilySyncPage(onBack, title = "共享规则")
}
