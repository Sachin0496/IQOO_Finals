package app.mouna.app

import app.mouna.app.sense.BlinkClicks
import app.mouna.app.sense.Box
import app.mouna.app.sense.EyeAction
import app.mouna.app.sense.FocusPicker
import app.mouna.app.sense.GazeFit
import app.mouna.app.sense.GazeMap
import app.mouna.app.sense.GazePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class EyePointerTest {
    /** A synthetic eye: iris across and up/down follow the screen point, the lids narrow looking down, plus noise. */
    private fun samples(x: Double, y: Double, rnd: Random, noise: Double = 0.003) = List(30) {
        doubleArrayOf(
            0.5 - x / 1080 * 0.12 + rnd.nextDouble(-noise, noise),
            0.02 + y / 2400 * 0.06 + rnd.nextDouble(-noise, noise),
            0.30 - y / 2400 * 0.06 + rnd.nextDouble(-noise, noise),
            rnd.nextDouble(-0.5, 0.5),
            0.6 + rnd.nextDouble(-0.005, 0.005),
        )
    }

    private val grid = listOf(0.1, 0.5, 0.9).flatMap { fy -> listOf(0.1, 0.5, 0.9).map { fx -> fx * 1080 to fy * 2400 } }

    @Test
    fun fitsAndPredictsTheDots() {
        val rnd = Random(1)
        val r = GazeMap.fit(grid.map { (x, y) -> GazePoint(x, y, samples(x, y, rnd)) }, maxErrorPx = 400.0)
        assertTrue(r.toString(), r is GazeFit.Ready)
        val map = (r as GazeFit.Ready).map
        assertTrue("leave-one-out error ${map.errorPx}", map.errorPx < 120)
        val f = samples(300.0, 1800.0, Random(2)).first()
        val (x, y) = map.predict(f)
        assertTrue("predicted ($x, $y)", hypot(x - 300, y - 1800) < 150)
        // Shut is under the narrowest open look (bottom row, lids at about 0.255).
        assertTrue(map.closedBelow in 0.12..0.17)
    }

    /** The 21-dot grid (4 x 5 plus the middle), on a 1080 x 2400 screen. */
    private val accurate = listOf(540.0 to 1200.0) + (0..4).flatMap { r -> (0..3).map { c -> (60 + c * 320.0) to (60 + r * 570.0) } }

    /** An eye whose iris flattens towards the edges (as a real one does at a phone's distance). */
    private fun bent(x: Double, y: Double, rnd: Random) = List(30) {
        val u = (x - 540) / 540
        val v = (y - 1200) / 1200
        doubleArrayOf(
            0.5 - 0.06 * kotlin.math.sin(u * 1.3) + rnd.nextDouble(-0.002, 0.002),
            0.03 + 0.03 * kotlin.math.sin(v * 1.3) + 0.01 * u * v + rnd.nextDouble(-0.002, 0.002),
            0.28 - 0.03 * v + rnd.nextDouble(-0.002, 0.002),
            rnd.nextDouble(-0.5, 0.5),
            0.6 + rnd.nextDouble(-0.005, 0.005),
        )
    }

    @Test
    fun moreDotsBendTheMapAndMissLess() {
        val rnd = Random(7)
        val pts = accurate.map { (x, y) -> GazePoint(x, y, bent(x, y, rnd)) }
        val many = (GazeMap.fit(pts, 600.0) as GazeFit.Ready).map
        assertTrue("21 dots should fit a bent eye with a bent map", many.curved)
        // The 9 quick dots of the same eye can only fit a straight map, and miss more.
        val quick = listOf(0, 1, 2, 4, 9, 12, 17, 18, 20).map { pts[it] }
        val few = (GazeMap.fit(quick, 600.0) as GazeFit.Ready).map
        assertTrue(!few.curved)
        assertTrue("accurate ${many.errorPx} vs quick ${few.errorPx}", many.errorPx < few.errorPx)
        // A bent map survives the store.
        val back = GazeMap.fromText(many.toText())!!
        assertTrue(back.curved)
        val f = bent(900.0, 300.0, rnd).first()
        assertEquals(many.predict(f).second, back.predict(f).second, 1e-9)
    }

    @Test
    fun aStraightEyeKeepsAStraightMap() {
        val rnd = Random(8)
        val map = (GazeMap.fit(accurate.map { (x, y) -> GazePoint(x, y, samples(x, y, rnd)) }, 600.0) as GazeFit.Ready).map
        assertTrue("error ${map.errorPx}", map.errorPx < 120)
    }

    @Test
    fun refusesEyesThatDoNotMove() {
        val rnd = Random(3)
        val still = grid.map { (x, y) -> GazePoint(x, y, samples(540.0, 1200.0, rnd)) }
        assertTrue(GazeMap.fit(still, maxErrorPx = 400.0) is GazeFit.Failed)
    }

    @Test
    fun refusesTooFewDots() {
        val rnd = Random(4)
        val few = grid.take(4).map { (x, y) -> GazePoint(x, y, samples(x, y, rnd)) }
        assertTrue(GazeMap.fit(few, maxErrorPx = 400.0) is GazeFit.Failed)
    }

    @Test
    fun survivesTheStore() {
        val rnd = Random(5)
        val map = (GazeMap.fit(grid.map { (x, y) -> GazePoint(x, y, samples(x, y, rnd)) }, 400.0) as GazeFit.Ready).map
        val back = GazeMap.fromText(map.toText())!!
        val f = samples(700.0, 500.0, rnd).first()
        assertEquals(map.predict(f).first, back.predict(f).first, 1e-9)
        assertEquals(map.errorPx, back.errorPx, 1e-9)
        assertNull(GazeMap.fromText("nonsense"))
    }

    /** Feeds one frame per 33 ms: each span is (eyes shut?, how long). */
    private fun run(b: BlinkClicks, vararg spans: Pair<Boolean, Long>): List<EyeAction> {
        var t = 0L
        val out = mutableListOf<EyeAction>()
        for ((shut, ms) in spans) {
            val end = t + ms
            while (t < end) {
                b.push(if (shut) 0.05 else 0.3, t)?.let { out += it }
                t += 33
            }
        }
        return out
    }

    @Test
    fun aNaturalBlinkDoesNothing() {
        assertEquals(emptyList<EyeAction>(), run(BlinkClicks(0.15), false to 1000, true to 150, false to 1000))
    }

    @Test
    fun aLongBlinkPresses() {
        assertEquals(listOf(EyeAction.CLICK), run(BlinkClicks(0.15), false to 500, true to 600, false to 500))
    }

    @Test
    fun eyesRestingShutPressNothing() {
        assertEquals(emptyList<EyeAction>(), run(BlinkClicks(0.15), false to 500, true to 3000, false to 500))
    }

    @Test
    fun twoQuickBlinksGoBack() {
        assertEquals(listOf(EyeAction.BACK), run(BlinkClicks(0.15), false to 500, true to 130, false to 250, true to 130, false to 500))
    }

    @Test
    fun twoBlinksFarApartAreNotBack() {
        assertEquals(emptyList<EyeAction>(), run(BlinkClicks(0.15), false to 500, true to 130, false to 1500, true to 130, false to 500))
    }

    @Test
    fun theOutlineGoesToTheButtonLookedAt() {
        val p = FocusPicker(dwellMs = 180, marginPx = 24f)
        val buttons = mapOf(1 to Box(0f, 0f, 100f, 100f), 2 to Box(400f, 0f, 500f, 100f))
        assertEquals(1, p.push(buttons, 50f, 50f, 0))
        // A glance at the other one for less than the dwell does not move it.
        assertEquals(1, p.push(buttons, 450f, 50f, 33))
        assertEquals(1, p.push(buttons, 50f, 50f, 66))
        // Looking there and staying does.
        assertEquals(1, p.push(buttons, 450f, 50f, 100))
        assertEquals(1, p.push(buttons, 450f, 50f, 200))
        assertEquals(2, p.push(buttons, 450f, 50f, 300))
    }

    @Test
    fun jitterBetweenTwoButtonsDoesNotFlicker() {
        val p = FocusPicker(dwellMs = 180, marginPx = 24f)
        val buttons = mapOf(1 to Box(0f, 0f, 100f, 100f), 2 to Box(120f, 0f, 220f, 100f))
        p.push(buttons, 50f, 50f, 0)
        // Halfway between, within the margin: stays.
        for (t in 1..20) assertEquals(1, p.push(buttons, if (t % 2 == 0) 105f else 115f, 50f, t * 33L))
    }

    @Test
    fun aButtonOnACardBeatsTheCard() {
        val p = FocusPicker()
        val card = mapOf(1 to Box(0f, 0f, 1000f, 600f), 2 to Box(100f, 100f, 300f, 200f))
        assertEquals(2, p.push(card, 200f, 150f, 0))
    }

    @Test
    fun anOutlinedButtonThatGoesAwayIsReplacedAtOnce() {
        val p = FocusPicker()
        p.push(mapOf(1 to Box(0f, 0f, 100f, 100f)), 50f, 50f, 0)
        assertEquals(3, p.push(mapOf(3 to Box(0f, 300f, 100f, 400f)), 50f, 50f, 33))
    }
}
