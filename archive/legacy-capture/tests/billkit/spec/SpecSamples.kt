package com.example.billkit.spec

/** 原创回放：来源与预期均显式记录，合成输入不冒充设备采集。 */
object SpecSamples {
    data class Sample(
        val id: String, val packageName: String, val type: String, val data: String,
        val provenance: String, val evidence: String, val expectMatch: Boolean,
        val expectedRuleId: String, val expectedType: String, val expectedCents: Long,
        val expectedMerchant: String, val expectedExplicitTime: Boolean, val expectedTimeMillis: Long,
    ) { val label: String get() = id }

    fun load(): List<Sample> = parse(SpecAssets.classpath("spec/samples.json"))
    fun parse(json: String): List<Sample> {
        val root = SpecJson.decodeObject(json)
        require((root["schemaVersion"] as? Number)?.toInt() == 1)
        return (root["samples"] as? List<*>)?.map { raw ->
            val row = raw as Map<*, *>
            val expected = row["expect"] as Map<*, *>
            Sample(
                row["id"] as String, row["app"] as String, row["type"] as String,
                SpecJson.encode(row["raw"]), row["provenance"] as String, row["evidence"] as String,
                expected["match"] == true, expected["ruleId"] as? String ?: "",
                expected["billType"] as? String ?: "", (expected["cents"] as? Number)?.toLong() ?: 0,
                expected["merchant"] as? String ?: "", expected["hasExplicitTime"] == true, (expected["time"] as? Number)?.toLong() ?: 0L,
            )
        } ?: error("缺少原创回放数组")
    }
}

internal fun originalEngine() = SpecRuleEngine().apply {
    rebuild(SpecAssets.classpath(SpecAssets.COMMON_JS), SpecAssets.parseRules(SpecAssets.classpath(SpecAssets.RULES_ALL)))
}
