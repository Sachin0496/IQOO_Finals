package app.mouna.app.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * A normal carrier call. The call is placed on speakerphone, so Mouna's voice, played loud on the phone's speaker, is
 * picked up by the phone's own microphone and carried into the call; the person hears the other side on the speaker.
 * There is no READ_PHONE_STATE: whether a call is up is read from the audio mode, polled while a call was started.
 * OEMs differ on every step, so each is best effort and logged under "MounaCall".
 */
class CarrierLink(private val context: Context) : CallLink {
    override var state = CallState.IDLE
        private set
    override var onState: (CallState) -> Unit = {}
    override val takesAudio = false

    /** Set by the activity: whether Mouna is the app on screen (used to check "return to Mouna"). */
    @Volatile var foreground = false
    var taskId = -1

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var dialedAt = 0L
    private var offPolls = 0
    private var polls = 0
    private var lastMode = -1

    override fun dial(target: String): Boolean {
        val number = Phones.normalise(target) ?: return false
        if (!granted(android.Manifest.permission.CALL_PHONE)) return false
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
        if (runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "dial failed", it) }.isFailure) return false
        dialedAt = SystemClock.elapsedRealtime()
        offPolls = 0
        polls = 0
        set(CallState.DIALING)
        main.postDelayed(poll, POLL_MS)
        main.postDelayed({ comeBack(1) }, 1500)
        main.postDelayed({ comeBack(2) }, 4000)
        return true
    }

    override fun hangUp(): Boolean {
        val ok = Build.VERSION.SDK_INT >= 28 && granted(android.Manifest.permission.ANSWER_PHONE_CALLS) &&
            runCatching { context.getSystemService(TelecomManager::class.java).endCall() }.getOrDefault(false)
        Log.i(TAG, "hang up via endCall: $ok")
        return ok
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun set(s: CallState) {
        if (s == state) return
        state = s
        onState(s)
    }

    private val poll = object : Runnable {
        override fun run() {
            val mode = audio.mode
            if (mode != lastMode) Log.i(TAG, "audio mode $lastMode -> $mode")
            lastMode = mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            when (state) {
                CallState.DIALING -> when {
                    inCall -> { set(CallState.ACTIVE); onConnected() }
                    SystemClock.elapsedRealtime() - dialedAt > DIAL_GIVE_UP_MS -> { Log.w(TAG, "no call audio mode after dialing"); set(CallState.IDLE) }
                }
                CallState.ACTIVE -> if (inCall) {
                    offPolls = 0
                    if (++polls % 10 == 0) speaker() // some phones drop the route once the other side answers
                } else if (++offPolls >= 2) {
                    release()
                    set(CallState.IDLE)
                }
                CallState.IDLE -> Unit
            }
            if (state != CallState.IDLE) main.postDelayed(this, POLL_MS)
        }
    }

    private fun onConnected() {
        speaker()
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        }.onFailure { Log.w(TAG, "volume", it) }
        if (!foreground) comeBack(3)
    }

    /** Speakerphone, best effort (API 31+ routes by communication device). */
    private fun speaker() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (d != null && audio.communicationDevice?.id != d.id) Log.i(TAG, "speaker via setCommunicationDevice: ${audio.setCommunicationDevice(d)}")
            } else if (!audio.isSpeakerphoneOn) {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
                Log.i(TAG, "speaker via isSpeakerphoneOn")
            }
        }.onFailure { Log.w(TAG, "speaker", it) }
    }

    private fun release() = runCatching {
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 31) audio.isSpeakerphoneOn = false
    }

    /** The system call screen takes over after dialing; try to bring Mouna back. May be blocked by background-start rules. */
    private fun comeBack(attempt: Int) {
        if (state == CallState.IDLE || foreground) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }.onFailure { Log.w(TAG, "return #$attempt: startActivity refused: ${it.message}") }
        main.postDelayed({
            if (foreground) {
                Log.i(TAG, "return #$attempt: startActivity brought Mouna back")
            } else {
                runCatching { context.getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0) }
                    .onFailure { Log.w(TAG, "return #$attempt: moveTaskToFront refused: ${it.message}") }
                main.postDelayed({ Log.i(TAG, "return #$attempt: Mouna in front after moveTaskToFront: $foreground") }, 700)
            }
        }, 700)
    }

    companion object {
        private const val TAG = "MounaCall"
        private const val POLL_MS = 500L
        private const val DIAL_GIVE_UP_MS = 45_000L
    }
}
