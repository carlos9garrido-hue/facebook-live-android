package com.carlos.fblive

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Un lente que se puede elegir en la app.
 *
 * - [id]: identificador de la cámara (o del lente físico si [parentId] no es null).
 * - [parentId]: cámara lógica que contiene este lente físico (se abre la lógica y se pide este lente).
 * - [zoom]: si no es null, el lente es la misma cámara con ese zoom (por ejemplo 0.5x ultra gran angular).
 */
data class Lens(
    val id: String,
    val parentId: String?,
    val front: Boolean,
    val focalEq: Float,
    val ratio: Float,
    val zoom: Float?
) {
    val key: String
        get() = when {
            zoom != null -> "$id@zoom"
            parentId != null -> "$parentId/$id"
            else -> id
        }

    /** Id de la cámara que realmente se abre. */
    val openId: String get() = parentId ?: id

    val ratioText: String
        get() {
            if (ratio <= 0f) return "Cam $id"
            val r = ratio
            return if (abs(r - r.roundToInt()) < 0.05f) "${r.roundToInt()}×"
            else String.format(Locale.US, "%.1f×", r)
        }

    val shortName: String
        get() = when {
            ratio <= 0f -> "Cámara"
            ratio < 0.8f -> "Ultra"
            ratio < 1.25f -> if (front) "Frontal" else "Principal"
            else -> "Tele"
        }

    val longName: String
        get() = when {
            ratio <= 0f -> "Cámara $id"
            ratio < 0.8f -> "Ultra gran angular"
            ratio < 1.25f -> if (front) "Frontal principal" else "Principal"
            else -> "Teleobjetivo"
        }
}

object CameraCatalog {

    private class Raw(val id: String, val parentId: String?, val front: Boolean, val eq: Float)

    /** Busca todos los lentes de la cámara frontal y trasera, incluidos los lentes físicos ocultos. */
    fun scan(context: Context): List<Lens> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            cm.cameraIdList.toList()
        } catch (e: Exception) {
            return emptyList()
        }

        val raws = mutableListOf<Raw>()
        for (id in ids) {
            val ch = characteristics(cm, id) ?: continue
            val front = isFront(ch) ?: continue
            if (!usable(ch)) continue
            raws += Raw(id, null, front, equivalentFocal(ch))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (pid in ch.physicalCameraIds) {
                    if (pid in ids) continue
                    val pch = characteristics(cm, pid) ?: continue
                    if (!usable(pch)) continue
                    raws += Raw(pid, id, front, equivalentFocal(pch))
                }
            }
        }

        val result = mutableListOf<Lens>()
        for (front in listOf(false, true)) {
            // Primero las cámaras que se abren directo; los lentes físicos solo si aportan algo nuevo.
            val group = raws.filter { it.front == front }
                .sortedWith(compareBy({ it.parentId != null }, { it.id.toIntOrNull() ?: 999 }))
            val kept = mutableListOf<Raw>()
            for (r in group) {
                val duplicate = r.eq > 0f && kept.any { it.eq > 0f && abs(it.eq - r.eq) / it.eq < 0.08f }
                if (!duplicate) kept += r
            }
            val mainEq = kept.filter { it.eq > 0f }.minByOrNull { abs(it.eq - 26f) }?.eq ?: 0f

            val lenses = kept.map { r ->
                val ratio = if (r.eq > 0f && mainEq > 0f) r.eq / mainEq else 0f
                Lens(r.id, r.parentId, front, r.eq, ratio, null)
            }.toMutableList()

            // Muchos teléfonos ofrecen el ultra gran angular solo como zoom < 1x de la cámara principal.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lenses.none { it.ratio in 0.01f..0.8f }) {
                val logical = kept.firstOrNull { it.parentId == null }
                val ch = logical?.let { characteristics(cm, it.id) }
                val range = ch?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (logical != null && range != null && range.lower < 0.95f) {
                    lenses += Lens(logical.id, null, front, logical.eq * range.lower, range.lower, range.lower)
                }
            }
            result += lenses.sortedBy { if (it.ratio > 0f) it.ratio else 999f }
        }
        return result
    }

    /** Cámaras que Android permite abrir al mismo tiempo que [id] (Android 11 o superior). */
    fun concurrentPartners(context: Context, id: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val partners = mutableSetOf<String>()
            for (group in cm.concurrentCameraIds) {
                if (id in group) partners += group.filter { it != id }
            }
            partners
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun characteristics(cm: CameraManager, id: String): CameraCharacteristics? =
        try {
            cm.getCameraCharacteristics(id)
        } catch (e: Exception) {
            null
        }

    private fun isFront(ch: CameraCharacteristics): Boolean? =
        when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> true
            CameraMetadata.LENS_FACING_BACK -> false
            else -> null
        }

    /** Descarta cámaras de profundidad, monocromáticas o infrarrojas que no sirven para video normal. */
    private fun usable(ch: CameraCharacteristics): Boolean {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (caps != null && !caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
            return false
        }
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        if (cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
            cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR
        ) return false
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
        return sizes != null && sizes.isNotEmpty()
    }

    /** Distancia focal equivalente en formato de 35 mm (así se comparan los lentes entre sí). */
    private fun equivalentFocal(ch: CameraCharacteristics): Float {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val diagonal = hypot(sensor.width, sensor.height)
        if (diagonal <= 0f) return 0f
        return focal * 43.27f / diagonal
    }
}
