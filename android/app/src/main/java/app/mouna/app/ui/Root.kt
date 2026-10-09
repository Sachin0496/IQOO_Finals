package app.mouna.app.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.engine.Lang

@Composable
fun MounaRoot(
    app: MounaApp,
    cameraReady: Boolean,
    cameraDenied: Boolean,
    bindCamera: (PreviewView) -> Unit,
    openProbe: () -> Unit,
) {
    val k by app.engine.knowledge.collectAsState()
    // Back closes a prompt, then returns towards Speak; only on Speak does it leave the app.
    BackHandler(enabled = app.prompt != null || app.screen != Screen.SPEAK) {
        when {
            app.prompt != null -> app.close()
            app.screen == Screen.EYES || app.screen == Screen.SWITCH -> app.go(Screen.SETTINGS)
            else -> app.go(Screen.SPEAK)
        }
    }
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(app)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!cameraReady) {
                    NoCamera(cameraDenied)
                } else {
                    AnimatedContent(
                        targetState = app.screen,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.SPEAK -> SpeakScreen(app, k, bindCamera)
                            Screen.TEACH -> TeachScreen(app, k, bindCamera)
                            Screen.ASK -> AskScreen(app)
                            Screen.SETTINGS -> SettingsScreen(app, k, openProbe)
                            Screen.EYES -> EyesSetup(app, bindCamera)
                            Screen.SWITCH -> SwitchSetup(app, bindCamera)
                        }
                    }
                }
            }
            if (app.screen in MAIN) NavBar(app)
        }
        AnimatedVisibility(visible = app.prompt != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            app.prompt?.let { PromptOverlay(app, it, k) }
        }
    }
}

private val MAIN = listOf(Screen.SPEAK, Screen.TEACH, Screen.ASK)

@Composable
private fun TopBar(app: MounaApp) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.screen !in MAIN) {
            IconButtonSoft(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { app.go(if (app.screen == Screen.SETTINGS) Screen.SPEAK else Screen.SETTINGS) }
            Spacer(Modifier.width(8.dp))
        }
        Text("mouna", style = Type.title.copy(fontSize = 30.sp, fontStyle = FontStyle.Italic))
        Box(Modifier.padding(start = 3.dp, top = 10.dp).size(6.dp).clip(CircleShape).background(Ink.turmeric))
        Spacer(Modifier.weight(1f))
        LangChip(app.lang) { app.chooseLang(Lang.entries[(app.lang.ordinal + 1) % Lang.entries.size]) }
        Spacer(Modifier.width(6.dp))
        if (app.screen in MAIN) IconButtonSoft(Icons.Rounded.Settings, "Settings") { app.go(Screen.SETTINGS) }
    }
}

/** Tap to cycle the caregiver's language; the person's own input language never matters. */
@Composable
private fun LangChip(lang: Lang, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .border(1.dp, Ink.rule2, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(lang.label, style = Type.body.copy(color = Ink.bone, fontSize = 14.sp))
    }
}

@Composable
fun IconButtonSoft(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Ink.bone2, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun NavBar(app: MounaApp) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Ink.raised)
            .border(1.dp, Ink.rule, RoundedCornerShape(32.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NavItem(Icons.Rounded.RecordVoiceOver, "Speak", app.screen == Screen.SPEAK, Modifier.weight(1f)) { app.go(Screen.SPEAK) }
        NavItem(Icons.Rounded.School, "Teach", app.screen == Screen.TEACH, Modifier.weight(1f)) { app.go(Screen.TEACH) }
        NavItem(Icons.Rounded.QuestionAnswer, "Ask", app.screen == Screen.ASK, Modifier.weight(1f)) { app.go(Screen.ASK) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (on) Ink.bone else Ink.raised)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) Ink.bg else Ink.bone2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.button.copy(color = if (on) Ink.bg else Ink.bone2, fontSize = 15.sp))
    }
}

@Composable
private fun NoCamera(denied: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (denied) "Mouna needs the camera to see your lips." else "Starting the camera…",
            style = Type.title.copy(fontStyle = FontStyle.Italic),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing leaves this phone: the app has no internet permission, and no video is stored.",
            style = Type.body,
            textAlign = TextAlign.Center,
        )
    }
}
