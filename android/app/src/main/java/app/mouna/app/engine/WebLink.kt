package app.mouna.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** Where a web call is, finer than [CallState]: what the screen says while the guest has not answered yet. */
enum class WebPhase { OFF, CONNECTING, WAITING, GUEST_OPENED, ACTIVE, RECONNECTING }

/**
 * A call over the internet. [dial] takes a room id and connects out to the call relay (call-server/) over WSS; the
 * other person opens the room's link in any browser. Mouna's rendered voice goes to them as audio messages with a
 * caption, theirs comes back as PCM that [GuestAudio] plays on the speaker. The call is [CallState.DIALING] until the
 * guest taps Answer, then [CallState.ACTIVE]. If the socket drops it reconnects with backoff for up to [GIVE_UP_MS].
 * Everything but the socket callbacks runs on the main thread; wire format is [CallWire].
 */
class WebLink(context: Context, private val http: OkHttpClient = client()) : CallLink {
    override var state = CallState.IDLE
        private set
    override var onState: (CallState) -> Unit = {}
    override val takesAudio = true

    /** The relay's address now (Settings or the build); read at each [dial]. */
    var server: () -> String = { "" }
    /** Said in the guest's "X is calling you". */
    var callerName: () -> String = { "" }

    private val _phase = MutableStateFlow(WebPhase.OFF)
    val phase: StateFlow<WebPhase> = _phase
    private val _room = MutableStateFlow<String?>(null)
    val room: StateFlow<String?> = _room
    private val _note = MutableStateFlow<String?>(null)
    /** Why the last call ended or could not start, in words for the screen. */
    val note: StateFlow<String?> = _note

    private val guest = GuestAudio(context)
    /** How loud the guest is, 0..1. */
    val guestLevel: StateFlow<Float> get() = guest.level

    private val main = Handler(Looper.getMainLooper())
    private var base = ""
    private var socket: WebSocket? = null
    private var open = false
    private var generation = 0 // callbacks from an older socket are ignored
    private var attempts = 0
    private var offlineSince = 0L
    private var guestHere = false
    private var answered = false

    /** The link to give the guest, or null when there is no call. */
    val joinUrl: String? get() = _room.value?.let { Rooms.joinUrl(base, it) }
    val shortUrl: String? get() = _room.value?.let { Rooms.shortUrl(base, it) }

    override fun dial(target: String): Boolean {
        if (state != CallState.IDLE) return false
        val room = Rooms.normalise(target)
        val url = Rooms.base(server())
        if (room == null) { _note.value = "That is not a call link."; return false }
        if (url.isEmpty()) { _note.value = "Set the call server in Settings first."; return false }
        base = url
        _room.value = room
        _note.value = null
        guestHere = false
        answered = false
        attempts = 0
        offlineSince = 0L
        _phase.value = WebPhase.CONNECTING
        set(CallState.DIALING)
        connect()
        return true
    }

    override fun hangUp(): Boolean {
        socket?.runCatching { send(CallWire.bye()) }
        end(null)
        return true
    }

    override fun sendText(text: String) {
        if (open && answered) socket?.send(CallWire.say(text))
    }

    override fun sendAudio(data: ByteArray, mime: String): Boolean {
        val s = socket
        if (!open || !answered || s == null) return false
        return s.send(CallWire.audioFrame(mime, data).toByteString())
    }

    // ---------------- socket ----------------

    private fun connect() {
        val gen = ++generation
        val req = Request.Builder().url(Rooms.wsUrl(base, _room.value!!)).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (gen == generation) opened() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                CallWire.parseText(text)?.let { m -> main.post { if (gen == generation) onMsg(m) } }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen != generation) return
                // Straight to the speaker, off the main thread: this is the live voice.
                val f = CallWire.parseBinary(bytes.toByteArray())
                if (f is CallWire.Binary.Pcm) guest.write(f.data)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation) dropped(code, reason) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "web socket failed: ${t.javaClass.simpleName} ${t.message}")
                main.post { if (gen == generation) dropped(-1, t.message.orEmpty()) }
            }
        })
    }

    private fun opened() {
        open = true
        attempts = 0
        offlineSince = 0L
        Log.i(TAG, "web call: connected to the relay")
        if (_phase.value == WebPhase.CONNECTING || _phase.value == WebPhase.RECONNECTING) {
            _phase.value = if (answered) WebPhase.ACTIVE else WebPhase.WAITING
        }
    }

    private fun onMsg(m: CallWire.Msg) {
        when (m) {
            is CallWire.Msg.Peer -> {
                guestHere = m.joined
                Log.i(TAG, "web call: guest ${if (m.joined) "opened the link" else "left"}")
                if (m.joined) {
                    socket?.send(CallWire.hello(callerName()))
                    if (!answered) _phase.value = WebPhase.GUEST_OPENED
                } else {
                    answered = false
                    if (state == CallState.ACTIVE) {
                        guest.stop()
                        set(CallState.DIALING)
                    }
                    _phase.value = WebPhase.WAITING
                }
            }
            CallWire.Msg.Answered -> if (!answered) {
                answered = true
                Log.i(TAG, "web call: guest answered")
                _phase.value = WebPhase.ACTIVE
                guest.start()
                set(CallState.ACTIVE)
            }
            CallWire.Msg.Bye -> end("The other person hung up.")
            is CallWire.Msg.Other -> Unit
        }
    }

    /** The socket closed or failed: a wrong room or a taken one ends the call, anything else is retried. */
    private fun dropped(code: Int, reason: String) {
        open = false
        if (state == CallState.IDLE) return
        when (code) {
            CLOSE_BAD_ROOM -> return end("The call link was refused.")
            CLOSE_FULL -> return end("This call link is already in use. Hang up and start again in a minute.")
        }
        val now = SystemClock.elapsedRealtime()
        if (offlineSince == 0L) offlineSince = now
        if (now - offlineSince > GIVE_UP_MS) return end("Lost the connection to the call server.")
        if (_phase.value != WebPhase.RECONNECTING) _phase.value = WebPhase.RECONNECTING
        val wait = minOf(1000L shl attempts.coerceAtMost(3), 8000L)
        attempts++
        Log.i(TAG, "web call: socket lost ($code $reason), retry #$attempts in $wait ms")
        val gen = generation
        main.postDelayed({ if (gen == generation && state != CallState.IDLE) connect() }, wait)
    }

    private fun end(why: String?) {
        generation++ // drops late callbacks
        open = false
        socket?.runCatching { close(1000, "bye") }
        socket = null
        guest.stop()
        guestHere = false
        answered = false
        _phase.value = WebPhase.OFF
        _room.value = null
        if (why != null) _note.value = why
        set(CallState.IDLE)
    }

    private fun set(s: CallState) {
        if (s == state) return
        state = s
        onState(s)
    }

    companion object {
        private const val TAG = "MounaCall"
        private const val CLOSE_BAD_ROOM = 4400
        private const val CLOSE_FULL = 4409
        /** Keep trying to get back for this long before the call is given up. */
        const val GIVE_UP_MS = 90_000L

        /** A socket that notices a dead network: OkHttp pings every 20 s and fails the socket if no pong. */
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
