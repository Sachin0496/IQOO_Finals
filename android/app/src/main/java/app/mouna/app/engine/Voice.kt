package app.mouna.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.mouna.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class VoiceInfo(val id: String, val label: String)

/**
 * How Mouna's voice reaches the other person on a phone call. The call is on speakerphone and the phone's own
 * microphone carries Mouna's voice into it. VOICE plays as call audio (routed to the in-call speaker); MEDIA plays as
 * ordinary media. Which one the microphone picks up best differs by phone: check it on the device (Settings > QA tools,
 * or `adb shell am broadcast -a app.mouna.CALLAUDIO --es usage media`).
 */
enum class CallUsage(val usage: Int) {
    VOICE(AudioAttributes.USAGE_VOICE_COMMUNICATION),
    MEDIA(AudioAttributes.USAGE_MEDIA),
}

/**
 * Speaks a phrase in the caregiver's language. In order: the pre-rendered natural voice pack (assets/voices, from
 * voices/render.py: instant, offline); Sarvam Bulbul live, if there is a key and the network answers in time (text only
 * goes out, see [Sarvam]); the phone's own offline TTS voice. Any failure moves down the list at once.
 */
class Voice(private val context: Context, private val store: Store? = null) : AutoCloseable {
    private val clips = HashMap<String, Map<String, Map<String, String>>>() // voice -> lang -> phrase -> asset path
    val voices: List<VoiceInfo>
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newCachedThreadPool() // a hung request must not hold up the next phrase
    private val linkJobs = java.util.concurrent.ConcurrentHashMap<String, File>() // TTS files being rendered for the call
    private var generation = 0 // bumped by every say() and stop(): answers for an older one are dropped

    /** The active call, if any. When it [CallLink.takesAudio], rendered audio is handed to it instead of being played. */
    @Volatile var link: CallLink? = null

    /** On a phone call: play loud as call audio ([callUsage]) instead of as an accessibility prompt. */
    @Volatile var callMode = false
    var callUsage: CallUsage = runCatching { CallUsage.valueOf(store?.callUsage?.uppercase() ?: "VOICE") }.getOrDefault(CallUsage.VOICE)
        set(v) {
            field = v
            store?.callUsage = v.name.lowercase()
        }

    private val _via = MutableStateFlow<String?>(null)
    /** Which engine spoke last: [PACK], [SARVAM] or [PHONE] (for QA). */
    val via: StateFlow<String?> = _via

    init {
        val list = mutableListOf<VoiceInfo>()
        runCatching {
            val o = JSONObject(context.assets.open("voices/manifest.json").readBytes().toString(Charsets.UTF_8))
            val vs = o.getJSONArray("voices")
            for (i in 0 until vs.length()) {
                val v = vs.getJSONObject(i)
                val c = v.getJSONObject("clips")
                clips[v.getString("id")] = c.keys().asSequence().associateWith { lang ->
                    val m = c.getJSONObject(lang)
                    m.keys().asSequence().associateWith { m.getString(it) }
                }
                list += VoiceInfo(v.getString("id"), v.optString("label", v.getString("id")))
            }
        }
        list += VoiceInfo(DEVICE, "Phone voice")
        voices = list
        tts = TextToSpeech(context) { ttsReady = it == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onError(id: String?) { linkJobs.remove(id)?.delete() }
            override fun onDone(id: String?) {
                val f = linkJobs.remove(id) ?: return
                main.post {
                    val sent = id == "link:$generation" && toLink { f.readBytes() to "audio/wav" }
                    if (sent) used(PHONE)
                    f.delete()
                }
            }
        })
    }

    /** The key in Settings wins over the one built in from local.properties; blank means no live voice. */
    fun sarvamKey(): String = store?.sarvamKey.orEmpty().ifBlank { BuildConfig.SARVAM_KEY }

    /** Plays the natural clip if the pack has one, else Sarvam live, else the phone's voice. Returns at once. */
    fun say(phraseId: String?, text: String, lang: Lang, voice: String) {
        stop()
        val gen = generation
        val path = phraseId?.let { clips[voice]?.get(lang.tag)?.get(it) }
        if (path != null) {
            val sent = toLink { context.assets.open("voices/$path").use { it.readBytes() } to "audio/mp4" }
            if (sent || runCatching { play { context.assets.openFd("voices/$path").use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) } } }.isSuccess) {
                used(PACK)
                return
            }
        }
        // "Phone voice" is the person's own choice for ordinary speech; on a call the words matter more than the voice.
        val key = sarvamKey()
        if (key.isBlank() || (voice == DEVICE && !callMode)) return speakTts(text, lang, phraseId)
        val req = Sarvam.Request(text, lang, Sarvam.speakerFor(voice))
        val cached = Sarvam.cacheFile(context.cacheDir, req)
        if (cached.exists() && playSarvam(cached)) return
        val t0 = SystemClock.elapsedRealtime()
        val settled = AtomicBoolean(false) // the answer and the deadline race; the first one wins
        val deadline = Runnable {
            if (gen == generation && settled.compareAndSet(false, true)) {
                Log.w(TAG, "sarvam: no answer in ${Sarvam.TIMEOUT_MS} ms, phone voice instead")
                speakTts(text, lang, phraseId)
            }
        }
        main.postDelayed(deadline, Sarvam.TIMEOUT_MS.toLong())
        net.execute {
            val file = runCatching { Sarvam.fetch(key, req, context.cacheDir) }
                .onFailure { Log.w(TAG, "sarvam failed: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull()
            main.post {
                if (gen != generation || !settled.compareAndSet(false, true)) return@post
                main.removeCallbacks(deadline)
                if (file != null && playSarvam(file)) Log.i(TAG, "sarvam: ${SystemClock.elapsedRealtime() - t0} ms to first sound")
                else speakTts(text, lang, phraseId)
            }
        }
    }

    /** Fetches [texts] into the cache without speaking them, so they are instant later. Best effort, one at a time. */
    fun prefetch(texts: List<String>, lang: Lang, voice: String) {
        val key = sarvamKey()
        if (key.isBlank() || (voice == DEVICE && !callMode)) return
        net.execute {
            for (t in texts) {
                if (runCatching { Sarvam.fetch(key, Sarvam.Request(t, lang, Sarvam.speakerFor(voice)), context.cacheDir) }.isFailure) break
            }
        }
    }

    private fun used(v: String) {
        _via.value = v
        Log.i(TAG, "spoke via $v")
    }

    /** True if the active call takes audio and accepted what [render] produced; false means play it on the speaker. */
    private fun toLink(render: () -> Pair<ByteArray, String>): Boolean {
        val l = link ?: return false
        if (!l.takesAudio || l.state != CallState.ACTIVE) return false
        return runCatching { val (data, mime) = render(); l.sendAudio(data, mime) }.getOrDefault(false)
    }

    private fun playSarvam(f: File): Boolean {
        val ok = toLink { f.readBytes() to "audio/wav" } || runCatching { play { setDataSource(f.path) } }.onFailure { Log.w(TAG, "sarvam clip won't play", it) }.isSuccess
        if (ok) used(SARVAM)
        return ok
    }

    private fun speakTts(text: String, lang: Lang, phraseId: String?) {
        val t = tts ?: return
        if (!ttsReady) return
        t.setAudioAttributes(attributes())
        t.language = Locale.forLanguageTag(lang.code)
        val l = link
        if (l != null && l.takesAudio && l.state == CallState.ACTIVE) {
            // Render to a WAV and hand it to the call instead of the speaker.
            val id = "link:${generation}"
            val f = File(context.cacheDir, "tts-$id.wav")
            linkJobs[id] = f
            if (t.synthesizeToFile(text, null, f, id) == TextToSpeech.SUCCESS) return
            linkJobs.remove(id)
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, phraseId ?: "say")
        used(PHONE)
    }

    private fun attributes(): AudioAttributes {
        val usage = if (callMode) callUsage.usage else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        return AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    private fun play(source: MediaPlayer.() -> Unit) {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attributes())
            p.source()
            p.setOnCompletionListener { it.release(); if (player === it) player = null }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            throw e
        }
        player = p
    }

    /** Stops what is playing and drops any Sarvam answer still on its way. */
    fun stop() {
        generation++
        main.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }
        player = null
        tts?.stop()
    }

    override fun close() {
        stop()
        tts?.shutdown()
        net.shutdown()
    }

    companion object {
        const val DEVICE = "device"
        const val PACK = "phrase pack"
        const val SARVAM = "Sarvam"
        const val PHONE = "phone voice"
        private const val TAG = "MounaVoice"
    }
}
