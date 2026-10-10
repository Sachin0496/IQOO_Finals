package app.mouna.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Mouna's look, shared with the Lab: ink and bone, one turmeric accent for "heard", kumkum for "urgent",
 * leaf for "yes / safe". Serif for what people say, mono for what the machine measures. Nothing else.
 *
 * Dark is the default (night ward, low light). Light is a warm paper theme for bright rooms and sunlight,
 * toggled in Settings. Every color below reads [light], so all screens follow the switch with no per-screen code.
 */
object Ink {
    /** False = ink-on-black, true = ink-on-paper. Set once from the store at start-up, then by Settings. */
    var light by mutableStateOf(false)

    val bg get() = if (light) Color(0xFFFAF6ED) else Color(0xFF0E0D0B)
    val raised get() = if (light) Color(0xFFFFFFFF) else Color(0xFF171613)
    val card get() = if (light) Color(0xFFFFFFFF) else Color(0xFF1F1D19)
    val rule get() = if (light) Color(0xFFE7DCC4) else Color(0xFF2C2924)
    val rule2 get() = if (light) Color(0xFFD3C5A6) else Color(0xFF3D3A33)
    val bone get() = if (light) Color(0xFF211D15) else Color(0xFFEDE6D6)
    val bone2 get() = if (light) Color(0xFF57534A) else Color(0xFFB9B2A3)
    /** Quiet labels. Dark #958F81: 6.03:1 on bg, 5.23:1 on card (the old #7D776B was 4.37 / 3.78). Light #6E685B ≈ 5:1 on paper. */
    val mute get() = if (light) Color(0xFF6E685B) else Color(0xFF958F81)
    /** Turmeric fills stay warm in both modes; as small text on paper it is a readable bronze (was #F0AA2E). */
    val turmeric get() = if (light) Color(0xFF8F5E00) else Color(0xFFF0AA2E)
    /** Kumkum for icons and fills (dark 4.84:1 on bg). */
    val kumkum get() = if (light) Color(0xFFC93A1F) else Color(0xFFE4472B)
    /** Kumkum as small text: dark #F0694D (6.32:1 on bg); light #A92E15 on paper. */
    val kumkumInk get() = if (light) Color(0xFFA92E15) else Color(0xFFF0694D)
    /** Fill behind bone text on a danger button: #B8321A, bone on it is 4.81:1 (bone on kumkum was 3.23). */
    val kumkumDeep get() = Color(0xFFB8321A)
    /** Leaf fills: dark #8FBF8A on black; light #2E7D32 reads on paper. */
    val leaf get() = if (light) Color(0xFF2E7D32) else Color(0xFF8FBF8A)
}

object Type {
    val display get() = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 44.sp, color = Ink.bone)
    val title get() = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 32.sp, color = Ink.bone)
    val phrase get() = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 24.sp, color = Ink.bone)
    val body get() = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Ink.bone2)
    val label get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Ink.mute)
    val mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink.bone2)
    val button get() = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink.bone)
    /** Status pills on the camera: one line, readable from across a room. */
    val pill get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink.bone)
    val hint get() = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Ink.mute)
}

@Composable
fun MounaTheme(content: @Composable () -> Unit) {
    if (Ink.light) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                background = Ink.bg,
                surface = Ink.raised,
                primary = Ink.turmeric,
                onPrimary = Ink.bg,
                secondary = Ink.leaf,
                error = Ink.kumkum,
                onBackground = Ink.bone,
                onSurface = Ink.bone,
                outline = Ink.rule2,
            ),
            content = content,
        )
    } else {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Ink.bg,
                surface = Ink.raised,
                primary = Ink.turmeric,
                onPrimary = Ink.bg,
                secondary = Ink.leaf,
                error = Ink.kumkum,
                onBackground = Ink.bone,
                onSurface = Ink.bone,
                outline = Ink.rule2,
            ),
            content = content,
        )
    }
}

/** One picture per phrase: choices are made by looking or a switch, so they must read at a glance. */
fun iconFor(id: String): ImageVector = when (id) {
    "water" -> MounaIcons.WaterDrop
    "pain" -> MounaIcons.Whatshot
    "nurse" -> MounaIcons.LocalHospital
    "breathe" -> MounaIcons.Air
    "toilet" -> MounaIcons.Bathroom
    "medicine" -> MounaIcons.Medication
    "sit_up" -> MounaIcons.SingleBed
    "cold" -> MounaIcons.SevereCold
    "hot" -> MounaIcons.Thermostat
    "family" -> MounaIcons.FamilyRestroom
    "yes" -> Icons.Rounded.Check
    "no" -> Icons.Rounded.Close
    "thank_you" -> MounaIcons.VolunteerActivism
    "fan_off" -> MounaIcons.ModeFanOff
    "love" -> Icons.Rounded.Favorite
    "okay" -> MounaIcons.SentimentSatisfied
    "hold_hand" -> MounaIcons.Handshake
    "scared" -> MounaIcons.SentimentDissatisfied
    "stay" -> MounaIcons.People
    else -> if (id.startsWith("my_")) Icons.Rounded.Favorite else MounaIcons.ChatBubbleOutline
}
