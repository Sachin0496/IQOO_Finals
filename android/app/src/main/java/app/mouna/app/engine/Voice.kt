package app.mouna.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.util.Locale

data class VoiceInfo(val id: String, val label: String)

/**
 * Speaks a phrase in the caregiver's language. First choice: the pre-rendered natural voice pack
 * (assets/voices, from voices/render.py); otherwise the phone's own offline TTS voice. Nothing goes to the network.
 */
class Voice(private val context: Context) : AutoCloseable {
    private val clips = HashMap<String, Map<String, Map<String, String>>>() // voice -> lang -> phrase -> asset path
    val voices: List<VoiceInfo>
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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
    }

    /** Plays the natural clip if the pack has one, else speaks [text] with the phone's voice. */
    fun say(phraseId: String?, text: String, lang: Lang, voice: String) {
        stop()
        val path = phraseId?.let { clips[voice]?.get(lang.tag)?.get(it) }
        if (path != null && runCatching { play("voices/$path") }.isSuccess) return
        val t = tts ?: return
        if (!ttsReady) return
        t.language = Locale.forLanguageTag(lang.code)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, phraseId ?: "say")
    }

    private fun play(asset: String) {
        val fd = context.assets.openFd(asset)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        tts?.stop()
    }

    override fun close() {
        stop()
        tts?.shutdown()
    }

    companion object {
        const val DEVICE = "device"
    }
}
