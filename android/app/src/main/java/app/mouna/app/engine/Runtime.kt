package app.mouna.app.engine

import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** The Android emulator (e.g. on an Apple M4 Mac): it advertises CPU features (SME) that then trap. */
val isEmulator: Boolean =
    Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("emu") || Build.PRODUCT.startsWith("sdk_gphone")

/** ONNX Runtime CPU options for any model; KleidiAI kernels off on the emulator (SIGILL otherwise). */
fun cpuOptions(threads: Int): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(threads)
    addConfigEntry("session.intra_op.allow_spinning", "0") // idle pool threads sleep instead of burning a core (and battery) between clips
    if (isEmulator) {
        addConfigEntry("mlas.disable_kleidiai", "1")
        addConfigEntry("session.disable_prepacking", "1")
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }
}

/**
 * Crash marks. A model that crashes natively takes the app down with it, so each risky attempt is marked on disk first;
 * a mark still there at the next start means the app died during the attempt, and that model is skipped instead of
 * crash-looping. But dying is not crashing: the first NPU compile takes minutes, and a swipe-away or a low-memory kill
 * in that window must not demote the encoder to the CPU for good. So a leftover mark counts only if Android says the
 * last process really crashed, and no mark counts after a day.
 */
object CrashMarks {
    const val EXPIRE_MS = 24 * 60 * 60 * 1000L
    const val TRYING = "trying"
    const val CRASHED = "crashed"

    enum class Verdict { SKIP, RETRY }

    /** Exits that are the model's fault; a user swipe, a low-memory kill or an OS update are not. */
    private val REAL_CRASHES = setOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR)

    /**
     * [state]: [TRYING] (written before the attempt) or [CRASHED] (a later start already saw a real crash behind it);
     * [ageMs]: since the attempt began; [lastExit]: why the previous process ended (an [ApplicationExitInfo] reason),
     * or null when Android can't say (before Android 11, or no history): then the guard stays, as it always did.
     */
    fun decide(state: String, ageMs: Long, lastExit: Int?): Verdict = when {
        ageMs > EXPIRE_MS -> Verdict.RETRY
        state == CRASHED -> Verdict.SKIP // seen once: stays until it expires, whatever the intervening starts ended with
        lastExit == null -> Verdict.SKIP
        lastExit in REAL_CRASHES -> Verdict.SKIP
        else -> Verdict.RETRY
    }

    /** Mark file content: "trying:<epoch ms the attempt began>". */
    fun content(state: String, startedAtMs: Long) = "$state:$startedAtMs"

    /** (state, started-at) from a mark's content; a mark from an older build ("trying") falls back to the file's time. */
    fun parse(text: String, fileTimeMs: Long): Pair<String, Long> {
        val state = if (text.startsWith(CRASHED)) CRASHED else TRYING
        return state to (text.substringAfter(':', "").trim().toLongOrNull() ?: fileTimeMs)
    }

    /** Why the previous process of this app ended, or null if unknown (API < 30, or nothing recorded yet). */
    fun lastExitReason(context: Context): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason
        }.getOrNull()
    }

    /**
     * True if [mark] says a crash killed the last attempt and the attempt should be skipped. A mark that doesn't count
     * is deleted. When the caller goes ahead it calls [begin] next.
     */
    fun blocks(context: Context, mark: File, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!mark.exists()) return false
        val (state, at) = parse(runCatching { mark.readText() }.getOrDefault(""), mark.lastModified())
        return when (decide(state, nowMs - at, lastExitReason(context))) {
            Verdict.SKIP -> {
                if (state != CRASHED) runCatching { mark.writeText(content(CRASHED, at)) } // remember it: the next start's exit reason will be a different process's
                Log.w("Mouna", "${mark.name}: a crash left this mark, skipping (delete it to retry)")
                true
            }
            Verdict.RETRY -> {
                Log.i("Mouna", "${mark.name}: the last run was cut short, not crashed; trying again")
                mark.delete()
                false
            }
        }
    }

    fun begin(mark: File, nowMs: Long = System.currentTimeMillis()) {
        mark.writeText(content(TRYING, nowMs))
    }
}

/** Runs [run] behind a crash mark (see [CrashMarks]); null if a crash left the mark or [run] has nothing. */
fun <T> crashGuarded(context: Context, dir: File, name: String, run: () -> T?): T? {
    val mark = File(dir, ".$name.crashed")
    if (CrashMarks.blocks(context, mark)) return null
    CrashMarks.begin(mark)
    return run().also { mark.delete() }
}
