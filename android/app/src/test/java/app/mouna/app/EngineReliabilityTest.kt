package app.mouna.app

import android.app.ApplicationExitInfo
import android.speech.tts.TextToSpeech
import app.mouna.app.engine.CrashMarks
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Sarvam
import app.mouna.app.engine.Store
import app.mouna.app.engine.TtsLanguage
import app.mouna.app.sense.LipOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class CrashMarksTest {
    private val hour = 3_600_000L

    @Test
    fun aMarkLeftByARealCrashSkipsTheModel() {
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR)) {
            assertEquals(CrashMarks.Verdict.SKIP, CrashMarks.decide(CrashMarks.TRYING, hour, reason))
        }
    }

    @Test
    fun aKilledOrSwipedAwayProcessIsNotACrash() {
        // The first NPU compile takes minutes: the person leaves, the OS reclaims memory, the app is updated.
        for (reason in listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_USER_STOPPED,
        )) {
            assertEquals(CrashMarks.Verdict.RETRY, CrashMarks.decide(CrashMarks.TRYING, hour, reason))
        }
    }

    @Test
    fun withoutAnExitReasonTheGuardStays() {
        // Before Android 11, or no history: unknown is treated as before (skip), until the mark expires.
        assertEquals(CrashMarks.Verdict.SKIP, CrashMarks.decide(CrashMarks.TRYING, hour, null))
    }

    @Test
    fun aMarkOlderThanADayExpires() {
        val old = CrashMarks.EXPIRE_MS + 1
        assertEquals(CrashMarks.Verdict.RETRY, CrashMarks.decide(CrashMarks.TRYING, old, ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(CrashMarks.Verdict.RETRY, CrashMarks.decide(CrashMarks.CRASHED, old, null))
        assertEquals(CrashMarks.Verdict.SKIP, CrashMarks.decide(CrashMarks.TRYING, CrashMarks.EXPIRE_MS - 1, null))
    }

    @Test
    fun aCrashSeenOnceStaysEvenIfALaterStartEndedAnotherWay() {
        // Run A crashed; run B saw it (and upgraded the mark) but was swiped away; run C must not retry the crash.
        assertEquals(CrashMarks.Verdict.SKIP, CrashMarks.decide(CrashMarks.CRASHED, hour, ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun marksRoundTripAndOldOnesFallBackToTheFileTime() {
        assertEquals(CrashMarks.TRYING to 1234L, CrashMarks.parse(CrashMarks.content(CrashMarks.TRYING, 1234L), 99L))
        assertEquals(CrashMarks.CRASHED to 1234L, CrashMarks.parse(CrashMarks.content(CrashMarks.CRASHED, 1234L), 99L))
        assertEquals(CrashMarks.TRYING to 99L, CrashMarks.parse("trying", 99L)) // written by an older build
        assertEquals(CrashMarks.TRYING to 99L, CrashMarks.parse("", 99L))
    }
}

class TtsLanguageTest {
    private val ok = TextToSpeech.LANG_AVAILABLE
    private val missing = TextToSpeech.LANG_MISSING_DATA
    private val unsupported = TextToSpeech.LANG_NOT_SUPPORTED

    private fun engineWith(vararg have: String): (Locale) -> Int = { l -> if (l.toLanguageTag() in have) TextToSpeech.LANG_COUNTRY_AVAILABLE else missing }

    @Test
    fun theCaregiversLanguageWinsWhenInstalled() {
        val p = TtsLanguage.pick(Lang.HI, engineWith("hi-IN", "en-IN"))!!
        assertEquals("hi-IN", p.locale.toLanguageTag())
        assertFalse(p.fellBack)
    }

    @Test
    fun withoutAHindiVoiceItFallsBackToIndianEnglishAndSaysSo() {
        val p = TtsLanguage.pick(Lang.HI, engineWith("en-IN"))!!
        assertEquals("en-IN", p.locale.toLanguageTag())
        assertTrue(p.fellBack)
    }

    @Test
    fun plainEnglishIsTheLastResort() {
        val p = TtsLanguage.pick(Lang.TA, engineWith("en"))!!
        assertEquals("en", p.locale.toLanguageTag())
        assertTrue(p.fellBack)
    }

    @Test
    fun noUsableVoiceAtAllIsNull() {
        assertEquals(null, TtsLanguage.pick(Lang.TA) { unsupported })
    }

    @Test
    fun englishAsksForIndianEnglishFirstWithoutCountingItAsAFallback() {
        val p = TtsLanguage.pick(Lang.EN, engineWith("en-IN", "en"))!!
        assertEquals("en-IN", p.locale.toLanguageTag())
        assertFalse(p.fellBack)
    }

    @Test
    fun onlyAvailableResultsCount() {
        assertTrue(TtsLanguage.usable(ok))
        assertTrue(TtsLanguage.usable(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
        assertFalse(TtsLanguage.usable(missing))
        assertFalse(TtsLanguage.usable(unsupported))
    }
}

class SarvamPlanTest {
    private fun plan(key: Boolean = true, phone: Boolean = false, call: Boolean = false, cached: Boolean = false) = Sarvam.plan(key, phone, call, cached)

    @Test
    fun offACallNobodyWaitsForTheNetwork() {
        assertEquals(Sarvam.Plan.PHONE_NOW, plan())
        assertEquals(Sarvam.Plan.PHONE_NOW, plan(phone = true))
        assertEquals(Sarvam.Plan.PHONE_NOW, plan(key = false))
    }

    @Test
    fun aKeptSentenceIsInstantEvenOffACall() {
        assertEquals(Sarvam.Plan.CACHED, plan(cached = true))
        assertEquals(Sarvam.Plan.PHONE_NOW, plan(cached = true, phone = true)) // "Phone voice" is the person's choice off a call
    }

    @Test
    fun onACallSarvamIsAskedWithAShortWait() {
        assertEquals(Sarvam.Plan.FETCH, plan(call = true))
        assertEquals(Sarvam.Plan.FETCH, plan(call = true, phone = true)) // a call prefers the natural voice
        assertEquals(Sarvam.Plan.CACHED, plan(call = true, cached = true))
        assertEquals(Sarvam.Plan.PHONE_NOW, plan(call = true, key = false))
        assertTrue(Sarvam.CALL_WAIT_MS in 1..Sarvam.TIMEOUT_MS)
        assertEquals(1500, Sarvam.CALL_WAIT_MS)
    }
}

class StoreExamplesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun good() = """{"samples":{"water":[[0.5,1.5],[2.0,3.0]]},"negatives":[[9.0,8.0]]}"""

    @Test
    fun noFileIsAnEmptySet() {
        val e = Store.loadExamples(tmp.root, "enc")
        assertTrue(e.samples.isEmpty())
        assertTrue(e.negatives.isEmpty())
    }

    @Test
    fun aGoodFileLoads() {
        File(tmp.root, "examples-enc.json").writeText(good())
        val e = Store.loadExamples(tmp.root, "enc")
        assertEquals(2, e.samples["water"]!!.size)
        assertEquals(1.5f, e.samples["water"]!![0][1], 0f)
        assertEquals(1, e.negatives.size)
    }

    @Test
    fun aCorruptFileIsKeptAsideNotOverwritten() {
        val f = File(tmp.root, "examples-enc.json")
        f.writeText("""{"samples":{"water":[[0.5,""") // cut off mid-write
        val logged = mutableListOf<String>()
        val e = Store.loadExamples(tmp.root, "enc", nowMs = 1700000000000L, log = { logged += it })
        assertTrue(e.samples.isEmpty())
        assertFalse("the next save must not land on top of the rescued file", f.exists())
        val aside = File(tmp.root, "examples-enc.json.corrupt-1700000000000")
        assertTrue(aside.exists())
        assertTrue(aside.readText().startsWith("""{"samples":{"water"""))
        assertEquals(1, logged.size)
    }

    @Test
    fun aFileWithTheWrongShapeIsAlsoKept() {
        File(tmp.root, "examples-enc.json").writeText("""{"samples":[1,2,3]}""")
        Store.loadExamples(tmp.root, "enc", nowMs = 5L)
        assertTrue(File(tmp.root, "examples-enc.json.corrupt-5").exists())
    }

    @Test
    fun otherEncodersAreUntouched() {
        File(tmp.root, "examples-a.json").writeText(good())
        File(tmp.root, "examples-b.json").writeText("not json")
        Store.loadExamples(tmp.root, "b")
        assertEquals(1, Store.loadExamples(tmp.root, "a").samples.size)
    }
}

class LipOutlineTest {
    @Test
    fun readsPointsFromOneArray() {
        val o = LipOutline(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        assertEquals(2, o.size)
        assertEquals(0.3f to 0.4f, o[1])
        assertEquals(listOf(0.1f to 0.2f, 0.3f to 0.4f), o.toList())
        assertEquals(0.3f to 0.4f, o.drop(1)[0]) // the overlay calls drop(1)
    }

    @Test
    fun comparesByContentSoAnUnchangedContourIsNotANewFrame() {
        assertEquals(LipOutline(floatArrayOf(1f, 2f)), LipOutline(floatArrayOf(1f, 2f)))
        assertEquals(LipOutline(floatArrayOf(1f, 2f)).hashCode(), LipOutline(floatArrayOf(1f, 2f)).hashCode())
        assertNotEquals(LipOutline(floatArrayOf(1f, 2f)), LipOutline(floatArrayOf(1f, 3f)))
        assertEquals(listOf(1f to 2f), LipOutline(floatArrayOf(1f, 2f)))
    }
}
