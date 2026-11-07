package io.github.mymoney.data.rulesync

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * 一次安装一个内嵌节点的会话外壳。
 *
 * 关心的是三件真事：
 * - **只有一个节点**：`TsnetNode.acquireShared` 保证进程内单实例，并发同步/备份共享同一条隧道；
 * - **authKey 不落盘**：只从 [PendingInvite] 的内存副本取一次，节点身份随后由加密状态目录承载；
 * - **地址变了就换节点**：peer（scheme/host/port）或状态目录变了要重建，否则会拿旧隧道去连新地址。
 *
 * 失败一律抛 [FamilyTransportException]，由协调器转成界面文案；不在这里吞异常。
 */
object TsnetSession {

    private const val PREFS = "tsnet"
    private const val KEY_HOSTNAME = "hostname"

    private data class Live(
        val peer: EmbeddedTsnetTransport.Peer,
        val transport: EmbeddedTsnetTransport,
        val node: TsnetNode,
        val handle: TsnetNode.Handle,
    )

    @Volatile private var live: Live? = null

    /** Human-readable node state for the settings page. */
    fun status(context: Context): String = live?.transport?.status() ?: "未启动"

    /** Stops the node and re-seals its state (disconnect / sign-out). */
    @Synchronized
    fun shutdown() {
        live?.let { current ->
            current.handle.close()
        }
        live = null
        TsnetNode.forget()
    }

    @Synchronized
    fun transport(context: Context, config: RuleSyncConfig): FamilyTransport {
        val peer = FamilyTransportProvider.derivePeer(config.serverUrl)
        val dir = TsnetIdentity.workDir(context)
        val existing = live
        if (existing != null && existing.peer == peer) {
            // 隧道可能被系统回收过：在持有引用不变的前提下就地重启，不新增计数。
            val inviteNow = PendingInvite.peek()
            existing.node.restart(inviteNow?.authKey, inviteNow?.ctrlUrl ?: config.ctrlUrl)
            return existing.transport
        }
        if (existing != null) {
            existing.handle.close()
            live = null
            TsnetNode.forget()
        }

        val transport = EmbeddedTsnetTransport(dir, hostname(context), peer)
        val invite = PendingInvite.peek()
        val (node, handle) = TsnetNode.acquireShared(invite?.authKey, invite?.ctrlUrl ?: config.ctrlUrl) {
            TsnetNode(
                driver = object : TsnetNode.Driver {
                    override fun start(authKey: String?, ctrlUrl: String) = transport.ensureStarted(authKey, ctrlUrl)
                    override fun stop() = transport.shutdown()
                    override fun isRunning(): Boolean = transport.isRunning
                    override fun status(): String = transport.status()
                },
                sealer = object : TsnetNode.Sealer {
                    override fun unseal(): Boolean = TsnetIdentity.unseal(context)
                    override fun seal(): Boolean = TsnetIdentity.seal(context)
                },
            )
        }
        PendingInvite.consume()
        live = Live(peer, transport, node, handle)
        return transport
    }

    /** Stable per-install hostname so the tailnet node keeps one identity across restarts. */
    private fun hostname(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_HOSTNAME, null)?.let { return it }
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val name = "mymoney-" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_HOSTNAME, name).commit()
        return name
    }

    /** Test-only: forget the cached session without touching disk. */
    internal fun resetForTest() {
        live = null
    }

    /** Only used by tests to keep the object honest about the peer cache. */
    internal fun currentPeerForTest(): EmbeddedTsnetTransport.Peer? = live?.peer

    internal fun stateDirForTest(context: Context): File = TsnetIdentity.workDir(context)
}

/**
 * 邀请在本进程里的临时副本。
 *
 * 只在内存里：进程重启后重新导入邀请即可；这样磁盘上永远不会出现 `tskey-` 明文，备份、
 * 云同步、`adb backup` 也拿不到它。
 */
/**
 * 深链 `mymoney://join?invite=…` 带进来的**原始邀请串**，等设置页来取。
 *
 * 与 [PendingInvite] 一样只在内存：取走即清空，不写偏好、不进备份。
 */
object PendingInviteText {
    @Volatile private var text: String? = null

    fun put(value: String) { text = value }

    fun consume(): String? = text.also { text = null }

    fun clear() { text = null }
}

object PendingInvite {
    @Volatile private var invite: FamilyInvite? = null

    fun put(value: FamilyInvite) { invite = value }

    fun peek(): FamilyInvite? = invite

    fun consume() { invite = null }

    fun clear() { invite = null }
}
