package app.mouna.app.engine

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * Sarvam Bulbul text-to-speech, live: the call feature needs words nobody pre-rendered ("I'll be there at five").
 * Network use of its own: text goes out, a WAV comes back; no audio or video from the phone ever does. (Web calls use
 * the network too, see [WebLink]: there Mouna's rendered voice and its caption go to the relay.)
 * Pure Kotlin (no Android types) so the request, response and cache key are unit-tested on the JVM.
 */
object Sarvam {
    const val URL = "https://api.sarvam.ai/text-to-speech"
    const val MODEL = "bulbul:v3"
    const val PACE = 0.9 // same pace as the pre-rendered pack (voices/render.py)

    /** One request gives up after this long (the file still lands in the cache if it comes late: next time it is instant). */
    const val TIMEOUT_MS = 4000

    /** On a call the other person is waiting: past this the phone's own voice speaks instead. */
    const val CALL_WAIT_MS = 1500

    /** What [Voice] does with a phrase the pre-rendered pack doesn't have. */
    enum class Plan {
        /** The phone's own offline voice, now. */
        PHONE_NOW,
        /** A sentence Sarvam spoke before, from the cache: instant. */
        CACHED,
        /** Ask Sarvam and wait up to [CALL_WAIT_MS], then the phone's voice. Only on a call. */
        FETCH,
    }

    /**
     * Nobody waits for the network to hear their own words: off a call the phone's voice speaks at once unless the
     * sentence is already cached; on a call a natural voice is worth a short wait, but only a short one.
     * [phoneVoiceChosen]: the person picked "Phone voice" for ordinary speech (a call still prefers Sarvam).
     */
    fun plan(hasKey: Boolean, phoneVoiceChosen: Boolean, onCall: Boolean, cached: Boolean): Plan = when {
        !hasKey -> Plan.PHONE_NOW
        phoneVoiceChosen && !onCall -> Plan.PHONE_NOW
        cached -> Plan.CACHED
        onCall -> Plan.FETCH
        else -> Plan.PHONE_NOW
    }

    /** The cache holds the newest this-many spoken sentences (typed text included); Settings can clear it. */
    const val CACHE_KEEP = 300

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
            // Two requests for the same phrase can be in flight (a tap while the call's warm-up fetches it): each writes
            // its own file and moves it into place, so neither sees the other's half-written bytes.
            val tmp = File.createTempFile("fetch-", ".tmp", out.parentFile)
            try {
                tmp.writeBytes(wav)
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete() // nothing left behind if the move failed (after a successful move there is no such file)
            }
            prune(out.parentFile)
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Typed sentences are unbounded; keep the newest [CACHE_KEEP] files. */
    private fun prune(dir: File?) {
        dir?.listFiles { f -> f.extension == "tmp" && System.currentTimeMillis() - f.lastModified() > 600_000 }?.forEach { it.delete() } // a crashed fetch
        val files = dir?.listFiles { f -> f.extension == "wav" } ?: return
        if (files.size <= CACHE_KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - CACHE_KEEP).forEach { it.delete() }
    }

    /** How many spoken sentences are kept on the phone, and their size in bytes. */
    fun cacheStats(cacheDir: File): Pair<Int, Long> {
        val files = File(cacheDir, "sarvam").listFiles { f -> f.extension == "wav" } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    /** Deletes every kept sentence (and any half-written file); returns how many sentences went. */
    fun clearCache(cacheDir: File): Int {
        val files = File(cacheDir, "sarvam").listFiles() ?: return 0
        var sentences = 0
        for (f in files) if (f.delete() && f.extension == "wav") sentences++
        return sentences
    }

    class SarvamException(message: String) : Exception(message)
}
