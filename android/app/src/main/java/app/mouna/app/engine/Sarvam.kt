package app.mouna.app.engine

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * Sarvam Bulbul text-to-speech, live: the call feature needs words nobody pre-rendered ("I'll be there at five").
 * The only network use in the app. Text goes out, a WAV comes back; no audio or video from the phone ever does.
 * Pure Kotlin (no Android types) so the request, response and cache key are unit-tested on the JVM.
 */
object Sarvam {
    const val URL = "https://api.sarvam.ai/text-to-speech"
    const val MODEL = "bulbul:v3"
    const val PACE = 0.9 // same pace as the pre-rendered pack (voices/render.py)

    /** The whole answer must be in this long, or the phone's own voice speaks instead (the person is on a live call). */
    const val TIMEOUT_MS = 4000

    private const val CACHE_KEEP = 300

    /** The app's voice ids that Bulbul has a speaker for; every other voice (open models, the phone's) speaks as kavitha. */
    fun speakerFor(voiceId: String): String = if (voiceId == "anand") "anand" else "kavitha"

    data class Request(val text: String, val lang: Lang, val speaker: String)

    fun requestJson(r: Request): String = JSONObject()
        .put("text", r.text)
        .put("target_language_code", r.lang.code)
        .put("speaker", r.speaker)
        .put("model", MODEL)
        .put("pace", PACE)
        .toString()

    /** The first audio of a response `{"audios": ["<base64 WAV>"]}`, or null if there is none. */
    fun parseAudio(body: String): ByteArray? = runCatching {
        val a = JSONObject(body).getJSONArray("audios")
        if (a.length() == 0) null else Base64.getMimeDecoder().decode(a.getString(0)).takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** The same words in the same voice always give the same file, so a repeated phrase never touches the network. */
    fun cacheKey(r: Request): String =
        MessageDigest.getInstance("SHA-1").digest("${r.lang.code}|${r.speaker}|${r.text}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun cacheFile(dir: File, r: Request) = File(File(dir, "sarvam"), cacheKey(r) + ".wav")

    /**
     * Blocking: returns the cached WAV or asks Sarvam and caches the answer. Throws on any failure (no network, a bad
     * key, a rate limit, an empty answer); the caller falls back to the phone's voice. Run it off the main thread.
     * The key goes only into the request header and is never logged or put in an exception message.
     */
    fun fetch(key: String, r: Request, cacheDir: File, url: String = URL): File {
        val out = cacheFile(cacheDir, r)
        if (out.exists() && out.length() > 0) return out
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("api-subscription-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(requestJson(r).toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) throw SarvamException("HTTP $code")
            val wav = parseAudio(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) ?: throw SarvamException("no audio in the answer")
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, "${out.name}.tmp")
            tmp.writeBytes(wav)
            tmp.renameTo(out)
            prune(out.parentFile)
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Typed sentences are unbounded; keep the newest few hundred files. */
    private fun prune(dir: File?) {
        val files = dir?.listFiles { f -> f.extension == "wav" } ?: return
        if (files.size <= CACHE_KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - CACHE_KEEP).forEach { it.delete() }
    }

    class SarvamException(message: String) : Exception(message)
}
