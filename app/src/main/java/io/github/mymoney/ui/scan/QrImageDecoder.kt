package io.github.mymoney.ui.scan

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 本地二维码解码：只把相机帧 / 位图喂给 ZXing，纯本地、不联网、不上传。
 *
 * 只收 QR；限制单帧最多尝试少量候选，避免低端机卡顿。返回第一段能解出的原始文本，
 * 上层再用 [io.github.mymoney.data.rulesync.FamilyInvite.parse] 校验是不是合法连接码。
 */
internal object QrImageDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** 解一行相机 YUV 帧（CameraX 默认给出的 YUV_420_888 / NV21 布局）。 */
    fun decodeYuv(data: ByteArray, width: Int, height: Int): String? {
        val source = runCatching {
            PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        }.getOrNull() ?: return null
        return decode(source)
    }

    /** 解一张已经转成 ARGB_8888 的位图（例如从图库选择）。 */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        return decode(source)
    }

    private inline fun decode(source: com.google.zxing.LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
