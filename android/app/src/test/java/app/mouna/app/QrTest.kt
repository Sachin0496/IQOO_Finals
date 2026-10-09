package app.mouna.app

import app.mouna.app.engine.Qr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    @Test
    fun joinLinkMakesASquareCodeWithFinderPatterns() {
        val m = Qr.matrix("https://calm-river.trycloudflare.com/c/K7M2QX")
        assertEquals(m.width, m.height)
        assertTrue("a version 3 code or larger for ~45 characters", m.width >= 29)
        // The three 7x7 finder squares: dark outer ring in the top-left, top-right and bottom-left corners.
        for ((ox, oy) in listOf(0 to 0, m.width - 7 to 0, 0 to m.height - 7)) {
            for (i in 0 until 7) {
                assertTrue(m[ox + i, oy]); assertTrue(m[ox + i, oy + 6]); assertTrue(m[ox, oy + i]); assertTrue(m[ox + 6, oy + i])
            }
            assertTrue(m[ox + 3, oy + 3]) // centre
            assertTrue(!m[ox + 1, oy + 1]) // light ring inside
        }
    }

    @Test
    fun sameLinkSameCode() {
        val a = Qr.matrix("https://x.example/c/AAAAAA")
        val b = Qr.matrix("https://x.example/c/AAAAAA")
        assertEquals(a, b)
    }
}
