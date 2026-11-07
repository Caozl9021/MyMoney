package io.github.mymoney

import io.github.mymoney.data.rulesync.TsnetSeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * U3.4: seal/unseal round-trip, atomicity and tamper handling for the tsnet identity
 * store. Uses a plain AES-GCM [SecretKey] so it runs on the JVM; TsnetIdentity wires
 * the same [TsnetSeal] to a Keystore key + noBackupFilesDir on device.
 */
class TsnetSealTest {

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun tmp(): File = File.createTempFile("seal", ".d").apply { delete(); mkdirs() }

    @Test
    fun roundTripRestoresExactTree() {
        val k = key()
        val root = tmp()
        File(root, "tsnet").mkdirs()
        File(root, "tsnet/backend-state").writeBytes(byteArrayOf(0, 1, 2, 3, 0xff.toByte()))
        File(root, "logs.txt").writeText("hello 世界")
        val out = File(tmp(), "x.sealed")
        assertTrue(TsnetSeal.seal(k, root, out))
        assertTrue(out.isFile && out.length() > 0)

        val dest = File(tmp(), "restored")
        assertTrue(TsnetSeal.unseal(k, out, dest))
        assertEquals(
            File(root, "logs.txt").readText(),
            File(dest, "logs.txt").readText(),
        )
        assertArrayEquals(
            File(root, "tsnet/backend-state").readBytes(),
            File(dest, "tsnet/backend-state").readBytes(),
        )
    }

    @Test
    fun emptyDirSealIsNoop() {
        val root = tmp()
        val out = File(tmp(), "e.sealed")
        assertTrue(TsnetSeal.seal(key(), root, out))
        assertFalse(out.exists())
    }

    @Test
    fun tamperedBlobFails() {
        val root = tmp()
        File(root, "a").writeText("secret-node-key")
        val out = File(tmp(), "t.sealed")
        assertTrue(TsnetSeal.seal(key(), root, out))
        // Flip a byte in the ciphertext region.
        val bytes = out.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        out.writeBytes(bytes)
        var threw = false
        try {
            TsnetSeal.unseal(key(), out, File(tmp(), "r"))
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("tampered seal must not silently decrypt", threw)
    }

    @Test
    fun missingSealReturnsFalse() {
        assertFalse(TsnetSeal.unseal(key(), File("/nonexistent/x.sealed"), File(tmp(), "r")))
    }


    @Test fun failedRestorePreservesEncryptedIdentityAndExistingWork() {
        val root = tmp()
        File(root, "tsnet").mkdirs()
        File(root, "tsnet/state").writeText("original identity")
        val seal = File(tmp(), "node.sealed")
        assertTrue(TsnetSeal.seal(key(), root, seal))
        val original = seal.readBytes()
        val dest = tmp()
        File(dest, "keep").writeText("do not erase on decrypt failure")
        assertTrue(runCatching { TsnetSeal.restorePreserving(key(), seal, dest) }.isFailure)
        assertArrayEquals(original, seal.readBytes())
        assertEquals("do not erase on decrypt failure", File(dest, "keep").readText())
        assertFalse(File(dest.parentFile, dest.name + ".restore").exists())
    }

    @Test fun successfulStagedRestoreCanBeRepeatedFromSameSeal() {
        val k = key()
        val root = tmp()
        File(root, "state").writeText("same node")
        val seal = File(tmp(), "node.sealed")
        assertTrue(TsnetSeal.seal(k, root, seal))
        val dest = File(tmp(), "work")
        repeat(2) {
            assertTrue(TsnetSeal.restorePreserving(k, seal, dest))
            assertEquals("same node", File(dest, "state").readText())
            assertTrue(seal.exists())
        }
    }


    @Test fun eachRecordGetsAProviderGeneratedFreshIv() {
        val k = key()
        val root = tmp()
        File(root, "a").writeText("same plaintext")
        File(root, "b").writeText("same plaintext")
        val seal = File(tmp(), "node.sealed")
        assertTrue(TsnetSeal.seal(k, root, seal))
        val ivs = mutableListOf<ByteArray>()
        java.io.DataInputStream(seal.inputStream()).use { input ->
            assertEquals(2, input.read())
            assertEquals(2, input.readInt())
            repeat(2) {
                input.readUTF()
                val iv = ByteArray(input.readInt()).also { input.readFully(it) }
                assertTrue(iv.isNotEmpty())
                ivs += iv
                input.skipBytes(input.readInt())
            }
        }
        assertFalse(ivs[0].contentEquals(ivs[1]))
        val restored = File(tmp(), "restored")
        assertTrue(TsnetSeal.restorePreserving(k, seal, restored))
        assertEquals("same plaintext", File(restored, "a").readText())
        assertEquals("same plaintext", File(restored, "b").readText())
    }

    private fun assertArrayEquals(a: ByteArray, b: ByteArray) = org.junit.Assert.assertArrayEquals(a, b)
}
