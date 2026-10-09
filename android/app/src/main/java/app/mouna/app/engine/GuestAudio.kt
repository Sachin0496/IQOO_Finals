package app.mouna.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * Plays the guest's voice (16 kHz mono PCM from the relay) on the phone's speaker, loud: the person who cannot speak
 * must hear the caller. Call audio route and volume are set while it runs and put back after. Mouna's own microphone
 * is never opened. The guest's PCM goes through a [JitterBuffer] (about 120 ms) on a playback thread.
 */
class GuestAudio(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val jitter = JitterBuffer()
    @Volatile private var running = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedVolume = -1

    private val _level = MutableStateFlow(0f)
    /** How loud the guest is right now, 0..1, for the "guest is speaking" indicator. */
    val level: StateFlow<Float> = _level

    val playing get() = running

    /** Why the last [start] failed, in words for the screen. */
    var error: String? = null
        private set

    /** Starts the call audio; false (with [error], and the audio mode put back) if the phone would not. */
    fun start(): Boolean {
        if (running) return true
        error = null
        savedMode = audio.mode
        savedVolume = -1
        val started = runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            speaker()
            runCatching {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            }.onFailure { Log.w(TAG, "volume", it) }

            val format = AudioFormat.Builder()
                .setSampleRate(CallWire.PCM_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(CallWire.PCM_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, CallWire.PCM_BYTES_PER_MS * 200))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            try {
                t.play()
            } catch (e: Exception) {
                t.release()
                throw e
            }
            t
        }
        val t = started.getOrElse {
            Log.w(TAG, "guest audio won't start", it)
            error = "This phone would not start the call audio (${it.javaClass.simpleName})."
            restore()
            return false
        }
        track = t
        running = true
        Log.i(TAG, "guest audio: AudioTrack ${t.sampleRate} Hz, buffer ${t.bufferSizeInFrames} frames, mode=${audio.mode}, route=${t.routedDevice?.type}")
        worker = thread(name = "guest-audio", isDaemon = true) { pump(t) }
        return true
    }

    /** Called with each frame of the guest's PCM, from the socket's thread. */
    fun write(pcm: ByteArray) {
        if (running) jitter.offer(pcm)
    }

    private fun pump(t: AudioTrack) {
        var lastSound = 0L
        var played = 0L
        while (running) {
            val c = jitter.poll()
            if (c == null) {
                if (_level.value > 0f && System.nanoTime() - lastSound > 250_000_000L) _level.value = 0f
                Thread.sleep(8)
                continue
            }
            lastSound = System.nanoTime()
            _level.value = Pcm.level(c)
            if (played == 0L) Log.i(TAG, "guest audio: first sound, ${c.size} bytes")
            played += c.size
            if (t.write(c, 0, c.size) < 0) break
        }
        Log.i(TAG, "guest audio: stopped after ${played / CallWire.PCM_BYTES_PER_MS} ms played, ${jitter.dropped / CallWire.PCM_BYTES_PER_MS} ms dropped")
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { worker?.join(300) }
        track?.runCatching { stop(); release() }
        track = null
        jitter.clear()
        _level.value = 0f
        restore()
    }

    /** Volume, route and audio mode back to what they were before [start]. */
    private fun restore() {
        runCatching {
            if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 31) audio.isSpeakerphoneOn = false
            audio.mode = savedMode
        }
        savedVolume = -1
    }

    /** Speakerphone, best effort (API 31+ routes by communication device). */
    private fun speaker() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (d != null) Log.i(TAG, "speaker via setCommunicationDevice: ${audio.setCommunicationDevice(d)}")
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
        }.onFailure { Log.w(TAG, "speaker", it) }
    }

    companion object {
        private const val TAG = "MounaCall"
    }
}
