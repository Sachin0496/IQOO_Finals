package app.mouna.app

import app.mouna.app.engine.CallWire
import app.mouna.app.engine.JitterBuffer
import app.mouna.app.engine.Pcm
import app.mouna.app.engine.Rooms
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CallWireTest {
    @Test
    fun roomIdsAreSixUnambiguousCharacters() {
        val rnd = Random(7)
        repeat(300) {
            val id = Rooms.newId(rnd)
            assertEquals(6, id.length)
            assertTrue(id.all { it in Rooms.ALPHABET })
            assertEquals(id, Rooms.normalise(id))
        }
        assertTrue(Rooms.ALPHABET.none { it in "ILO01" })
        assertEquals("K7M2QX", Rooms.normalise(" k7m2qx "))
        assertNull(Rooms.normalise("K7M"))
        assertNull(Rooms.normalise("K7M2Q!"))
        assertNull(Rooms.normalise("K7M2Q0")) // 0 is not in the alphabet
        assertFalse(Rooms.newId() == Rooms.newId())
    }

    @Test
    fun serverAddressesBecomeLinks() {
        assertEquals("https://calm.trycloudflare.com", Rooms.base(" calm.trycloudflare.com/ "))
        assertEquals("http://192.168.1.5:8787", Rooms.base("http://192.168.1.5:8787/"))
        assertEquals("", Rooms.base("  "))
        assertEquals("wss://calm.trycloudflare.com/ws?room=K7M2QX&role=mouna", Rooms.wsUrl("https://calm.trycloudflare.com", "K7M2QX"))
        assertEquals("ws://192.168.1.5:8787/ws?room=K7M2QX&role=guest", Rooms.wsUrl("http://192.168.1.5:8787", "K7M2QX", "guest"))
        assertEquals("https://calm.trycloudflare.com/c/K7M2QX", Rooms.joinUrl("calm.trycloudflare.com/", "K7M2QX"))
        assertEquals("calm.trycloudflare.com/c/K7M2QX", Rooms.shortUrl("https://calm.trycloudflare.com", "K7M2QX"))
    }

    @Test
    fun audioFramesStartWithAKindByte() {
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3)
        val f = CallWire.audioFrame("audio/wav", wav)
        assertEquals(1, f[0].toInt())
        assertEquals(wav.size + 1, f.size)
        val back = CallWire.parseBinary(f) as CallWire.Binary.Audio
        assertEquals("audio/wav", back.mime)
        assertArrayEquals(wav, back.data)

        val m4a = CallWire.audioFrame("audio/mp4", byteArrayOf(9, 9, 9))
        assertEquals(2, m4a[0].toInt())
        assertEquals("audio/mp4", (CallWire.parseBinary(m4a) as CallWire.Binary.Audio).mime)
    }

    @Test
    fun guestPcmFramesAreParsed() {
        val pcm = ByteArray(1280) { (it % 7).toByte() }
        val f = CallWire.pcmFrame(pcm)
        assertEquals(0x10, f[0].toInt())
        assertArrayEquals(pcm, (CallWire.parseBinary(f) as CallWire.Binary.Pcm).data)
        // an odd trailing byte can't be a sample
        assertEquals(2, (CallWire.parseBinary(byteArrayOf(0x10, 1, 2, 3)) as CallWire.Binary.Pcm).data.size)
    }

    @Test
    fun junkIsIgnored() {
        assertNull(CallWire.parseBinary(ByteArray(0)))
        assertNull(CallWire.parseBinary(byteArrayOf(1)))
        assertNull(CallWire.parseBinary(byteArrayOf(0x7F, 1, 2)))
    }

    @Test
    fun textMessagesRoundTrip() {
        val say = JSONObject(CallWire.say("I'll be there at five", "en"))
        assertEquals("say", say.getString("t"))
        assertEquals("I'll be there at five", say.getString("text"))
        assertEquals("Ravi", JSONObject(CallWire.hello("Ravi")).getString("name"))
        assertEquals(CallWire.Msg.Peer(true), CallWire.parseText("""{"t":"peer","joined":true}"""))
        assertEquals(CallWire.Msg.Peer(false), CallWire.parseText("""{"t":"peer","joined":false}"""))
        assertEquals(CallWire.Msg.Answered, CallWire.parseText("""{"t":"answered"}"""))
        assertEquals(CallWire.Msg.Bye, CallWire.parseText(CallWire.bye()))
        assertEquals(CallWire.Msg.Other("future"), CallWire.parseText("""{"t":"future","x":1}"""))
        assertNull(CallWire.parseText("not json"))
        assertNull(CallWire.parseText("""{"no":"t"}"""))
    }

    @Test
    fun levelFollowsLoudness() {
        assertEquals(0f, Pcm.level(ByteArray(0)), 0f)
        assertEquals(0f, Pcm.level(ByteArray(640)), 0f)
        val quiet = Pcm.bytes(ShortArray(320) { (500 * Math.sin(it * 0.3)).toInt().toShort() })
        val loud = Pcm.bytes(ShortArray(320) { (12000 * Math.sin(it * 0.3)).toInt().toShort() })
        assertTrue(Pcm.level(quiet) < Pcm.level(loud))
        assertTrue(Pcm.level(loud) in 0.1f..1f)
        assertEquals(1f, Pcm.level(Pcm.bytes(ShortArray(100) { Short.MAX_VALUE })), 0f)
        // the sign of a sample does not matter
        assertEquals(Pcm.level(Pcm.bytes(ShortArray(10) { 9000 })), Pcm.level(Pcm.bytes(ShortArray(10) { -9000 })), 1e-6f)
    }

    private fun ms(n: Int) = ByteArray(n * CallWire.PCM_BYTES_PER_MS)

    @Test
    fun jitterBufferWaitsForAPrebuffer() {
        val j = JitterBuffer(prebufferBytes = 120 * CallWire.PCM_BYTES_PER_MS)
        j.offer(ms(40))
        j.offer(ms(40))
        assertNull(j.poll()) // 80 ms: still filling
        j.offer(ms(40))
        assertNotNull(j.poll()) // 120 ms: plays
        assertNotNull(j.poll())
        assertNotNull(j.poll())
    }

    @Test
    fun jitterBufferRefillsAfterRunningDry() {
        val j = JitterBuffer(prebufferBytes = 120 * CallWire.PCM_BYTES_PER_MS)
        repeat(3) { j.offer(ms(40)) }
        repeat(3) { assertNotNull(j.poll()) }
        assertNull(j.poll()) // dry: goes back to filling
        j.offer(ms(40))
        assertNull(j.poll()) // one frame is not enough again
        j.offer(ms(40)); j.offer(ms(40))
        assertNotNull(j.poll())
    }

    @Test
    fun jitterBufferDropsTheOldestWhenItFallsFarBehind() {
        val j = JitterBuffer(prebufferBytes = 120 * CallWire.PCM_BYTES_PER_MS, maxBytes = 400 * CallWire.PCM_BYTES_PER_MS)
        repeat(15) { i -> j.offer(ByteArray(40 * CallWire.PCM_BYTES_PER_MS) { i.toByte() }) } // 600 ms
        assertTrue(j.queuedBytes() <= 400 * CallWire.PCM_BYTES_PER_MS)
        assertTrue(j.dropped > 0)
        assertEquals(5, j.poll()!![0].toInt()) // the first five frames are gone; the newest audio is kept
    }

    @Test
    fun jitterBufferClearStartsOver() {
        val j = JitterBuffer(prebufferBytes = 64)
        j.offer(ByteArray(100))
        j.clear()
        assertEquals(0, j.queuedBytes())
        assertNull(j.poll())
    }
}
