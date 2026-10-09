package app.mouna.app

import app.mouna.app.engine.SelfTrain
import app.mouna.core.CoreConstants
import app.mouna.core.Decision
import app.mouna.core.DecisionKind
import app.mouna.core.Ranked
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTrainTest {
    /** A clear match: best 0.5 (<= Q), runner-up 2.0 (>= 2x best), nearest "none of these" 3.0 (>= 2x best). */
    private val clear = Ranked(listOf("yes", "no"), listOf(0.5, 2.0), listOf(0.1, 0.3), negative = 3.0)
    private val speakYes = Decision(DecisionKind.SPEAK, listOf("yes"), "clear")

    @Test
    fun keepsAClearSpeak() {
        assertTrue(SelfTrain.keep(clear, speakYes, count = 2))
    }

    @Test
    fun rejectsEveryDecisionThatIsNotASpeak() {
        for (kind in listOf(DecisionKind.CONFIRM, DecisionKind.RESCUE, DecisionKind.CHOOSE, DecisionKind.ASK, DecisionKind.NOT_TAUGHT)) {
            assertFalse(kind.name, SelfTrain.keep(clear, Decision(kind, listOf("yes")), count = 2))
        }
    }

    @Test
    fun rejectsASpeakOfAnythingButTheTopPhrase() {
        assertFalse(SelfTrain.keep(clear, Decision(DecisionKind.SPEAK, listOf("no")), count = 2))
    }

    @Test
    fun rejectsABestScoreAboveQ() {
        val r = Ranked(listOf("yes", "no"), listOf(SelfTrain.Q + 0.01, 3.0), listOf(0.2, 0.5), negative = 3.0)
        assertFalse(SelfTrain.keep(r, speakYes, count = 2))
    }

    @Test
    fun keepsABestScoreExactlyAtQ() {
        val r = Ranked(listOf("yes", "no"), listOf(SelfTrain.Q, 2.0), listOf(0.2, 0.5), negative = 3.0)
        assertTrue(SelfTrain.keep(r, speakYes, count = 2))
    }

    @Test
    fun rejectsASmallMargin() {
        // Runner-up only 1.5x the best: below MARGIN (2.0).
        val r = Ranked(listOf("yes", "no"), listOf(0.5, 0.75), listOf(0.1, 0.2), negative = 3.0)
        assertFalse(SelfTrain.keep(r, speakYes, count = 2))
    }

    @Test
    fun rejectsACloseNegative() {
        // The nearest "none of these" is only 1.5x the best: below NEG (2.0).
        val r = Ranked(listOf("yes", "no"), listOf(0.5, 2.0), listOf(0.1, 0.3), negative = 0.75)
        assertFalse(SelfTrain.keep(r, speakYes, count = 2))
    }

    @Test
    fun rejectsAPhraseThatIsAlreadyFull() {
        assertFalse(SelfTrain.keep(clear, speakYes, count = CoreConstants.MAX_SHOTS))
        assertFalse(SelfTrain.keep(clear, speakYes, count = CoreConstants.MAX_SHOTS + 1))
        assertTrue(SelfTrain.keep(clear, speakYes, count = CoreConstants.MAX_SHOTS - 1))
    }

    @Test
    fun aSingleTaughtPhraseHasNoRunnerUp() {
        // Only one phrase taught: no runner-up score, so the margin is open and only Q and the negative gate apply.
        val r = Ranked(listOf("yes"), listOf(0.4), listOf(0.1))
        assertTrue(SelfTrain.keep(r, Decision(DecisionKind.SPEAK, listOf("yes"), "clear"), count = 1))
    }

    @Test
    fun aSingleTaughtPhraseStillNeedsAGoodMatch() {
        val r = Ranked(listOf("yes"), listOf(0.9), listOf(0.4))
        assertFalse(SelfTrain.keep(r, Decision(DecisionKind.SPEAK, listOf("yes"), "clear"), count = 1))
    }

    @Test
    fun noPhrasesTaughtKeepsNothing() {
        val r = Ranked(emptyList(), emptyList(), emptyList())
        assertFalse(SelfTrain.keep(r, Decision(DecisionKind.NOT_TAUGHT), count = 0))
    }
}
