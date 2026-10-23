package com.carlos.fblive

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archivos de las grabaciones locales. Se graban primero en la carpeta de la app
 * (siempre disponible) y al terminar se copian a la galería, en Movies/FacebookLive.
 */
object RecordingStore {

    private const val TAG = "RecordingStore"

    /** Espacio libre mínimo para empezar a grabar. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    /** Archivo que se está grabando ahora (no se debe copiar todavía). */
    @Volatile
    var current: File? = null

    private val exporting = mutableSetOf<String>()

    private fun dir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return base.also { it.mkdirs() }
    }

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir(context), "FacebookLive_$stamp.mp4")
    }

    fun freeBytes(context: Context): Long = runCatching { StatFs(dir(context).path).availableBytes }
        .getOrDefault(Long.MAX_VALUE)

    /**
     * Copia la grabación a la galería en un hilo aparte y borra el original si todo salió bien.
     * En Android 9 o anterior el archivo se queda en la carpeta de la app.
     * [onDone] recibe un mensaje listo para mostrar (se llama desde otro hilo).
     */
    fun export(context: Context, file: File, onDone: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(exporting) {
            if (!exporting.add(file.absolutePath)) return
        }
        Thread {
            var message: String
            try {
                if (!file.exists() || file.length() < 20_000L) {
                    file.delete()
                    message = "La grabación quedó vacía y se descartó"
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    message = "Grabación guardada en: ${file.absolutePath}"
                } else {
                    message = copyToGallery(app, file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo copiar la grabación", e)
                message = "La grabación quedó en la carpeta de la app (no se pudo copiar a la galería)"
            } finally {
                synchronized(exporting) { exporting.remove(file.absolutePath) }
            }
            onDone?.invoke(message)
        }.start()
    }

    private fun copyToGallery(context: Context, file: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FacebookLive")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en la galería")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("No se pudo escribir en la galería")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        file.delete()
        return "Grabación guardada en la galería (Movies/FacebookLive)"
    }

    /** Copia a la galería las grabaciones que quedaron pendientes (por ejemplo si la app se cerró). */
    fun exportPending(context: Context) {
        val running = current
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: return
        for (f in files) {
            if (running != null && f.absolutePath == running.absolutePath) continue
            export(context, f)
        }
    }
}
