package io.github.mymoney.data.rulesync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * U3.4 — pure seal/unseal of a directory into one blob. Kept free of Android types
 * (takes a [SecretKey] and plain [File]s) so the format, atomic rename, and tamper
 * handling are unit-testable on the JVM. [TsnetIdentity] supplies the Keystore key
 * and the noBackup paths.
 *
 * Format (version 2):
 *   byte   version = 2
 *   int    entryCount
 *   repeat: UTF relPath; int ivLen; bytes iv; int encLen; bytes encGcm(fileBytes)
 * Each file is its own AES/GCM record with a FRESH random IV (GCM forbids reusing a
 * key+IV pair; the JDK enforces this with an IllegalStateException). All length
 * fields are big-endian ints. Layout is written to a `.tmp` sibling, fsynced, then
 * atomically renamed, so a crash never leaves a half-written seal.
 */
object TsnetSeal {
    private const val VERSION = 2
    private const val TAG_BITS = 128

    /** Seals all files under [root] into [out] atomically. Returns true on success; a
     *  pure no-op (empty/missing dir) also returns true without creating [out]. */
    fun seal(key: SecretKey, root: File, out: File): Boolean {
        if (!root.isDirectory) return true
        val files = root.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return true
        val tmp = File(out.parentFile, out.name + ".tmp")
        return try {
            DataOutputStream(tmp.outputStream().buffered()).use { dos ->
                dos.write(VERSION)
                dos.writeInt(files.size)
                for (f in files) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    // AndroidKeyStore rejects caller-supplied encryption IVs when
                    // randomized encryption is required (the secure default).
                    // Let the provider generate a fresh IV for every record.
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    val iv = cipher.iv
                    val enc = cipher.doFinal(f.readBytes())
                    dos.writeUTF(f.relativeTo(root).invariantSeparatorsPath)
                    dos.writeInt(iv.size); dos.write(iv)
                    dos.writeInt(enc.size); dos.write(enc)
                }
                dos.flush()
            }
            java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
            if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** Restore into a sibling first. Errors never delete the seal or replace the
     * existing work tree with partially decrypted state. */
    fun restorePreserving(key: SecretKey, seal: File, dest: File): Boolean {
        if (!seal.isFile) return false
        val staging = File(dest.parentFile, dest.name + ".restore")
        try {
            if (!unseal(key, seal, staging)) return false
            if (dest.exists() && !dest.deleteRecursively()) throw java.io.IOException("cannot replace work directory")
            if (!staging.renameTo(dest)) throw java.io.IOException("cannot publish restored identity")
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unseals [seal] into [dest] (recreated). Returns true ok; throws on corruption. */
    fun unseal(key: SecretKey, seal: File, dest: File): Boolean {
        if (!seal.isFile) return false
        DataInputStream(seal.inputStream().buffered()).use { dis ->
            val version = dis.read()
            if (version != VERSION) throw java.io.IOException("bad version")
            val count = dis.readInt()
            dest.deleteRecursively(); dest.mkdirs()
            repeat(count) {
                val rel = dis.readUTF()
                val ivLen = dis.readInt()
                val iv = ByteArray(ivLen).also { dis.readFully(it) }
                val len = dis.readInt()
                val enc = ByteArray(len).also { dis.readFully(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                val plain = cipher.doFinal(enc)
                val f = File(dest, rel)
                f.parentFile?.mkdirs()
                f.writeBytes(plain)
            }
        }
        return true
    }
}
