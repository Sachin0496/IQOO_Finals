package app.mouna.app

import app.mouna.app.engine.Segmenter
import app.mouna.app.sense.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Free talk's segmenter: sentences from frames that carry only the Auto-AVSR crop (the lip crop is off then). */
class SegmenterTest {
    private fun frame(t: Long, open: Boolean) = Frame(
        tMs = t, face = true, features = FloatArray(80), gateOpen = open, crop = null, avsr = ByteArray(96 * 96),
    )

    private fun run(seg: Segmenter, pattern: List<Boolean>) = pattern.mapIndexedNotNull { i, open -> seg.push(frame(i * 33L, open)) }

    @Test
    fun freeTalkClipNeedsOnlyItsOwnCrop() {
        val seg = Segmenter(preRoll = 8, minFrames = 30, maxFrames = 300, tail = 20, keepTail = 8, hasCrop = { it.avsr != null })
        val clips = run(seg, List(10) { false } + List(45) { true } + List(30) { false })
        assertEquals(1, clips.size)
        assertEquals(8 + 45 + 8, clips[0].size) // pre-roll + mouthing + the kept still tail
    }

    @Test
    fun pausesBetweenWordsDoNotSplitASentence() {
        val seg = Segmenter(preRoll = 8, minFrames = 30, maxFrames = 300, tail = 20, keepTail = 8, hasCrop = { it.avsr != null })
        val clips = run(seg, List(20) { true } + List(12) { false } + List(20) { true } + List(30) { false })
        assertEquals(1, clips.size)
    }

    @Test
    fun aTwitchIsNotASentence() {
        val seg = Segmenter(preRoll = 8, minFrames = 30, maxFrames = 300, tail = 20, keepTail = 8, hasCrop = { it.avsr != null })
        assertEquals(0, run(seg, List(8) { true } + List(30) { false }).size)
    }

    @Test
    fun theLipSegmenterStillWantsTheLipCrop() {
        val seg = Segmenter()
        assertNull(run(seg, List(20) { true } + List(5) { false }).firstOrNull())
        assertNotNull(Segmenter(hasCrop = { true }).let { s -> run(s, List(20) { true } + List(5) { false }).firstOrNull() })
    }
}
