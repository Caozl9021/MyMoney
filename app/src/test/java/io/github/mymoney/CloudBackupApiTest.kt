package io.github.mymoney

import io.github.mymoney.data.MiniJson
import io.github.mymoney.data.rulesync.CloudBackupApi
import io.github.mymoney.data.rulesync.CloudBackupIdentity
import io.github.mymoney.data.rulesync.CloudBackupRetryPolicy
import io.github.mymoney.data.rulesync.FamilyTransport
import io.github.mymoney.data.rulesync.RuleSyncApiException
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

/** Verify identity wire boundaries: credentials are never UUIDs, and old/global backup APIs fail closed. */
class CloudBackupApiTest {
    private val server = "http://127.0.0.1:18080"
    private class Wire(val status: Int = 200, val result: String) : FamilyTransport {
        var method = ""; var url = ""; var headers: Map<String, String> = emptyMap(); var payload = ""; var closed = false
        override fun execute(method: String, url: String, headers: Map<String, String>, body: FamilyTransport.RequestBody?, connectTimeoutMs: Int, readTimeoutMs: Int): FamilyTransport.Response {
            this.method = method; this.url = url; this.headers = headers
            payload = (body as? FamilyTransport.RequestBody.Bytes)?.bytes?.toString(Charsets.UTF_8).orEmpty()
            return object : FamilyTransport.Response {
                override val status = this@Wire.status
                override val headers = emptyMap<String, List<String>>()
                override val contentLength = result.toByteArray().size.toLong()
                override fun bodyStream() = ByteArrayInputStream(result.toByteArray())
                override fun errorStream() = bodyStream()
                override fun close() { closed = true }
            }
        }
    }
    @Test fun ownSourceCapabilityRequired() {
        val wire = Wire(result = """{"deviceIdentityVersion":1,"ownBackupsOnly":false}""")
        assertTrue(runCatching { CloudBackupApi.capabilities(server, wire) }.isFailure)
        assertTrue(wire.closed)
    }
    @Test fun oldServerPromptsUpgradeWithoutLegacyPairFallback() {
        val wire = Wire(404, """{"error":"Not found"}""")
        val error = runCatching { CloudBackupApi.capabilities(server, wire) }.exceptionOrNull()
        assertTrue(error is RuleSyncApiException)
        assertTrue(error!!.message!!.contains("升级"))
        assertTrue(wire.url.endsWith("/v1/backup-capabilities"))
    }
    @Test fun replacementHasGrantSecretAndExpectedGeneration() {
        val wire = Wire(result = """{"deviceId":"source-a","spaceId":"space","epoch":"epoch","credentialGeneration":3}""")
        val source = CloudBackupApi.Source("source-a", "同名手机", null, null, 2, false, false)
        val result = CloudBackupApi.complete(server, "short-lived-grant", "installation", "operation", "secret-256-bit", "手机", source, wire)
        assertEquals("Bearer short-lived-grant", wire.headers["Authorization"])
        val sent = MiniJson.decode(wire.payload) as Map<*, *>
        assertEquals("source-a", sent["sourceId"])
        assertEquals(2L, (sent["expectedGeneration"] as Number).toLong())
        assertEquals("secret-256-bit", sent["credential"])
        assertEquals(3L, result.credentialGeneration)
        assertFalse(wire.url.contains("secret-256-bit"))
        assertTrue(wire.closed)
    }
    @Test fun newDeviceDoesNotInferSourceFromItsName() {
        val wire = Wire(result = """{"deviceId":"new","spaceId":"space","epoch":"epoch","credentialGeneration":1}""")
        CloudBackupApi.complete(server, "grant", "installation", "operation", "secret", "同名手机", null, wire)
        val sent = MiniJson.decode(wire.payload) as Map<*, *>
        assertNull(sent["sourceId"])
        assertNull(sent["expectedGeneration"])
    }
    @Test fun credentialIsIndependent256BitSecret() {
        val first = CloudBackupIdentity.randomCredential()
        val second = CloudBackupIdentity.randomCredential()
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(first).size)
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    @Test fun capabilitiesReportNewFeatures() {
        val wire = Wire(result = """{"deviceIdentityVersion":1,"ownBackupsOnly":true,"deleteBackups":true,"backupContext":true}""")
        val features = CloudBackupApi.capabilities(server, wire)
        assertTrue(features.deleteBackups)
        assertTrue(features.backupContext)
        val legacy = Wire(result = """{"deviceIdentityVersion":1,"ownBackupsOnly":true}""")
        assertFalse(CloudBackupApi.capabilities(server, legacy).backupContext)
    }

    @Test fun contextCarriesIdentityNewestBackupAndInstallationState() {
        val id = java.util.UUID.randomUUID().toString()
        val wire = Wire(result = """{"protocolVersion":1,
            |"identity":{"deviceId":"device-a","spaceId":"space","epoch":"epoch","credentialGeneration":2,"name":"手机"},
            |"capabilities":{"deviceIdentityVersion":1,"ownBackupsOnly":true,"deleteBackups":true,"backupContext":true},
            |"installationId":"inst-1","installationMatches":false,
            |"latestBackup":{"id":"$id","deviceName":"手机","appVersion":"1.9.0/155","backupSchema":10,
            |"sha256":"${"b".repeat(64)}","sizeBytes":2048,"billCount":7,
            |"snapshotAt":"2026-11-06T00:00:00Z","uploadedAt":"2026-11-06T00:01:00Z"}}""".trimMargin())
        val context = CloudBackupApi.context(server, "token", "inst-1", wire)
        assertEquals("GET", wire.method)
        assertTrue(wire.url.endsWith("/v1/backup-context?installationId=inst-1"))
        assertEquals("Bearer token", wire.headers["Authorization"])
        assertEquals("device-a", context.identity.deviceId)
        assertEquals(2L, context.identity.credentialGeneration)
        assertEquals("inst-1", context.installationId)
        assertEquals(false, context.installationMatches)
        assertEquals(id, context.latestBackup?.id)
        assertEquals(7L, context.latestBackup?.billCount)
        assertTrue(context.features.deleteBackups)
        assertTrue(wire.closed)
    }

    @Test fun oldServerWithoutContextAsksForUpgrade() {
        val wire = Wire(404, """{"error":"Not found"}""")
        val error = runCatching { CloudBackupApi.context(server, "token", "inst", wire) }.exceptionOrNull()
        assertTrue(error is RuleSyncApiException)
        assertTrue(error!!.message!!.contains("升级"))
    }

    @Test fun lostReceiptRetryCannotChangeNameSourceOrGeneration() {
        assertTrue(CloudBackupRetryPolicy.sameRequest("手机", "source-a", 4, "手机", "source-a", 4))
        assertFalse(CloudBackupRetryPolicy.sameRequest("手机", "source-a", 4, "改名", "source-a", 4))
        assertFalse(CloudBackupRetryPolicy.sameRequest("手机", "source-a", 4, "手机", "source-b", 4))
        assertFalse(CloudBackupRetryPolicy.sameRequest("手机", "source-a", 4, "手机", "source-a", 5))
        assertFalse(CloudBackupRetryPolicy.sameRequest("手机", "source-a", 4, "手机", null, null))
        assertFalse(CloudBackupRetryPolicy.sameRequest("手机", null, null, "手机", "source-a", 4))
    }

}
