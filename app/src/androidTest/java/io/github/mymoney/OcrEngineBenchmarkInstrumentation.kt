package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import com.example.billkit.visual.VisualBillParser
import com.example.billkit.visual.VisualText
import io.github.mymoney.data.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Generated bitmap comparison only: no ledger, preference, permission or network mutations. */
class OcrEngineBenchmarkInstrumentation : Instrumentation() {
    private var allowed = false
    private var maxImages = 70
    private val rows = mutableListOf<Map<String, Any?>>()
    private val at = 1_793_790_000_000L
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        allowed = arguments?.getString("allowBenchmark") == "synthetic-bitmaps"
        maxImages = arguments?.getString("maxImages")?.toIntOrNull()?.coerceIn(1,70) ?: 70
        start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        try {
            check(allowed && (Build.MODEL.contains("waydroid", true) || Build.PRODUCT.contains("sdk", true) ||
                Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true))) {
                "Explicit synthetic-bitmaps flag and a virtual device are required"
            }
            runBlocking(Dispatchers.IO) { compare() }
        } catch (error: Throwable) { failure = error }
        val report = mapOf(
            "scope" to "synthetic-only-same-bitmap-and-parser-not-phone-benchmark",
            "realUniqueImages" to 0, "syntheticUniqueImages" to rows.map { it["id"] }.distinct().size,
            "positiveImages" to rows.filter { it["expectedPositive"] == true }.map { it["id"] }.distinct().size, "negativeImages" to rows.filter { it["expectedPositive"] == false }.map { it["id"] }.distinct().size,
            "datasetGate" to "NOT_MET: no authorized real-image corpus, no ARM64 measurements",
            "device" to mapOf("model" to Build.MODEL,"api" to Build.VERSION.SDK_INT,"abis" to Build.SUPPORTED_ABIS.toList()),
            "timingScope" to "recognize including per-request engine construction; excludes bitmap generation, parsing and UI; engine order alternates",
            "memoryScope" to "sampled process PSS every 50ms during recognition; not guaranteed true native peak",
            "rows" to rows, "error" to failure?.stackTraceToString(),
        )
        finish(if(failure==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("status",if(failure==null)"completed-research-only" else "failed")
            putString("report",MiniJson.encode(report));putInt("tests",rows.size)
        })
    }
    private data class Sample(val id:String,val positive:Boolean,val cents:Long,val variant:Int,val group:String)
    private suspend fun compare() {
        val v6Class = Class.forName("io.github.mymoney.ocr.research.V6ResearchEngine")
        val v6 = v6Class.getConstructor(android.content.Context::class.java).newInstance(targetContext)
        val method = v6Class.getMethod("recognizeBlocking",Bitmap::class.java)
        val mlkit = MlKitResearchEngine(context)
        val samples = (0 until 40).map { Sample("positive-$it",true,101L+37L*it,it,"layout-${it/10}") } +
            (0 until 30).map { Sample("negative-$it",false,1800L,it,"negative-${it/10}") }
        for((index,sample) in samples.take(maxImages).withIndex()) {
            val bitmap = render(sample)
            val digest = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)
                MessageDigest.getInstance("SHA-256").digest(stream.toByteArray()).joinToString(""){"%02x".format(it)}
            }
            try {
                val engines = if(index%2==0)listOf("mlkit","v6") else listOf("v6","mlkit")
                for(engine in engines) {
                    val baseline=Debug.getPss();val peak=AtomicLong(baseline);val sampling=AtomicBoolean(true)
                    val sampler=Thread {
                        while(sampling.get()) {
                            peak.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) }
                            try { Thread.sleep(50) } catch (_:InterruptedException) { break }
                        }
                    }.apply { isDaemon=true;start() }
                    val started=SystemClock.elapsedRealtime()
                    var visual:VisualText?=null;var error:String?=null
                    try {
                        visual=if(engine=="mlkit")mlkit.recognize(bitmap) else method.invoke(v6,bitmap) as VisualText
                    } catch(failure:Throwable) { error=(failure.cause?:failure).javaClass.simpleName+":"+(failure.cause?:failure).message }
                    finally { sampling.set(false);sampler.interrupt();sampler.join(1000) }
                    val elapsed=SystemClock.elapsedRealtime()-started
                    val parsed=visual?.let { VisualBillParser.parse(it,at) }
                    rows += mapOf(
                        "id" to sample.id,"synthetic" to true,"sampleGroup" to sample.group,
                        "split" to if(sample.variant%2==0)"development" else "held-out-synthetic-layout",
                        "variantRelatedWarning" to "generated layouts share families; not independent real holdout",
                        "imageSha256" to digest,"width" to bitmap.width,"height" to bitmap.height,
                        "expectedPositive" to sample.positive,"expectedCents" to if(sample.positive)sample.cents else null,
                        "engine" to engine,"engineVersion" to visual?.engine,"requestIndex" to index,
                        "firstRequest" to (index==0),"elapsedMs" to elapsed,"engineElapsedMs" to visual?.elapsedMs,
                        "baselinePssKiB" to baseline,"sampledPeakPssKiB" to peak.get(),"afterPssKiB" to Debug.getPss(),
                        "diagnosticLines" to visual?.lines?.take(100)?.map { line -> mapOf("text" to line.text,
                            "confidence" to line.confidence,"box" to listOf(line.left,line.top,line.right,line.bottom)) },
                        "draftProduced" to (parsed?.draft!=null),"amountCents" to parsed?.amountCents,
                        "amountExact" to (sample.positive && parsed?.draft?.priceCents==sample.cents),
                        "merchant" to parsed?.fields?.merchant,"method" to parsed?.fields?.method,
                        "product" to parsed?.fields?.productName,"issue" to parsed?.issue,"error" to error,
                    )
                    sendStatus(0,Bundle().apply {putString("progress","${rows.size}/140 ${sample.id} $engine")})
                }
            } finally { bitmap.recycle() }
        }
    }
    private fun render(sample:Sample):Bitmap {
        val width=if(sample.variant%4==0)720 else 900
        val height=if(width==720)1280 else 1600
        val scale=width/900f
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val dark=sample.variant%2==1
        canvas.drawColor(if(dark)Color.rgb(25,27,31) else Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=if(dark)Color.WHITE else Color.BLACK
            typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        }
        fun line(text:String,x:Float,y:Float,size:Float=43f) {
            paint.textSize=size*scale;canvas.drawText(text,x*scale,y*scale,paint)
        }
        if(sample.positive) {
            val amount=java.math.BigDecimal(sample.cents).movePointLeft(2).toPlainString()
            line(if(sample.variant%3==0)"付款成功" else "支付成功",300f,230f,55f)
            line("测试咖啡店",260f,370f,50f)
            line("¥$amount",270f,540f,96f)
            if(sample.variant/10==1) {
                line("付款金额",70f,680f);line("$amount 元",530f,680f)
            } else line("付款金额：$amount 元",70f,680f)
            line("商户名称：测试咖啡店",70f,790f)
            line("支付方式：零钱",70f,900f)
            line("商品说明：拿铁咖啡",70f,1010f)
            if(sample.variant/10==2)line("优惠券：5.00 元",70f,1120f)
            if(sample.variant/10==3)line("支付时间：2026-11-04 12:00",70f,1120f,35f)
            line("完成",380f,1440f)
        } else when(sample.variant/10) {
            0 -> {
                line(if(sample.variant%2==0)"待付款" else "支付失败",300f,250f,55f)
                line("¥18.00",270f,520f,96f);line("付款金额：18.00 元",70f,700f)
                line("确认付款",300f,1000f)
            }
            1 -> {
                line("领取优惠券",250f,250f,55f);line("¥18.00",270f,520f,96f)
                line("钱包余额：128.00 元",70f,700f);line("最高可省 18.00 元",70f,900f)
            }
            else -> {
                line("支付成功",300f,230f,55f);line("¥18.00",270f,520f,96f)
                line("¥28.00",270f,740f,96f);line("测试咖啡店",250f,900f)
            }
        }
        line("模拟样本 ${sample.id}",70f,1540f,20f)
        return bitmap
    }
}
