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

    /** "auto", "builtin" o la llave de una entrada concreta (ver AudioInput.key). */
    var audioInput: String
        get() = p.getString("audio_input", AudioInput.AUTO) ?: AudioInput.AUTO
        set(v) = p.edit().putString("audio_input", v).apply()

    /** Volumen del micrófono en porcentaje (100 = sin cambios). */
    var micGainPercent: Int
        get() = p.getInt("mic_gain", 100)
        set(v) = p.edit().putInt("mic_gain", v).apply()

    /** Graba una copia del video en el teléfono mientras se transmite. */
    var recordLocal: Boolean
        get() = p.getBoolean("record_local", true)
        set(v) = p.edit().putBoolean("record_local", v).apply()

    /** Si es true, la clave de transmisión se guarda cifrada en este teléfono. */
    var rememberKey: Boolean
        get() = p.getBoolean("remember_key", false)
        set(v) = p.edit().putBoolean("remember_key", v).apply()

    /** Guarda la clave cifrada. Devuelve false si el teléfono no pudo cifrarla. */
    fun saveStreamKey(key: String): Boolean {
        if (key.isEmpty()) {
            p.edit().remove("key_enc").apply()
            return true
        }
        val encrypted = KeyVault.encrypt(key) ?: return false
        p.edit().putString("key_enc", encrypted).apply()
        return true
    }

    fun loadStreamKey(): String = p.getString("key_enc", null)?.let { KeyVault.decrypt(it) } ?: ""

    fun clearStreamKey() {
        p.edit().remove("key_enc").apply()
    }

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

    /** Pantallas listas (Ya regreso, pausa, cuenta regresiva), guardadas como JSON. */
    var cardsJson: String
        get() = p.getString("cards", "") ?: ""
        set(v) = p.edit().putString("cards", v).apply()

    /** Datos para los rótulos con plantilla. */
    var profileName: String
        get() = p.getString("profile_name", "") ?: ""
        set(v) = p.edit().putString("profile_name", v).apply()

    var profileSocial: String
        get() = p.getString("profile_social", "") ?: ""
        set(v) = p.edit().putString("profile_social", v).apply()

    /** "manual" (lo que tú elijas), "auto" (según la red) o la llave de un perfil (wifi, mobile, saver). */
    var qualityProfile: String
        get() = p.getString("quality_profile", QualityProfiles.MANUAL) ?: QualityProfiles.MANUAL
        set(v) = p.edit().putString("quality_profile", v).apply()

    /** Último resultado de la prueba de subida, en Mbps (0 = sin medir). */
    var lastUploadMbps: Float
        get() = p.getFloat("last_upload", 0f)
        set(v) = p.edit().putFloat("last_upload", v).apply()

    companion object {
        const val DEFAULT_URL = "rtmps://live-api-s.facebook.com:443/rtmp/"
    }
}
