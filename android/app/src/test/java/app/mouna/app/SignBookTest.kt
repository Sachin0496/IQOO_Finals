package app.mouna.app

import app.mouna.app.engine.SignBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

class SignBookTest {
    /** A unit vector in the plane of axes 0 and [axis], [deg] degrees from axis 0 (cosine to axis 0 = cos(deg)). */
    private fun v(deg: Double, axis: Int = 1, dims: Int = 8) = FloatArray(dims).also {
        it[0] = cos(Math.toRadians(deg)).toFloat()
        it[axis] = sin(Math.toRadians(deg)).toFloat()
    }

    private fun axis(i: Int, dims: Int = 8) = FloatArray(dims).also { it[i] = 1f }

    @Test
    fun matchesNearestTaughtSignBestFirst() {
        val b = SignBook(null)
        val water = b.add("Water")
        val pain = b.add("Pain")
        b.teach(water, axis(0))
        b.teach(pain, axis(1))
        val m = b.match(v(20.0))
        assertEquals(listOf("Water", "Pain"), m.map { it.text })
        assertEquals(cos(Math.toRadians(20.0)).toFloat(), m[0].cos, 1e-5f)
    }

    @Test
    fun scaleDoesNotMatter() {
        val b = SignBook(null)
        val id = b.add("Water")
        b.teach(id, FloatArray(8).also { it[0] = 7f })
        assertEquals(1f, b.match(FloatArray(8).also { it[0] = 0.01f })[0].cos, 1e-6f)
    }

    @Test
    fun speaksOnlyAClearMatchOtherwiseOffers() {
        val b = SignBook(null)
        val water = b.add("Water")
        val pain = b.add("Pain")
        b.teach(water, axis(0))
        b.teach(pain, axis(1))
        // cos 0.996 to Water, ~0.09 to Pain: clear
        assertTrue(b.decide(v(5.0)) is SignBook.Decision.Speak)
        // cos 0.82 to Water: below SPEAK_COS, offered with Pain
        val d = b.decide(v(35.0))
        assertTrue(d is SignBook.Decision.Offer)
        assertEquals(listOf("Water", "Pain"), (d as SignBook.Decision.Offer).matches.map { it.text })
        // two taught signs almost alike: high cosine but no margin, offered
        val near = b.add("Near")
        b.teach(near, v(4.0))
        assertTrue(b.decide(v(2.0)) is SignBook.Decision.Offer)
    }

    @Test
    fun nothingTaughtNoDecision() {
        val b = SignBook(null)
        b.add("Untaught")
        assertNull(b.decide(axis(0)))
    }

    @Test
    fun keepsAtMostMaxShotsNewestLast() {
        val b = SignBook(null)
        val id = b.add("Water")
        repeat(SignBook.MAX_SHOTS + 2) { b.teach(id, axis(0)) }
        assertEquals(SignBook.MAX_SHOTS, b.count(id))
    }

    @Test
    fun savesAndLoadsRemovesAndClears() {
        val f = File(Files.createTempDirectory("signs").toFile(), "person/signs.json")
        val b = SignBook(f)
        val water = b.add("Water")
        val pain = b.add("Pain")
        b.teach(water, axis(0))
        b.teach(water, v(10.0))
        b.teach(pain, axis(2))
        val again = SignBook(f)
        assertEquals(listOf("Water" to 2, "Pain" to 1), again.signs.map { it.text to it.examples.size })
        assertEquals("Water", again.match(v(8.0))[0].text)
        again.remove(water)
        assertEquals(listOf("Pain"), SignBook(f).signs.map { it.text })
        again.clear()
        assertTrue(SignBook(f).signs.isEmpty())
        val ids = List(5) { again.add("same millisecond") }
        assertEquals(5, ids.toSet().size) // ids stay unique even when added together
    }
}
