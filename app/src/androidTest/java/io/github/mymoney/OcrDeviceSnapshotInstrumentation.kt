package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Android-only bridge: also runnable against the pre-upgrade app; no AppDb or fixture writes. */
class OcrDeviceSnapshotInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        check(arguments?.getString("isolated") == "waydroid-snapshot" &&
            (Build.MODEL.contains("waydroid", true) || Build.PRODUCT.contains("waydroid", true)))
        start()
    }
    override fun onStart() {
        try {
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val database = targetContext.getDatabasePath("mymoney.db")
            val mainFiles = listOf(database, File(database.path+"-wal"))
            val before = mainFiles.associate { it.name to if (it.exists()) digest(it) else "missing" }
            val out = ByteArrayOutputStream()
            var total = 0L
            ZipOutputStream(out).use { zip ->
                for (directory in listOf(database.parentFile!!, targetContext.filesDir, File(targetContext.applicationInfo.dataDir,"shared_prefs"))) {
                    if (!directory.exists()) continue
                    directory.walkTopDown().filter { it.isFile }.forEach { file ->
                        total += file.length(); check(total < 20L*1024*1024) { "Snapshot exceeds safe size" }
                        zip.putNextEntry(ZipEntry(directory.name+"/"+file.relativeTo(directory).path))
                        file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            }
            val after = mainFiles.associate { it.name to if (it.exists()) digest(it) else "missing" }
            check(before == after) { "Database changed during snapshot; retry" }
            check(out.size() < 650000) { "Compressed backup too large for safe IPC" }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("status","passed");putString("snapshot",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP))
                putString("mainSha",before["mymoney.db"]);putString("walSha",before["mymoney.db-wal"])
            })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("status","failed");putString("error",failure.toString()) })
        }
    }
}
