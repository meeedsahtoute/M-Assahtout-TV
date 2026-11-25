package com.streamtube.tv.ui

import android.media.MediaPlayer
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.streamtube.tv.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TOTAL_SPLASH_MS = 3200f
private const val HOME_MOUNT_MS = 2250f
private const val DIVE_START_MS = 2400f
private const val BOKEH_COUNT = 24
private const val RIBBON_COUNT = 14

private val FlagshipSilkEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val DiveAccelEase = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

private class OpticalSplashPool {
    // 24 ultra-fine slow-drifting PS5-style bokeh light motes
    val bokehNormX = FloatArray(BOKEH_COUNT)
    val bokehNormY = FloatArray(BOKEH_COUNT)
    val bokehVelX = FloatArray(BOKEH_COUNT)
    val bokehVelY = FloatArray(BOKEH_COUNT)
    val bokehRadiusDp = FloatArray(BOKEH_COUNT)
    val bokehDepth = FloatArray(BOKEH_COUNT)
    val bokehPhase = FloatArray(BOKEH_COUNT)
    val bokehKind = IntArray(BOKEH_COUNT) // 0 = ruby, 1 = silver, 2 = warm white

    // 14 Netflix-style vertical translucent optical light ribbons for Phase 4 dive-through
    val ribbonStartNormX = FloatArray(RIBBON_COUNT)
    val ribbonSpreadVel = FloatArray(RIBBON_COUNT)
    val ribbonWidthDp = FloatArray(RIBBON_COUNT)
    val ribbonAlpha = FloatArray(RIBBON_COUNT)
    val ribbonKind = IntArray(RIBBON_COUNT) // 0 = crimson, 1 = ruby, 2 = silver

    init {
        val rng = Random(480600)
        for (i in 0 until BOKEH_COUNT) {
            // Keep motes balanced across 16:9 depth field
            bokehNormX[i] = 0.08f + rng.nextFloat() * 0.84f
            bokehNormY[i] = 0.08f + rng.nextFloat() * 0.84f
            bokehVelX[i] = (rng.nextFloat() - 0.5f) * 0.022f
            bokehVelY[i] = -(0.012f + rng.nextFloat() * 0.026f)
            val depth = 0.35f + rng.nextFloat() * 1.15f
            bokehDepth[i] = depth
            bokehRadiusDp[i] = 10f + depth * 28f
            bokehPhase[i] = rng.nextFloat() * (2f * PI.toFloat())
            bokehKind[i] = i % 3
        }

        // Symmetric yet organically staggered 14 vertical spectrum ribbons
        val ribbonPatterns = floatArrayOf(
            -0.015f, 0.015f, -0.042f, 0.042f,
            -0.075f, 0.075f, -0.115f, 0.115f,
            -0.165f, 0.165f, -0.220f, 0.220f,
            -0.285f, 0.285f
        )
        val widths = floatArrayOf(
            24f, 22f, 44f, 40f,
            68f, 64f, 34f, 36f,
            82f, 78f, 48f, 52f,
            96f, 90f
        )
        val kinds = intArrayOf(
            0, 2, 1, 0,
            0, 1, 2, 0,
            1, 2, 0, 1,
            0, 2
        )
        for (i in 0 until RIBBON_COUNT) {
            val baseOffset = ribbonPatterns[i]
            ribbonStartNormX[i] = baseOffset * 0.35f
            val dir = if (baseOffset < 0f) -1f else 1f
            val tier = (i / 2) + 1
            ribbonSpreadVel[i] = dir * (0.42f + tier * 0.19f + rng.nextFloat() * 0.08f)
            ribbonWidthDp[i] = widths[i]
            ribbonAlpha[i] = 0.32f + rng.nextFloat() * 0.42f
            ribbonKind[i] = kinds[i]
        }
    }
}

@Composable
fun SplashScreen(
    onMountHome: () -> Unit,
    onHomeTransitionProgress: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val logoBitmap = remember { ImageBitmap.imageResource(context.resources, R.drawable.splash_logo) }
    val focusRequester = remember { FocusRequester() }
    val pool = remember { OpticalSplashPool() }

    // Pre-allocated Brushes (reused across all frames with zero GC allocation)
    val slitHorizBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color(0x55991B1B),
                Color(0xCCFF0033),
                Color(0xFFFFFFFF),
                Color(0xCCFF0033),
                Color(0x55991B1B),
                Color.Transparent
            )
        )
    }
    val slitBloomBrush = remember {
        Brush.radialGradient(
            0.00f to Color(0x88FF1E42),
            0.35f to Color(0x44B91C1C),
            0.70f to Color(0x167F1D1D),
            1.00f to Color.Transparent
        )
    }
    val rubyBacklightBrush = remember {
        Brush.radialGradient(
            0.00f to Color(0x66FF0033),
            0.38f to Color(0x36991B1B),
            0.72f to Color(0x14450A0A),
            1.00f to Color.Transparent
        )
    }
    val bokehRubyBrush = remember {
        Brush.radialGradient(
            0.00f to Color(0x52FF2E55),
            0.45f to Color(0x26E11D48),
            1.00f to Color.Transparent,
            center = Offset.Zero,
            radius = 64f
        )
    }
    val bokehSilverBrush = remember {
        Brush.radialGradient(
            0.00f to Color(0x44F5F5F7),
            0.45f to Color(0x1AA8A8B3),
            1.00f to Color.Transparent,
            center = Offset.Zero,
            radius = 64f
        )
    }
    val bokehWarmBrush = remember {
        Brush.radialGradient(
            0.00f to Color(0x3EFFD6DE),
            0.45f to Color(0x18FB7185),
            1.00f to Color.Transparent,
            center = Offset.Zero,
            radius = 64f
        )
    }
    val rimSweepBrush = remember {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.06f to Color(0x44FF1E42),
            0.13f to Color(0xAAE5E7EB),
            0.17f to Color(0xFFFFFFFF),
            0.21f to Color(0xAAE5E7EB),
            0.27f to Color(0x44FF1E42),
            0.35f to Color.Transparent,
            1.00f to Color.Transparent
        )
    }
    val glassReflectionBrush = remember(density) {
        val span = 132f * density
        Brush.linearGradient(
            0.00f to Color.Transparent,
            0.26f to Color(0x16FFFFFF),
            0.45f to Color(0x5CF5F5F7),
            0.52f to Color(0x7AFFFFFF),
            0.60f to Color(0x30FF4D6D),
            0.78f to Color(0x12FFFFFF),
            1.00f to Color.Transparent,
            start = Offset(-span, -span),
            end = Offset(span, span)
        )
    }
    val dividerBeamBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color(0xCCFF0033),
                Color(0xFFF5F5F7),
                Color(0xCCFF0033),
                Color.Transparent
            )
        )
    }
    val ribbonCrimsonBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color(0x44991B1B),
                Color(0xCCFF0033),
                Color(0xEEFF5577),
                Color(0xCCFF0033),
                Color(0x44991B1B),
                Color.Transparent
            ),
            startX = -0.5f,
            endX = 0.5f
        )
    }
    val ribbonRubyBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color(0x387F1D1D),
                Color(0xBBDC2626),
                Color(0x88FDA4AF),
                Color(0xBBDC2626),
                Color(0x387F1D1D),
                Color.Transparent
            ),
            startX = -0.5f,
            endX = 0.5f
        )
    }
    val ribbonSilverBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color(0x30A8A8B3),
                Color(0xBBF5F5F7),
                Color(0xEEFFFFFF),
                Color(0xBBF5F5F7),
                Color(0x30A8A8B3),
                Color.Transparent
            ),
            startX = -0.5f,
            endX = 0.5f
        )
    }

    var elapsedMs by remember { mutableFloatStateOf(0f) }
    var skipRequested by remember { mutableStateOf(false) }
    var homeMounted by remember { mutableStateOf(false) }

    val mediaPlayer = remember {
        runCatching {
            MediaPlayer.create(context, R.raw.intro_cinema)?.apply {
                setVolume(1.0f, 1.0f)
            }
        }.getOrNull()
    }

    fun releaseAudio() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
    }

    fun triggerSkip() {
        if (!skipRequested) {
            skipRequested = true
            if (!homeMounted) {
                homeMounted = true
                onMountHome()
            }
            onHomeTransitionProgress(1f)
            releaseAudio()
            onFinished()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            releaseAudio()
        }
    }

    BackHandler(enabled = !skipRequested) {
        triggerSkip()
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        runCatching { mediaPlayer?.start() }

        val startNs = withFrameNanos { it }
        while (!skipRequested) {
            val nowMs = withFrameNanos { nowNs -> (nowNs - startNs) / 1_000_000f }
            elapsedMs = nowMs

            if (!homeMounted && nowMs >= HOME_MOUNT_MS) {
                homeMounted = true
                onMountHome()
            }
            if (nowMs >= DIVE_START_MS) {
                val rawDive = ((nowMs - DIVE_START_MS) / (TOTAL_SPLASH_MS - DIVE_START_MS)).coerceIn(0f, 1f)
                onHomeTransitionProgress(DiveAccelEase.transform(rawDive))
            }
            if (nowMs >= TOTAL_SPLASH_MS) {
                if (!homeMounted) {
                    homeMounted = true
                    onMountHome()
                }
                onHomeTransitionProgress(1f)
                onFinished()
                break
            }
        }
    }

    // Phase 4 Dive-Through parameters (2400ms - 3200ms)
    val diveP = ((elapsedMs - DIVE_START_MS) / (TOTAL_SPLASH_MS - DIVE_START_MS)).coerceIn(0f, 1f)
    val diveEase = DiveAccelEase.transform(diveP)
    // Pure black OLED scrim holds 1.0 until 2400ms, then smoothly unveils already-bright Home (zero black flash)
    val backdropAlpha = (1f - FlagshipSilkEase.transform(diveP)).coerceIn(0f, 1f)

    // Phase 2 Sculpted 3D Logo Emergence + Focus Pull (480ms - 1450ms)
    val emergeRaw = ((elapsedMs - 480f) / 720f).coerceIn(0f, 1f)
    val emergeEase = FlagshipSilkEase.transform(emergeRaw)
    val focusPullRaw = ((elapsedMs - 480f) / 420f).coerceIn(0f, 1f)
    val focusPullEase = FlagshipSilkEase.transform(focusPullRaw)

    val emergeScale = 0.84f + 0.16f * emergeEase
    val logoScale = emergeScale * (1f + 9.0f * diveEase)
    val logoRotY = -10f * (1f - emergeEase)
    val logoBlurDp = 10f * (1f - focusPullEase)

    val logoAlpha = when {
        elapsedMs < 480f -> 0f
        elapsedMs < 760f -> {
            // Smooth optical fade-in synced with Hit 1 (480ms) & Hit 2 (600ms)
            val a = ((elapsedMs - 480f) / 220f).coerceIn(0f, 1f)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) FlagshipSilkEase.transform(a) else a
        }
        elapsedMs >= DIVE_START_MS -> (1f - diveEase * 1.15f).coerceIn(0f, 1f)
        else -> 1f
    }

    // Phase 3 Editorial Luxury Typography (1150ms - 2400ms)
    val textRaw = ((elapsedMs - 1150f) / 1050f).coerceIn(0f, 1f)
    val textEase = FlagshipSilkEase.transform(textRaw)
    val textFocusRaw = ((elapsedMs - 1150f) / 420f).coerceIn(0f, 1f)
    val textBlurDp = 6f * (1f - FlagshipSilkEase.transform(textFocusRaw))
    val textSlideYDp = 12f * (1f - textEase)
    val textTrackingEm = 0.16f + 0.24f * textEase
    val textRevealAlpha = ((elapsedMs - 1150f) / 340f).coerceIn(0f, 1f) * (1f - diveEase * 2.1f).coerceIn(0f, 1f)
    val subtitleAlpha = ((elapsedMs - 1340f) / 380f).coerceIn(0f, 1f) * (1f - diveEase * 2.3f).coerceIn(0f, 1f)
    val beamProgress = FlagshipSilkEase.transform(((elapsedMs - 1220f) / 860f).coerceIn(0f, 1f))

    val supportsRenderEffectBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (ev.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BACK -> {
                            triggerSkip()
                            true
                        }
                        else -> true
                    }
                } else {
                    false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        // Master 60fps Optical Backdrop Canvas: OLED Scrim, Optical Prism Slit, Ruby Backlight, Bokeh Motes & 14 Ribbons
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.44f

            // Pure #000000 OLED canvas fading smoothly during Phase 4
            if (backdropAlpha > 0.004f) {
                drawRect(Color.Black.copy(alpha = backdropAlpha))
            }

            // Atmospheric Ruby-Crimson Radial Backlight (dissolves out of Phase 1 slit & breathes smoothly)
            val backlightBuild = if (elapsedMs < 300f) {
                0f
            } else {
                FlagshipSilkEase.transform(((elapsedMs - 300f) / 480f).coerceIn(0f, 1f))
            }
            val backlightPulse = 0.90f + 0.10f * sin(elapsedMs * 0.0028f)
            val backlightAlpha = backlightBuild * backlightPulse * (1f - diveEase * 0.85f).coerceIn(0f, 1f)
            if (backlightAlpha > 0.005f) {
                val glowRadius = (360f * density) * (1f + 0.55f * diveEase)
                drawCircle(
                    brush = rubyBacklightBrush,
                    radius = glowRadius,
                    center = Offset(cx, cy),
                    alpha = backlightAlpha
                )
            }

            // 24 Ultra-Fine Slow-Drifting PS5-Style Bokeh Light Motes across entire 3.2s
            val tSec = elapsedMs / 1000f
            val bokehFadeIn = (elapsedMs / 320f).coerceIn(0f, 1f)
            val bokehFadeOut = (1f - diveEase * 1.15f).coerceIn(0f, 1f)
            val bokehGlobalAlpha = bokehFadeIn * bokehFadeOut
            if (bokehGlobalAlpha > 0.01f) {
                for (i in 0 until BOKEH_COUNT) {
                    val bx = (pool.bokehNormX[i] + pool.bokehVelX[i] * tSec) * w
                    val by = (pool.bokehNormY[i] + pool.bokehVelY[i] * tSec) * h
                    val rPx = pool.bokehRadiusDp[i] * density
                    val shimmer = 0.55f + 0.45f * sin(tSec * 1.9f + pool.bokehPhase[i])
                    val moteAlpha = bokehGlobalAlpha * shimmer * (0.42f + 0.36f * (pool.bokehDepth[i] / 1.5f))
                    val brush = when (pool.bokehKind[i]) {
                        0 -> bokehRubyBrush
                        1 -> bokehSilverBrush
                        else -> bokehWarmBrush
                    }
                    val scaleF = rPx / 64f
                    withTransform({
                        translate(left = bx, top = by)
                        scale(scaleX = scaleF, scaleY = scaleF, pivot = Offset.Zero)
                    }) {
                        drawCircle(
                            brush = brush,
                            radius = 64f,
                            center = Offset.Zero,
                            alpha = moteAlpha
                        )
                    }
                }
            }

            // Phase 1 (0ms - 520ms): Optical Prism Slit + Soft Vertical Volumetric Bloom
            if (elapsedMs < 640f) {
                val openEase = FlagshipSilkEase.transform(((elapsedMs - 20f) / 360f).coerceIn(0f, 1f))
                val slitFadeOut = if (elapsedMs > 360f) {
                    1f - FlagshipSilkEase.transform(((elapsedMs - 360f) / 260f).coerceIn(0f, 1f))
                } else {
                    (elapsedMs / 110f).coerceIn(0f, 1f)
                }
                val slitW = 420f * density * openEase
                if (slitW > 2f && slitFadeOut > 0.01f) {
                    // Soft vertical volumetric bloom expanding gently behind the slit
                    val bloomW = slitW * 1.18f
                    val bloomH = (54f + 165f * openEase) * density
                    withTransform({
                        translate(left = cx, top = cy)
                        scale(scaleX = 1f, scaleY = bloomH / bloomW.coerceAtLeast(1f), pivot = Offset.Zero)
                    }) {
                        drawCircle(
                            brush = slitBloomBrush,
                            radius = bloomW * 0.5f,
                            center = Offset.Zero,
                            alpha = slitFadeOut * 0.85f
                        )
                    }

                    // Razor-thin crimson-white horizontal optical light slit
                    val coreH = (1.5f + 1.5f * openEase) * density
                    drawRect(
                        brush = slitHorizBrush,
                        topLeft = Offset(cx - slitW * 0.5f, cy - coreH * 0.5f),
                        size = Size(slitW, coreH),
                        alpha = slitFadeOut * 0.96f
                    )
                    // Hairline white center filament
                    drawLine(
                        color = Color.White.copy(alpha = slitFadeOut * 0.92f),
                        start = Offset(cx - slitW * 0.28f, cy),
                        end = Offset(cx + slitW * 0.28f, cy),
                        strokeWidth = 1.2f * density,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Phase 4 (2400ms - 3200ms): 14 Signature Netflix-Style Optical Spectrum Ribbons
            if (elapsedMs >= DIVE_START_MS) {
                val ribbonFadeIn = ((elapsedMs - DIVE_START_MS) / 140f).coerceIn(0f, 1f)
                val ribbonFadeOut = (1f - ((diveP - 0.68f) / 0.32f).coerceIn(0f, 1f))
                val ribbonGlobalAlpha = ribbonFadeIn * ribbonFadeOut
                if (ribbonGlobalAlpha > 0.01f) {
                    for (i in 0 until RIBBON_COUNT) {
                        val xPx = cx +
                            (pool.ribbonStartNormX[i] + pool.ribbonSpreadVel[i] * diveEase * 1.35f) * w
                        val widthPx = pool.ribbonWidthDp[i] * density * (0.75f + 1.25f * diveEase)
                        if (xPx + widthPx * 0.5f >= 0f && xPx - widthPx * 0.5f <= w) {
                            val brush = when (pool.ribbonKind[i]) {
                                0 -> ribbonCrimsonBrush
                                1 -> ribbonRubyBrush
                                else -> ribbonSilverBrush
                            }
                            withTransform({
                                translate(left = xPx, top = 0f)
                                scale(scaleX = widthPx, scaleY = 1f, pivot = Offset.Zero)
                            }) {
                                drawRect(
                                    brush = brush,
                                    topLeft = Offset(-0.5f, 0f),
                                    size = Size(1f, h),
                                    alpha = pool.ribbonAlpha[i] * ribbonGlobalAlpha
                                )
                            }
                        }
                    }
                }
            }
        }

        // Foreground Hero Stack: Sculpted 3D Circular Logo + Editorial Luxury Typography
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(236.dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                        alpha = logoAlpha
                        rotationY = logoRotY
                        cameraDistance = 20f * density
                    }
                    .then(
                        if (supportsRenderEffectBlur && logoBlurDp > 0.15f) {
                            Modifier.blur(
                                radius = logoBlurDp.dp,
                                edgeTreatment = BlurredEdgeTreatment.Unbounded
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Full-bleed circular 4K logo with SrcAtop angled glass/chrome reflection
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()

                            // Angled soft glass/chrome reflection gliding across "M" and "A" (BlendMode.SrcAtop)
                            val sheen1P = ((elapsedMs - 680f) / 1150f)
                            if (sheen1P in -0.35f..1.35f && diveP < 0.75f) {
                                val travel = (sheen1P * 1.4f - 0.7f) * size.width
                                withTransform({
                                    translate(left = center.x + travel, top = center.y + travel * 0.32f)
                                }) {
                                    drawRect(
                                        brush = glassReflectionBrush,
                                        topLeft = Offset(-size.width, -size.height),
                                        size = Size(size.width * 2f, size.height * 2f),
                                        blendMode = BlendMode.SrcAtop
                                    )
                                }
                            }
                        }
                ) {
                    Image(
                        bitmap = logoBitmap,
                        contentDescription = "M. ASSAHTOUT TV",
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 360° Orbiting Photorealistic Metallic Specular Rim Highlight (SweepGradient)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (elapsedMs >= 520f && diveP < 0.85f) {
                        val rimProgress = ((elapsedMs - 520f) / 1820f).coerceIn(0f, 1.2f)
                        val rimAlpha = ((elapsedMs - 520f) / 180f).coerceIn(0f, 1f) *
                            (1f - diveEase * 1.4f).coerceIn(0f, 1f)
                        val orbitDeg = -135f + rimProgress * 360f
                        val bezelR = size.minDimension * 0.486f
                        rotate(degrees = orbitDeg, pivot = center) {
                            drawCircle(
                                brush = rimSweepBrush,
                                radius = bezelR,
                                center = center,
                                style = Stroke(width = 3.2f * density),
                                alpha = rimAlpha
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // Phase 3: Editorial Luxury Typography ("M. ASSAHTOUT" + crimson-silver beam + "TV EDITION")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .graphicsLayer {
                        val diveZoom = 1f + 2.2f * diveEase
                        scaleX = diveZoom
                        scaleY = diveZoom
                        translationY = textSlideYDp * density
                    }
            ) {
                Text(
                    text = "M. ASSAHTOUT",
                    modifier = Modifier
                        .graphicsLayer { alpha = textRevealAlpha }
                        .then(
                            if (supportsRenderEffectBlur && textBlurDp > 0.15f) {
                                Modifier.blur(
                                    radius = textBlurDp.dp,
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded
                                )
                            } else {
                                Modifier
                            }
                        ),
                    style = TextStyle(
                        color = Color(0xFFF5F5F7),
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = textTrackingEm.em,
                        shadow = Shadow(
                            color = Color(0x88FF0033),
                            offset = Offset(0f, 0f),
                            blurRadius = 18f
                        )
                    )
                )

                Spacer(Modifier.height(11.dp))

                // Hairline crimson-to-silver light beam expanding cleanly from center
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth(0.44f)
                        .height(1.5.dp)
                        .graphicsLayer { alpha = textRevealAlpha }
                ) {
                    val beamW = size.width * beamProgress
                    if (beamW > 1f) {
                        val mid = size.width * 0.5f
                        drawLine(
                            brush = dividerBeamBrush,
                            start = Offset(mid - beamW * 0.5f, size.height * 0.5f),
                            end = Offset(mid + beamW * 0.5f, size.height * 0.5f),
                            strokeWidth = 1.5f * density,
                            cap = StrokeCap.Round
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "TV EDITION",
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha },
                    style = TextStyle(
                        color = Color(0xFFA8A8B3),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (textTrackingEm * 1.15f).em
                    )
                )
            }
        }
    }
}
