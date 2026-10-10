package app.mouna.app

import app.mouna.app.ui.Ink
import app.mouna.app.ui.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun darkIsDefault() {
        Ink.light = false
        assertEquals(Ink.bone, Type.display.color)
        assertEquals(Ink.bone2, Type.body.color)
    }

    @Test
    fun lightSwitchesSurfacesAndText() {
        Ink.light = false
        val darkBg = Ink.bg
        val darkBone = Ink.bone
        Ink.light = true
        try {
            assertNotEquals(darkBg, Ink.bg)
            assertNotEquals(darkBone, Ink.bone)
            // Text styles follow the switch (they are getters over Ink).
            assertEquals(Ink.bone, Type.display.color)
            assertEquals(Ink.bone, Type.title.color)
            assertEquals(Ink.bone2, Type.body.color)
            assertEquals(Ink.mute, Type.hint.color)
            // Light surfaces are light, light ink is dark.
            assert(Ink.bg.red + Ink.bg.green + Ink.bg.blue > 2.0f)
            assert(Ink.bone.red + Ink.bone.green + Ink.bone.blue < 1.0f)
        } finally {
            Ink.light = false
        }
    }

    @Test
    fun accentsStayWarmInBothModes() {
        Ink.light = false
        val darkTurmeric = Ink.turmeric
        Ink.light = true
        try {
            // Same hue family (warm bronze), darker for paper contrast.
            assertNotEquals(darkTurmeric, Ink.turmeric)
            assert(Ink.turmeric.red > Ink.turmeric.blue)
        } finally {
            Ink.light = false
        }
    }
}
