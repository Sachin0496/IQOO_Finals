package app.mouna.app

import app.mouna.app.engine.CallPhrases
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Phones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPhrasesTest {
    @Test
    fun introNamesTheCallerAndAsksForYesNoQuestions() {
        assertEquals(
            "Hello, this is Ravi speaking through the Mouna app. I can't talk, but I can hear you. Please ask me yes or no questions.",
            CallPhrases.intro("Ravi", Lang.EN),
        )
    }

    @Test
    fun introWithoutANameStillWorks() {
        assertEquals(
            "Hello, I'm speaking through the Mouna app. I can't talk, but I can hear you. Please ask me yes or no questions.",
            CallPhrases.intro("  ", Lang.EN),
        )
    }

    @Test
    fun everyLanguageHasEveryQuickPhraseAndAnIntro() {
        for (l in Lang.entries) {
            assertTrue(CallPhrases.intro("Ravi", l).contains("Ravi"))
            for (q in CallPhrases.quick) assertTrue("${q.id} in ${l.tag}", q.say(l).isNotBlank())
        }
        assertEquals(listOf("Yes", "No", "Please repeat", "Wait a moment", "Speak slowly please", "I'll message you", "Thank you", "Bye"), CallPhrases.quick.map { it.say(Lang.EN) })
    }

    @Test
    fun warmListSkipsWhatThePackAlreadyHas() {
        val w = CallPhrases.warm(Lang.EN, "Ravi")
        assertFalse(w.contains("Yes"))
        assertTrue(w.contains("Wait a moment"))
        assertTrue(w.last().startsWith("Hello, this is Ravi"))
    }

    @Test
    fun typedScriptPicksTheLanguage() {
        assertEquals(Lang.HI, CallPhrases.langOf("मुझे पानी चाहिए", Lang.EN))
        assertEquals(Lang.TA, CallPhrases.langOf("நன்றி", Lang.EN))
        assertEquals(Lang.EN, CallPhrases.langOf("I'll be there at five", Lang.EN))
        assertEquals(Lang.HI, CallPhrases.langOf("ok", Lang.HI))
    }

    @Test
    fun numbersAreCleanedForDialing() {
        assertEquals("9876543210", Phones.normalise("98765 43210"))
        assertEquals("+919876543210", Phones.normalise("+91 (98765) 43-210"))
        assertEquals("112", Phones.normalise(" 112 "))
        assertEquals("*123#", Phones.normalise("*123#"))
        assertEquals("919876543210", Phones.normalise("91+9876543210")) // a plus only means something at the start
        assertNull(Phones.normalise(""))
        assertNull(Phones.normalise("ab"))
        assertNull(Phones.normalise("12"))
    }

    @Test
    fun numbersAreGroupedForReading() {
        assertEquals("98765 43210", Phones.pretty("9876543210"))
        assertEquals("+91 98765 43210", Phones.pretty("+919876543210"))
        assertEquals("112", Phones.pretty("112"))
        assertEquals("*123#", Phones.pretty("*123#"))
    }

    @Test
    fun clockShowsMinutesAndSeconds() {
        assertEquals("01:23", Phones.clock(83))
        assertEquals("00:00", Phones.clock(0))
        assertEquals("65:05", Phones.clock(3905))
    }
}
