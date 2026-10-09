package app.mouna.app

import app.mouna.app.engine.VoiceMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMatcherTest {
    private val pack = mapOf(
        "water" to listOf("I need water"),
        "pain" to listOf("I am in pain"),
        "nurse" to listOf("Please call the nurse"),
        "love" to listOf("I love you"),
    )

    @Test
    fun clearSpeechMatchesItsPhrase() {
        val r = VoiceMatcher.rank("I need water.", pack)
        assertEquals("water", r[0].id)
        assertTrue(r[0].score >= VoiceMatcher.SPEAK)
        assertTrue(r[0].score - r[1].score >= VoiceMatcher.MARGIN)
    }

    @Test
    fun misheardWordsStillLandNearby() {
        assertEquals("water", VoiceMatcher.rank("need wadder", pack)[0].id)
        assertEquals("nurse", VoiceMatcher.rank("call the nurs", pack)[0].id)
    }

    @Test
    fun personalTemplatesBeatTheTextbook() {
        // Whisper hears this person's "water" as "what a" every time; once taught, that is a clear match.
        val taught = pack + ("water" to listOf("I need water", "eye knee what a"))
        val r = VoiceMatcher.rank("eye knee what a", taught)
        assertEquals("water", r[0].id)
        assertTrue(r[0].score >= VoiceMatcher.SPEAK)
    }

    @Test
    fun unrelatedSpeechIsNotForcedOntoAPhrase() {
        val r = VoiceMatcher.rank("what time is the cricket match tonight", pack)
        assertTrue(r[0].score < VoiceMatcher.SPEAK)
    }

    @Test
    fun measuredOnTheEmulator() {
        // Real Whisper outputs from macOS `say` recordings run through the app on the emulator (7 Oct).
        assertEquals("water", VoiceMatcher.rank("I need, watch her.", pack)[0].id) // slow, effortful "I need water"
        val r = VoiceMatcher.rank("Can you open the window please?", pack)
        assertTrue("filler words must not match: ${r[0]}", r[0].score < VoiceMatcher.SHOW)
    }
}
