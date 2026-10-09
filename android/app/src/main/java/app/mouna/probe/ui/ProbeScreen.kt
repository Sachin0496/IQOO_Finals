package app.mouna.probe.ui

import android.speech.tts.TextToSpeech
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.mouna.probe.FaceTracker
import app.mouna.probe.PROBE_LANGS
import app.mouna.probe.SoakLog
import app.mouna.probe.Telemetry
import app.mouna.probe.VoiceState
import app.mouna.probe.readPower
import app.mouna.probe.voiceState
import kotlinx.coroutines.delay

// The Lab's palette: ink and bone, turmeric for "heard", kumkum for "recording / bad".
private val Ink = Color(0xFF0E0D0B)
private val Card = Color(0xFF1A1815)
private val Rule = Color(0xFF2C2924)
private val Bone = Color(0xFFEDE6D6)
private val Dim = Color(0xFFB9B2A3)
private val Mute = Color(0xFF7D776B)
private val Turmeric = Color(0xFFF0AA2E)
private val Kumkum = Color(0xFFE4472B)
private val Leaf = Color(0xFF8FBF8A)

private val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.4.sp, color = Mute)
private val Value = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Bone)
private val Body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Dim)
private val Display = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, color = Bone)

@Composable
fun ProbeScreen(
    tracker: FaceTracker?,
    tts: TextToSpeech?,
    cameraDenied: Boolean,
    permissions: List<String>,
    bindCamera: (PreviewView, FaceTracker) -> Unit,
    speak: (String, String) -> Unit,
) {
    val t by (tracker?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(Telemetry()) }).collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("mouna", style = Display.copy(fontStyle = FontStyle.Italic, fontSize = 34.sp))
            Text(".", style = Display.copy(fontSize = 34.sp, color = Turmeric))
            Spacer(Modifier.width(12.dp))
            Text("PROBE · S1 S5 S6", style = Label, modifier = Modifier.padding(bottom = 6.dp))
        }
        Mirror(tracker, t, cameraDenied, bindCamera)
        TelemetryGrid(t, permissions)
        Voices(tts, speak)
        Soak(t)
        Text(
            "Spike probe. Not the product. Built before the event and disclosed. No network permission, no video stored.",
            style = Body.copy(color = Mute, fontSize = 11.sp),
        )
    }
}

@Composable
private fun Mirror(tracker: FaceTracker?, t: Telemetry, cameraDenied: Boolean, bindCamera: (PreviewView, FaceTracker) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .background(Card)
            .border(1.dp, if (t.gateOpen) Turmeric else Rule),
    ) {
        if (tracker != null && !cameraDenied) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        bindCamera(this, tracker)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            LipOverlay(t)
        } else {
            Text(
                if (cameraDenied) "Camera permission refused." else "Loading the face landmarker…",
                style = Display.copy(fontStyle = FontStyle.Italic, fontSize = 22.sp, color = Dim),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        t.crop?.let {
            Column(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalAlignment = Alignment.End) {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Mouth crop, 96 pixels, grey",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(110.dp).border(1.dp, Rule),
                )
                Text("MOUTH · 96² · GREY", style = Label, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Outer lip contour over the (mirrored, fit-centre) preview. */
@Composable
private fun LipOverlay(t: Telemetry) {
    Canvas(Modifier.fillMaxSize()) {
        if (t.outer.isEmpty() || t.imageW == 0) return@Canvas
        val s = minOf(size.width / t.imageW, size.height / t.imageH)
        val ox = (size.width - t.imageW * s) / 2
        val oy = (size.height - t.imageH * s) / 2
        fun at(p: Pair<Float, Float>) = Offset(ox + (1 - p.first) * t.imageW * s, oy + p.second * t.imageH * s)
        val path = Path().apply {
            t.outer.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            close()
        }
        drawPath(path, if (t.gateOpen) Turmeric else Bone.copy(alpha = 0.8f), style = Stroke(width = 2.5.dp.toPx()))
    }
}

@Composable
private fun TelemetryGrid(t: Telemetry, permissions: List<String>) {
    val noInternet = "android.permission.INTERNET" !in permissions
    val cells = listOf(
        Triple("FPS", "%.1f".format(t.fps), if (t.fps >= 24f) Leaf else Kumkum),
        Triple("LANDMARKS", "%.1f ms".format(t.landmarkMs), Bone),
        Triple("CROP", "%.1f ms".format(t.cropMs), Bone),
        Triple("COMPUTE", t.delegate, Bone),
        Triple("EYE SPAN", if (t.face) "${t.iod.toInt()} px" else "—", if (!t.face || t.iod >= 55f) Bone else Kumkum),
        Triple("YAW", if (t.face) "${t.yaw.toInt()}°" else "—", Bone),
        Triple("GATE", if (t.gateOpen) "open" else "shut", if (t.gateOpen) Turmeric else Bone),
        Triple("INTERNET", if (noInternet) "absent" else "TTS + web calls", if (noInternet) Leaf else Turmeric),
    )
    Column(Modifier.border(1.dp, Rule)) {
        cells.chunked(4).forEach { row ->
            Row {
                row.forEach { (k, v, c) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .border(0.5.dp, Rule)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(k, style = Label)
                        Text(v, style = Value.copy(color = c))
                    }
                }
            }
        }
    }
    Text("Permissions in this APK: " + permissions.joinToString { it.substringAfterLast('.') }.ifEmpty { "none" }, style = Body)
    if (!noInternet) {
        Text(
            "Internet is used for Sarvam text-to-speech (only the words to speak go out) and for web calls: Mouna's spoken " +
                "voice and a caption of it go to the call relay, and the other person's voice comes back through it. " +
                "No camera or microphone data leaves the phone.",
            style = Body,
        )
    }
}

@Composable
private fun Voices(tts: TextToSpeech?, speak: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OFFLINE VOICES · S5", style = Label)
        PROBE_LANGS.forEach { (tag, name, sample) ->
            val state = tts?.let { voiceState(it, tag) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Rule)
                    .clickable(enabled = state != null && state != VoiceState.MISSING) { speak(tag, sample) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = Body.copy(color = Bone, fontSize = 15.sp, fontFamily = FontFamily.Default), modifier = Modifier.weight(1f))
                Text(tag, style = Label, modifier = Modifier.width(64.dp))
                Text(
                    when (state) {
                        null -> "…"
                        VoiceState.OFFLINE -> "offline · tap"
                        VoiceState.NEEDS_DOWNLOAD -> "download needed"
                        VoiceState.MISSING -> "missing"
                    },
                    style = Body.copy(
                        color = when (state) {
                            VoiceState.OFFLINE -> Leaf
                            VoiceState.NEEDS_DOWNLOAD -> Turmeric
                            VoiceState.MISSING -> Kumkum
                            null -> Mute
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun Soak(t: Telemetry) {
    val context = LocalContext.current
    var log by remember { mutableStateOf<SoakLog?>(null) }
    var line by remember { mutableStateOf("") }
    val latest by rememberUpdatedState(t)
    LaunchedEffect(log) {
        val l = log ?: return@LaunchedEffect
        while (true) {
            val p = readPower(context)
            l.row(p, latest)
            val mins = (System.currentTimeMillis() - l.startedAt) / 60000
            line = "$mins min · battery ${"%.0f".format(p.batteryPct)}% · drain ${"%.1f".format(l.drainPerHour(p))}%/h · " +
                "temp ${p.tempC}°C (max ${l.maxTemp}) · thermal ${p.thermal}"
            delay(10_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("30-MINUTE SOAK · S6", style = Label)
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .border(1.dp, if (log != null) Kumkum else Rule)
                .clickable { log = if (log == null) SoakLog(context) else null }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (log == null) "Start soak" else "Stop soak", style = Display.copy(fontStyle = FontStyle.Italic, fontSize = 22.sp))
        }
        if (line.isNotEmpty()) Text(line, style = Body)
        Text("Logs to files/soak.csv every 10 s. Phone on the stand, screen on, airplane mode.", style = Body.copy(color = Mute, fontSize = 11.sp))
    }
}
