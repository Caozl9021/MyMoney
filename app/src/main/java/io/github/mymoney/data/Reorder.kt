package io.github.mymoney.data

/** 列表里把一项从 [from] 挪到 [to]；越界时原样返回，不抛异常（UI 双击/过期索引）。 */
object Reorder {
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val next = list.toMutableList()
        next.add(to, next.removeAt(from))
        return next
    }
}
