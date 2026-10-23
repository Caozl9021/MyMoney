package com.example.billkit.root

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 极简 su 执行器。
 *
 * 只做三件事：跑命令、拿输出、超时杀掉。没有常驻守护进程，也没有静默自升级逻辑——
 * 一个记账工具不需要那些。
 */
object RootShell {

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    fun exec(command: String, timeoutMs: Long = 6_000): Result = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            Result(-1, text)
        } else {
            Result(process.exitValue(), text)
        }
    } catch (e: Exception) {
        Result(-1, e.message ?: "exec-failed")
    }

    fun execBytes(command: String, timeoutMs: Long = 8_000): ByteArray? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        val buffer = ByteArrayOutputStream()
        process.inputStream.use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
            }
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else {
            buffer.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /** 只在用户点了"检测 root"时才调用，避免每次启动都弹授权框 */
    fun isGranted(): Boolean = exec("id -u").output.trim().split("\n").lastOrNull()?.trim() == "0"
}
