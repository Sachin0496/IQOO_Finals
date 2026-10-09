package app.mouna.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bathroom
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.ModeFanOff
import androidx.compose.material.icons.rounded.SevereCold
import androidx.compose.material.icons.rounded.SingleBed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Whatshot
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
    val mute = Color(0xFF7D776B)
    val turmeric = Color(0xFFF0AA2E)
    val kumkum = Color(0xFFE4472B)
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
    "water" -> Icons.Rounded.WaterDrop
    "pain" -> Icons.Rounded.Whatshot
    "nurse" -> Icons.Rounded.LocalHospital
    "breathe" -> Icons.Rounded.Air
    "toilet" -> Icons.Rounded.Bathroom
    "medicine" -> Icons.Rounded.Medication
    "sit_up" -> Icons.Rounded.SingleBed
    "cold" -> Icons.Rounded.SevereCold
    "hot" -> Icons.Rounded.Thermostat
    "family" -> Icons.Rounded.FamilyRestroom
    "yes" -> Icons.Rounded.Check
    "no" -> Icons.Rounded.Close
    "thank_you" -> Icons.Rounded.VolunteerActivism
    "fan_off" -> Icons.Rounded.ModeFanOff
    "love" -> Icons.Rounded.Favorite
    "okay" -> Icons.Rounded.SentimentSatisfied
    "hold_hand" -> Icons.Rounded.Handshake
    "scared" -> Icons.Rounded.SentimentDissatisfied
    "stay" -> Icons.Rounded.People
    else -> if (id.startsWith("my_")) Icons.Rounded.Favorite else Icons.Rounded.ChatBubbleOutline
}
