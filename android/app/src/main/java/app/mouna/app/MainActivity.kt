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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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
import android.provider.ContactsContract
import app.mouna.app.engine.CallUsage
import app.mouna.app.engine.CarrierLink
import app.mouna.app.engine.Engine
import app.mouna.app.engine.SignSegmenter
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Hearing
import app.mouna.app.engine.PhrasePack
import app.mouna.app.engine.Store
import app.mouna.app.engine.Voice
import app.mouna.app.engine.WebLink
import android.telephony.TelephonyManager
import app.mouna.app.ui.MounaRoot
import app.mouna.app.ui.MounaTheme
import app.mouna.probe.ProbeActivity
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
    private var callAnswer: ((Boolean) -> Unit)? = null
    private val askCallPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Calling is what matters; hanging up from Mouna (ANSWER_PHONE_CALLS) falls back to the phone's own call screen.
        callAnswer?.invoke(granted[Manifest.permission.CALL_PHONE] == true)
        callAnswer = null
    }
    private var contactAnswer: ((Pair<String, String>?) -> Unit)? = null
    // The picker grants read access to the one row picked: no READ_CONTACTS needed.
    private val pickContactResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        contactAnswer?.invoke(r.data?.data?.let { readContact(it) })
        contactAnswer = null
    }
    private lateinit var carrier: CarrierLink
    private val receivers = mutableListOf<BroadcastReceiver>()
    private val cameraDenied = mutableStateOf(false)
    private val cameraReady = mutableStateOf(false)
    /** First launch (or after Start over): the Welcome screen, until Continue. Set in onCreate from the store. */
    private val welcome = mutableStateOf(false)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied.value = !granted
        cameraReady.value = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark: fix the bar icons to match, since a uiMode change no longer recreates the activity.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // android:keepScreenOn on the <activity> does nothing (it is a View attribute): hands-free mouthing needs the screen to stay lit.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back with nothing left to close (Speak, no prompt) would finish the activity: the engine dies and a call hangs up.
        // Added before the UI's own BackHandlers, which therefore still win whenever they are enabled.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = moveToBack()
        })
        engine = Engine(applicationContext, PhrasePack.bundled(), Store(applicationContext))
        voice = Voice(applicationContext, engine.store)
        carrier = CarrierLink(applicationContext)
        hearing = Hearing(applicationContext)
        app = MounaApp(
            engine, voice, hearing,
            askMic = { answer ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    answer(true)
                } else {
                    micAnswer = answer
                    askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            carrier = carrier,
            web = WebLink(applicationContext),
            hasSim = ::hasSim,
            askCall = { answer ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    answer(true)
                } else {
                    callAnswer = answer
                    askCallPermissions.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS))
                }
            },
            pickContact = { answer ->
                contactAnswer = answer
                pickContactResult.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            },
        )
        welcome.value = !engine.store.welcomed
        engine.start()
        lifecycleScope.launch { engine.events.collect { app.onEvent(it) } }

        // First launch: the Welcome screen says why Mouna needs the camera before Android asks for it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraReady.value = true
        } else if (!welcome.value) {
            askCamera.launch(Manifest.permission.CAMERA)
        }

        // QA (debug builds): adb shell am broadcast -a app.mouna.HEAR --es wav /sdcard/Android/data/app.mouna/files/x.wav
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            debugReceiver("app.mouna.HEAR") { i ->
                val path = i.getStringExtra("wav") ?: return@debugReceiver
                runCatching { app.hearRecording(readWav(File(path))) }.onFailure { Log.e("Mouna", "hear", it) }
            }
            // adb shell am broadcast -a app.mouna.SAY --es text "hello" [--es lang hi|ta] [--es voice anand] [--ez call true]
            // Speaks through Voice (pack, Sarvam, phone voice); --ez call true plays as call audio, as on a call.
            debugReceiver("app.mouna.SAY") { i ->
                val text = i.getStringExtra("text") ?: return@debugReceiver
                val lang = Lang.entries.firstOrNull { it.tag == i.getStringExtra("lang") } ?: Lang.EN
                voice.callMode = app.onCall || i.getBooleanExtra("call", false)
                Log.i("MounaCall", "SAY \"$text\" ${lang.tag} callMode=${voice.callMode} usage=${voice.callUsage}")
                app.debugSay(text, lang, i.getStringExtra("voice") ?: app.voiceId)
            }
            // adb shell am broadcast -a app.mouna.CALLSERVER --es url https://....trycloudflare.com   (blank url: back to the build's)
            debugReceiver("app.mouna.CALLSERVER") { i ->
                app.chooseCallServer(i.getStringExtra("url").orEmpty())?.let { Log.w("MounaCall", "call server refused: $it") }
                Log.i("MounaCall", "call server = ${app.callServer().ifEmpty { "(none)" }}")
            }
            // adb shell am broadcast -a app.mouna.WEBCALL [--es room K7M2QX] [--es name Amma]: starts a web call; the join link goes to logcat
            debugReceiver("app.mouna.WEBCALL") { i ->
                app.debugWebCall(i.getStringExtra("room"), i.getStringExtra("name").orEmpty())
            }
            // adb shell am broadcast -a app.mouna.CALLAUDIO --es usage media|voice
            debugReceiver("app.mouna.CALLAUDIO") { i ->
                voice.callUsage = if (i.getStringExtra("usage") == "media") CallUsage.MEDIA else CallUsage.VOICE
                Log.i("MounaCall", "call audio usage = ${voice.callUsage}")
            }
            // adb shell am broadcast -a app.mouna.AVSR --ez test true            free talk NPU self-test (logcat tag Mouna)
            // adb shell am broadcast -a app.mouna.AVSR --es crops <file> [--ef fps 25]   read a clip of Auto-AVSR crops
            debugReceiver("app.mouna.AVSR") { i ->
                if (i.getBooleanExtra("test", false)) engine.freeTalkSelfTest()
                if (i.hasExtra("save")) engine.saveClips = i.getBooleanExtra("save", false) // --ez save true: keep clips
                i.getStringExtra("remember")?.let { engine.rememberSentence(it) } // --es remember "text": a personal sentence
                if (i.getBooleanExtra("record", false)) app.go(Screen.RECORD) // --ez record true: the recording screen
                i.getStringExtra("crops")?.let { engine.freeTalkRead(File(it), i.getFloatExtra("fps", 25f).toDouble()) }
            }
            // adb shell am broadcast -a app.mouna.SIGN --es json <path to [[54 floats], ...]>       one sign
            // adb shell am broadcast -a app.mouna.SIGN --es stream <path to {"points": [...], "wrists": [...]}>
            //   a recording, cut into signs as Sign mode does (models/isl/eval/include_eval.py device)
            debugReceiver("app.mouna.SIGN") { i ->
                i.getStringExtra("stream")?.let { path ->
                    runCatching {
                        val o = org.json.JSONObject(File(path).readText())
                        val pts = o.getJSONArray("points")
                        val wr = o.getJSONArray("wrists")
                        fun floats(a: Any?) = (a as? org.json.JSONArray)?.let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } }
                        engine.signStream(List(pts.length()) { SignSegmenter.Step(floats(pts.opt(it)), floats(wr.opt(it))) })
                    }.onFailure { Log.e("Mouna", "sign stream", it) }
                }
                val path = i.getStringExtra("json") ?: return@debugReceiver
                runCatching {
                    val a = org.json.JSONArray(File(path).readText())
                    engine.classifySign(List(a.length()) { t -> a.getJSONArray(t).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } })
                }.onFailure { Log.e("Mouna", "sign", it) }
            }
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
                    welcome = welcome.value,
                    onWelcomeDone = ::welcomeDone,
                )
            }
        }
    }

    /** Continue on the Welcome screen: remember it, then ask for the camera (unless it was already allowed). */
    private fun welcomeDone() {
        engine.store.welcomed = true
        welcome.value = false
        if (!cameraReady.value) askCamera.launch(Manifest.permission.CAMERA)
    }

    /**
     * A QA broadcast, for debug builds. The receiver is exported so `adb shell am broadcast` reaches it, but it asks the
     * sender for DUMP, which the shell holds and an ordinary app can't get: no other app can drive the call or the voice.
     */
    private fun debugReceiver(action: String, handle: (Intent) -> Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = handle(i)
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(action), Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
        receivers += r
    }

    /** Sends the app to the background, the way Home does: the engine and any call keep running. */
    fun moveToBack() {
        moveTaskToBack(true)
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
            // Null if the face model could not start at all: say so instead of waiting for it forever.
            val sensor = engine.awaitSensor()
            if (sensor == null) {
                noCamera("the face model did not start")
                return@launch
            }
            val future = ProcessCameraProvider.getInstance(this@MainActivity)
            future.addListener({
                try {
                    val provider = future.get()
                    // Front camera faces the person; any camera is better than none (some devices misreport facing).
                    val camera = listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                        .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
                        ?: provider.availableCameraInfos.firstOrNull()?.cameraSelector
                    if (camera == null) {
                        noCamera("this phone reports no camera")
                        return@addListener
                    }
                    @Suppress("DEPRECATION")
                    val builder = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    // Lip reading wants >= 25 fps (Auto-AVSR is trained at 25). Indoors auto-exposure drops the front
                    // camera to 20 fps (measured on the iQOO 15); ask for the best supported range topping out at 30.
                    fpsRange(provider, camera)?.let { r ->
                        androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                        Log.i("Mouna", "camera fps range $r")
                    }
                    val analyzer = builder.build().also { it.setAnalyzer(engine.analysis, sensor) }
                    provider.bindToLifecycle(this@MainActivity, camera, preview, analyzer)
                } catch (e: Exception) { // the camera is taken by another app, the HAL is down, a policy blocks it
                    Log.e("Mouna", "camera bind", e)
                    noCamera("the camera could not be opened")
                }
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }
    }

    /** The existing no-camera screen, instead of a Speak screen that will never see a face. */
    private fun noCamera(why: String) {
        Log.e("Mouna", "no camera: $why")
        cameraDenied.value = true
        cameraReady.value = false
    }

    /** The supported AE range with the highest floor whose ceiling is 30 fps ([30, 30] if the camera has it). */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun fpsRange(provider: ProcessCameraProvider, selector: CameraSelector): android.util.Range<Int>? = runCatching {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        val ranges = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ranges?.filter { it.upper == 30 }?.maxByOrNull { it.lower }
    }.getOrNull()

    override fun onResume() {
        super.onResume()
        carrier.foreground = true
        carrier.taskId = taskId
    }

    override fun onPause() {
        carrier.foreground = false
        super.onPause()
    }

    /** A SIM that can place a call. Needs no permission; false on a phone with no SIM, or no telephony at all. */
    private fun hasSim(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            getSystemService(TelephonyManager::class.java)?.simState == TelephonyManager.SIM_STATE_READY

    /** Name and number of the contact row the picker returned. */
    private fun readContact(uri: android.net.Uri): Pair<String, String>? = runCatching {
        contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
    }.getOrNull()

    override fun onDestroy() {
        for (r in receivers) runCatching { unregisterReceiver(r) }
        receivers.clear()
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
