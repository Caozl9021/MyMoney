package com.example.billkit.spec

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The atomic state file is authoritative. Database and engines are recoverable projections. */
class RulePackageFiles(private val directory: File) {
    data class State(val active: String? = null, val previous: String? = null,
        val canRollback: Boolean = false, val enabled: Map<String, Boolean> = emptyMap(),
        val versions: Map<String, String> = emptyMap(), val personalRules: List<PersonalRule> = emptyList())
    fun state(): State {
        val f = File(directory, "state.json")
        if (!f.exists()) return State()
        val m = SpecJson.decodeObject(f.readText())
        fun ref(key: String): String? = (m[key] as? String)?.also { require(Regex("[a-f0-9]{64}").matches(it)) { "规则状态损坏" } }
        val enabled = (m["enabled"] as? Map<*, *>).orEmpty().map { (k,v) -> (k as String) to (v as Boolean) }.toMap()
        val versions = (m["versions"] as? Map<*, *>).orEmpty().map { (k,v) -> (k as String) to (v as String) }.toMap()
        return State(ref("active"), ref("previous"), m["canRollback"] == true, enabled, versions,
            m["personalRules"]?.let { PersonalRuleCodec.decodeRows(it) }.orEmpty())
    }
    fun load(ref: String): PortableRulePackage {
        require(Regex("[a-f0-9]{64}").matches(ref))
        return File(directory, "$ref.zip").inputStream().use { PortableRulePackage.load(it) }.also { require(it.digest == ref) { "活动规则文件损坏" } }
    }
    fun stage(bytes: ByteArray, pack: PortableRulePackage) {
        require(PortableRulePackage.hash(bytes) == pack.digest)
        val f = File(directory, "${pack.digest}.zip")
        if (!f.exists()) atomic(f, bytes)
    }
    fun commit(state: State) {
        atomic(File(directory, "state.json"), SpecJson.encode(mapOf("active" to state.active,
            "previous" to state.previous, "canRollback" to state.canRollback,
            "enabled" to state.enabled, "versions" to state.versions, "personalRules" to state.personalRules.map { it.json() })).toByteArray())
    }
    /** Only unreferenced copies made by this manager, never user source files. */
    fun prune(state: State) {
        directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.zip")) &&
            it.nameWithoutExtension !in setOf(state.active, state.previous) }?.forEach { it.delete() }
    }
    private fun atomic(file: File, bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
