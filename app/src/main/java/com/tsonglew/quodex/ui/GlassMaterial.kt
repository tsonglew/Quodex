package com.tsonglew.quodex.ui

import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tsonglew.quodex.data.GlassPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

internal class GlassBackdrop(val layer: GraphicsLayer, val knownStats: GlassBackdropStats? = null) {
    var generation by mutableLongStateOf(0L)
    var origin by mutableStateOf(Offset.Zero)
}

internal data class GlassEnvironment(
    val dark: Boolean = false,
    val preferences: GlassPreferences = GlassPreferences(),
    val highContrast: Boolean = false,
)

internal val LocalGlassEnvironment = staticCompositionLocalOf { GlassEnvironment() }
internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
internal val LocalGlassScene = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
internal fun ProvideGlassMaterial(dark: Boolean, preferences: GlassPreferences, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { GlassBackdrop(layer) }
    val sceneLayer = rememberGraphicsLayer()
    val sceneColors = glassSceneColors(dark)
    val scene = remember(sceneLayer, dark) {
        GlassBackdrop(sceneLayer, GlassBackdropStats.from(List(15) { sceneColors[it % sceneColors.size] }))
    }
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var highContrast by remember { mutableStateOf(Build.VERSION.SDK_INT >= 36 && manager.isHighContrastTextEnabled) }
    DisposableEffect(manager) {
        if (Build.VERSION.SDK_INT >= 36) {
            val listener = AccessibilityManager.HighContrastTextStateChangeListener { highContrast = it }
            manager.addHighContrastTextStateChangeListener(context.mainExecutor, listener)
            onDispose { manager.removeHighContrastTextStateChangeListener(listener) }
        } else onDispose { }
    }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalGlassScene provides scene,
        LocalGlassEnvironment provides GlassEnvironment(dark, preferences, highContrast), content = content)
}

/** Attach to the existing page root. Separate Dialog/Popup windows are not in this display list. */
@Composable
internal fun Modifier.captureGlassBackdrop(): Modifier {
    val source = LocalGlassBackdrop.current ?: return this
    return onGloballyPositioned { source.origin = it.localToScreen(Offset.Zero) }
        .drawWithContent {
            source.layer.record { this@drawWithContent.drawContent() }
            drawLayer(source.layer)
            // Do not subscribe this source draw to its own revision.
            Snapshot.withoutReadObservation { source.generation++ }
        }
}

internal data class GlassSurfaceMaterial(val modifier: Modifier, val tokens: GlassTokens, val stats: GlassBackdropStats?)

@OptIn(FlowPreview::class)
@Composable
internal fun rememberGlassSurface(id: String, shape: Shape, lens: Boolean = true, active: Boolean = true,
    source: GlassBackdrop? = LocalGlassBackdrop.current): GlassSurfaceMaterial {
    val environment = LocalGlassEnvironment.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val inkStore = remember(context) { context.getSharedPreferences("glass_ink", android.content.Context.MODE_PRIVATE) }
    // Only an ink bit is persisted, never pixels, account details, or screen contents.
    val inkKey = "$id:${if (environment.dark) "dark" else "light"}"
    var previousInk by remember(inkKey) { mutableStateOf(inkStore.getBoolean(inkKey, environment.dark)) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var stats by remember(source) { mutableStateOf(source?.knownStats) }
    var waitingForSample by remember { mutableStateOf(true) }
    val sampleLayer = rememberGraphicsLayer()
    val materialLayer = rememberGraphicsLayer()
    val tintLayer = rememberGraphicsLayer()
    val reduced = environment.preferences.reduceTransparency || environment.highContrast
    val supported = active && Build.VERSION.SDK_INT >= 31 && source != null && !reduced
    LaunchedEffect(source, supported, environment.dark, density, direction) {
        stats = source?.knownStats
        waitingForSample = supported
        // The page scene is a known continuous gradient: all panels share its conservative
        // color bounds and ink decision, with no per-card GPU readback or scroll sampling.
        if (source?.knownStats != null) { waitingForSample = false; return@LaunchedEffect }
        if (!supported) return@LaunchedEffect
        snapshotFlow { Triple(source.generation, source.origin, bounds) }.debounce(120).collectLatest { (revision, origin, rect) ->
            if (revision == 0L || rect.width <= 0 || rect.height <= 0) return@collectLatest
            val delta = rect.topLeft - origin
            if (delta.x < 0 || delta.y < 0 || delta.x + rect.width > source.layer.size.width ||
                delta.y + rect.height > source.layer.size.height) { stats = null; waitingForSample = false; return@collectLatest }
            try {
                // Fifteen point samples, not a downscaled image (which would average away texture).
                // GPU clipping keeps readback at fifteen pixels without a full-screen CPU copy.
                sampleLayer.record(density, direction, IntSize(5, 3)) {
                    for (y in 0..2) for (x in 0..4)
                        clipRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f) {
                            translate(x + 0.5f - delta.x - (x + 0.5f) * rect.width / 5f,
                                y + 0.5f - delta.y - (y + 0.5f) * rect.height / 3f) { drawLayer(source.layer) }
                        }
                }
                val pixels = sampleLayer.toImageBitmap().toPixelMap()
                stats = GlassBackdropStats.from(List(15) { pixels[it % 5, it / 5] })
                waitingForSample = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { stats = null; waitingForSample = false }
        }
    }
    val target = remember(environment, stats, previousInk, supported, waitingForSample) {
        resolveGlassTokens(environment.dark, environment.preferences, stats, previousInk,
            forceSolid = !supported || stats == null, restorePreviousInk = supported && waitingForSample,
            // The source is a page containing text/controls, not a known continuous image.
            // Fifteen samples can miss white glyphs and tiny black details between grid points.
            protectUnsampledDetail = source?.knownStats == null)
    }
    LaunchedEffect(target.lightInk, stats != null, inkKey) {
        if (stats != null) {
            previousInk = target.lightInk
            if (inkStore.getBoolean(inkKey, !target.lightInk) != target.lightInk)
                inkStore.edit().putBoolean(inkKey, target.lightInk).apply()
        }
    }
    // Immediate solid fallback; animate normal settings changes only within the same ink state.
    val tint by key(target.lightInk) {
        animateColorAsState(target.tint, if (target.solid) snap() else tween(220), label = "glass-tint")
    }
    val saturation by animateFloatAsState(target.saturation, tween(220), label = "glass-saturation")
    val brightness by animateFloatAsState(target.brightness, tween(220), label = "glass-brightness")
    val edgeAlpha by animateFloatAsState(target.edgeAlpha, if (target.solid) snap() else tween(220), label = "glass-bevel-tint")
    // Raise readability density immediately; thinning may glide without briefly losing contrast.
    val tokens = target.copy(tint = tint.copy(alpha = max(tint.alpha, target.tint.alpha)),
        edgeAlpha = edgeAlpha, saturation = saturation, brightness = brightness)
    val blurPixels = with(density) { GlassTokens.BlurDp.dp.toPx() }
    val shader = remember(blurPixels) {
        if (Build.VERSION.SDK_INT >= 33) runCatching { GlassLensEffect(blurPixels) }.getOrNull() else null
    }
    val blur = remember(blurPixels) { GlassBlurEffect(blurPixels) }
    val tintBlur = remember(blurPixels) { GlassBlurEffect(blurPixels * 1.5f) }
    val modifier = Modifier.onGloballyPositioned {
        val origin = it.localToScreen(Offset.Zero)
        bounds = Rect(origin, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val rounded = (outline as? Outline.Rounded)?.roundRect
        val uniformCorners = rounded != null && rounded.topLeftCornerRadius == rounded.topRightCornerRadius &&
            rounded.topLeftCornerRadius == rounded.bottomLeftCornerRadius &&
            rounded.topLeftCornerRadius == rounded.bottomRightCornerRadius &&
            rounded.topLeftCornerRadius.x == rounded.topLeftCornerRadius.y
        val radius = if (uniformCorners) rounded!!.topLeftCornerRadius.x else 0f
        val rim = if (lens && uniformCorners && size.minDimension >= GlassTokens.MinLensSideDp.dp.toPx())
            max(4.dp.toPx(), min(GlassTokens.RimDp.dp.toPx(), size.minDimension / 3.5f)) else 0f
        val padding = 4 * blurPixels
        val paddedSize = IntSize((size.width + padding * 2).roundToInt(), (size.height + padding * 2).roundToInt())
        val materialSize = size
        val highlight = Brush.radialGradient(listOf(Color.White.copy(alpha = tokens.highlight), Color.Transparent),
            center = Offset.Zero, radius = max(size.maxDimension * 0.55f, 1f))
        val rimBrush = Brush.linearGradient(listOf(Color.White.copy(alpha = tokens.highlight * 2.5f),
            Color.White.copy(alpha = tokens.highlight * 0.35f), Color.Black.copy(alpha = tokens.highlight * 0.2f)),
            start = Offset.Zero, end = Offset(size.width, size.height))
        onDrawWithContent {
            val revision = source?.generation ?: 0L
            clipPath(path) {
                if (!target.solid && revision > 0 && source != null) {
                    val delta = bounds.topLeft - source.origin
                    materialLayer.record(this, layoutDirection, paddedSize) {
                        translate(padding - delta.x, padding - delta.y) { drawLayer(source.layer) }
                    }
                    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                        shader.update(materialSize.width, materialSize.height, padding, radius, rim,
                            tokens.saturation, tokens.brightness, 0.05f,
                            tokens.copy(edgeAlpha = if (uniformCorners) tokens.edgeAlpha else tokens.tint.alpha),
                            if (lens) 12.dp.toPx() else 4.dp.toPx(), revision to bounds)
                        materialLayer.renderEffect = shader.effect
                        materialLayer.colorFilter = null
                    } else {
                        materialLayer.renderEffect = blur.forInput(listOf(revision, bounds, tokens.saturation, tokens.brightness))
                        val matrix = ColorMatrix().apply { setToSaturation(tokens.saturation) }
                        for (row in 0..2) for (column in 0..4) matrix[row, column] *= tokens.brightness
                        materialLayer.colorFilter = ColorFilter.colorMatrix(matrix)
                    }
                    translate(-padding, -padding) { drawLayer(materialLayer) }
                }
                if (target.solid || revision == 0L) drawRect(tokens.tint)
                else if (shader == null) {
                    if (uniformCorners && lens) {
                        // API 31–32: same readable core with a blurred inset fill, no AGSL/refraction.
                        drawRect(tokens.tint.copy(alpha = tokens.edgeAlpha))
                        val inset = 8.dp.toPx()
                        val coreAlpha = if (tokens.edgeAlpha >= 1f) 0f else
                            ((tokens.tint.alpha - tokens.edgeAlpha) / (1f - tokens.edgeAlpha)).coerceIn(0f, 1f)
                        tintLayer.record(this, layoutDirection, IntSize(materialSize.width.roundToInt(), materialSize.height.roundToInt())) {
                            drawRoundRect(tokens.tint.copy(alpha = coreAlpha), topLeft = Offset(inset, inset),
                                size = Size(max(0f, materialSize.width - 2 * inset), max(0f, materialSize.height - 2 * inset)),
                                cornerRadius = CornerRadius(max(0f, radius - inset)))
                        }
                        tintLayer.renderEffect = tintBlur.forInput(listOf(coreAlpha, tokens.tint, materialSize))
                        drawLayer(tintLayer)
                    } else drawRect(tokens.tint)
                }
                if (!target.solid) {
                    drawRect(highlight)
                    drawPath(path, rimBrush, style = Stroke(3.dp.toPx()))
                    drawPath(path, tokens.outline, style = Stroke(0.5.dp.toPx()))
                }
            }
            drawContent()
        }
    }
    return GlassSurfaceMaterial(modifier, tokens, stats)
}
