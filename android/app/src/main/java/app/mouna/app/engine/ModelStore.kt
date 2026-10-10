package app.mouna.app.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Where the model files live (lips, voice, sign, phrase encoder; weights stay out of git and are pushed with adb).
 *
 * With "All files access" they live in /sdcard/Mouna/<name>: that folder survives reinstalling Mouna, a build signed
 * with another key, and "Clear storage", and the NPU-compiled caches next to them survive too, so nothing is copied or
 * compiled again. Without it they live in the app's own folder (/sdcard/Android/data/app.mouna/files/<name>), which
 * Android deletes with the app.
 *
 * Switching over: once access is granted, the next load moves the app's folder to /sdcard/Mouna/<name> (a rename on
 * the same storage, instant even for the 3.5 GB lips model). If that fails, the app's folder keeps being used, so the
 * app is never left without a model.
 */
object ModelStore {
    /** The folder that outlives the app: adb push models here. */
    val shared: File get() = File(Environment.getExternalStorageDirectory(), "Mouna")

    /** True when Mouna may use [shared] ("All files access", Android 11+). */
    fun kept(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** The folder for one kind of model, e.g. "avsr", "isl", "asr", "encoder". Created if missing. */
    fun dir(context: Context, name: String): File {
        val own = File(context.getExternalFilesDir(null), name)
        val keep = File(shared, name)
        if (!kept()) return own.apply { mkdirs() }
        if (own.hasFiles() && !keep.hasFiles()) {
            shared.mkdirs()
            keep.delete() // an empty folder left by an earlier mkdirs, or rename fails
            own.renameTo(keep)
        }
        return (if (keep.hasFiles() || !own.hasFiles()) keep else own).apply { mkdirs() }
    }

    /** Opens Android's "All files access" page for Mouna. */
    fun askAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun File.hasFiles(): Boolean = listFiles()?.isNotEmpty() == true
}
