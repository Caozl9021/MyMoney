package io.github.caozl9021.mymoney.lsp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.caozl9021.mymoney.capture.HookIngress
import io.github.caozl9021.mymoney.capture.HookTargetMonitor

/** Target receipt and loading acknowledgement endpoint; it exposes no app data queries or edits. */
class LspStatusProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == HookProtocol.STATUS_METHOD) {
            val ctx = getContext()
            if (extras != null && ctx != null) HookTargetMonitor.accept(ctx, extras, callerPackages())
            return null
        }
        if (method == HookProtocol.METHOD) {
            val ctx = getContext()
            if (extras != null && ctx != null && HookTargetRegistry.acceptsCaller(
                    extras.getString(HookProtocol.EXTRA_APP), callerPackages(), HookProtocol.hookedPackages))
                HookIngress.offer(ctx, extras)
            return null
        }
        // Unknown methods, including the removed system report, have no side effects.
        return null
    }

    /**
     * UID 由内核填，应用无法伪造；再比对它名下确实有被 hook 的包名，
     * 就等价于「这条数据确实来自微信/支付宝进程里的模块」。
     */
    private fun callerPackages(): Set<String> = runCatching {
        val uid = Binder.getCallingUid()
        if (uid / 100000 != Process.myUid() / 100000) emptySet()
        else getContext()?.packageManager?.getPackagesForUid(uid).orEmpty().toSet()
    }.getOrDefault(emptySet())

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
