package app.mouna.app.engine

import ai.onnxruntime.OrtSession
import android.os.Build

/** The Android emulator (e.g. on an Apple M4 Mac): it advertises CPU features (SME) that then trap. */
val isEmulator: Boolean =
    Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("emu") || Build.PRODUCT.startsWith("sdk_gphone")

/** ONNX Runtime CPU options for any model; KleidiAI kernels off on the emulator (SIGILL otherwise). */
fun cpuOptions(threads: Int): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(threads)
    if (isEmulator) {
        addConfigEntry("mlas.disable_kleidiai", "1")
        addConfigEntry("session.disable_prepacking", "1")
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }
}

/**
 * A model that crashes natively takes the app down with it. Mark the attempt on disk first; if the app dies during
 * it, the mark is still there at the next start and that model is skipped instead of crash-looping.
 */
fun <T> crashGuarded(dir: java.io.File, name: String, run: () -> T?): T? {
    val mark = java.io.File(dir, ".$name.crashed")
    if (mark.exists()) return null
    mark.writeText("trying")
    return run().also { mark.delete() }
}
