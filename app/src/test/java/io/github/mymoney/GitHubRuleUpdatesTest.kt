package io.github.mymoney

import io.github.mymoney.data.GitHubRuleUpdates
import io.github.mymoney.data.MiniJson
import org.junit.Assert.*
import org.junit.Test

class GitHubRuleUpdatesTest {
    private fun release(version:String="1.2.0", draft:Boolean=false, prerelease:Boolean=false,
        name:String="MyMoney-rules-$version.mymoney-rules.zip",url:String="https://github.com/Caozl9021/MyMoney/releases/download/rules-v$version/$name",size:Long=1024)=
        mapOf("draft" to draft,"prerelease" to prerelease,"assets" to listOf(mapOf("name" to name,"browser_download_url" to url,"size" to size)))
    private fun zip():ByteArray {
        val root=java.io.File("../billkit/src/main/assets/spec")
        val names=listOf("rules_all.json","js_common.js","js_category.js","categories.json","assets.json","samples.json")
        val files=names.associateWith{if(it=="samples.json") java.io.File("../billkit/src/test/resources/spec/samples.json").readText() else java.io.File(root,it).readText()}.toMutableMap()
        val original=MiniJson.decode(java.io.File(root,"manifest.json").readText()) as Map<*, *>
        val manifest=original.entries.associate{it.key.toString() to it.value}+mapOf(
            "kind" to "MyMoneyRulePackage","packageId" to "mymoney-official","version" to "1.2.0","packageVersion" to "1.2.0",
            "engineApiVersion" to 1,"requiredCapabilities" to emptyList<String>(),"publishedAt" to "2026-11-01T12:30:00+08:00","notes" to "network fixture",
            "files" to files.mapValues{com.example.billkit.spec.PortableRulePackage.hash(it.value.toByteArray())},
            "rules" to (original["rules"] as List<*>).map{row->(row as Map<*, *>).entries.associate{it.key.toString() to it.value}+mapOf("revision" to 1)})
        files["manifest.json"]=MiniJson.encode(manifest)
        return java.io.ByteArrayOutputStream().also{out->java.util.zip.ZipOutputStream(out).use{z->files.forEach{(n,t)->z.putNextEntry(java.util.zip.ZipEntry(n));z.write(t.toByteArray());z.closeEntry()}}}.toByteArray()
    }
    @Test fun downloadedCompletePackagePassesProductionEngineAndVersionMismatchRejects() {
        val bytes=zip()
        val release=GitHubRuleUpdates.Release("1.2.0","fixture.zip","https://github.com/fixture",bytes.size.toLong(),com.example.billkit.spec.PortableRulePackage.hash(bytes))
        val downloaded=GitHubRuleUpdates.download(release){_,_->bytes}
        assertTrue(com.example.billkit.spec.PortableRulePackage.load(downloaded).validate()>100)
        assertThrows(IllegalArgumentException::class.java){GitHubRuleUpdates.download(release.copy(version="1.3.0")){_,_->bytes}}
    }
    @Test fun appOnlyReleaseDoesNotCountAsRules() {
        assertTrue(GitHubRuleUpdates.parse(MiniJson.encode(listOf(release(name="MyMoney-9.0.0-master-release.apk")))).isEmpty())
    }
    @Test fun acceptsAppAttachedAndPureRuleAssetIndependentlyOfTag() {
        assertEquals("1.2.0",GitHubRuleUpdates.parse(MiniJson.encode(listOf(release()+mapOf("tag_name" to "v8.0.0")))).single().version)
    }
    @Test fun draftsPreReleasesWrongOriginAndOversizedAreExcluded() {
        for(r in listOf(release(draft=true),release(prerelease=true),release(url="http://github.com/test"),
            release(url="https://github.com/other/repo/releases/download/x/y"),release(size=999999999),
            release(version="99999999999999.1.0"),release()+mapOf("assets" to emptyList<Any>()))) {
            assertTrue(GitHubRuleUpdates.parse(MiniJson.encode(listOf(r))).isEmpty())
        }
    }
    @Test fun semanticVersionWinsOverReleaseOrdering() {
        val bytes=MiniJson.encode(listOf(release("1.9.0"),release("1.10.0"),release("1.2.0"))).toByteArray()
        assertEquals("1.10.0",GitHubRuleUpdates.latest { _,_ -> bytes }!!.version)
    }
    @Test fun paginatesPastAppReleasesAndReportsNoPackage() {
        var calls=0
        val latest=GitHubRuleUpdates.latest { _,_ -> calls++;MiniJson.encode(if(calls==1) List(100){release(name="app.apk")} else listOf(release())).toByteArray() }
        assertEquals(2,calls);assertEquals("1.2.0",latest!!.version)
        assertNull(GitHubRuleUpdates.latest { _,_ -> "[]".toByteArray() })
    }
    @Test fun transportFailureIsNotReportedAsUpToDate() {
        assertThrows(java.io.IOException::class.java) {GitHubRuleUpdates.latest { _,_ -> throw java.io.IOException("offline") }}
    }
    @Test fun downloadRejectsTruncationAndDigestMismatchBeforeActivation() {
        val r=GitHubRuleUpdates.Release("1.2.0","rules.zip","https://github.com/test",20,null)
        assertThrows(IllegalArgumentException::class.java) {GitHubRuleUpdates.download(r){_,_->byteArrayOf(1)}}
        assertThrows(IllegalArgumentException::class.java) {GitHubRuleUpdates.download(r.copy(size=1,digest="0".repeat(64))){_,_->byteArrayOf(1)}}
    }
}
