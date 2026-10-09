package app.mouna.app.engine

import org.json.JSONObject
import java.security.SecureRandom

/**
 * What the app and the guest's browser say to each other through the call relay (call-server/README.md has the
 * same table). Pure Kotlin so the framing is unit-tested on the JVM, and the page's JavaScript is held to the same bytes.
 */
object Rooms {
    /** No I, L, O, 0 or 1: an id read aloud or typed from a screenshot can't be mistaken. Matches the server. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6
    private val ID = Regex("^[A-Z2-9]{4,16}$")
    private val rng = SecureRandom()

    fun newId(random: java.util.Random = rng): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** The id as the server sees it (upper case), or null if it can't be a room. */
    fun normalise(raw: String): String? = raw.trim().uppercase().takeIf { ID.matches(it) }

    /** A server address as typed ("calls.example.com", "https://x.trycloudflare.com/"): scheme and no trailing slash, or "" if blank. */
    fun base(raw: String): String {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }

    /** The WebSocket address of [room] on the server at [base]; https becomes wss. */
    fun wsUrl(base: String, room: String, role: String = "mouna"): String {
        val b = base(base)
        val ws = if (b.startsWith("https://")) "wss://" + b.removePrefix("https://") else "ws://" + b.removePrefix("http://")
        return "$ws/ws?room=$room&role=$role"
    }

    /** What the guest opens (and the QR code holds). */
    fun joinUrl(base: String, room: String): String = "${base(base)}/c/$room"

    /** The link without its scheme, for reading aloud or typing: "calm-river.trycloudflare.com/c/K7M2QX". */
    fun shortUrl(base: String, room: String): String = joinUrl(base, room).substringAfter("://")
}

object CallWire {
    const val KIND_WAV = 0x01
    const val KIND_MP4 = 0x02
    const val KIND_PCM = 0x10

    /** What the page sends: 16 kHz mono 16-bit, so 32 bytes per millisecond. */
    const val PCM_RATE = 16_000
    const val PCM_BYTES_PER_MS = PCM_RATE * 2 / 1000

    sealed interface Binary {
        /** A rendered sentence to play; [mime] is "audio/wav" or "audio/mp4". */
        data class Audio(val mime: String, val data: ByteArray) : Binary
        /** Microphone samples from the guest. */
        class Pcm(val data: ByteArray) : Binary
    }

    fun kindOf(mime: String): Int = if (mime == "audio/mp4" || mime == "audio/aac") KIND_MP4 else KIND_WAV

    /** One binary message: a kind byte, then the file. */
    fun audioFrame(mime: String, data: ByteArray): ByteArray = ByteArray(data.size + 1).also {
        it[0] = kindOf(mime).toByte()
        data.copyInto(it, 1)
    }

    fun pcmFrame(pcm: ByteArray): ByteArray = ByteArray(pcm.size + 1).also {
        it[0] = KIND_PCM.toByte()
        pcm.copyInto(it, 1)
    }

    /** Null for anything that is not a frame this app understands (empty, unknown kind, or an odd PCM length). */
    fun parseBinary(b: ByteArray): Binary? {
        if (b.size < 2) return null
        val body = b.copyOfRange(1, b.size)
        return when (b[0].toInt() and 0xFF) {
            KIND_WAV -> Binary.Audio("audio/wav", body)
            KIND_MP4 -> Binary.Audio("audio/mp4", body)
            KIND_PCM -> if (body.size % 2 == 0) Binary.Pcm(body) else Binary.Pcm(body.copyOf(body.size - 1))
            else -> null
        }
    }

    // ---- text messages ----

    sealed interface Msg {
        data class Peer(val joined: Boolean) : Msg
        data object Answered : Msg
        data object Bye : Msg
        data class Other(val t: String) : Msg
    }

    fun say(text: String, lang: String? = null): String =
        JSONObject().put("t", "say").put("text", text).apply { if (lang != null) put("lang", lang) }.toString()

    fun hello(name: String): String = JSONObject().put("t", "hello").put("name", name).toString()
    fun bye(): String = """{"t":"bye"}"""

    fun parseText(s: String): Msg? = runCatching {
        val o = JSONObject(s)
        when (val t = o.getString("t")) {
            "peer" -> Msg.Peer(o.optBoolean("joined", false))
            "answered" -> Msg.Answered
            "bye" -> Msg.Bye
            else -> Msg.Other(t)
        }
    }.getOrNull()
}

/**
 * Holds the guest's voice for a moment before it is played, so network jitter doesn't chop it. Nothing plays until
 * [prebufferBytes] have arrived (about 120 ms); once playing, it plays until it runs dry and then waits for that much
 * again. If it ever holds more than [maxBytes] (a stall that then delivered in a burst), the oldest audio is dropped so
 * the call doesn't fall behind. Thread-safe.
 */
class JitterBuffer(
    private val prebufferBytes: Int = CallWire.PCM_BYTES_PER_MS * 120,
    private val maxBytes: Int = CallWire.PCM_BYTES_PER_MS * 1000,
) {
    private val chunks = ArrayDeque<ByteArray>()
    private var queued = 0
    private var primed = false
    var dropped = 0L
        private set

    @Synchronized fun offer(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        chunks.addLast(chunk)
        queued += chunk.size
        while (queued > maxBytes && chunks.size > 1) {
            val old = chunks.removeFirst()
            queued -= old.size
            dropped += old.size
        }
        if (queued >= prebufferBytes) primed = true
    }

    /** The next chunk to play, or null while filling or when empty (which starts filling again). */
    @Synchronized fun poll(): ByteArray? {
        if (!primed) return null
        val c = chunks.removeFirstOrNull()
        if (c == null) {
            primed = false
            return null
        }
        queued -= c.size
        return c
    }

    @Synchronized fun queuedBytes() = queued
    @Synchronized fun clear() {
        chunks.clear()
        queued = 0
        primed = false
    }
}

object Pcm {
    /** Loudness of 16-bit little-endian samples, 0..1 (RMS, scaled so ordinary speech reaches about 0.5). */
    fun level(pcm: ByteArray): Float {
        val n = pcm.size / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
        }
        val rms = Math.sqrt(sum / n) / 32768.0
        return (rms * 4).coerceIn(0.0, 1.0).toFloat()
    }

    /** Mono 16-bit samples as little-endian bytes (tests and the tone generator). */
    fun bytes(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { b ->
        for (i in samples.indices) {
            b[2 * i] = samples[i].toInt().toByte()
            b[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
    }
}
