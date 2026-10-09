package app.mouna.app.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The PCM inside a WAV file: 16-bit little-endian samples, interleaved when there is more than one channel. */
class WavPcm(val sampleRate: Int, val channels: Int, val pcm: ByteArray) {
    companion object {
        /** Reads the "fmt " and "data" chunks of a 16-bit PCM WAV; null for anything else. Pure, so it is unit-tested. */
        fun parse(wav: ByteArray): WavPcm? {
            if (wav.size < 12 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12
            var rate = 0
            var channels = 0
            var bits = 0
            var format = 0
            while (at + 8 <= wav.size) {
                val id = String(wav, at, 4, Charsets.US_ASCII)
                val size = b.getInt(at + 4)
                val body = at + 8
                if (size < 0) return null
                when (id) {
                    "fmt " -> if (body + 16 <= wav.size) {
                        format = b.getShort(body).toInt() and 0xffff
                        channels = b.getShort(body + 2).toInt()
                        rate = b.getInt(body + 4)
                        bits = b.getShort(body + 14).toInt()
                    }
                    // Some writers (TTS engines that stream) leave the size at 0 or too big: take what is there.
                    "data" -> {
                        if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                        val end = if (size == 0 || body + size > wav.size) wav.size else body + size
                        return WavPcm(rate, channels, wav.copyOfRange(body, end))
                    }
                }
                at = body + size + (size and 1)
            }
            return null
        }
    }
}

/**
 * Re-encodes a sentence as AAC in an MP4 (m4a) before it goes into a web call: about seven times smaller than the WAV
 * that Sarvam and the phone's voice produce, so it reaches the other side in a fraction of the time on a slow venue
 * uplink. The guest page already plays m4a (the voice pack's clips are m4a). Null means "send the WAV as it is".
 */
object Aac {
    private const val TAG = "MounaVoice"
    private const val BITRATE = 48_000
    private const val TIMEOUT_US = 10_000L

    fun fromWav(wav: ByteArray, tmp: File): ByteArray? {
        val src = WavPcm.parse(wav) ?: return null
        if (src.pcm.isEmpty()) return null
        val t0 = System.nanoTime()
        return runCatching { encode(src, tmp) }
            .onSuccess { Log.i(TAG, "call audio: ${wav.size / 1024} KB wav -> ${it.size / 1024} KB m4a in ${(System.nanoTime() - t0) / 1_000_000} ms") }
            .onFailure { Log.w(TAG, "call audio: AAC encode failed, sending the WAV", it) }
            .getOrNull()
            .also { tmp.delete() }
    }

    private fun encode(src: WavPcm, tmp: File): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, src.sampleRate, src.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytesPerFrame = 2 * src.channels
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var track = -1
            var muxing = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.remaining() / bytesPerFrame * bytesPerFrame, src.pcm.size - fed)
                        val us = fed.toLong() / bytesPerFrame * 1_000_000L / src.sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(src.pcm, fed, n)
                            codec.queueInputBuffer(i, 0, n, us, 0)
                            fed += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && muxing) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(track, out, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (muxing) muxer.stop()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.release() }
        }
        return tmp.readBytes()
    }
}
