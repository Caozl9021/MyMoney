package com.example.autobook.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 短信广播接收器（SPEC §5.2 / §8 阶段 2）。
 *
 * 只做三件事：校验 action、把长短信的多段拼成一条正文、读出号码后交给 [SmsIngress]。
 * 判定（哪些短信算账、金额多少）全在 24 条银行规则里，这里不写任何金额正则——
 * SPEC §0 明确禁止硬编码正则，规则是唯一事实来源。
 *
 * 长短信拼接是这里最容易被忽略的一步：运营商把超过 70 字的短信拆成多段，
 * `getMessagesFromIntent` 返回数组，金额经常落在第二段。不拼接就永远匹配不到。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
        if (parts.isNullOrEmpty()) return
        val sender = parts.firstOrNull()?.originatingAddress
        val body = buildString {
            for (part in parts) {
                val segment = part?.messageBody ?: continue
                append(segment)
            }
        }
        if (body.isEmpty()) return
        SmsIngress.offer(context, sender, body, System.currentTimeMillis())
    }
}
