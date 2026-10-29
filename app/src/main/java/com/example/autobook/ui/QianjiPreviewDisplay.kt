package com.example.autobook.ui

import com.example.autobook.importer.QianjiImport

/** Presentation filters never alter the document submitted to the importer. */
internal enum class QianjiRowFilter(val label: String) {
    ATTENTION("需关注"), ALL("全部"), READY("可导入"), DUPLICATE("重复");

    fun select(items: List<QianjiImport.Item>): List<QianjiImport.Item> = when (this) {
        ALL -> items
        ATTENTION -> items.filter { it.state == QianjiImport.State.SKIPPED || it.state == QianjiImport.State.CONFLICT }
        READY -> items.filter { it.state == QianjiImport.State.READY }
        DUPLICATE -> items.filter { it.state == QianjiImport.State.DUPLICATE }
    }
}

internal const val QIANJI_PREVIEW_PAGE_SIZE = 50
