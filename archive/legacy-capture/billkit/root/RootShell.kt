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

    /**
     * 读 stdout 必须与 `waitFor` 并发，不能先 `readText()` 再等待。
     *
     * `readText()` 要读到 EOF 才返回，而 EOF 只在子进程关闭管道时出现。一旦子进程因为任何原因
     * 没有关闭 stdout（`su` 派生孙进程、孙进程继承了管道、或者 `dumpsys` 被 kill 而 `su` 还活着），
     * 这一行就会**永久阻塞**，后面那句 `waitFor(timeoutMs)` 的超时保护形同虚设。
     *
     * 真机实测：`dumpsys notification --noredact` 一次输出 8189 行，轮询跑了几十轮后
     * 47 分钟没有再产出任何证据（`capture_evidence` 停在 19:38:45，而进程本身没崩、也没重启）。
     * 表现就是「root 通道静默死亡」，比报错更糟。
     *
     * 所以改成：读线程负责把输出灌进 buffer，主线程只做带超时的 `waitFor`；
     * 超时就 `destroyForcibly` 并**中断读线程**，保证调用方一定能在 timeoutMs 内返回。
     */
    fun exec(command: String, timeoutMs: Long = 6_000): Result = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val buffer = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        synchronized(buffer) { buffer.write(chunk, 0, n) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.interrupt()
        }
        reader.join(200L)
        val text = synchronized(buffer) { buffer.toString("UTF-8") }
        if (finished) Result(process.exitValue(), text) else Result(-1, text)
    } catch (e: Exception) {
        Result(-1, e.message ?: "exec-failed")
    }

    /** 二进制版：`su -c dd if=...` 之类。读取同样不能阻塞在 `waitFor` 之前，理由见 [exec]。 */
    fun execBytes(command: String, timeoutMs: Long = 8_000): ByteArray? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        val buffer = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        synchronized(buffer) { buffer.write(chunk, 0, n) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.interrupt()
            null
        } else {
            reader.join(200L)
            synchronized(buffer) { buffer.toByteArray() }
        }
    } catch (e: Exception) {
        null
    }

    /** 只在用户点了"检测 root"时才调用，避免每次启动都弹授权框 */
    fun isGranted(): Boolean = exec("id -u").output.trim().split("\n").lastOrNull()?.trim() == "0"
}
