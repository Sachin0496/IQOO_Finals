package app.mouna.app

import app.mouna.app.engine.Clip
import app.mouna.app.engine.PhrasePack
import app.mouna.app.engine.Segmenter
import app.mouna.app.engine.ShapeEncoder
import app.mouna.app.sense.Frame
import app.mouna.core.DecisionKind
import app.mouna.core.Learner
import app.mouna.core.Tier
import app.mouna.core.decide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class EngineTest {
    private fun frame(t: Long, open: Boolean, shape: FloatArray = FloatArray(80), aperture: Float = 0.1f) =
        Frame(tMs = t, face = true, features = shape, aperture = aperture, gateOpen = open, crop = ByteArray(96 * 96))

    @Test
    fun segmenterCutsOneUtteranceWithPreRoll() {
        val s = Segmenter(preRoll = 6, minFrames = 10)
        var t = 0L
        repeat(20) { assertNull(s.push(frame(t, false))); t += 33 }
        repeat(15) { assertNull(s.push(frame(t, true))); t += 33 }
        val clip = s.push(frame(t, false))
        assertNotNull(clip)
        assertEquals(6 + 15 + 1, clip!!.size)
    }

    @Test
    fun segmenterDropsBlipsAndLostFaces() {
        val s = Segmenter(minFrames = 10)
        var t = 0L
        repeat(3) { s.push(frame(t, true)); t += 33 }
        assertNull(s.push(frame(t, false))) // 3 + preroll < 10 frames: a twitch, not a phrase
        repeat(8) { s.push(frame(t, true)); t += 33 }
        assertNull(s.push(Frame(t, face = false))) // face lost mid-utterance: dropped
        assertTrue(!s.recording)
    }

    /** A synthetic mouthing: lip points moving with a phrase-specific rhythm, plus noise and a per-person offset. */
    private fun mouthing(freq: Double, rnd: Random, offset: Float): Clip {
        val n = 30 + rnd.nextInt(15)
        return Clip(List(n) { i ->
            val ph = 2 * PI * freq * i / n
            val shape = FloatArray(80) { k -> offset + (sin(ph + k * 0.3) * 0.1).toFloat() * (if (k % 2 == 0) 1f else 0.5f) + rnd.nextFloat() * 0.01f }
            frame(i * 33L, true, shape, (0.2 + 0.15 * sin(ph)).toFloat())
        })
    }

    @Test
    fun shapeEncoderIgnoresFaceShapeAndSeparatesRhythms() {
        val rnd = Random(7)
        val enc = ShapeEncoder()
        val a = enc.embed(mouthing(1.0, rnd, 0f))
        val b = enc.embed(mouthing(1.0, rnd, 0.5f)) // same movement, different resting face
        val diff = a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }
        assertTrue("offset leaked into the embedding: $diff", diff < 0.05f)

        val learner = Learner()
        repeat(3) {
            learner.addSample("water", enc.embed(mouthing(1.0, rnd, rnd.nextFloat())))
            learner.addSample("pain", enc.embed(mouthing(2.0, rnd, rnd.nextFloat())))
        }
        assertEquals("water", learner.predict(enc.embed(mouthing(1.0, rnd, 0.2f))).intents[0])
        assertEquals("pain", learner.predict(enc.embed(mouthing(2.0, rnd, 0.2f))).intents[0])
    }

    @Test
    fun phrasePackLoadsWithTiers() {
        val p = PhrasePack.bundled()
        assertTrue(p.phrases.size >= 8)
        assertTrue(p.demo.all { p[it] != null })
        val tiers = p.tiers()
        assertEquals(Tier.B, tiers["pain"])
        assertEquals(Tier.A, tiers["water"])
        assertEquals(Tier.C, tiers["fan_off"])
    }

    @Test
    fun actionsAreNeverSpokenWithoutConfirm() {
        val learner = Learner()
        val rnd = Random(3)
        val enc = ShapeEncoder()
        repeat(4) { learner.addSample("fan_off", enc.embed(mouthing(1.0, rnd, 0f))) }
        val d = decide(learner.predict(enc.embed(mouthing(1.0, rnd, 0f))), PhrasePack.bundled().tiers())
        assertTrue(d.kind != DecisionKind.SPEAK)
    }
}
