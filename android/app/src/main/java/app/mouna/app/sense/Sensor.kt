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

/** One camera frame, reduced to what Mouna uses. Produced on the analysis thread. */
class Frame(
    val tMs: Long,
    val face: Boolean,
    /** 40 aligned lip points, interleaved x,y, unit = inter-ocular distance (Lab lips.ts). */
    val features: FloatArray? = null,
    val aperture: Float = 0f,
    val gateOpen: Boolean = false,
    /** 96 x 96 grey mouth crop, row-major, one byte per pixel (the encoder's input). */
    val crop: ByteArray? = null,
    /** 96 x 96 grey mouth crop in Auto-AVSR's geometry (FreeTalk.cropMatrix), for free talk. */
    val avsr: ByteArray? = null,
    /** MediaPipe's 52 blendshape scores (personal switch). */
    val blend: FloatArray? = null,
    /** Iris position between the eye corners, 0..1 (look to choose); NaN without a face. */
    val iris: Double = Double.NaN,
    val yawDeg: Float = 0f,
    /** Head pitch proxy and eye openness, for nod / shake / double blink. */
    val pitch: Float = 0f,
    val eyeOpen: Float = 0f,
    /** Outer lip contour, normalised upright-image coordinates, for the overlay. */
    val outer: List<Pair<Float, Float>> = emptyList(),
    val imageW: Int = 0,
    val imageH: Int = 0,
    val landmarkMs: Float = 0f,
    /** Sign mode only: 27 body + hand points (x, y) for ISL, and how many hands were seen. */
    val sign: FloatArray? = null,
    val hands: Int = 0,
)

/**
 * CameraX analyzer: frame -> MediaPipe Face Landmarker (lips, iris, blendshapes) -> aligned lips -> activity gate ->
 * 96 px grey mouth crop. Same geometry and constants as the Lab and the probe.
 */
class Sensor(context: Context, private val onFrame: (Frame) -> Unit) : ImageAnalysis.Analyzer, AutoCloseable {
    private val context = context.applicationContext
    private val model: ByteBuffer
    private var landmarker: FaceLandmarker
    @Volatile var delegate: String
        private set
    private val gate = ActivityGate()
    private var prev: FloatArray? = null
    @Volatile var blendNames: List<String> = emptyList()
        private set
    /** Sign mode: run body + hands (for ISL) instead of the face. */
    @Volatile var signing = false
    private var signer: Signer? = null
    @Volatile var signerFailed = false
        private set

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

    init {
        // The model as one direct buffer: after a failed GPU start, MediaPipe can't re-open the asset by path
        // ("Unable to get file size"), so the CPU retry would fail too (seen on the emulator).
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val gpu = create(Delegate.GPU)
        landmarker = gpu ?: checkNotNull(create(Delegate.CPU)) { "Face landmarker failed on GPU and CPU" }
        delegate = if (gpu != null) "GPU" else "CPU"
    }

    private fun create(d: Delegate): FaceLandmarker? =
        runCatching { FaceLandmarker.createFromOptions(context, options(d, model)) }
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
        landmarker = create(Delegate.CPU) ?: return false
        delegate = "CPU"
        return true
    }

    private fun options(d: Delegate, model: ByteBuffer) = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).setDelegate(d).build())
        .setRunningMode(RunningMode.VIDEO)
        .setNumFaces(1)
        .setOutputFaceBlendshapes(true)
        .build()

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val now = SystemClock.uptimeMillis()
            val frame = upright(proxy)
            if (signing) {
                val sg = signer ?: runCatching { Signer(context) }.onFailure { signerFailed = true; Log.e("Mouna", "signer", it) }.getOrNull()?.also { signer = it }
                val kp = sg?.let { runCatching { it.keypoints(frame, now) }.getOrNull() }
                onFrame(Frame(now, face = false, imageW = frame.width, imageH = frame.height, sign = kp?.first, hands = kp?.second ?: 0))
                return
            }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val result = try {
                landmarker.detectForVideo(BitmapImageBuilder(frame).build(), now)
            } catch (e: RuntimeException) {
                if (!fallBackToCpu(e)) onFrame(Frame(now, face = false, imageW = frame.width, imageH = frame.height))
                return
            }
            val lmMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f

            val lm = result.faceLandmarks().firstOrNull()
            if (lm == null) {
                prev = null
                onFrame(Frame(now, face = false, imageW = frame.width, imageH = frame.height, landmarkMs = lmMs))
                return
            }
            val xs = FloatArray(lm.size) { lm[it].x() }
            val ys = FloatArray(lm.size) { lm[it].y() }
            val f = analyseFace(xs, ys, frame.width, frame.height)
            val motion = prev?.let { lipMotion(f.features, it) } ?: 0f
            prev = f.features
            val open = gate.push(motion, f.aperture)

            val blend = result.faceBlendshapes().orElse(null)?.firstOrNull()?.let { cats ->
                if (blendNames.size != cats.size) blendNames = cats.map { it.categoryName() }
                FloatArray(cats.size) { cats[it].score() }
            }
            val iris = if (lm.size > 473) irisPosition(lm.map { Pt(it.x(), it.y()) }, frame.width, frame.height) else Double.NaN

            onFrame(
                Frame(
                    tMs = now,
                    face = true,
                    features = f.features,
                    aperture = f.aperture,
                    gateOpen = open,
                    crop = cutMouth(frame, f),
                    avsr = if (lm.size >= 468) cutMouthAvsr(frame, xs, ys) else null,
                    blend = blend,
                    iris = iris,
                    yawDeg = f.yawDeg,
                    pitch = pitchProxy(xs, ys, frame.width, frame.height),
                    eyeOpen = eyeOpenness(xs, ys, frame.width, frame.height),
                    outer = Lips.OUTER.map { xs[it] to ys[it] },
                    imageW = frame.width,
                    imageH = frame.height,
                    landmarkMs = lmMs,
                ),
            )
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

    /** Auto-AVSR's crop: eyes, nose and mouth onto its mean face (similarity), 96 px patch around the mouth. */
    private fun cutMouthAvsr(src: Bitmap, xs: FloatArray, ys: FloatArray): ByteArray {
        val px = FloatArray(xs.size) { xs[it] * src.width }
        val py = FloatArray(ys.size) { ys[it] * src.height }
        val m = FreeTalk.cropMatrix(FreeTalk.stablePoints(px, py))
        for (i in 0 until 6) avsrValues[i] = m[i].toFloat()
        avsrValues[6] = 0f; avsrValues[7] = 0f; avsrValues[8] = 1f
        avsrMatrix.setValues(avsrValues)
        cropCanvas.drawColor(android.graphics.Color.BLACK)
        cropCanvas.drawBitmap(src, avsrMatrix, luma)
        cropBmp.getPixels(cropPixels, 0, CROP, 0, 0, CROP, CROP)
        return ByteArray(CROP * CROP) { (cropPixels[it] shr 16 and 0xff).toByte() }
    }

    private fun upright(proxy: ImageProxy): Bitmap {
        val bmp = proxy.toBitmap()
        val rot = proxy.imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    override fun close() {
        landmarker.close()
        signer?.close()
    }

    companion object {
        const val CROP = 96
        const val CROP_IOD = 1.1f
        const val MODEL = "face_landmarker.task"
    }
}
