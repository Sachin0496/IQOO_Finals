package app.mouna.app

import android.Manifest
import java.io.File
import android.util.Log
import android.content.pm.ApplicationInfo
import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mouna.app.engine.Engine
import app.mouna.app.engine.Hearing
import app.mouna.app.engine.PhrasePack
import app.mouna.app.engine.Store
import app.mouna.app.engine.Voice
import app.mouna.app.ui.MounaRoot
import app.mouna.app.ui.MounaTheme
import app.mouna.probe.ProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var engine: Engine
    private lateinit var voice: Voice
    private lateinit var app: MounaApp
    private lateinit var hearing: Hearing
    private var micAnswer: ((Boolean) -> Unit)? = null
    private val askMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAnswer?.invoke(granted)
        micAnswer = null
    }
    private val cameraDenied = mutableStateOf(false)
    private val cameraReady = mutableStateOf(false)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied.value = !granted
        cameraReady.value = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = Engine(applicationContext, PhrasePack.bundled(), Store(applicationContext))
        voice = Voice(applicationContext)
        hearing = Hearing(applicationContext)
        app = MounaApp(engine, voice, hearing) { answer ->
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                answer(true)
            } else {
                micAnswer = answer
                askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        engine.start()
        lifecycleScope.launch { engine.events.collect { app.onEvent(it) } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraReady.value = true
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }

        // QA (debug builds): adb shell am broadcast -a app.mouna.HEAR --es wav /sdcard/Android/data/app.mouna/files/x.wav
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val path = i.getStringExtra("wav") ?: return
                    runCatching { app.hearRecording(readWav(File(path))) }.onFailure { Log.e("Mouna", "hear", it) }
                }
            }, IntentFilter("app.mouna.HEAR"), ContextCompat.RECEIVER_EXPORTED)
            // adb shell am broadcast -a app.mouna.SIGN --es json <path to [[54 floats], ...]>
            ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val path = i.getStringExtra("json") ?: return
                    val a = org.json.JSONArray(File(path).readText())
                    engine.classifySign(List(a.length()) { t -> a.getJSONArray(t).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } })
                }
            }, IntentFilter("app.mouna.SIGN"), ContextCompat.RECEIVER_EXPORTED)
        }

        setContent {
            MounaTheme {
                LaunchedEffect(Unit) { app.go(app.screen) } // start listening once the UI is up
                MounaRoot(
                    app = app,
                    cameraReady = cameraReady.value,
                    cameraDenied = cameraDenied.value,
                    bindCamera = ::bindCamera,
                    openProbe = { startActivity(Intent(this, ProbeActivity::class.java)) },
                )
            }
        }
    }

    private val preview = Preview.Builder().build()
    private var bound = false

    /**
     * Each screen's camera card hands over its view; only the preview surface moves. The camera itself is bound once,
     * so switching screens never closes and reopens it (no black flash, no stall in the camera HAL).
     */
    private fun bindCamera(view: PreviewView) {
        preview.surfaceProvider = view.surfaceProvider
        if (bound) return
        bound = true
        lifecycleScope.launch {
            while (engine.sensor == null) delay(50)
            val sensor = engine.sensor!!
            val future = ProcessCameraProvider.getInstance(this@MainActivity)
            future.addListener({
                @Suppress("DEPRECATION")
                val analyzer = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(engine.analysis, sensor) }
                val provider = future.get()
                // Front camera faces the person; any camera is better than none (some devices misreport facing).
                val camera = listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                    .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
                    ?: provider.availableCameraInfos.firstOrNull()?.cameraSelector
                if (camera == null) {
                    cameraDenied.value = true
                    return@addListener
                }
                provider.bindToLifecycle(this@MainActivity, camera, preview, analyzer)
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }
    }

    override fun onDestroy() {
        app.shutdown()
        voice.close()
        engine.close()
        super.onDestroy()
    }

    /** 16-bit PCM mono WAV -> floats (QA only). */
    private fun readWav(f: File): FloatArray {
        val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(p + it) })
            val size = b.getInt(p + 4)
            if (id == "data") return FloatArray(size / 2) { b.getShort(p + 8 + it * 2) / 32768f }
            p += 8 + size
        }
        error("no data chunk")
    }
}
