package io.github.liquidlyf.start

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.components.StartLogoHandoff
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.concurrent.thread

/** The screen a fresh start lands on, which decides how the start logo leaves (D25). */
enum class StartScreen { WELCOME, SIGN_IN, HOME, OTHER }

/** Glide into a stacked logo, shrink into Home's top-bar logo, or fade where there's no logo to land on. */
internal enum class StartExit { GLIDE, SHRINK, FADE }

internal fun StartScreen.exit(): StartExit = when (this) {
    StartScreen.WELCOME, StartScreen.SIGN_IN -> StartExit.GLIDE
    StartScreen.HOME -> StartExit.SHRINK
    StartScreen.OTHER -> StartExit.FADE
}

/**
 * D25: the app's own copy of the still picture, at the same size and place, from the app's first
 * frame until the first screen is ready. It covers that screen while it's built, then moves into the
 * screen's own logo ([landing], from AppNavHost): it glides into the welcome page's or Sign in's
 * stacked logo, shrinks into Home's top-bar logo, or fades out in 0.2 s on any other screen.
 *
 * While loading: from [StartClock.DROP_MS] the drop in the logo drains and refills, and from
 * [StartClock.LINE_MS] a thin loading line fades in under it; a later first frame starts them at
 * once. Those two are [views] when given (render-thread drawables, which keep moving while the main
 * thread builds the first screen; this leaves out the drop while they show and takes it over at the
 * move), else drawn here. With "Remove animations" nothing moves: the words "Loading…" show instead
 * of the line, and the screen comes at once. The copy takes no taps and no TalkBack focus; TalkBack
 * hears "Loading…" once, when the line appears.
 */
@Composable
fun StartLogo(
    handoff: StartLogoHandoff,
    clock: StartClock,
    arts: StartLogoArts,
    /** The first screen, once AppNavHost has settled on it (read in a snapshot, so no recomposition is waited for). */
    landing: () -> StartScreen?,
    modifier: Modifier = Modifier,
    /** "Remove animations" (a parameter for StartLogoTest; the phone's setting otherwise). */
    reduced: Boolean = LocalContext.current.let { context -> remember { animationsOff(context) } },
    /** The drop and the line on the render thread (MainActivity's), or null to draw them here. */
    views: StartViews? = null,
    /** Still loading: the line (and TalkBack's "Loading…") only shows then, never as the first screen appears. */
    loading: () -> Boolean = { true },
    onComposed: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val art = remember(dark) { arts[dark] }
    val drain = remember { Animatable(0f) } // 0 = the drop is full, 1 = drained
    val hint = remember { Animatable(0f) } // the loading line's (or words') opacity
    var move by remember { mutableFloatStateOf(0f) } // 0 = in the middle, 1 = landed
    val cover = remember { Animatable(1f) } // the background over the screen
    val copy = remember { Animatable(1f) } // the copy's opacity (the fade)
    var exit by remember { mutableStateOf<StartExit?>(null) }
    var hintShown by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Composed: the still picture can go now (MainActivity's hold), as this draws the same logo.
    SideEffect(onComposed)
    // The drop: from 1.3 s (or the first frame, if later), drains and refills until the screen is ready.
    LaunchedEffect(Unit) {
        clock.log("the app draws the logo")
        if (reduced || views != null) return@LaunchedEffect
        delay((StartClock.DROP_MS - clock.now()).coerceAtLeast(0))
        if (exit != null) return@LaunchedEffect
        clock.log("drop")
        drain.animateTo(
            0f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = DRAIN_CYCLE_MS
                    0f at 0 using EaseInOut
                    1f at DRAINED_AT_MS using EaseInOut
                },
            ),
        )
    }
    // The thin line (with Remove animations, the words): from 1.5 s, or the first frame if later.
    if (views == null) LaunchedEffect(Unit) {
        delay((StartClock.LINE_MS - clock.now()).coerceAtLeast(0))
        if (exit != null || !loading()) return@LaunchedEffect
        clock.log("line")
        hintShown = true
        if (reduced) hint.snapTo(1f) else hint.animateTo(1f, tween(HINT_IN_MS, easing = LinearEasing))
    }
    // Ready: into the screen's own logo, measured now (it moves with the language and the text size).
    // From the screen's first frame: no recomposition is waited for.
    LaunchedEffect(Unit) {
        val screen = snapshotFlow(landing).filterNotNull().first()
        val wanted = screen.exit()
        val target = when (wanted) {
            StartExit.GLIDE -> awaitTarget { handoff.stacked }
            StartExit.SHRINK -> art.bar?.let { awaitTarget { handoff.horizontal } }
            StartExit.FADE -> null
        }
        val how = if (target == null) StartExit.FADE else wanted
        // The drop from where the render thread drew it; they go, and the line fades out.
        if (views?.active == true) drain.snapTo(views.drainedAt(clock.now()))
        views?.hide()
        exit = how
        clock.log("move $how")
        if (!reduced) {
            launch { hint.animateTo(0f, tween(HINT_OUT_MS, easing = LinearEasing)) }
            launch { drain.animateTo(0f, tween(REFILL_MS, easing = LinearEasing)) }
            launch { cover.animateTo(0f, tween(COVER_MS, easing = LinearEasing)) }
            when (how) {
                StartExit.FADE -> {
                    handoff.covering = false
                    copy.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
                }
                StartExit.GLIDE -> moveSmoothly(GLIDE_MS) { move = it }
                StartExit.SHRINK -> moveSmoothly(SHRINK_MS) { move = it }
            }
        }
        // Landed: the screen's own logo shows in the same frame as the copy goes.
        handoff.covering = false
        handoff.done = true
        clock.log("landed")
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.background, alpha = cover.value)
            val k = SPLASH_ART_DP.dp.toPx() / ART_BOX.width
            val start = Placement(k, center - ART_BOX.center * k)
            val p = move
            val stacked = handoff.stacked?.takeIf { exit == StartExit.GLIDE }
            val bar = art.bar?.takeIf { exit == StartExit.SHRINK }
            val barRect = handoff.horizontal?.takeIf { bar != null }
            val viewsDrawTheDrop = views?.active == true
            for (shape in art.shapes) {
                if (shape.drop && viewsDrawTheDrop) continue
                val placement = when {
                    stacked != null -> start.lerp(Placement.of(stacked.translate(-origin), art.width), p, ART_BOX.center)
                    bar != null && barRect != null -> {
                        val into = Placement.of(barRect.translate(-origin), bar.width)
                        if (shape.mark) start.lerp(into.then(bar.mark), p, art.markCentre) else start.lerp(into.then(bar.word), p, art.wordCentre)
                    }
                    else -> start
                }
                drawShape(shape, placement, if (shape.drop) drain.value else 0f, copy.value)
            }
        }
        if (hintShown) {
            val loading = stringResource(R.string.state_loading)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = HINT_BELOW_CENTRE)
                    .graphicsLayer { alpha = hint.value }
                    // Read once, when it appears; nothing to read once the screen is ready.
                    .then(
                        when {
                            exit != null -> Modifier.clearAndSetSemantics {}
                            reduced -> Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                            else -> Modifier.clearAndSetSemantics {
                                contentDescription = loading
                                liveRegion = LiveRegionMode.Polite
                            }
                        },
                    ),
            ) {
                if (reduced) {
                    Text(loading, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    LoadingLine(Modifier.size(HINT_WIDTH, HINT_HEIGHT))
                }
            }
        }
    }
}

/**
 * The move, frame by frame. The first screen's next few frames are slow while it settles (up to
 * 0.3 s on the test phone), so a late frame moves the logo no more than [MAX_STEP_MS]: it never
 * jumps, it just takes a little longer then. Scaled like any animation by the animator speed setting.
 */
private suspend fun moveSmoothly(durationMs: Int, onProgress: (Float) -> Unit) {
    val total = durationMs * (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)
    var t = 0f
    var last = withFrameMillis { it }
    while (t < total) {
        val now = withFrameMillis { it }
        t += (now - last).coerceAtMost(MAX_STEP_MS).toFloat()
        last = now
        onProgress(MoveEasing.transform((t / total).coerceAtMost(1f)))
    }
}

/**
 * The thin loading line, as the render-thread one draws it (avd_start_line_*): a primary bar, 40% of
 * the track, sweeping along the secondary-container track from off its left to off its right in
 * 1.5 s (fast out, slow in), as D's picture the maintainer chose.
 */
@Composable
private fun LoadingLine(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val t by rememberInfiniteTransition(label = "loadingLine").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier) {
        val h = size.height
        val y = h / 2
        val x = { f: Float -> h / 2 + f * (size.width - h) }
        drawLine(colors.secondaryContainer, Offset(x(0f), y), Offset(x(1f), y), strokeWidth = h, cap = StrokeCap.Round)
        val e = FastOutSlowInEasing.transform(t)
        val from = (-BAR + (1 + BAR) * e).coerceIn(0f, 1f)
        val to = ((1 + BAR) * e).coerceIn(0f, 1f)
        if (to > from) drawLine(colors.primary, Offset(x(from), y), Offset(x(to), y), strokeWidth = h, cap = StrokeCap.Round)
    }
}

private suspend fun awaitTarget(read: () -> Rect?): Rect? =
    withTimeoutOrNull(TARGET_WAIT_MS) { snapshotFlow { read()?.takeIf { it.width > 0f } }.filterNotNull().first() }

private fun DrawScope.drawShape(shape: LogoShape, at: Placement, drained: Float, alpha: Float) {
    withTransform({
        translate(at.offset.x, at.offset.y)
        scale(at.scale, at.scale, pivot = Offset.Zero)
    }) {
        if (drained > 0f) {
            val b = shape.bounds
            clipRect(left = b.left, top = b.top + drained * b.height, right = b.right, bottom = b.bottom) {
                drawPath(shape.path, shape.color, alpha = alpha)
            }
        } else {
            drawPath(shape.path, shape.color, alpha = alpha)
        }
    }
}

/**
 * "Remove animations" (Settings › Accessibility, or the developer options' animator scale at 0): as
 * the setting says, which Compose follows, or as Android tells the app's view animations (A46: the
 * two can differ, and then the render-thread drop and line wouldn't move).
 */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())

/** Where a drawing in logo units goes on the screen: screen = offset + scale × point. */
@Immutable
internal data class Placement(val scale: Float, val offset: Offset) {
    /** This placement, applied after [inner] (from logo units into another logo's units). */
    fun then(inner: Placement) = Placement(scale * inner.scale, offset + inner.offset * scale)

    /** Part way to [to]: the part's [centre] moves in a straight line while the size changes. */
    fun lerp(to: Placement, fraction: Float, centre: Offset): Placement {
        val s = lerp(scale, to.scale, fraction)
        val from = offset + centre * scale
        val dest = to.offset + centre * to.scale
        return Placement(s, from + (dest - from) * fraction - centre * s)
    }

    companion object {
        /** A logo drawn to fill [rect], whose drawing is [width] logo units wide. */
        fun of(rect: Rect, width: Float) = Placement(rect.width / width, rect.topLeft)
    }
}

internal class LogoShape(val path: Path, val color: Color, val mark: Boolean, val drop: Boolean) {
    val bounds: Rect = path.getBounds()
}

/** Home's top-bar logo: where the stacked logo's mark and word sit in it, in its own units. */
internal class BarLogo(val width: Float, val mark: Placement, val word: Placement)

/**
 * The stacked logo, drawn from the same vector the screens use (logo_full / logo_full_dark): the
 * 5 shapes of the mark (the 4th is the drop that drains), then the word. For the shrink, the same
 * parts of the horizontal logo say where the mark and the word go.
 */
@Immutable
internal class StartLogoArt(val width: Float, val shapes: List<LogoShape>, val bar: BarLogo?) {
    val markCentre: Offset = union(shapes.filter { it.mark }).center
    val wordCentre: Offset = union(shapes.filterNot { it.mark }).center

    companion object {
        private const val MARK_SHAPES = 5
        private const val DROP = 3

        fun of(stacked: ImageVector, horizontal: ImageVector): StartLogoArt {
            val shapes = paths(stacked).mapIndexed { i, (path, color) -> LogoShape(path, color, mark = i < MARK_SHAPES, drop = i == DROP) }
            val barShapes = paths(horizontal).mapIndexed { i, (path, color) -> LogoShape(path, color, mark = i < MARK_SHAPES, drop = false) }
            // Both logos are the same 5 mark shapes and 2 word shapes; anything else can't shrink, so it fades.
            val bar = if (shapes.size == barShapes.size && shapes.size > MARK_SHAPES) {
                BarLogo(
                    horizontal.viewportWidth,
                    mark = fit(union(shapes.filter { it.mark }), union(barShapes.filter { it.mark })),
                    word = fit(union(shapes.filterNot { it.mark }), union(barShapes.filterNot { it.mark })),
                )
            } else {
                null
            }
            return StartLogoArt(stacked.viewportWidth, shapes, bar)
        }

        private fun paths(v: ImageVector): List<Pair<Path, Color>> = v.root.filterIsInstance<VectorPath>().map { p ->
            p.pathData.toPath() to ((p.fill as? SolidColor)?.value ?: Color.Black).copy(alpha = p.fillAlpha)
        }

        private fun union(shapes: List<LogoShape>): Rect =
            shapes.map { it.bounds }.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }

        /** The placement that puts [from] onto [to] (same shape, another size and place). */
        private fun fit(from: Rect, to: Rect): Placement {
            val s = to.width / from.width
            return Placement(s, to.topLeft - from.topLeft * s)
        }
    }
}

/**
 * The start logo's drawing, light and dark, read on a background thread from the activity's start
 * (D25): reading the vectors on the main thread as the first screen is built would delay that screen.
 */
class StartLogoArts(resources: Resources, clock: StartClock) {
    private val light = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load(resources, dark = false) }
    private val dark = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load(resources, dark = true) }

    init {
        thread(name = "start-logo", isDaemon = true) {
            light.value
            dark.value
            clock.log("logo drawings read")
        }
    }

    /** The drawing; waits for the background read if it's still running (a few ms). */
    internal operator fun get(dark: Boolean): StartLogoArt = if (dark) this.dark.value else light.value

    private fun load(res: Resources, dark: Boolean): StartLogoArt = StartLogoArt.of(
        ImageVector.vectorResource(null, res, if (dark) R.drawable.logo_full_dark else R.drawable.logo_full),
        ImageVector.vectorResource(null, res, if (dark) R.drawable.logo_horizontal_dark else R.drawable.logo_horizontal),
    )
}

/**
 * The still picture's art box in logo-full.svg's units, and its width on screen: the same numbers
 * app/scripts/generate_launcher_icons.py uses for ic_splash_logo (SPLASH_ART_DP), so the copy sits
 * exactly on the still picture. Centred in the window, as Android centres the still picture.
 */
internal val ART_BOX = Rect(62.54f, 40.00f, 1054.88f, 742.30f)
internal const val SPLASH_ART_DP = 161

private val MoveEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val HINT_BELOW_CENTRE = 84.dp
private val HINT_WIDTH = 120.dp
private val HINT_HEIGHT = 4.dp
private const val SWEEP_MS = 1_500
private const val BAR = 0.4f
private const val DRAIN_CYCLE_MS = 1_600
private const val DRAINED_AT_MS = 720
private const val HINT_IN_MS = 400
private const val HINT_OUT_MS = 90
private const val REFILL_MS = 150
private const val COVER_MS = 150
private const val GLIDE_MS = 400
private const val SHRINK_MS = 450
private const val FADE_MS = 200
private const val TARGET_WAIT_MS = 500L
private const val MAX_STEP_MS = 25L
