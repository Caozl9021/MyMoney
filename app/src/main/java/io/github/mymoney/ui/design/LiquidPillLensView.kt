package io.github.mymoney.ui.design

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi

/**
 * Shared frame/theme bridge between Compose [LiquidTabCells] and the Android 13+
 * [LiquidPillLensView] sibling overlay.
 */
internal class LiquidPillLensBridge {
    var frameProvider: ((canvasW: Float, canvasH: Float) -> TabIndicatorFrame?)? = null
    var isDarkProvider: (() -> Boolean)? = null
    var onInvalidate: (() -> Unit)? = null
    var shaderReady: Boolean = false

    fun notifyFrameChanged() {
        onInvalidate?.invoke()
    }
}

private const val LIQUID_PILL_LENS_AGSL = """
uniform shader content;
uniform float2 canvasSize;
uniform float2 pillCenter;
uniform float2 pillHalfSize;
uniform float pillRadius;
uniform float lensMix;
uniform float refractionBand;
uniform float refractionAmount;
uniform float magnification;
uniform float dispersion;
uniform float isDark;
uniform float shadowBlur;
uniform float shadowOffsetY;
uniform float rimWidth;
uniform float verticalRefraction;
uniform float verticalMagnify;

float sdRoundedRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - (halfSize - float2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float2 gradSdRoundedRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - (halfSize - float2(r));
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    if (q.x >= 0.0 || q.y >= 0.0) {
        float2 outside = max(q, 0.0);
        float len = length(outside);
        if (len > 0.001) {
            return s * (outside / len);
        }
        float useX = step(q.y, q.x);
        return s * float2(useX, 1.0 - useX);
    }
    float gradX = step(q.y, q.x);
    return s * float2(gradX, 1.0 - gradX);
}

float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

half4 main(float2 coord) {
    float mixAmt = clamp(lensMix, 0.0, 1.0);
    if (mixAmt <= 0.001) {
        return half4(0.0);
    }

    float r = max(min(pillRadius, min(pillHalfSize.x, pillHalfSize.y)), 1.0);
    float2 p = coord - pillCenter;
    float sd = sdRoundedRect(p, pillHalfSize, r);
    float outerReach = shadowBlur + abs(shadowOffsetY) + 2.0;
    if (sd > outerReach) {
        return half4(0.0);
    }

    float t = smoothstep(0.0, 1.0, mixAmt);
    float seamFade = smoothstep(0.0, 0.08, mixAmt);
    float pillMask = (1.0 - smoothstep(-0.75, 0.75, sd)) * seamFade;

    float shadowSd = sdRoundedRect(p - float2(0.0, shadowOffsetY), pillHalfSize, r);
    float shadowDist = clamp(max(shadowSd, 0.0) / max(shadowBlur, 1.0), 0.0, 1.0);
    float inv = max(1.0 - shadowDist, 0.0);
    float shadowFalloff = inv * inv * sqrt(inv);
    float shadowStrength = (isDark > 0.5) ? 0.045 : 0.026;
    float shadowAlpha = shadowFalloff * shadowStrength * (t * t);

    if (pillMask <= 0.001) {
        return half4(0.0, 0.0, 0.0, half(shadowAlpha));
    }

    float2 grad = gradSdRoundedRect(p, pillHalfSize, r);
    // Anisotropic rim: the dock is short, so icons/labels sit right inside the top/bottom
    // refraction band and would be pulled onto the rim as stretched ghosts. Displacement
    // keeps full strength along X (left/right ends still refract icons while sliding), while
    // the Y component uses a tight vertical band and gentle verticalRefraction so the top/bottom
    // edges keep subtle glass curvature without pulling icons/labels onto the rim.
    float vert = grad.y * grad.y;
    float axisGain = mix(1.0, verticalRefraction, vert);
    float band = max(refractionBand * mix(1.0, 0.36, vert), 1.0);
    float depth = clamp(-sd, 0.0, band);
    float edgeFactor = clamp(1.0 - depth / band, 0.0, 1.0);
    float bevel = circleMap(edgeFactor) * refractionAmount * t;
    float2 bevelDir = float2(grad.x, grad.y * verticalRefraction);

    float2 minCoord = float2(0.5, 0.5);
    float2 maxCoord = max(canvasSize - float2(0.5, 0.5), minCoord);
    float2 magScale = float2(magnification, magnification * verticalMagnify) * t;
    float2 sampleCoord = clamp(coord - p * magScale - bevelDir * bevel, minCoord, maxCoord);
    float2 disp = bevelDir * (bevel * dispersion * edgeFactor);

    half4 cC = content.eval(sampleCoord);
    half4 cR = content.eval(clamp(sampleCoord + disp, minCoord, maxCoord));
    half4 cB = content.eval(clamp(sampleCoord - disp, minCoord, maxCoord));
    half3 crystal = mix(cC.rgb, half3(cR.r, cC.g, cB.b), half(edgeFactor * 0.88 * t * axisGain));

    float outerEdge = 1.0 - smoothstep(0.0, max(rimWidth * 0.65, 1.0), max(-sd, 0.0));
    float innerBand = smoothstep(0.0, max(rimWidth * 0.45, 0.8), max(-sd, 0.0))
        * (1.0 - smoothstep(max(rimWidth * 0.45, 0.8), max(rimWidth * 1.45, 1.8), max(-sd, 0.0)));
    float topLight = smoothstep(-0.55, 0.5, -grad.y);
    float bottomBounce = smoothstep(0.15, 0.9, grad.y) * 0.48;
    float rimSpec = innerBand * (0.22 + 0.78 * max(topLight, bottomBounce));

    half3 finalRgb;
    if (isDark > 0.5) {
        finalRgb = mix(crystal, half3(0.06, 0.08, 0.11), half(outerEdge * 0.20 * t));
        finalRgb += half3(rimSpec * 0.28 * t);
    } else {
        half3 clearCrystal = mix(crystal, half3(1.0), half(0.06 * t));
        finalRgb = mix(clearCrystal, half3(0.56, 0.60, 0.66), half(outerEdge * 0.22 * t));
        finalRgb += half3(rimSpec * 0.25 * t);
    }
    finalRgb = clamp(finalRgb, half3(0.0), half3(1.0));

    half outAlpha = half(clamp(pillMask + shadowAlpha * (1.0 - pillMask), 0.0, 1.0));
    return half4(finalRgb * half(pillMask), outAlpha);
}
"""

/**
 * Android 13+ AGSL convex liquid-glass lens for the moving/lifted dock indicator pill.
 *
 * Positioned as a sibling above [dockStage] (which holds the shadow, dock glass and Compose icons)
 * and records [contentSource] + [dockStage] into a private [RenderNode] only when [lensMix] > 0,
 * keeping idle cost at zero when resting.
 */
@SuppressLint("ViewConstructor")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LiquidPillLensView(
    context: Context,
    private val contentSource: ViewGroup,
    private val dockStage: ViewGroup,
    private val bridge: LiquidPillLensBridge,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val shader: RuntimeShader? = runCatching { RuntimeShader(LIQUID_PILL_LENS_AGSL) }.getOrNull()
    private val lensNode = RenderNode("LiquidPillLens")
    private val srcLoc = IntArray(2)
    private val hostLoc = IntArray(2)
    private var hasActiveLens = false
    private var isRecording = false
    private var listenerAttached = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateLensNode()
        true
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        bridge.shaderReady = shader != null
        bridge.onInvalidate = { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bridge.onInvalidate = { invalidate() }
        if (!listenerAttached) {
            viewTreeObserver.addOnPreDrawListener(preDrawListener)
            listenerAttached = true
        }
    }

    override fun onDetachedFromWindow() {
        if (listenerAttached) {
            if (viewTreeObserver.isAlive) {
                viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            listenerAttached = false
        }
        bridge.onInvalidate = null
        hasActiveLens = false
        lensNode.discardDisplayList()
        super.onDetachedFromWindow()
    }

    private fun updateLensNode() {
        val activeShader = shader ?: return
        if (isRecording || !isLaidOut || !dockStage.isLaidOut || width <= 0 || height <= 0) {
            if (hasActiveLens) {
                hasActiveLens = false
                lensNode.discardDisplayList()
            }
            return
        }
        val frame = bridge.frameProvider?.invoke(width.toFloat(), height.toFloat())
        if (frame == null || frame.lensMix <= 0.005f) {
            if (hasActiveLens) {
                hasActiveLens = false
                lensNode.discardDisplayList()
                invalidate()
            }
            return
        }

        isRecording = true
        try {
            contentSource.getLocationInWindow(srcLoc)
            getLocationInWindow(hostLoc)
            lensNode.setPosition(0, 0, width, height)
            val canvas = lensNode.beginRecording(width, height)
            try {
                if (contentSource.isLaidOut && contentSource.width > 0 && contentSource.height > 0) {
                    val savedSrc = canvas.save()
                    try {
                        canvas.translate(
                            (srcLoc[0] - hostLoc[0]).toFloat(),
                            (srcLoc[1] - hostLoc[1]).toFloat(),
                        )
                        contentSource.draw(canvas)
                    } finally {
                        canvas.restoreToCount(savedSrc)
                    }
                }
                val savedStage = canvas.save()
                try {
                    canvas.translate(
                        dockStage.left + dockStage.translationX,
                        dockStage.top + dockStage.translationY,
                    )
                    dockStage.draw(canvas)
                } finally {
                    canvas.restoreToCount(savedStage)
                }
            } finally {
                lensNode.endRecording()
            }

            val dark = if (bridge.isDarkProvider?.invoke() == true) 1f else 0f
            activeShader.setFloatUniform("canvasSize", width.toFloat(), height.toFloat())
            activeShader.setFloatUniform(
                "pillCenter",
                frame.left + frame.width * 0.5f,
                frame.top + frame.height * 0.5f,
            )
            activeShader.setFloatUniform("pillHalfSize", frame.width * 0.5f, frame.height * 0.5f)
            activeShader.setFloatUniform("pillRadius", frame.height * 0.5f)
            activeShader.setFloatUniform("lensMix", frame.lensMix)
            activeShader.setFloatUniform("refractionBand", 15f * density)
            activeShader.setFloatUniform("refractionAmount", 19f * density)
            activeShader.setFloatUniform("magnification", 0.145f)
            activeShader.setFloatUniform("dispersion", 0.24f)
            activeShader.setFloatUniform("isDark", dark)
            activeShader.setFloatUniform("shadowBlur", 3.0f * density)
            activeShader.setFloatUniform("shadowOffsetY", 0.5f * density)
            activeShader.setFloatUniform("rimWidth", 1.55f * density)
            activeShader.setFloatUniform("verticalRefraction", 0.08f)
            activeShader.setFloatUniform("verticalMagnify", 0.55f)
            lensNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(activeShader, "content"))
            hasActiveLens = true
        } finally {
            isRecording = false
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (!canvas.isHardwareAccelerated || !hasActiveLens) return
        canvas.drawRenderNode(lensNode)
    }
}
