package io.github.mymoney.ui.design

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 共享纯 Compose 液态玻璃控件的几何帧与 API 33+ AGSL 凸镜光学模型。
 *
 * 与底栏 [LiquidPillLensView] 同源：静止态 `lensMix <= 0.005f` 时零录制、零 Shader；
 * 按压/拖拽/切换飞行时仅对控件自身局部 `GraphicsLayer` 做各向异性凸镜折射、微色散与玻璃厚壁高光。
 */
internal data class ControlPillFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val fillAlpha: Float,
    val highlightAlpha: Float,
    val rimAlpha: Float,
    val shadowAlpha: Float,
    val lensMix: Float,
)

internal fun rubberBandSwitchPosition(rawPos: Float): Float = when {
    rawPos < 0f -> -((-rawPos) * 0.22f).coerceAtMost(0.08f)
    rawPos > 1f -> 1f + ((rawPos - 1f) * 0.22f).coerceAtMost(0.08f)
    else -> rawPos
}

internal fun resolveSwitchTarget(position: Float, velocityPerSec: Float): Boolean {
    val projection = if (abs(velocityPerSec) >= 0.4f) {
        (velocityPerSec * 0.055f).coerceIn(-0.45f, 0.45f)
    } else 0f
    return (position + projection) >= 0.5f
}

internal fun computeSwitchPillFrame(
    canvasWidth: Float,
    canvasHeight: Float,
    trackWidth: Float,
    trackHeight: Float,
    pillWidth: Float,
    pillHeight: Float,
    position: Float,
    velocityPerSec: Float,
    lift: Float,
    lens: Float,
    isDark: Boolean,
): ControlPillFrame {
    val trackLeft = ((canvasWidth - trackWidth) * 0.5f).coerceAtLeast(0f)
    val trackTop = ((canvasHeight - trackHeight) * 0.5f).coerceAtLeast(0f)
    val inset = ((trackHeight - pillHeight) * 0.5f).coerceAtLeast(0f)
    val travel = (trackWidth - inset * 2f - pillWidth).coerceAtLeast(0f)
    val speed = abs(velocityPerSec)
    val flow = speed / (speed + 5f)
    val l = lift.coerceIn(-0.03f, 1.15f)
    val posLift = lift.coerceIn(0f, 1.15f)
    val scaleX = 1f + 0.36f * l + 0.14f * flow
    val scaleY = 1f + 0.48f * l - 0.04f * flow
    val maxW = canvasWidth.coerceAtLeast(1f)
    val maxH = canvasHeight.coerceAtLeast(1f)
    val width = (pillWidth * scaleX).coerceIn(1f, maxW)
    val height = (pillHeight * scaleY).coerceIn(1f, maxH)
    val centerX = trackLeft + inset + pillWidth * 0.5f + travel * position
    val centerY = trackTop + trackHeight * 0.5f
    val left = (centerX - width * 0.5f).coerceIn(0f, (canvasWidth - width).coerceAtLeast(0f))
    val top = (centerY - height * 0.5f).coerceIn(0f, (canvasHeight - height).coerceAtLeast(0f))
    val lensMix = lens.coerceIn(0f, 1f)
    val energy = (posLift * 0.78f + flow * 0.30f).coerceIn(0f, 1f)
    val fillAlpha = (1f - smoothstep01(lensMix) * 0.92f).coerceIn(0f, 1f)
    val highlightAlpha = (0.30f + 0.34f * energy).coerceIn(0f, 1f)
    val rimAlpha = ((if (isDark) 0.45f else 0.70f) * energy).coerceIn(0f, 1f)
    val shadowAlpha = ((if (isDark) 0.24f else 0.14f) * (0.4f + 0.6f * posLift)).coerceIn(0f, 1f)
    return ControlPillFrame(
        left = left,
        top = top,
        width = width,
        height = height,
        fillAlpha = fillAlpha,
        highlightAlpha = highlightAlpha,
        rimAlpha = rimAlpha,
        shadowAlpha = shadowAlpha,
        lensMix = lensMix,
    )
}

internal fun segmentIndexForX(x: Float, rowWidth: Float, count: Int): Int {
    if (count <= 1 || rowWidth <= 0f) return 0
    return (x / (rowWidth / count)).toInt().coerceIn(0, count - 1)
}

internal fun rubberBandSegmentPosition(rawPos: Float, lastIndex: Int): Float {
    val maxIdx = lastIndex.coerceAtLeast(0).toFloat()
    return when {
        rawPos < 0f -> -((-rawPos) * 0.18f).coerceAtMost(0.10f)
        rawPos > maxIdx -> maxIdx + ((rawPos - maxIdx) * 0.18f).coerceAtMost(0.10f)
        else -> rawPos
    }
}

internal fun resolveSegmentTarget(currentPos: Float, velocitySegmentsPerSec: Float, count: Int): Int {
    if (count <= 1) return 0
    val projection = if (abs(velocitySegmentsPerSec) >= 0.6f) {
        (velocitySegmentsPerSec * 0.045f).coerceIn(-0.45f, 0.45f)
    } else 0f
    return (currentPos + projection).roundToInt().coerceIn(0, count - 1)
}

internal fun computeSegmentPillFrame(
    canvasWidth: Float,
    canvasHeight: Float,
    bleedPx: Float,
    paddingPx: Float,
    gapPx: Float,
    count: Int,
    position: Float,
    velocitySegmentsPerSec: Float,
    lift: Float,
    lens: Float,
    isDark: Boolean,
    dragSqueeze: Float = 0f,
    liftY: Float? = null,
): ControlPillFrame {
    val trackLeft = bleedPx + paddingPx
    val trackTop = bleedPx + paddingPx
    val trackW = (canvasWidth - (bleedPx + paddingPx) * 2f).coerceAtLeast(1f)
    val trackH = (canvasHeight - (bleedPx + paddingPx) * 2f).coerceAtLeast(1f)
    val n = count.coerceAtLeast(1)
    val cellW = ((trackW - gapPx * (n - 1).coerceAtLeast(0)) / n).coerceAtLeast(1f)
    val speed = abs(velocitySegmentsPerSec)
    val flow = speed / (speed + 6f)
    val lx = lift.coerceIn(-0.18f, 1.20f)
    val ly = (liftY ?: lift).coerceIn(-0.14f, 1.20f)
    val posLift = max(lift, liftY ?: lift).coerceIn(0f, 1.15f)
    val squeeze = dragSqueeze.coerceIn(-0.30f, 1.15f)
    val posSqueeze = dragSqueeze.coerceIn(0f, 1.15f)
    val scaleX = (1f + 0.22f * lx - 0.28f * squeeze - 0.08f * flow).coerceAtLeast(0.76f)
    val scaleY = (1f + 0.30f * ly + 0.10f * posSqueeze + 0.04f * flow).coerceAtLeast(0.92f)
    val overhangX = if (bleedPx > 0f) bleedPx * 0.92f else 0f
    val overhangY = if (bleedPx > 0f) bleedPx * 0.92f else 0f
    val maxW = (trackW + overhangX * 2f).coerceAtLeast(1f)
    val maxH = (trackH + overhangY * 2f).coerceAtLeast(1f)
    val width = (cellW * scaleX).coerceIn(1f, maxW)
    val height = (trackH * scaleY).coerceIn(1f, maxH)
    val centerX = trackLeft + cellW * 0.5f + (cellW + gapPx) * position
    val centerY = trackTop + trackH * 0.5f
    val minX = (trackLeft - overhangX).coerceAtLeast(0f)
    val maxX = (trackLeft + trackW + overhangX - width).coerceAtMost(canvasWidth - width).coerceAtLeast(minX)
    val left = (centerX - width * 0.5f).coerceIn(minX, maxX)
    val top = (centerY - height * 0.5f).coerceIn(0f, (canvasHeight - height).coerceAtLeast(0f))
    val lensMix = lens.coerceIn(0f, 1f)
    val energy = (posLift * 0.75f + flow * 0.35f + posSqueeze * 0.25f).coerceIn(0f, 1f)
    val fillAlpha = (1f - smoothstep01(lensMix) * 0.92f).coerceIn(0f, 1f)
    val highlightAlpha = ((if (isDark) 0.22f else 0.42f) * max(0.35f, energy)).coerceIn(0f, 1f)
    val rimAlpha = ((if (isDark) 0.38f else 0.65f) * energy).coerceIn(0f, 1f)
    val shadowAlpha = ((if (isDark) 0.22f else 0.08f) * max(0.35f, posLift)).coerceIn(0f, 1f)
    return ControlPillFrame(
        left = left,
        top = top,
        width = width,
        height = height,
        fillAlpha = fillAlpha,
        highlightAlpha = highlightAlpha,
        rimAlpha = rimAlpha,
        shadowAlpha = shadowAlpha,
        lensMix = lensMix,
    )
}

internal fun rubberBandSliderValue(raw: Float): Float = when {
    raw < 0f -> -((-raw) * 0.18f).coerceAtMost(0.05f)
    raw > 1f -> 1f + ((raw - 1f) * 0.18f).coerceAtMost(0.05f)
    else -> raw
}

internal fun computeSliderPillFrame(
    canvasWidth: Float,
    canvasHeight: Float,
    bleedPx: Float,
    knobWidth: Float,
    knobHeight: Float,
    value: Float,
    velocityPerSec: Float,
    lift: Float,
    lens: Float,
    isDark: Boolean,
): ControlPillFrame {
    val trackLeft = bleedPx
    val trackW = (canvasWidth - bleedPx * 2f).coerceAtLeast(1f)
    val travel = (trackW - knobWidth).coerceAtLeast(0f)
    val speed = abs(velocityPerSec)
    val flow = speed / (speed + 5f)
    val l = lift.coerceIn(-0.03f, 1.15f)
    val posLift = lift.coerceIn(0f, 1.15f)
    val scaleX = 1f + 0.30f * l + 0.10f * flow
    val scaleY = 1f + 0.42f * l - 0.04f * flow
    val maxW = canvasWidth.coerceAtLeast(1f)
    val maxH = canvasHeight.coerceAtLeast(1f)
    val width = (knobWidth * scaleX).coerceIn(1f, maxW)
    val height = (knobHeight * scaleY).coerceIn(1f, maxH)
    val centerX = trackLeft + knobWidth * 0.5f + travel * value
    val centerY = canvasHeight * 0.5f
    val left = (centerX - width * 0.5f).coerceIn(0f, (canvasWidth - width).coerceAtLeast(0f))
    val top = (centerY - height * 0.5f).coerceIn(0f, (canvasHeight - height).coerceAtLeast(0f))
    val lensMix = lens.coerceIn(0f, 1f)
    val energy = (posLift * 0.78f + flow * 0.30f).coerceIn(0f, 1f)
    val fillAlpha = (1f - smoothstep01(lensMix) * 0.92f).coerceIn(0f, 1f)
    val highlightAlpha = (0.30f + 0.34f * energy).coerceIn(0f, 1f)
    val rimAlpha = ((if (isDark) 0.45f else 0.70f) * energy).coerceIn(0f, 1f)
    val shadowAlpha = ((if (isDark) 0.24f else 0.14f) * (0.4f + 0.6f * posLift)).coerceIn(0f, 1f)
    return ControlPillFrame(
        left = left,
        top = top,
        width = width,
        height = height,
        fillAlpha = fillAlpha,
        highlightAlpha = highlightAlpha,
        rimAlpha = rimAlpha,
        shadowAlpha = shadowAlpha,
        lensMix = lensMix,
    )
}

private const val LIQUID_CONTROL_LENS_AGSL = """
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
    float shadowStrength = (isDark > 0.5) ? 0.060 : 0.032;
    float shadowAlpha = shadowFalloff * shadowStrength * (t * t);

    if (pillMask <= 0.001) {
        return half4(0.0, 0.0, 0.0, half(shadowAlpha));
    }

    float2 grad = gradSdRoundedRect(p, pillHalfSize, r);
    float vert = grad.y * grad.y;
    float axisGain = mix(1.0, verticalRefraction, vert);
    float band = max(refractionBand * mix(1.0, 0.42, vert), 1.0);
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

    half3 airGlass = (isDark > 0.5) ? half3(0.18, 0.19, 0.21) : half3(0.97, 0.98, 0.99);
    half baseAlpha = clamp(cC.a, half(0.0), half(1.0));
    half alphaR = clamp(cR.a, half(0.0), half(1.0));
    half alphaB = clamp(cB.a, half(0.0), half(1.0));
    half3 unpremulC = (baseAlpha > half(0.01)) ? clamp(cC.rgb / baseAlpha, half3(0.0), half3(1.0)) : airGlass;
    half3 unpremulR = (alphaR > half(0.01)) ? clamp(cR.rgb / alphaR, half3(0.0), half3(1.0)) : unpremulC;
    half3 unpremulB = (alphaB > half(0.01)) ? clamp(cB.rgb / alphaB, half3(0.0), half3(1.0)) : unpremulC;

    half3 sampledC = mix(airGlass, unpremulC, baseAlpha);
    half3 sampledR = mix(airGlass, unpremulR, alphaR);
    half3 sampledB = mix(airGlass, unpremulB, alphaB);
    half3 crystal = mix(sampledC, half3(sampledR.r, sampledC.g, sampledB.b), half(edgeFactor * 0.85 * t * axisGain));

    float outerEdge = 1.0 - smoothstep(0.0, max(rimWidth * 0.65, 1.0), max(-sd, 0.0));
    float innerBand = smoothstep(0.0, max(rimWidth * 0.45, 0.8), max(-sd, 0.0))
        * (1.0 - smoothstep(max(rimWidth * 0.45, 0.8), max(rimWidth * 1.45, 1.8), max(-sd, 0.0)));
    float topLight = smoothstep(-0.55, 0.5, -grad.y);
    float bottomBounce = smoothstep(0.15, 0.9, grad.y) * 0.48;
    float rimSpec = innerBand * (0.22 + 0.78 * max(topLight, bottomBounce));

    half3 finalRgb;
    if (isDark > 0.5) {
        finalRgb = mix(crystal, half3(0.06, 0.08, 0.11), half(outerEdge * 0.22 * t));
        finalRgb += half3(rimSpec * 0.30 * t);
    } else {
        half3 clearCrystal = mix(crystal, half3(1.0), half(0.08 * t));
        finalRgb = mix(clearCrystal, half3(0.56, 0.60, 0.66), half(outerEdge * 0.22 * t));
        finalRgb += half3(rimSpec * 0.26 * t);
    }
    finalRgb = clamp(finalRgb, half3(0.0), half3(1.0));

    half glassOverhangAlpha = mix(half(0.46), half(1.0), baseAlpha);
    half pillA = half(pillMask) * glassOverhangAlpha;
    half outAlpha = clamp(pillA + half(shadowAlpha) * (half(1.0) - half(pillMask)), half(0.0), half(1.0));
    return half4(finalRgb * pillA, outAlpha);
}
"""

@RequiresApi(33)
internal object ControlLensApi33 {
    fun createEffect(
        canvasWidth: Float,
        canvasHeight: Float,
        frame: ControlPillFrame,
        density: Float,
        isDark: Boolean,
        refractionBandDp: Float = 10f,
        refractionAmountDp: Float = 12f,
        magnification: Float = 0.14f,
        dispersion: Float = 0.20f,
        verticalRefraction: Float = 0.18f,
        verticalMagnify: Float = 0.62f,
    ): androidx.compose.ui.graphics.RenderEffect? = runCatching {
        val shader = RuntimeShader(LIQUID_CONTROL_LENS_AGSL).apply {
            setFloatUniform("canvasSize", canvasWidth.coerceAtLeast(1f), canvasHeight.coerceAtLeast(1f))
            setFloatUniform(
                "pillCenter",
                frame.left + frame.width * 0.5f,
                frame.top + frame.height * 0.5f,
            )
            setFloatUniform("pillHalfSize", frame.width * 0.5f, frame.height * 0.5f)
            setFloatUniform("pillRadius", frame.height * 0.5f)
            setFloatUniform("lensMix", frame.lensMix)
            setFloatUniform("refractionBand", refractionBandDp * density)
            setFloatUniform("refractionAmount", refractionAmountDp * density)
            setFloatUniform("magnification", magnification)
            setFloatUniform("dispersion", dispersion)
            setFloatUniform("isDark", if (isDark) 1f else 0f)
            setFloatUniform("shadowBlur", 2.5f * density)
            setFloatUniform("shadowOffsetY", 0.5f * density)
            setFloatUniform("rimWidth", 1.35f * density)
            setFloatUniform("verticalRefraction", verticalRefraction)
            setFloatUniform("verticalMagnify", verticalMagnify)
        }
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.getOrNull()
}
