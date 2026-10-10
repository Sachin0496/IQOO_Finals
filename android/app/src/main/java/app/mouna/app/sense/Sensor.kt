package app.mouna.app.sense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.mouna.core.FreeTalk
import app.mouna.core.Pt
import app.mouna.core.irisPosition
import app.mouna.probe.ActivityGate
import app.mouna.probe.FaceFrame
import app.mouna.probe.Lips
import app.mouna.probe.analyseFace
import app.mouna.probe.lipMotion
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** One camera frame, reduced to what Mouna uses. Produced on the analysis thread. */
class Frame(
    val tMs: Long,
    val face: Boolean,
    /** 40 aligned lip points, interleaved x,y, unit = inter-ocular distance (Lab lips.ts). */
    val features: FloatArray? = null,
    val aperture: Float = 0f,
    val gateOpen: Boolean = false,
    /** 96 x 96 grey mouth crop, row-major, one byte per pixel (the encoder's input). Only while something is listening. */
    val crop: ByteArray? = null,
    /** 96 x 96 grey mouth crop in Auto-AVSR's geometry (FreeTalk.cropMatrix), for free talk. */
    val avsr: ByteArray? = null,
    /** MediaPipe's 52 blendshape scores (personal switch). Only with a taught switch or while setting one up. */
    val blend: FloatArray? = null,
    /** Iris position between the eye corners, 0..1 (look to choose); NaN without a face. */
    val iris: Double = Double.NaN,
    /** Iris position across the eye's height (eye control); 0 without a face. See [irisVertical]. */
    val irisY: Float = 0f,
    val yawDeg: Float = 0f,
    /** Head pitch proxy and eye openness, for nod / shake / double blink. */
    val pitch: Float = 0f,
    val eyeOpen: Float = 0f,
    /** Outer lip contour, normalised upright-image coordinates, for the overlay. */
    val outer: List<Pair<Float, Float>> = emptyList(),
    val imageW: Int = 0,
    val imageH: Int = 0,
    val landmarkMs: Float = 0f,
    /** The whole frame (conversion, face model, crops), for free talk's frame-rate check. */
    val analyzeMs: Float = 0f,
    /** Sign mode only: 27 body + hand points (x, y) for ISL, how many hands were seen, the pose wrists (Signer.Seen). */
    val sign: FloatArray? = null,
    val hands: Int = 0,
    val wrists: FloatArray? = null,
)

/**
 * The outer lip contour as the (x, y) list the overlay draws, backed by one small array: no Pair per point per frame,
 * a Pair is only made when something reads a point. Compares by content, so an unchanged contour is not "new".
 */
class LipOutline(private val xy: FloatArray) : AbstractList<Pair<Float, Float>>(), RandomAccess {
    override val size get() = xy.size / 2
    override fun get(index: Int) = xy[2 * index] to xy[2 * index + 1]
    override fun equals(other: Any?) = if (other is LipOutline) xy.contentEquals(other.xy) else super.equals(other)
    override fun hashCode() = xy.contentHashCode()
}

/** The 478 landmarks as [Pt]s for the core's iris maths: a Pt is made only for the few indices it reads. */
private class LandmarkPts(val xs: FloatArray, val ys: FloatArray) : AbstractList<Pt>(), RandomAccess {
    override val size get() = xs.size
    override fun get(index: Int) = Pt(xs[index], ys[index])
}

/**
 * CameraX analyzer: frame -> MediaPipe Face Landmarker (lips, iris, blendshapes) -> aligned lips -> activity gate ->
 * 96 px grey mouth crop. Same geometry and constants as the Lab and the probe.
 */
class Sensor(context: Context, private val onFrame: (Frame) -> Unit, blendshapes: Boolean = true) : ImageAnalysis.Analyzer, AutoCloseable {
    private val context = context.applicationContext
    private val model: ByteBuffer
    private var landmarker: FaceLandmarker
    @Volatile var delegate: String
        private set
    // Stricter than the Lab's defaults (0.06 / 0.035): small idle mouth movements were read as speech.
    private val gate = ActivityGate(on = 0.09f, off = 0.05f)
    private var prev: FloatArray? = null
    @Volatile var blendNames: List<String> = emptyList()
        private set

    // analysis thread: did the running landmarker get built with the blendshape output, and did a rebuild fail
    private var hasBlend = blendshapes
    private var blendFailed = false

    /**
     * What the engine currently consumes. The 96 px mouth crop is only for the encoder (listening or teaching), the 52
     * blendshape scores only for a taught switch or switch setup: when nothing reads them they are not produced. The
     * blendshape output is part of the landmarker graph, so asking for it later rebuilds the landmarker once (a few
     * frames are lost); a failed rebuild keeps the working one.
     */
    @Volatile var wantCrop = true
    /** Free talk's Auto-AVSR mouth crop: only while free talk listens. */
    @Volatile var wantAvsr = false
    @Volatile var wantBlend = blendshapes

    /** Sign mode: run body + hands (for ISL) instead of the face. Turning it on builds the [Signer] off the analysis thread. */
    @Volatile var signing = false
        set(v) {
            field = v
            if (v) prepareSigner()
        }
    @Volatile private var signer: Signer? = null
    private var signerBuilding = false
    private var closed = false
    private val signerBuilder = Executors.newSingleThreadExecutor { Thread(it, "mouna-signer").apply { isDaemon = true } }
    /** The pose / hand models couldn't be built (not retried this run): sign mode then shows no body. */
    @Volatile var signerFailed = false
        private set

    // analysis thread: bitmaps and arrays reused from frame to frame (a 480 x 640 frame is 1.2 MB: none new per frame)
    private var rawBmp: Bitmap? = null
    private var upBmp: Bitmap? = null
    private var upCanvas: Canvas? = null
    private val rotation = Matrix()
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var pts: LandmarkPts? = null
    private var failures = 0
    private var perfFrames = 0
    private var perfNs = 0L
    private var perfLmMs = 0f
    private var perfPrepMs = 0f

    private val cropBmp = Bitmap.createBitmap(CROP, CROP, Bitmap.Config.ARGB_8888)
    private val cropCanvas = Canvas(cropBmp)
    private val cropPixels = IntArray(CROP * CROP)
    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    /** BT.601 luma, as OpenCV's RGB2GRAY in Auto-AVSR's preprocessing. */
    private val luma = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val y = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f)
        colorFilter = ColorMatrixColorFilter(ColorMatrix(y + y + y + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
    }
    private val avsrMatrix = Matrix()
    private val avsrValues = FloatArray(9)
    private var avsrPx = FloatArray(0)
    private var avsrPy = FloatArray(0)

    init {
        // The model as one direct buffer: after a failed GPU start, MediaPipe can't re-open the asset by path
        // ("Unable to get file size"), so the CPU retry would fail too (seen on the emulator).
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val gpu = create(Delegate.GPU, blendshapes)
        landmarker = gpu ?: checkNotNull(create(Delegate.CPU, blendshapes)) { "Face landmarker failed on GPU and CPU" }
        delegate = if (gpu != null) "GPU" else "CPU"
    }

    private fun create(d: Delegate, blend: Boolean): FaceLandmarker? =
        runCatching { FaceLandmarker.createFromOptions(context, options(d, model, blend)) }
            .onFailure { Log.w("Mouna", "face landmarker on $d: ${it.message?.take(120)}") }
            .getOrNull()

    /**
     * Some GPU drivers accept the delegate and then fail on the first frame (seen on the emulator); move to the CPU
     * instead of crashing. Returns false if there is nothing left to fall back to.
     */
    private fun fallBackToCpu(e: Throwable): Boolean {
        Log.w("Mouna", "face landmarker failed on $delegate: ${e.message?.take(120)}")
        if (delegate != "GPU") return false
        runCatching { landmarker.close() }
        landmarker = create(Delegate.CPU, hasBlend) ?: return false
        delegate = "CPU"
        return true
    }

    private fun options(d: Delegate, model: ByteBuffer, blend: Boolean) = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).setDelegate(d).build())
        .setRunningMode(RunningMode.VIDEO)
        .setNumFaces(1)
        .setOutputFaceBlendshapes(blend)
        .build()

    /** A frame that fails (a bad bitmap, a model error) is one lost frame, never a dead analysis thread. */
    override fun analyze(image: ImageProxy) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val turned = image.imageInfo.rotationDegrees % 180 != 0
        val w = if (turned) image.height else image.width
        val h = if (turned) image.width else image.height
        try {
            image.use { process(it) }
        } catch (e: Exception) {
            lost(e, w, h)
        } catch (e: OutOfMemoryError) {
            lost(e, w, h)
        }
        perfNs += SystemClock.elapsedRealtimeNanos() - t0
        if (++perfFrames == 100) {
            Log.i(
                "MounaPerf",
                "analyze mean %.2f ms over 100 frames (bitmap prep %.2f ms, landmarks %.2f ms; %s; crop %s, blendshapes %s)".format(
                    perfNs / 1e8, perfPrepMs / 100, perfLmMs / 100, if (signing) "sign" else delegate, if (wantCrop) "on" else "off", if (hasBlend) "on" else "off",
                ),
            )
            perfFrames = 0
            perfNs = 0
            perfLmMs = 0f
            perfPrepMs = 0f
        }
    }

    private fun lost(e: Throwable, w: Int, h: Int) {
        if (failures++ % 100 == 0) Log.e("Mouna", "frame lost ($failures so far)", e) // a persistent fault must not flood the log
        runCatching { onFrame(Frame(SystemClock.uptimeMillis(), face = false, imageW = w, imageH = h)) }
    }

    private fun process(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val tPrep = SystemClock.elapsedRealtimeNanos()
        val frame = upright(proxy)
        perfPrepMs += (SystemClock.elapsedRealtimeNanos() - tPrep) / 1e6f
        if (signing) {
            val kp = signer?.let { runCatching { it.keypoints(frame, now) }.getOrNull() }
            onFrame(Frame(now, face = false, imageW = frame.width, imageH = frame.height, sign = kp?.points, hands = kp?.hands ?: 0, wrists = kp?.wrists))
            return
        }
        if (wantBlend && !hasBlend && !blendFailed) enableBlendshapes()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val result = try {
            landmarker.detectForVideo(BitmapImageBuilder(frame).build(), now)
        } catch (e: RuntimeException) {
            if (!fallBackToCpu(e)) onFrame(Frame(now, face = false, imageW = frame.width, imageH = frame.height))
            return
        }
        val lmMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        perfLmMs += lmMs

        val lm = result.faceLandmarks().firstOrNull()
        if (lm == null) {
            prev = null
            onFrame(Frame(now, face = false, imageW = frame.width, imageH = frame.height, landmarkMs = lmMs))
            return
        }
        val n = lm.size
        if (xs.size != n) {
            xs = FloatArray(n)
            ys = FloatArray(n)
            pts = LandmarkPts(xs, ys)
        }
        for (i in 0 until n) {
            xs[i] = lm[i].x()
            ys[i] = lm[i].y()
        }
        val f = analyseFace(xs, ys, frame.width, frame.height)
        val motion = prev?.let { lipMotion(f.features, it) } ?: 0f
        prev = f.features
        val open = gate.push(motion, f.aperture)

        val blend = if (hasBlend) result.faceBlendshapes().orElse(null)?.firstOrNull()?.let { cats ->
            if (blendNames.size != cats.size) blendNames = cats.map { it.categoryName() }
            FloatArray(cats.size) { cats[it].score() }
        } else null
        val iris = if (n > 473) irisPosition(pts!!, frame.width, frame.height) else Double.NaN
        val outline = FloatArray(Lips.OUTER.size * 2)
        for ((k, idx) in Lips.OUTER.withIndex()) {
            outline[2 * k] = xs[idx]
            outline[2 * k + 1] = ys[idx]
        }

        onFrame(
            Frame(
                tMs = now,
                face = true,
                features = f.features,
                aperture = f.aperture,
                gateOpen = open,
                crop = if (wantCrop) cutMouth(frame, f) else null,
                avsr = if (wantAvsr && n >= 468) cutMouthAvsr(frame) else null,
                blend = blend,
                iris = iris,
                irisY = if (n > 473) irisVertical(xs, ys, frame.width, frame.height) else 0f,
                yawDeg = f.yawDeg,
                pitch = pitchProxy(xs, ys, frame.width, frame.height),
                eyeOpen = eyeOpenness(xs, ys, frame.width, frame.height),
                outer = LipOutline(outline),
                imageW = frame.width,
                imageH = frame.height,
                landmarkMs = lmMs,
                analyzeMs = (SystemClock.elapsedRealtimeNanos() - tPrep) / 1e6f,
            ),
        )
    }

    /** Auto-AVSR's crop: eyes, nose and mouth onto its mean face (similarity), 96 px patch around the mouth. */
    private fun cutMouthAvsr(src: Bitmap): ByteArray {
        if (avsrPx.size != xs.size) {
            avsrPx = FloatArray(xs.size)
            avsrPy = FloatArray(ys.size)
        }
        for (i in xs.indices) {
            avsrPx[i] = xs[i] * src.width
            avsrPy[i] = ys[i] * src.height
        }
        val m = FreeTalk.cropMatrix(FreeTalk.stablePoints(avsrPx, avsrPy))
        for (i in 0 until 6) avsrValues[i] = m[i].toFloat()
        avsrValues[6] = 0f; avsrValues[7] = 0f; avsrValues[8] = 1f
        avsrMatrix.setValues(avsrValues)
        cropCanvas.drawColor(android.graphics.Color.BLACK)
        cropCanvas.drawBitmap(src, avsrMatrix, luma)
        cropBmp.getPixels(cropPixels, 0, CROP, 0, 0, CROP, CROP)
        return ByteArray(CROP * CROP) { (cropPixels[it] shr 16 and 0xff).toByte() }
    }

    /** Rebuilds the landmarker with the blendshape output on (a taught switch, or switch setup, needs it). */
    private fun enableBlendshapes() {
        val t0 = SystemClock.elapsedRealtime()
        val fresh = create(if (delegate == "GPU") Delegate.GPU else Delegate.CPU, true)
        if (fresh == null) {
            blendFailed = true // keep the working landmarker, and don't retry on every frame
            Log.w("Mouna", "landmarker with blendshapes failed on $delegate: no blendshapes this run")
            return
        }
        val old = landmarker
        landmarker = fresh
        hasBlend = true
        runCatching { old.close() }
        Log.i("MounaPerf", "landmarker rebuilt with blendshapes in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** The Signer holds two MediaPipe models and takes a while to build: do it once, on its own thread. */
    private fun prepareSigner() {
        synchronized(this) {
            if (signer != null || signerFailed || signerBuilding || closed) return
            signerBuilding = true
        }
        try {
            signerBuilder.execute {
                val t0 = SystemClock.elapsedRealtime()
                val built = runCatching { Signer(context) }.onFailure { Log.e("Mouna", "signer", it) }.getOrNull()
                synchronized(this) {
                    signerBuilding = false
                    when {
                        built == null -> signerFailed = true
                        closed -> built.close()
                        else -> signer = built
                    }
                }
                if (built != null) Log.i("MounaPerf", "signer ready in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        } catch (e: RejectedExecutionException) {
            synchronized(this) { signerBuilding = false }
        }
    }

    /** Same affine as the Lab's MouthCropper: centre on the mouth, undo roll, side = 1.1 x inter-ocular distance. */
    private fun cutMouth(src: Bitmap, f: FaceFrame): ByteArray {
        val scale = CROP / (CROP_IOD * f.iod)
        val m = Matrix().apply {
            postTranslate(-f.centerX, -f.centerY)
            postRotate((-f.roll * 180 / Math.PI).toFloat())
            postScale(scale, scale)
            postTranslate(CROP / 2f, CROP / 2f)
        }
        cropCanvas.drawColor(android.graphics.Color.BLACK)
        cropCanvas.drawBitmap(src, m, grey)
        cropBmp.getPixels(cropPixels, 0, CROP, 0, 0, CROP, CROP)
        return ByteArray(CROP * CROP) { (cropPixels[it] shr 16 and 0xff).toByte() } // grey: R == G == B
    }

    /**
     * The camera frame, upright. Both bitmaps (as delivered, and rotated) are reused from frame to frame; only a row
     * stride the plain copy can't take falls back to CameraX's own conversion.
     */
    private fun upright(proxy: ImageProxy): Bitmap {
        val w = proxy.width
        val h = proxy.height
        val plane = proxy.planes[0]
        val raw = if (plane.pixelStride == 4 && plane.rowStride == w * 4) {
            val b = rawBmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { rawBmp = it }
            plane.buffer.rewind()
            b.copyPixelsFromBuffer(plane.buffer)
            b
        } else {
            proxy.toBitmap()
        }
        val rot = proxy.imageInfo.rotationDegrees
        if (rot == 0) return raw
        val turned = rot % 180 != 0
        val uw = if (turned) h else w
        val uh = if (turned) w else h
        var up = upBmp
        if (up == null || up.width != uw || up.height != uh) {
            up = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
            upBmp = up
            upCanvas = Canvas(up)
        }
        // Rotate about the origin, then slide the result back into view (what Bitmap.createBitmap(.., matrix) did).
        rotation.setRotate(rot.toFloat())
        when (rot) {
            90 -> rotation.postTranslate(h.toFloat(), 0f)
            180 -> rotation.postTranslate(w.toFloat(), h.toFloat())
            270 -> rotation.postTranslate(0f, w.toFloat())
        }
        upCanvas!!.drawBitmap(raw, rotation, null)
        return up
    }

    override fun close() {
        var s: Signer?
        synchronized(this) {
            closed = true
            s = signer
            signer = null
        }
        runCatching { landmarker.close() }
        s?.close()
        signerBuilder.shutdown()
    }

    companion object {
        const val CROP = 96
        const val CROP_IOD = 1.1f
        const val MODEL = "face_landmarker.task"
    }
}
