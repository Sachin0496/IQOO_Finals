package app.mouna.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stage mode: the screens say only plain words. With [debug] on they also show what the machine is doing (NPU or
 * CPU, model names, milliseconds, file paths). The switch lives in Settings, Advanced.
 */
object Stage {
    var debug by mutableStateOf(false)
}
