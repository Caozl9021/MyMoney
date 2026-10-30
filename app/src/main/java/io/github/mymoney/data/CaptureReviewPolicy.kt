package io.github.mymoney.data

/**
 * 待核对（review）只说明"两个来源可能同笔"，**不等于"已经弹过一次确认卡"**。
 *
 * 旧实现只看"存在一条同额记录"就压制投递：那条如果其实没送到用户面前（投递被抑制、
 * 失败，或根本没走到投递），这一笔也被一起吞掉，屏幕上什么都不出现，诊断只留一行
 * needs-review。现在以**投递回执**判定"弹过没有"：
 *
 * - 已认领/已展示（CLAIMED / ACKNOWLEDGED）或已入账（RECORDED）→ 视为弹过，压制本条；
 * - 未认领、被抑制（autoConfirm 关闭、锁屏、来源已关）或投递失败 → 视为没弹过，
 *   本条按独立记录正常投递，保证一笔真实付款至少有一次自动弹框的机会。
 *
 * CLAIMED 也算"弹过"，是为了关掉并发窗口：另一条记录刚认领、卡正在挂载时到来的同额事件，
 * 不应再叠第二张卡。纯策略，不依赖 Android/Room，便于在 JVM 上验证。
 */
object CaptureReviewPolicy {
    const val SAME_SLOT_SAME_AMOUNT = "same-slot-same-amount"
    const val CROSS_SOURCE_SAME_AMOUNT = "cross-source-same-amount"
    const val FIELD_CONFLICT = "field-conflict"
    const val SAME_OBSERVATION = "same-observation-conflict"

    /** 被指向的记录是否"已经呈现过"，只认投递回执，不认"存在同额记录"本身。 */
    fun presented(status: String, deliveryState: String?): Boolean =
        status == RecognitionStatus.RECORDED ||
            deliveryState == CaptureDeliveryState.CLAIMED ||
            deliveryState == CaptureDeliveryState.ACKNOWLEDGED

    /** 弱猜测（同槽位/跨来源同额）只有被指向的那条确实弹过才压制；
     * 已知同笔冲突（同一交易身份、同一通知实例内容变化）仍旧压制，否则会把一笔付款弹两次。
     */
    fun suppresses(weakGuess: Boolean, targetPresented: Boolean): Boolean = !weakGuess || targetPresented

    fun isWeakGuess(reason: String?): Boolean =
        reason == SAME_SLOT_SAME_AMOUNT || reason == CROSS_SOURCE_SAME_AMOUNT

    /** 诊断文案：把"为什么待核对/为什么仍然弹"写清楚，不再只留一个 needs-review。 */
    fun explanation(reason: String, suppressed: Boolean): String {
        val what = when (reason) {
            SAME_SLOT_SAME_AMOUNT -> "同一通知槽位 120 秒内又来一笔同额"
            CROSS_SOURCE_SAME_AMOUNT -> "另一来源在 120 秒内已有一笔同额"
            FIELD_CONFLICT -> "同一交易身份的内容与已有草稿冲突"
            SAME_OBSERVATION -> "同一通知实例再次上报但内容不同"
            else -> "存在同额弱候选"
        }
        return if (suppressed) "$what，且那条已展示或已入账；本条保留待核对，不重复弹框"
        else "$what，但那条从未展示；本条已按独立记录投递，可在识别历史核对是否合并"
    }
}
