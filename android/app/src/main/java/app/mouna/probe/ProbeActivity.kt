package app.mouna.probe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import app.mouna.probe.ui.ProbeScreen
import java.util.Locale
import java.util.concurrent.Executors

class ProbeActivity : ComponentActivity() {
    private val analysis = Executors.newSingleThreadExecutor()
    private var tracker: FaceTracker? = null
    private var tts: TextToSpeech? = null
    private val trackerState = mutableStateOf<FaceTracker?>(null)
    private val ttsReady = mutableStateOf(false)
    private val cameraDenied = mutableStateOf(false)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied.value = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status -> ttsReady.value = status == TextToSpeech.SUCCESS }
        analysis.execute {
            tracker = FaceTracker(this).also { trackerState.value = it }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askCamera.launch(Manifest.permission.CAMERA)
        }
        setContent {
            ProbeScreen(
                tracker = trackerState.value,
                tts = tts.takeIf { ttsReady.value },
                cameraDenied = cameraDenied.value,
                permissions = requestedPermissions(this),
                bindCamera = ::bindCamera,
                speak = ::speak,
            )
        }
    }

    private fun bindCamera(view: PreviewView, t: FaceTracker) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysis, t) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun speak(tag: String, text: String) {
        val t = tts ?: return
        t.language = Locale.forLanguageTag(tag)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, tag)
    }

    override fun onDestroy() {
        tts?.shutdown()
        analysis.execute { tracker?.close() }
        analysis.shutdown()
        super.onDestroy()
    }
}
