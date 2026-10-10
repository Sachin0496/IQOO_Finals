package app.mouna.app.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import app.mouna.app.engine.Engine
import app.mouna.app.sense.BlinkClicks
import app.mouna.app.sense.Box
import app.mouna.app.sense.EyeAction
import app.mouna.app.sense.FocusPicker
import app.mouna.app.sense.Frame
import app.mouna.app.sense.GazeMap
import app.mouna.app.sense.GazeSmoother
import app.mouna.app.sense.gazeFeatures
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** A part of the screen tagged with this ([androidx.compose.ui.platform.testTag]) is all eye control offers while it is up (a prompt over a screen). */
const val EYE_SCOPE = "eye-scope"

/**
 * Eye control: whatever the person looks at is outlined, as Tab outlines on a laptop; a long blink presses it, two
 * quick blinks go back. Works on every screen with no per-screen code: the buttons are read from Compose's semantics
 * tree (what TalkBack reads), pressed through their click action, and the outline is drawn on the window's overlay, so
 * dialogs work too. Scrolling lists get two small arrow buttons of their own.
 *
 * Threads: frames arrive on the camera analysis thread (gaze + blinks), everything else runs on the main thread.
 */
class EyeControl(
    private val activity: ComponentActivity,
    private val engine: Engine,
    /** False on the home screen with nothing open: there "back" would send Mouna to the background, out of reach. */
    private val canGoBack: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val roots = mutableListOf<WeakReference<ViewRootForTest>>()
    private var unwatch: (() -> Unit)? = null

    /** Read on the analysis thread; replaced whole on each [set], so a frame in flight never sees half a reset. */
    private class Tracking(val map: GazeMap, val blinks: BlinkClicks, val smoother: GazeSmoother = GazeSmoother())
    @Volatile private var tracking: Tracking? = null

    // analysis -> main: the newest gaze point, posted at most once per main-thread turn
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    private val posted = AtomicBoolean(false)

    // main thread
    private val picker = FocusPicker()
    private var targets: Map<Int, Target> = emptyMap()
    private var host: View? = null
    private val ring = Ring()
    private val pills = HashMap<Int, Pill>()
    private val hideRing = Runnable { ring.shown = false; host?.invalidate() }

    private class Target(val box: Box, val inRoot: Rect, val node: SemanticsNode, val scroll: Float = 0f)

    /** Every Compose window (the activity, each dialog) as it is created: set as [ViewRootForTest.onViewCreatedCallback]. */
    fun register(root: ViewRootForTest) {
        roots.removeAll { it.get() == null }
        roots += WeakReference(root)
    }

    /** On with a calibration, or off. Main thread. */
    fun set(on: Boolean, gaze: GazeMap?) {
        unwatch?.invoke()
        unwatch = null
        tracking = null
        clear()
        if (!on || gaze == null) return
        tracking = Tracking(gaze, BlinkClicks(gaze.closedBelow))
        unwatch = engine.watch(::onFrame)
    }

    // ---------------- analysis thread ----------------

    private fun onFrame(f: Frame) {
        val tr = tracking ?: return
        val feats = gazeFeatures(f) ?: return // no face: the outline fades on its own (see hideRing)
        tr.blinks.push(f.eyeOpen.toDouble(), f.tMs)?.let { a -> main.post { act(a) } }
        if (tr.blinks.closed) return // the iris is under the lids: the outline stays where the person was looking
        val (x, y) = tr.map.predict(feats)
        val (sx, sy) = tr.smoother.push(x, y)
        gx = sx.toFloat()
        gy = sy.toFloat()
        if (posted.compareAndSet(false, true)) main.post {
            posted.set(false)
            onGaze()
        }
    }

    // ---------------- main thread ----------------

    private fun onGaze() {
        if (unwatch == null) return
        val root = activeRoot() ?: return clear()
        val view = root.view
        val all = collect(root)
        if (host !== view) {
            clear()
            host = view
            view.overlay.add(ring)
        }
        targets = all
        syncPills(view)
        val id = picker.push(all.mapValues { it.value.box }, gx, gy, SystemClock.uptimeMillis())
        val t = id?.let { all[it] }
        if (t == null) {
            ring.shown = false
        } else {
            ring.moveTo(t.inRoot, view)
        }
        main.removeCallbacks(hideRing)
        main.postDelayed(hideRing, 1200) // no face, the eyes shut, sign mode: no gaze, no outline
        view.invalidate()
    }

    private fun act(a: EyeAction) {
        if (unwatch == null) return
        val root = activeRoot() ?: return
        when (a) {
            EyeAction.CLICK -> {
                val t = picker.current?.let { targets[it] } ?: return
                val cfg = t.node.config
                if (t.scroll != 0f) {
                    cfg.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, t.scroll)
                } else {
                    cfg.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
                }
                ring.flash(root.view)
            }
            EyeAction.BACK -> {
                val owner = root.view.findViewTreeOnBackPressedDispatcherOwner()
                if (owner === activity && !canGoBack()) return
                (owner ?: activity).onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    /** The window the person is using: the newest Compose window that has focus (a dialog over the activity wins). */
    private fun activeRoot(): ViewRootForTest? {
        roots.removeAll { it.get() == null }
        return roots.mapNotNull { it.get() }.lastOrNull { it.view.isAttachedToWindow && it.view.isShown && it.view.hasWindowFocus() }
    }

    /** Everything pressable on [root], in screen pixels, keyed by semantics id; scroll arrows get negative ids. */
    private fun collect(root: ViewRootForTest): Map<Int, Target> {
        val nodes = runCatching { root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }.getOrNull() ?: return emptyMap()
        val scope = nodes.lastOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == EYE_SCOPE }
        val loc = IntArray(2).also { root.view.getLocationOnScreen(it) }
        val dp = root.view.resources.displayMetrics.density
        val out = LinkedHashMap<Int, Target>()
        fun add(id: Int, r: Rect, n: SemanticsNode, scroll: Float = 0f) {
            out[id] = Target(Box((r.left + loc[0]).toFloat(), (r.top + loc[1]).toFloat(), (r.right + loc[0]).toFloat(), (r.bottom + loc[1]).toFloat()), r, n, scroll)
        }
        for (n in nodes) {
            if (scope != null && !n.isIn(scope)) continue
            val cfg = n.config
            if (cfg.getOrNull(SemanticsProperties.Disabled) != null) continue
            val b = n.boundsInRoot
            if (b.width < 1f || b.height < 1f) continue // scrolled away or not laid out
            val r = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            if (cfg.getOrNull(SemanticsActions.OnClick) != null) add(n.id, r, n)
            val range = cfg.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            if (range != null && cfg.getOrNull(SemanticsActions.ScrollBy) != null && r.height() > 200 * dp) {
                val w = (72 * dp).toInt()
                val h = (32 * dp).toInt()
                val inset = (8 * dp).toInt()
                val step = r.height() * 0.7f
                val value = range.value()
                if (value > 0f) add(-2 * n.id - 1, Rect(r.centerX() - w / 2, r.top + inset, r.centerX() + w / 2, r.top + inset + h), n, -step)
                if (value < range.maxValue()) add(-2 * n.id - 2, Rect(r.centerX() - w / 2, r.bottom - inset - h, r.centerX() + w / 2, r.bottom - inset), n, step)
            }
        }
        return out
    }

    private fun SemanticsNode.isIn(scope: SemanticsNode): Boolean {
        var p: SemanticsNode? = this
        while (p != null) {
            if (p.id == scope.id) return true
            p = p.parent
        }
        return false
    }

    /** The scroll arrows are drawn by eye control itself: the screens have no buttons for scrolling. */
    private fun syncPills(view: View) {
        val want = targets.filterValues { it.scroll != 0f }
        val gone = pills.keys - want.keys
        for (id in gone) pills.remove(id)?.let { view.overlay.remove(it) }
        for ((id, t) in want) {
            val p = pills.getOrPut(id) { Pill(down = t.scroll > 0f).also { view.overlay.add(it) } }
            p.bounds = t.inRoot
        }
    }

    private fun clear() {
        main.removeCallbacks(hideRing)
        host?.let { h ->
            h.overlay.remove(ring)
            for (p in pills.values) h.overlay.remove(p)
            h.invalidate()
        }
        pills.clear()
        host = null
        targets = emptyMap()
        picker.reset()
        ring.shown = false
    }

    /** The outline: a turmeric ring that glides to the next button and flashes when one is pressed. */
    private class Ring : Drawable() {
        var shown = false
        private var flash = 0f
        private var dp = 1f
        private val from = Rect()
        private val to = Rect()
        private var glide: ValueAnimator? = null
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun moveTo(target: Rect, view: View) {
            dp = view.resources.displayMetrics.density
            val pad = (4 * dp).toInt()
            val next = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
            if (!shown || bounds.isEmpty) {
                glide?.cancel()
                bounds = next
                to.set(next)
            } else if (next != to) {
                from.set(bounds)
                to.set(next)
                glide?.cancel()
                glide = ValueAnimator.ofFloat(0f, 1f).setDuration(140).apply {
                    addUpdateListener { a ->
                        val f = a.animatedValue as Float
                        bounds = Rect(lerp(from.left, to.left, f), lerp(from.top, to.top, f), lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f))
                        view.invalidate()
                    }
                    start()
                }
            }
            stroke.strokeWidth = 3 * dp
            shown = true
        }

        fun flash(view: View) {
            ValueAnimator.ofFloat(1f, 0f).setDuration(380).apply {
                addUpdateListener { flash = it.animatedValue as Float; view.invalidate() }
                start()
            }
        }

        override fun draw(c: Canvas) {
            if (!shown) return
            r.set(bounds)
            val rad = min(min(r.width(), r.height()) / 2f, 28 * dp)
            fill.color = Ink.turmeric.copy(alpha = 0.10f + 0.35f * flash).toArgb()
            c.drawRoundRect(r, rad, rad, fill)
            stroke.color = Ink.turmeric.toArgb()
            r.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
            c.drawRoundRect(r, rad, rad, stroke)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()
    }

    /** A small arrow button at the top or bottom of a list that can scroll that way. */
    private class Pill(private val down: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val chevron = Path()

        override fun draw(c: Canvas) {
            r.set(bounds)
            val rad = r.height() / 2
            paint.style = Paint.Style.FILL
            paint.color = Ink.raised.toArgb()
            c.drawRoundRect(r, rad, rad, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r.height() / 16
            paint.color = Ink.rule2.toArgb()
            c.drawRoundRect(r, rad, rad, paint)
            val cx = r.centerX()
            val cy = r.centerY()
            val s = r.height() / 5
            chevron.reset()
            chevron.moveTo(cx - 1.6f * s, cy + if (down) -0.6f * s else 0.6f * s)
            chevron.lineTo(cx, cy + if (down) 0.8f * s else -0.8f * s)
            chevron.lineTo(cx + 1.6f * s, cy + if (down) -0.6f * s else 0.6f * s)
            paint.strokeWidth = r.height() / 12
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Ink.bone.toArgb()
            c.drawPath(chevron, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
