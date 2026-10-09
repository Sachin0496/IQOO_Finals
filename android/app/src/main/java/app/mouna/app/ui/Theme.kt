package app.mouna.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Mouna's look, shared with the Lab: ink and bone, one turmeric accent for "heard", kumkum for "urgent",
 * leaf for "yes / safe". Serif for what people say, mono for what the machine measures. Nothing else.
 */
object Ink {
    val bg = Color(0xFF0E0D0B)
    val raised = Color(0xFF171613)
    val card = Color(0xFF1F1D19)
    val rule = Color(0xFF2C2924)
    val rule2 = Color(0xFF3D3A33)
    val bone = Color(0xFFEDE6D6)
    val bone2 = Color(0xFFB9B2A3)
    /** Quiet labels. #958F81: 6.03:1 on bg, 5.23:1 on card, 5.62:1 on raised (the old #7D776B was 4.37 / 3.78). */
    val mute = Color(0xFF958F81)
    val turmeric = Color(0xFFF0AA2E)
    /** Kumkum for icons and fills (4.84:1 on bg). */
    val kumkum = Color(0xFFE4472B)
    /** Kumkum as small text on dark: #F0694D, 6.32:1 on bg, 5.47:1 on card. */
    val kumkumInk = Color(0xFFF0694D)
    /** Fill behind bone text on a danger button: #B8321A, bone on it is 4.81:1 (bone on kumkum was 3.23). */
    val kumkumDeep = Color(0xFFB8321A)
    val leaf = Color(0xFF8FBF8A)
}

object Type {
    val display = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 44.sp, color = Ink.bone)
    val title = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 32.sp, color = Ink.bone)
    val phrase = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 24.sp, color = Ink.bone)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Ink.bone2)
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Ink.mute)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink.bone2)
    val button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink.bone)
    /** Status pills on the camera: one line, readable from across a room. */
    val pill = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink.bone)
    val hint = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Ink.mute)
}

@Composable
fun MounaTheme(content: @Composable () -> Unit) {
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
