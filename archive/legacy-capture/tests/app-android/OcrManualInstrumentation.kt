package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import io.github.mymoney.data.MiniJson
import io.github.mymoney.ui.OcrImportOperation
import java.io.File

/** Explicit virtual-only image imports. Does not save bills or alter recognition history. */
class OcrManualInstrumentation : Instrumentation() {
    private var allowed = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); allowed=arguments?.getString("isolated")=="ocr-manual"; start() }
    override fun onStart() {
        val results=mutableListOf<String>(); var failure:Throwable?=null
        val dir=File(targetContext.cacheDir,"ocr-manual-test-${System.nanoTime()}")
        try {
            check(allowed && (Build.MODEL.contains("waydroid",true)||Build.PRODUCT.contains("sdk",true)))
            check(dir.mkdir())
            val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=44f }
            canvas.drawText("付款成功",230f,180f,paint)
            paint.textSize=64f; canvas.drawText("¥3.50",230f,350f,paint)
            paint.textSize=36f
            canvas.drawText("付款金额：3.50元",58f,500f,paint)
            canvas.drawText("商户名称：模拟早餐店",58f,600f,paint)
            canvas.drawText("支付方式：零钱",58f,700f,paint)
            canvas.drawText("商品说明：豆浆包子",58f,800f,paint)
            val image=File(dir,"fixture.png")
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            fun operation(file:File):OcrImportOperation {
                lateinit var op:OcrImportOperation
                runOnMainSync { op=OcrImportOperation(); op.read(targetContext,Uri.fromFile(file)) }
                val deadline=System.currentTimeMillis()+30_000
                while(System.currentTimeMillis()<deadline) {
                    var busy=true; runOnMainSync { busy=op.busy }; if(!busy)return op; Thread.sleep(30)
                }; error("manual import timed out")
            }
            val op=operation(image)
            runOnMainSync {
                check(op.error==null) { op.error ?: "error" }
                val draft=checkNotNull(op.draft)
                check(draft.priceCents==350L && draft.merchant=="模拟早餐店" && draft.method=="零钱")
                check(draft.productName=="豆浆包子" && draft.transactionKey==null && draft.orderTail==null)
                check(!draft.hasExplicitTime && op.notice?.contains("交易时间")==true)
                check(draft.sourcePkg.startsWith("io.github.mymoney.manual.ocr."))
            }
            results+="real_model_selected_image_produces_review_only_with_missing_date_warning"
            val repeated=operation(image)
            runOnMainSync { check(repeated.draft?.sourcePkg==op.draft?.sourcePkg) }
            results+="same_image_stable_import_hint_without_transaction_alias"
            val broken=File(dir,"bad.png").apply { writeText("not an image") }
            val bad=operation(broken); runOnMainSync { check(bad.draft==null && bad.error!=null) }
            results+="corrupt_image_rejected"
            val huge=File(dir,"huge.png").apply { outputStream().use { it.write(ByteArray(5*1024*1024+1)) } }
            val oversized=operation(huge); runOnMainSync { check(oversized.draft==null && oversized.error?.contains("5MiB")==true) }
            results+="compressed_size_limit_rejected"
            lateinit var cancelled:OcrImportOperation
            runOnMainSync { cancelled=OcrImportOperation(); cancelled.read(targetContext,Uri.fromFile(image)); cancelled.cancel() }
            Thread.sleep(1800)
            runOnMainSync { check(cancelled.draft==null && !cancelled.busy) }
            results+="cancel_does_not_publish_late_draft"
        } catch(t:Throwable) { failure=t }
        finally { dir.deleteRecursively() }
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("status",if(failure==null)"passed" else "failed"); putInt("tests",results.size)
            putString("report",MiniJson.encode(mapOf("results" to results,"error" to failure?.stackTraceToString(),"scope" to "virtual-synthetic-images-no-bill-save")))
        })
    }
}
