package app.mouna.app

import app.mouna.app.engine.Clip
import app.mouna.app.engine.Suggestion
import app.mouna.app.engine.Words
import app.mouna.app.sense.Frame
import app.mouna.core.Decision
import app.mouna.core.DecisionKind
import app.mouna.core.Personal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Taught words next to Lips' readings: where they go in "Did you mean…?", and which clips they are matched on. */
class WordsTest {
    private val names = mapOf("w_neeru" to "ನೀರು", "w_maadhav" to "Maadhav", "w_amma" to "Amma")
    private val open = listOf(Personal.Option("I want some water", -10.0, false), Personal.Option("Call my son", -14.0, true))

    private fun merge(d: Decision?, o: List<Personal.Option> = open) = Words.merge(o, d) { names[it] }

    @Test
    fun clearWordLeads() {
        val out = merge(Decision(DecisionKind.SPEAK, listOf("w_neeru")))
        assertEquals(Suggestion("ನೀರು", true, "w_neeru"), out[0])
        assertEquals(listOf("ನೀರು", "I want some water", "Call my son"), out.map { it.text })
    }

    @Test
    fun confirmLeadsUnlessANoneExampleIsCloser() {
        assertEquals("w_neeru", merge(Decision(DecisionKind.CONFIRM, listOf("w_neeru")))[0].word)
        val wary = merge(Decision(DecisionKind.CONFIRM, listOf("w_neeru"), maybeNone = true))
        assertEquals(listOf("I want some water", "ನೀರು", "Call my son"), wary.map { it.text })
    }

    @Test
    fun lookAlikeWordsFollowTheFirstReading() {
        val out = merge(Decision(DecisionKind.RESCUE, listOf("w_maadhav", "w_amma")))
        assertEquals(listOf("I want some water", "Maadhav", "Amma", "Call my son"), out.map { it.text })
    }

    @Test
    fun notTaughtAndAskAddNothing() {
        assertEquals(open.map { it.text }, merge(Decision(DecisionKind.NOT_TAUGHT, listOf("w_neeru"))).map { it.text })
        assertEquals(open.map { it.text }, merge(Decision(DecisionKind.ASK, listOf("w_neeru", "w_amma"))).map { it.text })
        assertEquals(open.map { it.text }, merge(null).map { it.text })
    }

    @Test
    fun wordsAloneWhileLipsIsNotReady() {
        assertEquals(listOf("Maadhav", "Amma"), merge(Decision(DecisionKind.RESCUE, listOf("w_maadhav", "w_amma")), emptyList()).map { it.text })
    }

    @Test
    fun forgottenWordsAndDuplicatesAreDropped() {
        val out = merge(Decision(DecisionKind.CHOOSE, listOf("w_gone", "w_amma")), listOf(Personal.Option("amma", -9.0, false)))
        assertEquals(listOf("amma"), out.map { it.text })
    }

    private fun frame(t: Long, crop: Boolean, moving: Boolean): Frame {
        val avsr = ByteArray(96 * 96)
        if (moving && t % 2L == 0L) for (r in 24 until 72) for (c in 16 until 80) avsr[r * 96 + c] = 60
        return Frame(tMs = t, face = true, features = FloatArray(80), gateOpen = true, crop = if (crop) ByteArray(96 * 96) else null, avsr = avsr)
    }

    @Test
    fun clipIsTrimmedToTheMovingPart() {
        val frames = List(30) { frame(it.toLong(), true, false) } + List(40) { frame(30L + it, true, true) } + List(30) { frame(70L + it, true, false) }
        val c = Words.clip(Clip(frames))
        assertNotNull(c)
        assert(c!!.size in 40..56) { "span ${c.size}" } // the moving 40 frames plus a little padding
    }

    @Test
    fun sentencesAndMissingCropsAreNotWords() {
        assertNull(Words.clip(Clip(List(150) { frame(it.toLong(), true, true) }))) // ~5 s of mouthing: a sentence
        assertNull(Words.clip(Clip(List(40) { frame(it.toLong(), it != 20, true) }))) // one frame without the lip crop
    }
}
