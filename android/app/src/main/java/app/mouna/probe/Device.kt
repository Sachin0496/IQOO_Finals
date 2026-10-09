package app.mouna.probe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

/** Spike test S6: read the APK's own permission list at runtime, so the claim is shown, not asserted. */
fun requestedPermissions(context: Context): List<String> {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    return info.requestedPermissions?.toList().orEmpty()
}

data class Power(val batteryPct: Float, val tempC: Float, val thermal: Int)

fun readPower(context: Context): Power {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val thermal = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    return Power(100f * level / scale, temp, thermal)
}

/** Spike test S5: which of the four output languages have an installed, offline voice. */
enum class VoiceState { OFFLINE, NEEDS_DOWNLOAD, MISSING }

val PROBE_LANGS = listOf(
    Triple("en-IN", "English", "I need water"),
    Triple("ta-IN", "தமிழ்", "எனக்கு தண்ணீர் வேண்டும்"),
    Triple("kn-IN", "ಕನ್ನಡ", "ನನಗೆ ನೀರು ಬೇಕು"),
    Triple("hi-IN", "हिन्दी", "मुझे पानी चाहिए"),
)

fun voiceState(tts: TextToSpeech, tag: String): VoiceState {
    val locale = Locale.forLanguageTag(tag)
    if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return VoiceState.MISSING
    val voices = tts.voices.orEmpty().filter { it.locale.language == locale.language }
    return if (voices.any { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }) {
        VoiceState.OFFLINE
    } else {
        VoiceState.NEEDS_DOWNLOAD
    }
}

/** 30-minute soak (S6): one CSV row every 10 s in app storage; pull with `adb shell run-as app.mouna.probe cat files/soak.csv`. */
class SoakLog(context: Context) {
    private val file = File(context.filesDir, "soak.csv")
    val startedAt = System.currentTimeMillis()
    private val first = readPower(context)
    var maxTemp = first.tempC
        private set

    init {
        file.writeText("t_s,battery_pct,temp_c,thermal,fps,landmark_ms\n")
    }

    fun row(p: Power, t: Telemetry) {
        maxTemp = maxOf(maxTemp, p.tempC)
        val s = (System.currentTimeMillis() - startedAt) / 1000
        file.appendText("$s,${p.batteryPct},${p.tempC},${p.thermal},${"%.1f".format(t.fps)},${"%.1f".format(t.landmarkMs)}\n")
    }

    /** Battery drain in % per hour since the start. */
    fun drainPerHour(p: Power): Float {
        val hours = (System.currentTimeMillis() - startedAt) / 3_600_000f
        return if (hours > 0.01f) (first.batteryPct - p.batteryPct) / hours else 0f
    }
}
