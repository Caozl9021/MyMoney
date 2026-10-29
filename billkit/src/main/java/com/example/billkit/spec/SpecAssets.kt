package com.example.billkit.spec

import java.io.InputStream

/**
 * `_spec/data/` 里那几个数据文件在 :billkit 里的位置。
 *
 * 这些文件同时是 Android assets（`billkit/src/main/assets/spec/`）和离线重放测试的
 * classpath 资源（`sourceSets.test.resources.srcDir("src/main/assets")`），因此加载器
 * 只依赖"按名字开一个流"，两端各自注入。
 */
object SpecAssets {
    const val COMMON_JS = "spec/js_common.js"
    const val CATEGORY_JS = "spec/js_category.js"
    const val RULES_ALL = "spec/rules_all.json"
    const val CATEGORIES = "spec/categories.json"
    const val ASSETS = "spec/assets.json"

    /** 从 classpath 读（JVM 单测与任何带 resources 的环境）。 */
    fun classpath(name: String): String {
        val stream: InputStream = SpecAssets::class.java.classLoader
            ?.getResourceAsStream(name)
            ?: throw IllegalStateException("找不到规格资产 $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 解析 `rules_all.json` 成规则列表，顺序保持文件内的顺序（引擎按注册顺序短路）。 */
    fun parseRules(json: String): List<SpecRuleModel> =
        SpecJson.decodeArray(json).mapNotNull { SpecRuleModel.fromJson(it) }
}

/** 分类表一行（对应 `categories.json`）。 */
data class SpecCategory(
    val id: Long,
    val name: String,
    val remoteId: String,
    val book: Long,
    val sort: Long,
    val type: String,
) {
    companion object {
        fun fromJson(raw: Any?): SpecCategory? {
            val map = raw as? Map<*, *> ?: return null
            return SpecCategory(
                id = (map["id"] as? Number)?.toLong() ?: return null,
                name = map["name"] as? String ?: return null,
                remoteId = map["remoteId"]?.toString().orEmpty(),
                book = (map["book"] as? Number)?.toLong() ?: 0L,
                sort = (map["sort"] as? Number)?.toLong() ?: 0L,
                type = map["type"] as? String ?: "",
            )
        }

        fun parseAll(json: String): List<SpecCategory> =
            SpecJson.decodeArray(json).mapNotNull { fromJson(it) }
    }
}

/** 资产一行（对应 `assets.json`）。`type` 是 NORMAL / CREDITOR 等。 */
data class SpecAsset(
    val id: Long,
    val name: String,
    val type: String,
    val currency: String,
) {
    companion object {
        fun fromJson(raw: Any?): SpecAsset? {
            val map = raw as? Map<*, *> ?: return null
            return SpecAsset(
                id = (map["id"] as? Number)?.toLong() ?: return null,
                name = map["name"] as? String ?: return null,
                type = map["type"] as? String ?: "",
                currency = map["currency"] as? String ?: "",
            )
        }

        fun parseAll(json: String): List<SpecAsset> =
            SpecJson.decodeArray(json).mapNotNull { fromJson(it) }
    }
}
