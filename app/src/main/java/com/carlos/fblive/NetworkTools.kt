package com.carlos.fblive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.random.Random

/** Combinación de calidad de video pensada para un tipo de conexión. */
data class QualityProfile(
    val key: String,
    val label: String,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val description: String
)

object QualityProfiles {
    const val AUTO = "auto"
    const val MANUAL = "manual"

    val WIFI = QualityProfile("wifi", "Wi-Fi", 1080, 30, 5000, "1080p a 30 fps · 5 Mbps")
    val MOBILE = QualityProfile("mobile", "Datos móviles", 720, 30, 2500, "720p a 30 fps · 2.5 Mbps")
    val SAVER = QualityProfile("saver", "Ahorro", 720, 30, 1500, "720p a 30 fps · 1.5 Mbps")

    val all = listOf(WIFI, MOBILE, SAVER)

    fun byKey(key: String): QualityProfile? = all.firstOrNull { it.key == key }

    /** El perfil que corresponde a la red actual: Wi-Fi o cable usan el de Wi-Fi; datos móviles o sin red, el de datos. */
    fun forNetwork(context: Context): QualityProfile = if (NetworkInfoHelper.isFast(context)) WIFI else MOBILE
}

object NetworkInfoHelper {
    private fun caps(context: Context): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }

    /** true si la conexión es Wi-Fi o cable. */
    fun isFast(context: Context): Boolean {
        val c = caps(context) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun name(context: Context): String {
        val c = caps(context) ?: return "Sin conexión"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Cable"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos móviles"
            else -> "Otra red"
        }
    }
}

/** Recomendación de calidad a partir de la velocidad de subida medida. */
data class Recommendation(val height: Int, val fps: Int, val bitrateKbps: Int, val text: String)

object UploadAdvisor {
    fun recommend(mbps: Float): Recommendation {
        // Se usa solo una parte de la velocidad medida para tener margen si la red baja un poco.
        val usable = (mbps * 600f).toInt()
        val bitrate = ((usable / 250) * 250).coerceIn(1000, 6000)
        val height = if (bitrate >= 3500) 1080 else 720
        val label = if (height == 1080) "1080p" else "720p"
        val text = when {
            mbps < 1.5f -> "Tu subida es muy baja. Usa 720p con 1 Mbps y evita moverte; si puedes, cambia a una red mejor."
            mbps < 3f -> "Subida justa. Conviene $label con ${bitrate / 1000f} Mbps."
            else -> "Subida buena. Conviene $label a 30 fps con ${bitrate / 1000f} Mbps."
        }
        return Recommendation(height, 30, bitrate, text)
    }
}

/**
 * Mide la velocidad de subida enviando datos de prueba al servicio público de Cloudflare.
 * Usa una sola conexión, igual que la transmisión. Gasta como máximo unos 40 MB.
 */
class UploadTest(
    private val onProgress: (mbps: Float, seconds: Float) -> Unit,
    private val onDone: (Result) -> Unit
) {
    data class Result(val mbps: Float, val megabytes: Float, val error: String? = null)

    @Volatile
    private var cancelled = false

    @Volatile
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        cancelled = false
        Thread { run() }.start()
    }

    fun cancel() {
        cancelled = true
    }

    private fun run() {
        val block = ByteArray(64 * 1024).also { Random.nextBytes(it) }
        var size = 512 * 1024
        var totalBytes = 0L
        var measuredBytes = 0L
        var measuredNanos = 0L
        var firstRequest = true
        val startedAt = System.nanoTime()
        var error: String? = null
        try {
            while (!cancelled && System.nanoTime() - startedAt < MAX_NANOS && totalBytes < MAX_BYTES) {
                val t0 = System.nanoTime()
                val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 6000
                    conn.readTimeout = 12000
                    conn.setRequestProperty("Content-Type", "text/plain")
                    conn.setFixedLengthStreamingMode(size)
                    conn.outputStream.use { out ->
                        var left = size
                        while (left > 0 && !cancelled) {
                            val n = min(left, block.size)
                            out.write(block, 0, n)
                            left -= n
                        }
                    }
                    if (cancelled) break
                    conn.responseCode
                    runCatching { conn.inputStream.use { it.readBytes() } }
                } finally {
                    conn.disconnect()
                }
                val elapsed = System.nanoTime() - t0
                totalBytes += size
                if (!firstRequest) {
                    measuredBytes += size
                    measuredNanos += elapsed
                }
                firstRequest = false
                val bytes = if (measuredBytes > 0) measuredBytes else totalBytes
                val nanos = if (measuredNanos > 0) measuredNanos else elapsed
                onProgress(
                    bytes * 8f / (nanos / 1e9f) / 1e6f,
                    (System.nanoTime() - startedAt) / 1e9f
                )
                size = min(size * 2, 4 * 1024 * 1024)
            }
        } catch (e: Exception) {
            error = "No se pudo medir. Revisa tu conexión a internet."
        }
        running = false
        if (cancelled) {
            onDone(Result(0f, totalBytes / 1_048_576f, "Prueba cancelada"))
            return
        }
        val bytes = if (measuredBytes > 0) measuredBytes else totalBytes
        val nanos = if (measuredNanos > 0) measuredNanos else (System.nanoTime() - startedAt)
        if (error != null || bytes <= 0L) {
            onDone(Result(0f, totalBytes / 1_048_576f, error ?: "No se pudo medir."))
        } else {
            onDone(Result(bytes * 8f / (nanos / 1e9f) / 1e6f, totalBytes / 1_048_576f))
        }
    }

    companion object {
        private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
        private const val MAX_NANOS = 8_000_000_000L
        private const val MAX_BYTES = 40L * 1024 * 1024
    }
}
