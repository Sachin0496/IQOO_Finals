package app.mouna.app

import app.mouna.app.engine.CallPhrases
import app.mouna.app.engine.Lang
import app.mouna.app.ui.TapCounter
import app.mouna.app.ui.guestLine
import app.mouna.app.ui.linkHostSize
import app.mouna.app.ui.linkLines
import app.mouna.app.ui.previewName
import app.mouna.core.DecisionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCallTest {
    @Test
    fun saidStaysLongEnoughToReadThenLeaves() {
        assertTrue(SaidRules.SHOW_MS >= 4_000)
        assertFalse(SaidRules.expired(1_000, 1_000 + SaidRules.SHOW_MS - 1))
        assertTrue(SaidRules.expired(1_000, 1_000 + SaidRules.SHOW_MS))
    }

    @Test
    fun gesturesWaitAboutASecond() {
        assertTrue(GESTURE_ARM_MS in 800..1500)
    }

    @Test
    fun sevenQuickTapsUnlock() {
        val t = TapCounter()
        var left = -1
        for (i in 0 until 7) left = t.tap(i * 300L)
        assertEquals(0, left)
    }

    @Test
    fun aPauseStartsTheCountAgain() {
        val t = TapCounter()
        repeat(5) { t.tap(it * 300L) }
        assertEquals(6, t.tap(5 * 300L + 5_000)) // too slow: back to the first tap
    }

    @Test
    fun tapsCountDown() {
        val t = TapCounter()
        assertEquals(6, t.tap(0))
        assertEquals(5, t.tap(200))
        assertEquals(4, t.tap(400))
    }

    @Test
    fun unlockingStartsTheCountOver() {
        val t = TapCounter()
        repeat(7) { t.tap(it * 100L) }
        assertEquals(6, t.tap(800))
    }

    @Test
    fun linkSplitsAtTheRoom() {
        assertEquals("strand-extent-category-tiffany.trycloudflare.com" to "/c/K7M2QX", linkLines("strand-extent-category-tiffany.trycloudflare.com/c/K7M2QX"))
        assertEquals("example.com" to "", linkLines("example.com"))
    }

    @Test
    fun longServerNamesShrinkButStayReadable() {
        assertEquals(15, linkHostSize(20))
        assertTrue(linkHostSize(48) in 10..12)
        assertEquals(10, linkHostSize(200))
    }

    @Test
    fun guestLineUsesTheName() {
        assertEquals("Amma is speaking", guestLine("Amma", true))
        assertEquals("Amma is listening", guestLine("Amma", false))
        assertEquals("They're speaking", guestLine(null, true))
        assertEquals("They're listening", guestLine("  ", false))
    }

    @Test
    fun longerPhrasesGetSmallerTypeButNeverTiny() {
        val sizes = CallPhrases.quick.flatMap { q -> Lang.entries.map { CallPhrases.quickSize(q.say(it)) } }
        assertTrue(sizes.all { it in 16..22 })
        assertTrue(CallPhrases.quickSize("ஆம்") > CallPhrases.quickSize("தயவுசெய்து மீண்டும் சொல்லுங்கள்"))
    }

    @Test
    fun previewsHavePlainNames() {
        assertEquals("Did-you-mean prompt", previewName(DecisionKind.CONFIRM))
        assertEquals("Two look alike", previewName(DecisionKind.RESCUE))
        assertEquals("Choose from a few", previewName(DecisionKind.CHOOSE))
        assertEquals("Not one of my phrases", previewName(DecisionKind.NOT_TAUGHT))
    }
}
