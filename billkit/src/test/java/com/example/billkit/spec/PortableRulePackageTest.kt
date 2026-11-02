package com.example.billkit.spec

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableRulePackageTest {
    private fun files(): MutableMap<String,String> {
        val f = SpecRuleBundle.FILES.associateWith { SpecAssets.classpath("spec/$it") }.toMutableMap()
        f["samples.json"] = SpecAssets.classpath("spec/samples.json")
        val base = SpecJson.decodeObject(SpecAssets.classpath("spec/manifest.json")).toMutableMap()
        base.putAll(mapOf("kind" to "MyMoneyRulePackage", "schemaVersion" to 1, "packageId" to "mymoney-official",
            "publishedAt" to "2026-10-31T13:00:00+08:00", "notes" to "test", "packageVersion" to "1.0.0", "version" to "1.0.0", "engineApiVersion" to 1, "requiredCapabilities" to listOf("wechat-data-v1"),
            "files" to f.mapValues { PortableRulePackage.hash(it.value.toByteArray()) },
            "rules" to (base["rules"] as List<*>).map { (it as Map<*,*>).entries.associate { p -> p.key.toString() to p.value } + ("revision" to 1) }))
        f["manifest.json"] = SpecJson.encode(base)
        return f
    }
    private fun zip(f: Map<String,String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> f.forEach { (n,v) -> z.putNextEntry(ZipEntry(n)); z.write(v.toByteArray()); z.closeEntry() } }
    }.toByteArray()
    private fun invalid(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    private fun changeManifest(f: MutableMap<String,String>, key: String, value: Any) {
        f["manifest.json"] = SpecJson.encode(SpecJson.decodeObject(f.getValue("manifest.json")) + (key to value))
    }
    private fun withNotifications(): MutableMap<String,String> {
        val f=files();f["notification-templates.json"]=SpecAssets.classpath("spec/notification-templates.json")
        changeManifest(f,"requiredCapabilities",listOf("notification-template-v1"))
        changeManifest(f,"files",f.filterKeys { it!="manifest.json" }.mapValues { PortableRulePackage.hash(it.value.toByteArray()) })
        return f
    }
    @Test fun optionalNotificationSnapshotRunsItsOwnProductionSelfTests() {
        val pack=PortableRulePackage.load(zip(withNotifications()))
        assertEquals(1,pack.notificationConfig!!.templates.size);assertEquals(200,pack.validate())
    }
    @Test fun notificationCapabilityAndFileMustAgree() {
        val f=withNotifications();changeManifest(f,"requiredCapabilities",emptyList<String>());invalid {PortableRulePackage.load(zip(f))}
        val old=files();changeManifest(old,"requiredCapabilities",listOf("notification-template-v1"));invalid {PortableRulePackage.load(zip(old))}
    }
    @Test fun notificationConfigHashOrSelfTestFailureRejects() {
        val f=withNotifications();f["notification-templates.json"]="bad";invalid {PortableRulePackage.load(zip(f))}
        val bad=withNotifications();val n=SpecJson.decodeObject(bad.getValue("notification-templates.json")).toMutableMap();n["samples"]=emptyList<Any>()
        bad["notification-templates.json"]=SpecJson.encode(n);changeManifest(bad,"files",bad.filterKeys { it!="manifest.json" }.mapValues {PortableRulePackage.hash(it.value.toByteArray())})
        invalid {PortableRulePackage.load(zip(bad)).validate()}
    }
    @Test fun completeSnapshotPassesRealEngineAndAllSamples() {
        val p = PortableRulePackage.load(zip(files()))
        assertEquals("1.0.0", p.version); assertEquals(15, p.bundle.rules.size); assertEquals(183, p.validate())
    }
    @Test fun unsupportedApiAndCapabilitiesReject() {
        val f = files(); changeManifest(f,"engineApiVersion",2); invalid { PortableRulePackage.load(zip(f)) }
        changeManifest(f,"engineApiVersion",1); changeManifest(f,"requiredCapabilities",listOf("new-input")); invalid { PortableRulePackage.load(zip(f)) }
    }
    @Test fun corruptFileOrMissingMemberReject() {
        val f = files(); f["js_common.js"] = "bad"; invalid { PortableRulePackage.load(zip(f)) }
        f.remove("assets.json"); invalid { PortableRulePackage.load(zip(f)) }
    }
    @Test fun pathTraversalAndUnknownMemberRejectWithoutExtraction() {
        val f = files(); f["../escape"] = "bad"; invalid { PortableRulePackage.load(zip(f)) }
    }
    @Test fun expandedContentHasActualByteLimit() {
        val f = files(); f["samples.json"] = "a".repeat(PortableRulePackage.MAX_FILE+1); invalid { PortableRulePackage.load(zip(f)) }
    }
    @Test fun wrongSelfTestFailsBeforeActivation() {
        val f=files(); val samples=SpecJson.decodeObject(f.getValue("samples.json")).toMutableMap()
        samples["samples"] = (samples["samples"] as List<*>).take(1)
        f["samples.json"] = SpecJson.encode(samples)
        val m=SpecJson.decodeObject(f.getValue("manifest.json")).toMutableMap()
        m["files"] = f.filterKeys { it!="manifest.json" }.mapValues { PortableRulePackage.hash(it.value.toByteArray()) }
        f["manifest.json"] = SpecJson.encode(m)
        invalid { PortableRulePackage.load(zip(f)).validate() }
    }
    @Test fun javaBridgeIsUnavailableAndInfiniteLoopHasBudget() {
        val e=SpecRuleEngine()
        val model=SpecRuleModel("com.tencent.mm","DATA","test","test",js="var test={get:function(){return java.lang.System.getProperty('user.home');}};")
        e.rebuild("",listOf(model)); assertNull(e.parse("com.tencent.mm","DATA","{}")); assertEquals(SpecRuleEngine.Outcome.ERROR,e.lastOutcome)
        val start=System.nanoTime()
        e.rebuild("",listOf(model.copy(js="var test={get:function(){while(true){} }};")))
        assertNull(e.parse("com.tencent.mm","DATA","{}")); assertEquals(SpecRuleEngine.Outcome.ERROR,e.lastOutcome)
        assertTrue(System.nanoTime()-start < 5_000_000_000L)
        try { e.rebuild("while(true){}",listOf(model)); fail("compile evaluation budget") } catch (_: IllegalArgumentException) {}
        e.close()
    }
    @Test fun journalCommitIsAuthoritativeAndStagingCannotActivate() {
        val dir=Files.createTempDirectory("mymoney-rule-journal").toFile()
        try {
            val store=RulePackageFiles(dir); val bytes=zip(files());val pack=PortableRulePackage.load(bytes)
            store.stage(bytes,pack); assertNull(RulePackageFiles(dir).state().active)
            val state=RulePackageFiles.State(active=pack.digest,canRollback=true,enabled=mapOf("disabled-id" to false))
            store.commit(state)
            assertEquals(state,RulePackageFiles(dir).state()); assertEquals(pack.version,store.load(pack.digest).version)
            store.commit(state.copy(active=null,previous=pack.digest)); assertNull(store.state().active)
            assertFalse(store.state().enabled.getValue("disabled-id"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun truncatedArchiveDuplicateJsonKeysAndDeepJsonReject() {
        val bytes=zip(files())
        invalid { PortableRulePackage.load(bytes.copyOf(bytes.size-1)) }
        invalid { SpecJson.decode("{\"a\":1,\"a\":2}") }
        invalid { SpecJson.decode("[".repeat(40)+"0"+"]".repeat(40)) }
    }
    @Test fun symlinkMetadataRejectsEvenWithoutFilesystemExtraction() {
        val bytes=zip(files())
        for(i in 0 until bytes.size-46) {
            if(bytes[i]==0x50.toByte() && bytes[i+1]==0x4b.toByte() && bytes[i+2]==1.toByte() && bytes[i+3]==2.toByte()) {
                bytes[i+40]=0xff.toByte();bytes[i+41]=0xa1.toByte(); break
            }
        }
        invalid { PortableRulePackage.load(bytes) }
    }
    @Test fun fractionalApiAndSchemaReject() {
        val f=files(); changeManifest(f,"engineApiVersion",1.5);invalid { PortableRulePackage.load(zip(f)) }
        changeManifest(f,"engineApiVersion",1);changeManifest(f,"schemaVersion",1.5);invalid { PortableRulePackage.load(zip(f)) }
    }
    @Test fun semanticVersionComparisonIsNumeric() {
        assertTrue(PortableRulePackage.compareVersions("1.10.0","1.9.9")>0)
        assertTrue(PortableRulePackage.compareVersions("0.9.9","1.0.0")<0)
    }
}
