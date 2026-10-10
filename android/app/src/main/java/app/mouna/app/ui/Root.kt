package app.mouna.app.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
    /** First launch: show the Welcome screen, and call [onWelcomeDone] on Continue (the caller then asks for the camera). */
    welcome: Boolean = false,
    onWelcomeDone: () -> Unit = {},
) {
    val k by app.engine.knowledge.collectAsState()
    val activity = LocalContext.current as? Activity
    // Back closes a prompt, then returns towards Speak. On Speak it sends Mouna to the background instead of
    // finishing the activity: finishing would stop the engine and hang up a call.
    BackHandler {
        when {
            welcome -> activity?.moveTaskToBack(true)
            app.prompt != null -> app.close()
            app.screen == Screen.EYES || app.screen == Screen.SWITCH || app.screen == Screen.RECORD || app.screen == Screen.EYE_CONTROL -> app.go(Screen.SETTINGS)
            app.screen == Screen.WORDS -> app.go(app.wordsBack)
            app.screen == Screen.SIGNS -> app.go(app.signsBack)
            app.screen != Screen.SPEAK -> app.go(Screen.SPEAK)
            else -> activity?.moveTaskToBack(true)
        }
    }
    if (welcome) {
        Welcome(onWelcomeDone)
        return
    }
    if (app.screen == Screen.EYE_CONTROL && cameraReady) {
        EyeControlSetup(app, bindCamera) // full screen: the dots reach the corners, where Back and Settings are
        return
    }
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(app)
            if (app.onCall && app.screen != Screen.CALL) CallBar(app)
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
                            Screen.CALL -> CallScreen(app, k, bindCamera)
                            Screen.SETTINGS -> SettingsScreen(app, k, openProbe)
                            Screen.EYES -> EyesSetup(app, bindCamera)
                            Screen.SWITCH -> SwitchSetup(app, bindCamera)
                            Screen.RECORD -> RecordScreen(app, k, bindCamera)
                            Screen.WORDS -> WordsScreen(app, k, bindCamera)
                            Screen.SIGNS -> SignsScreen(app, k, bindCamera)
                            Screen.EYE_CONTROL -> Unit // drawn full screen above
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

private val MAIN = listOf(Screen.SPEAK, Screen.CALL)

private val ChipShape = CircleShape

@Composable
private fun TopBar(app: MounaApp) {
    // The wordmark and the language chip never move: the way back sits in the slot where the gear is on the main screens.
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("mouna", style = wordmark)
        Box(Modifier.padding(start = 3.dp, top = 10.dp).size(6.dp).clip(CircleShape).background(Ink.turmeric))
        Spacer(Modifier.weight(1f))
        LangChip(app.lang) { app.chooseLang(Lang.entries[(app.lang.ordinal + 1) % Lang.entries.size]) }
        Spacer(Modifier.width(6.dp))
        if (app.screen in MAIN) {
            IconButtonSoft(Icons.Rounded.Settings, "Settings") { app.go(Screen.SETTINGS) }
        } else {
            BackButton {
                app.go(
                    when (app.screen) {
                        Screen.SETTINGS -> Screen.SPEAK
                        Screen.WORDS -> app.wordsBack
                        Screen.SIGNS -> app.signsBack
                        else -> Screen.SETTINGS
                    },
                )
            }
        }
    }
}

private val wordmark get() = Type.title.copy(fontSize = 30.sp, fontStyle = FontStyle.Italic)
private val chipStyle get() = Type.body.copy(color = Ink.bone, fontSize = 14.sp, lineHeight = 20.sp)

/** The way back: an arrow and the word, so nobody has to guess what it does. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(ChipShape).clickable(onClick = onClick).padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Ink.bone2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text("Back", style = chipStyle.copy(color = Ink.bone2))
    }
}

/** Tap to cycle the caregiver's language; the person's own input language never matters. Always 48dp tall. */
@Composable
private fun LangChip(lang: Lang, onClick: () -> Unit) {
    Box(
        Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(ChipShape)
            .border(1.dp, Ink.rule2, ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(lang.label, style = chipStyle, maxLines = 1)
    }
}

@Composable
fun IconButtonSoft(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
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
        NavItem(MounaIcons.RecordVoiceOver, "Speak", app.screen == Screen.SPEAK, Modifier.weight(1f)) { app.go(Screen.SPEAK) }
        NavItem(Icons.Rounded.Call, "Call", app.screen == Screen.CALL, Modifier.weight(1f)) { app.go(Screen.CALL) }
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
        Icon(icon, null, tint = if (on) Ink.bg else Ink.bone2, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.button.copy(color = if (on) Ink.bg else Ink.bone2, fontSize = 14.sp))
    }
}

@Composable
private fun NoCamera(denied: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (denied) "Mouna needs the camera to see you." else "Starting the camera…",
            style = Type.title.copy(fontStyle = FontStyle.Italic),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No video or microphone audio leaves this phone and none is stored. The internet is used only to fetch a clearer voice for the words you speak on a call, and on a web call to carry that voice to the other person.",
            style = Type.body,
            textAlign = TextAlign.Center,
        )
    }
}

/** On a call, on every other screen: who, how long; tap to go back to the Call screen. */
@Composable
private fun CallBar(app: MounaApp) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Ink.leaf.copy(alpha = 0.16f))
            .border(1.dp, Ink.leaf.copy(alpha = 0.5f), CircleShape)
            .clickable { app.go(Screen.CALL) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.leaf))
        Spacer(Modifier.width(10.dp))
        Text(callStatus(app), style = Type.mono.copy(color = Ink.bone, fontSize = 13.sp), modifier = Modifier.weight(1f))
        Text("OPEN", style = Type.label.copy(color = Ink.leaf))
    }
}
