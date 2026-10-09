package app.mouna.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Technical status (NPU/CPU, model names, timings, QA tools) shows only while this is on: Settings › Advanced. */
object Stage {
    var debug by mutableStateOf(false)
}
