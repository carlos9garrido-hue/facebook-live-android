package com.carlos.fblive

import android.content.Context

/** Ajustes que se guardan en el teléfono. La clave de transmisión NUNCA se guarda. */
class AppSettings(context: Context) {

    private val p = context.getSharedPreferences("fblive", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = p.edit().putString("url", v).apply()

    /** 720 o 1080 (siempre 16:9). */
    var height: Int
        get() = p.getInt("height", 720)
        set(v) = p.edit().putInt("height", v).apply()

    var fps: Int
        get() = p.getInt("fps", 30)
        set(v) = p.edit().putInt("fps", v).apply()

    var bitrateKbps: Int
        get() = p.getInt("bitrate", 4000)
        set(v) = p.edit().putInt("bitrate", v).apply()

    var vertical: Boolean
        get() = p.getBoolean("vertical", false)
        set(v) = p.edit().putBoolean("vertical", v).apply()

    var noiseSuppression: Boolean
        get() = p.getBoolean("noise", true)
        set(v) = p.edit().putBoolean("noise", v).apply()

    var adaptiveBitrate: Boolean
        get() = p.getBoolean("adaptive", true)
        set(v) = p.edit().putBoolean("adaptive", v).apply()

    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = p.edit().putBoolean("grid", v).apply()

    /** true = cámara frontal activa. */
    var front: Boolean
        get() = p.getBoolean("front", false)
        set(v) = p.edit().putBoolean("front", v).apply()

    fun lensKey(front: Boolean): String? = p.getString(if (front) "lens_front" else "lens_back", null)

    fun setLensKey(front: Boolean, key: String) {
        p.edit().putString(if (front) "lens_front" else "lens_back", key).apply()
    }

    // Imagen en imagen (multicámara)
    var pipEnabled: Boolean
        get() = p.getBoolean("pip", false)
        set(v) = p.edit().putBoolean("pip", v).apply()

    var pipCameraKey: String?
        get() = p.getString("pip_cam", null)
        set(v) = p.edit().putString("pip_cam", v).apply()

    /** 0 = arriba izquierda, 1 = arriba derecha, 2 = abajo izquierda, 3 = abajo derecha. */
    var pipCorner: Int
        get() = p.getInt("pip_corner", 3)
        set(v) = p.edit().putInt("pip_corner", v).apply()

    var pipSize: Int
        get() = p.getInt("pip_size", 28)
        set(v) = p.edit().putInt("pip_size", v).apply()

    /** 0, 90, 180 o 270. */
    var pipRotation: Int
        get() = p.getInt("pip_rot", 0)
        set(v) = p.edit().putInt("pip_rot", v).apply()

    // Capas (textos e imágenes) y escenas de video, guardadas como JSON.
    var overlaysJson: String
        get() = p.getString("overlays", "[]") ?: "[]"
        set(v) = p.edit().putString("overlays", v).apply()

    var scenesJson: String
        get() = p.getString("scenes", "[]") ?: "[]"
        set(v) = p.edit().putString("scenes", v).apply()

    /** Dirección (uri) del video que se reproduce como intro al empezar a transmitir. */
    var introUri: String?
        get() = p.getString("intro_uri", null)
        set(v) = p.edit().putString("intro_uri", v).apply()

    var introEnabled: Boolean
        get() = p.getBoolean("intro_on", false)
        set(v) = p.edit().putBoolean("intro_on", v).apply()

    companion object {
        const val DEFAULT_URL = "rtmps://live-api-s.facebook.com:443/rtmp/"
    }
}
