package com.example.billkit.spec

import com.example.billkit.notification.NotificationTemplateConfig
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Portable, complete snapshot. Never extracts ZIP paths into the filesystem. */
data class PortableRulePackage(
    val version: String, val digest: String, val files: Map<String, String>,
    val bundle: SpecRuleBundle, val notes: String,
) {
    val publishedAt: String? get() = SpecJson.decodeObject(files.getValue("manifest.json"))["publishedAt"] as? String
    val notificationConfig: NotificationTemplateConfig? get() = files[NotificationTemplateConfig.FILE]?.let(NotificationTemplateConfig::decode)
    fun read(assetPath: String): String = files.getValue(assetPath.removePrefix("spec/"))
    fun validate(): Int {
        val all = bundle.rules.map { it.copy(enabled = true) }
        val engine = SpecRuleEngine()
        try {
            engine.rebuild(read(SpecAssets.COMMON_JS), all)
            SpecCategoryEngine().apply {
                try { rebuild(read(SpecAssets.COMMON_JS), read(SpecAssets.CATEGORY_JS)) } finally { close() }
            }
            val root = SpecJson.decodeObject(files.getValue("samples.json"))
            require((root["schemaVersion"] as? Number)?.toInt() == 1) { "自检样本版本无效" }
            val rows = root["samples"] as? List<*> ?: error("缺少自检样本")
            require(rows.size in 1..1024) { "自检样本数量超限" }
            val positives = mutableSetOf<String>()
            val negatives = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            val until = System.nanoTime() + 30_000_000_000L
            rows.forEach { value ->
                require(System.nanoTime() < until) { "自检总耗时超限" }
                val row = value as? Map<*, *> ?: error("自检样本无效")
                val id = row["id"] as? String ?: error("样本ID缺失")
                require(ids.add(id)) { "样本ID重复" }
                require(row["provenance"] in setOf("synthetic", "captured-redacted", "transcribed") &&
                    (row["evidence"] as? String).orEmpty().isNotBlank()) { "样本来源缺失" }
                val app = row["app"] as? String ?: error("样本应用缺失")
                val type = row["type"] as? String ?: error("样本类型缺失")
                val expect = row["expect"] as? Map<*, *> ?: error("样本预期缺失")
                val input = SpecJson.encode(row["raw"])
                require(input.length <= 65_536) { "样本输入超限" }
                val parsed = engine.parseDetailed(app, type, input)
                require(parsed.outcome != SpecRuleEngine.Outcome.ERROR) { "样本执行失败：$id" }
                val hit = parsed.result
                if (expect["match"] == true) {
                    require(hit != null && hit.ruleId == expect["ruleId"] &&
                        hit.bill.type == expect["billType"] && hit.bill.priceCents == (expect["cents"] as? Number)?.toLong() &&
                        hit.bill.shopName == expect["merchant"] && hit.bill.hasExplicitTime == expect["hasExplicitTime"] &&
                        hit.bill.time == (expect["time"] as? Number)?.toLong()) { "自检预期不符：$id" }
                    if (expect.containsKey("method")) require(hit.bill.accountNameFrom == expect["method"]) { "支付方式不符：$id" }
                    positives.add(hit.ruleId)
                } else {
                    require(expect["match"] == false && hit == null) { "反例误命中：$id" }
                    // Existing fixture IDs start with the tested stable rule ID.
                    val target = row["ruleId"] as? String ?: all.firstOrNull { id.startsWith(it.systemRuleName + "-") }?.systemRuleName
                    if (target != null) negatives.add(target)
                }
            }
            require(all.all { it.systemRuleName in positives && it.systemRuleName in negatives }) { "每条规则必须包含正反例" }
            return rows.size + (notificationConfig?.validateSamples() ?: 0)
        } finally { engine.close() }
    }

    companion object {
        const val MAX_ZIP = 8 * 1024 * 1024
        const val MAX_FILE = 4 * 1024 * 1024
        private val baseNames = (SpecRuleBundle.FILES + "samples.json" + "manifest.json").toSet()
        private val names = baseNames + NotificationTemplateConfig.FILE
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun readBytes(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
                require(out.size() <= limit) { "规则文件大小超限" }
            }
            return out.toByteArray()
        }
        fun load(input: InputStream): PortableRulePackage = load(readBytes(input, MAX_ZIP))
        fun load(bytes: ByteArray): PortableRulePackage {
            require(bytes.size <= MAX_ZIP) { "规则包超过8MiB" }
            rejectSymlinks(bytes)
            val contents = linkedMapOf<String, String>()
            var size = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in names && !contents.containsKey(entry.name)) { "规则包包含未知路径或重复文件" }
                    val value = readBytes(zip, MAX_FILE)
                    size += value.size
                    require(size <= 16 * 1024 * 1024) { "规则包解压超限" }
                    contents[entry.name] = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
                }
            }
            require(contents.keys.containsAll(baseNames)) { "规则包必须包含清单、五份资产和自检样本" }
            val m = SpecJson.decodeObject(contents.getValue("manifest.json"))
            require(m["kind"] == "MyMoneyRulePackage" && (m["schemaVersion"] as? Number)?.toDouble() == 1.0) { "规则包格式不支持" }
            require(m["packageId"] == "mymoney-official") { "规则包来源ID不支持" }
            require((m["engineApiVersion"] as? Number)?.toDouble() == 1.0) { "需要支持对应规则引擎API的App" }
            val caps = m["requiredCapabilities"] as? List<*> ?: error("规则能力声明缺失")
            require(caps.all { it in setOf("wechat-data-v1", "alipay-data-v1", "notice-v1", NotificationTemplateConfig.CAPABILITY) }) { "此App缺少规则要求的采集能力" }
            require(runCatching { java.time.OffsetDateTime.parse(m["publishedAt"] as? String ?: "") }.isSuccess) { "发布时间缺失或无时区" }
            require((m["notes"] as? String)?.length?.let { it <= 4000 } == true) { "更新说明缺失或过长" }
            val version = m["packageVersion"] as? String ?: error("规则版本缺失")
            require(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})").matches(version) && m["version"] == version) { "规则版本必须是独立的X.Y.Z" }
            val hashes = m["files"] as? Map<*, *> ?: error("文件摘要缺失")
            require(hashes.keys == contents.keys - "manifest.json") { "摘要清单无效" }
            hashes.forEach { (name, hash) -> require(hash == hash(contents.getValue(name as String).toByteArray(Charsets.UTF_8))) { "文件摘要不一致：$name" } }
            val hasNotificationConfig = contents.containsKey(NotificationTemplateConfig.FILE)
            require(hasNotificationConfig == (NotificationTemplateConfig.CAPABILITY in caps)) { "通知配置必须声明对应能力且包含配置文件" }
            val notifications = contents[NotificationTemplateConfig.FILE]?.let(NotificationTemplateConfig::decode)
            val adapted = m.toMutableMap().apply { put("files", hashes.filterKeys { it in SpecRuleBundle.FILES }) }
            val bundle = SpecRuleBundle.load { path ->
                if (path == SpecRuleBundle.MANIFEST) SpecJson.encode(adapted) else contents.getValue(path.removePrefix("spec/"))
            }
            require(bundle.rules.size <= 256 && bundle.rules.all { it.app in setOf("com.tencent.mm", "com.eg.android.AlipayGphone") &&
                it.js.toByteArray().size <= 256 * 1024 }) { "规则条数、脚本大小或应用范围无效" }
            val entries = m["rules"] as List<*>
            require(entries.map { (it as? Map<*, *>)?.get("id") } == bundle.rules.map { it.systemRuleName }) { "规则顺序清单不一致" }
            require(entries.all { ((it as? Map<*, *>)?.get("revision") as? Number)?.toDouble()?.let { n -> n > 0 && n <= Int.MAX_VALUE && n == kotlin.math.floor(n) } == true }) { "规则修订号缺失" }
            require(notifications == null || notifications.templates.all { t -> bundle.rules.any { it.systemRuleName==t.ruleId && it.type=="NOTICE" && it.app==t.packageName } }) { "通知模板关联规则不存在" }
            return PortableRulePackage(version, hash(bytes), contents, bundle, (m["notes"] as? String).orEmpty())
        }
        private fun rejectSymlinks(bytes: ByteArray) {
            fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
            fun u32(i: Int) = (u16(i).toLong() or (u16(i + 2).toLong() shl 16))
            fun signature(i: Int, low: Int, high: Int) = i >= 0 && i + 4 <= bytes.size && u16(i) == 0x4b50 && u16(i+2) == low + (high shl 8)
            val end = bytes.size - 22
            require(signature(end, 5, 6) && u16(end+20) == 0 && u16(end+4) == 0 && u16(end+6) == 0 &&
                u16(end+8) in baseNames.size..names.size && u16(end+10) == u16(end+8)) { "ZIP目录不完整或格式不支持" }
            val start = u32(end+16)
            require(start + u32(end+12) == end.toLong() && start <= end) { "ZIP目录位置无效" }
            var i = start.toInt()
            val seen = mutableSetOf<String>()
            repeat(u16(end+8)) {
                require(i + 46 <= end && signature(i, 1, 2)) { "ZIP目录损坏" }
                require(u16(i+40) and 0xF000 != 0xA000 && u16(i+8) and 1 == 0) { "不支持符号链接或加密ZIP" }
                val length = u16(i+28); val next = i + 46 + length + u16(i+30) + u16(i+32)
                require(next <= end) { "ZIP目录越界" }
                val name = bytes.copyOfRange(i+46,i+46+length).toString(Charsets.UTF_8)
                require(name in names && seen.add(name)) { "ZIP目录路径无效或重复" }
                i = next
            }
            require(i == end) { "ZIP目录含额外条目" }
        }
        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.toInt() }; val right = b.split('.').map { it.toInt() }
            for (i in 0..2) { val c = left[i].compareTo(right[i]); if (c != 0) return c }
            return 0
        }
    }
}
