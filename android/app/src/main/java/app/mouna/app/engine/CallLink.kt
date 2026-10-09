package app.mouna.app.engine

enum class CallState { IDLE, DIALING, ACTIVE }

/**
 * How a call reaches the other person. The app only talks to this interface, so the transport can change:
 * [CarrierLink] puts a normal phone call on speakerphone and Mouna's voice goes out of the speaker into the phone's
 * microphone; an internet transport can take the rendered audio and send it straight into the call.
 */
interface CallLink {
    val state: CallState

    /** Called on the main thread whenever [state] changes. */
    var onState: (CallState) -> Unit

    /** True if this transport wants Mouna's rendered audio ([sendAudio]) instead of playing it on the speaker. */
    val takesAudio: Boolean

    /** Starts a call to [target] (a phone number for the carrier). False if it could not be started. */
    fun dial(target: String): Boolean

    /** Ends the call. False if the transport could not (the person must then end it on the phone). */
    fun hangUp(): Boolean

    /**
     * Sends [data] (WAV, or "audio/mp4" for the pre-rendered pack) into the call. Only used when [takesAudio];
     * returns true if the transport took it, false to let Voice play it on the speaker.
     */
    fun sendAudio(data: ByteArray, mime: String): Boolean = false
}
