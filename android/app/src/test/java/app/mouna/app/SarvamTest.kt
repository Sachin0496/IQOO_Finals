package app.mouna.app

import app.mouna.app.engine.Lang
import app.mouna.app.engine.Sarvam
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.util.Base64

class SarvamTest {
    private val req = Sarvam.Request("Hello there", Lang.HI, "anand")

    @Test
    fun requestHasTheFieldsSarvamExpects() {
        val o = JSONObject(Sarvam.requestJson(req))
        assertEquals("Hello there", o.getString("text"))
        assertEquals("hi-IN", o.getString("target_language_code"))
        assertEquals("anand", o.getString("speaker"))
        assertEquals("bulbul:v3", o.getString("model"))
        assertEquals(0.9, o.getDouble("pace"), 1e-9)
    }

    @Test
    fun speakerMapsKnownVoicesAndDefaultsToKavitha() {
        assertEquals("anand", Sarvam.speakerFor("anand"))
        assertEquals("kavitha", Sarvam.speakerFor("kavitha"))
        assertEquals("kavitha", Sarvam.speakerFor("anu"))
        assertEquals("kavitha", Sarvam.speakerFor("device"))
    }

    @Test
    fun parsesTheFirstAudio() {
        val wav = byteArrayOf(1, 2, 3, 4)
        val body = JSONObject().put("audios", listOf(Base64.getEncoder().encodeToString(wav), "ignored")).toString()
        assertArrayEquals(wav, Sarvam.parseAudio(body))
    }

    @Test
    fun badAnswersGiveNull() {
        assertNull(Sarvam.parseAudio("""{"audios": []}"""))
        assertNull(Sarvam.parseAudio("""{"error": "bad key"}"""))
        assertNull(Sarvam.parseAudio("not json"))
    }

    @Test
    fun cacheKeyIsStableAndSeparatesLanguageSpeakerAndText() {
        assertEquals(Sarvam.cacheKey(req), Sarvam.cacheKey(req.copy()))
        assertEquals(40, Sarvam.cacheKey(req).length)
        assertNotEquals(Sarvam.cacheKey(req), Sarvam.cacheKey(req.copy(lang = Lang.EN)))
        assertNotEquals(Sarvam.cacheKey(req), Sarvam.cacheKey(req.copy(speaker = "kavitha")))
        assertNotEquals(Sarvam.cacheKey(req), Sarvam.cacheKey(req.copy(text = "Hello there.")))
    }

    /** A one-purpose HTTP server on loopback (the JVM's HttpServer is not on the Android test classpath). */
    private class FakeServer(private val status: Int, private val body: String) : AutoCloseable {
        private val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${socket.localPort}/tts"
        @Volatile var hits = 0
        @Volatile var key: String? = null
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                c.use {
                    val r = it.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("api-subscription-key:", true)) key = line.substringAfter(":").trim()
                        if (line.startsWith("content-length:", true)) length = line.substringAfter(":").trim().toInt()
                    }
                    r.skip(length.toLong())
                    hits++
                    val bytes = body.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        override fun close() = socket.close()
    }

    @Test
    fun fetchAsksOnceThenServesFromTheCache() {
        val wav = "RIFFfake".toByteArray()
        val dir = Files.createTempDirectory("sarvam").toFile()
        FakeServer(200, JSONObject().put("audios", listOf(Base64.getEncoder().encodeToString(wav))).toString()).use { server ->
            try {
                val f = Sarvam.fetch("secret", req, dir, server.url)
                assertArrayEquals(wav, f.readBytes())
                assertEquals("secret", server.key)
                Sarvam.fetch("secret", req, dir, server.url)
                assertEquals(1, server.hits)
                assertTrue(Sarvam.cacheFile(dir, req).exists())
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun aRejectedKeyThrowsWithoutCachingAndWithoutLeakingTheKey() {
        val dir = Files.createTempDirectory("sarvam").toFile()
        FakeServer(403, "{}").use { server ->
            try {
                Sarvam.fetch("topsecret", req, dir, server.url)
                fail("expected a failure")
            } catch (e: Sarvam.SarvamException) {
                assertFalse(e.message!!.contains("topsecret"))
                assertFalse(Sarvam.cacheFile(dir, req).exists())
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
