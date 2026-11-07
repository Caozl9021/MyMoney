package io.github.mymoney

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import io.github.mymoney.data.rulesync.FamilyInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z4 扫码解码逻辑的本地验证（不联网、不连相机）。
 *
 * 用 ZXing 把真实格式的 MMJOIN2 连接码编码成位图，再走与 App [io.github.mymoney.ui.scan.QrImageDecoder]
 * 相同的解码路径（MultiFormatReader + HybridBinarizer + RGBLuminanceSource），断言：
 * 解出的字符串就是原连接码，并且 [FamilyInvite.parse] 能接受、识别为完整连接码。
 *
 * 另附「坏码」用例：非 QR 内容的位图应解不出（返回 null），保证不会把噪声当码。
 */
class QrDecodeTest {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private fun encodeToPixels(text: String, size: Int = 600): Pair<IntArray, Int> {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return pixels to size
    }

    private fun decode(pixels: IntArray, size: Int): String? {
        val source = RGBLuminanceSource(size, size, pixels)
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
        } catch (_: com.google.zxing.NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    @Test fun connectCodeEncodesThenDecodesThenParses() {
        val invite = FamilyInvite(
            ctrlUrl = "https://controlplane.tailscale.com",
            authKey = "tskey-auth-kTEST1234CNTRL-abcdefghijklmnop",
            serverUrl = "http://100.65.248.62:18080",
            deviceName = "验收测试设备",
            pairCode = "CAOZL9021",
        )
        val code = FamilyInvite.encode(invite)
        assertTrue(code.startsWith("MMJOIN2."))

        val (pixels, size) = encodeToPixels(code)
        val decoded = decode(pixels, size)

        assertEquals("解码应还原原始连接码", code, decoded)
        val parsed = FamilyInvite.parse(decoded!!)
        assertTrue("应识别为完整连接码", FamilyInvite.isConnectCode(parsed))
        assertEquals("http://100.65.248.62:18080", parsed.serverUrl)
        assertEquals("验收测试设备", parsed.deviceName)
        assertEquals("CAOZL9021", parsed.pairCode)
    }

    @Test fun blankImageDoesNotDecodeToAnything() {
        val size = 400
        val white = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        assertTrue("空白图不应解出二维码", decode(white, size) == null)
    }
}
