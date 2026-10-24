package com.example.autobook.capture

import android.content.Context
import com.example.billkit.UiSnapshot
import java.security.MessageDigest
import java.util.UUID

/** Stores only the last ordered-page digest and a random continuity token, never screen text. */
class PageSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("capture_page_session", Context.MODE_PRIVATE)

    @Synchronized
    fun sessionFor(snapshot: UiSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(snapshot.packageName)
        snapshot.items.forEach(::add)
        val key = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val previous = preferences.getString("digest", null)
        val token = preferences.getString("token", null)
        if (key == previous && token != null) return token
        val next = UUID.randomUUID().toString()
        check(preferences.edit().putString("digest", key).putString("token", next).commit()) {
            "无法保存页面会话，请重试"
        }
        return next
    }
}
