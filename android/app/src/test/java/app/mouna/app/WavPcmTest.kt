package app.mouna.app

import app.mouna.app.engine.WavPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {
    private fun wav(rate: Int, channels: Int, bits: Int, format: Int, pcm: ByteArray, dataSize: Int = pcm.size, extra: ByteArray? = null): ByteArray {
        val extraChunk = extra?.let { 8 + it.size + (it.size and 1) } ?: 0
        val b = ByteBuffer.allocate(12 + 24 + extraChunk + 8 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extra != null) {
            b.put("LIST".toByteArray()).putInt(extra.size).put(extra)
            if (extra.size and 1 == 1) b.put(0)
        }
        b.put("data".toByteArray()).putInt(dataSize).put(pcm)
        return b.array()
    }

    @Test
    fun readsMono16Bit() {
        val pcm = ByteArray(48) { it.toByte() }
        val w = WavPcm.parse(wav(24_000, 1, 16, 1, pcm))!!
        assertEquals(24_000, w.sampleRate)
        assertEquals(1, w.channels)
        assertArrayEquals(pcm, w.pcm)
    }

    @Test
    fun skipsOtherChunksIncludingOddPadding() {
        val pcm = ByteArray(8) { 7 }
        val w = WavPcm.parse(wav(22_050, 1, 16, 1, pcm, extra = byteArrayOf(1, 2, 3)))!!
        assertArrayEquals(pcm, w.pcm)
    }

    @Test
    fun streamedFileWithZeroOrOversizedDataLengthTakesWhatIsThere() {
        val pcm = ByteArray(10) { 3 }
        assertArrayEquals(pcm, WavPcm.parse(wav(16_000, 1, 16, 1, pcm, dataSize = 0))!!.pcm)
        assertArrayEquals(pcm, WavPcm.parse(wav(16_000, 1, 16, 1, pcm, dataSize = 1_000_000))!!.pcm)
    }

    @Test
    fun refusesWhatTheEncoderCannotTake() {
        assertNull(WavPcm.parse(wav(16_000, 1, 8, 1, ByteArray(4)))) // 8-bit
        assertNull(WavPcm.parse(wav(16_000, 1, 32, 3, ByteArray(8)))) // float
        assertNull(WavPcm.parse(ByteArray(40))) // not a WAV
        assertNull(WavPcm.parse("RIFF".toByteArray())) // truncated
    }
}
