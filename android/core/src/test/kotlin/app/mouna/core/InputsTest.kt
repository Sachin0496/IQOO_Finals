package app.mouna.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Same scenarios as lab/src/core/switch.test.ts. */
class InputsTest {
    private var seed = 1L
    private fun rand(): Double {
        seed = (seed * 16807) % 2147483647
        return seed / 2147483647.0 - 0.5
    }
    private fun frame(brow: Double, mouth: Double) =
        FloatArray(52) { c -> (0.05 + 0.01 * rand() + (if (c == 3) brow else 0.0) + (if (c == 10) mouth else 0.0)).toFloat() }
    private val rest = List(90) { t -> frame(0.0, 0.3 * abs(sin(t / 3.0))) }
    private fun move() = List(20) { t -> frame(0.5 * sin(PI * t / 19), 0.0) }

    @Test
    fun switchLearnsTheMovingChannelAndPressesOncePerMovement() {
        val m = (teachSwitch(rest, listOf(move(), move(), move())) as SwitchTeachResult.Learned).model
        assertEquals(listOf(3), m.channels.map { it.index })
        val sw = PersonalSwitch(m)
        var t = 0L
        var presses = 0
        for (f in rest + move() + rest + move() + rest) if (sw.push(f, 40L.also { t += it }.let { t })) presses++
        assertEquals(2, presses)
    }

    @Test
    fun gazeSelectsOnceAfterDwellAndRejectsBadCalibration() {
        val g = (calibrateGaze(List(9) { 0.5 }, List(9) { 0.6 }, List(9) { 0.4 }) as GazeCalibration.Ready).model
        val s = GazeSelector(g)
        val picks = (0..1500 step 40).mapNotNull { t -> s.push(if (t < 200) 0.5 else 0.59, t.toLong()) }
        assertEquals(listOf(Zone.LEFT), picks)
        assertTrue(calibrateGaze(List(9) { 0.5 }, List(9) { 0.6 }, List(9) { 0.61 }) is GazeCalibration.Failed)
    }
}
