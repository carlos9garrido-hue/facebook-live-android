package com.carlos.fblive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.base.StreamBase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Un texto o una imagen que se dibuja encima del video de la transmisión. */
data class OverlayItem(
    val id: String,
    val kind: String, // "text" o "image"
    var text: String = "",
    var color: Int = Color.WHITE,
    var bold: Boolean = true,
    var background: Boolean = true,
    /** Ancho de la capa como porcentaje del ancho del video. */
    var sizePercent: Int = 35,
    /** 0..8: fila (arriba, centro, abajo) × columna (izquierda, centro, derecha). */
    var position: Int = 7,
    var opacity: Int = 100,
    var visible: Boolean = true,
    var imageFile: String? = null
) {
    val isText: Boolean get() = kind == KIND_TEXT

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("text", text)
        put("color", color)
        put("bold", bold)
        put("background", background)
        put("size", sizePercent)
        put("position", position)
        put("opacity", opacity)
        put("visible", visible)
        put("image", imageFile ?: "")
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"

        fun fromJson(o: JSONObject) = OverlayItem(
            id = o.getString("id"),
            kind = o.optString("kind", KIND_TEXT),
            text = o.optString("text", ""),
            color = o.optInt("color", Color.WHITE),
            bold = o.optBoolean("bold", true),
            background = o.optBoolean("background", true),
            sizePercent = o.optInt("size", 35),
            position = o.optInt("position", 7),
            opacity = o.optInt("opacity", 100),
            visible = o.optBoolean("visible", true),
            imageFile = o.optString("image", "").ifEmpty { null }
        )
    }
}

/** Un video pregrabado que se puede transmitir en lugar de la cámara (por ejemplo una intro). */
data class Scene(val name: String, val uri: String)

object SceneStore {
    fun load(json: String): MutableList<Scene> {
        val list = mutableListOf<Scene>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Scene(o.getString("name"), o.getString("uri"))
            }
        }
        return list
    }

    fun toJson(list: List<Scene>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        return arr.toString()
    }

    fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return name ?: "Video"
    }
}

/**
 * Guarda las capas y las dibuja sobre la transmisión con filtros de imagen.
 * Cada capa (texto o imagen) se convierte en una imagen y se coloca en el video.
 */
class OverlayManager(private val context: Context, private val settings: AppSettings) {

    val items: MutableList<OverlayItem> = mutableListOf()

    private val renders = mutableListOf<ImageFilterRender>()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private val dir = File(context.filesDir, "overlays").apply { mkdirs() }

    init {
        runCatching {
            val arr = JSONArray(settings.overlaysJson)
            for (i in 0 until arr.length()) items += OverlayItem.fromJson(arr.getJSONObject(i))
        }
    }

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        settings.overlaysJson = arr.toString()
    }

    fun addText(): OverlayItem {
        val item = OverlayItem(id = UUID.randomUUID().toString(), kind = OverlayItem.KIND_TEXT, text = "Tu texto aquí")
        items += item
        save()
        return item
    }

    /** Rótulos listos: "nombre" (nombre y redes abajo a la izquierda), "redes" (solo redes arriba a la derecha), "sigueme". */
    fun addTemplate(kind: String, name: String, social: String): OverlayItem {
        val n = name.trim().ifEmpty { "Tu nombre" }
        val r = social.trim().ifEmpty { "@tu_usuario" }
        val item = when (kind) {
            TEMPLATE_SOCIAL -> OverlayItem(UUID.randomUUID().toString(), OverlayItem.KIND_TEXT, text = r, sizePercent = 22, position = 2, bold = false)
            TEMPLATE_FOLLOW -> OverlayItem(UUID.randomUUID().toString(), OverlayItem.KIND_TEXT, text = "Sígueme en $r", sizePercent = 40, position = 7)
            else -> OverlayItem(UUID.randomUUID().toString(), OverlayItem.KIND_TEXT, text = "$n\n$r", sizePercent = 32, position = 6)
        }
        items += item
        save()
        return item
    }

    /** Copia la imagen elegida a la carpeta de la app para no depender del permiso del selector. */
    fun addImage(uri: Uri): OverlayItem? {
        val bitmap = decodeScaled(uri) ?: return null
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.png")
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { return null }
        val item = OverlayItem(
            id = id,
            kind = OverlayItem.KIND_IMAGE,
            sizePercent = 25,
            position = 2,
            imageFile = file.name
        )
        items += item
        save()
        return item
    }

    fun remove(item: OverlayItem) {
        items.remove(item)
        item.imageFile?.let { File(dir, it).delete() }
        save()
    }

    /** Vuelve a dibujar las capas poco después del último cambio (para no hacerlo en cada letra). */
    fun scheduleRebuild(action: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { action() }
        pending = r
        handler.postDelayed(r, 350)
    }

    /** Quita las capas anteriores y coloca las visibles. Hay que llamarlo con la vista previa activa. */
    fun attach(stream: StreamBase, streamWidth: Int, streamHeight: Int) {
        detach(stream)
        for (item in items) {
            if (!item.visible) continue
            val bitmap = (if (item.isText) textBitmap(item) else imageBitmap(item)) ?: continue
            val render = ImageFilterRender()
            render.setImage(bitmap)
            var sx = item.sizePercent.coerceIn(3, 100).toFloat()
            var sy = sx * streamWidth * bitmap.height / (streamHeight.toFloat() * bitmap.width)
            if (sy > 100f) {
                sx = sx * 100f / sy
                sy = 100f
            }
            render.setScale(sx, sy)
            render.setPosition(translate(item.position))
            render.alpha = item.opacity.coerceIn(5, 100) / 100f
            stream.getGlInterface().addFilter(render)
            renders += render
        }
    }

    companion object {
        const val TEMPLATE_NAME = "name"
        const val TEMPLATE_SOCIAL = "social"
        const val TEMPLATE_FOLLOW = "follow"
    }

    fun detach(stream: StreamBase) {
        for (r in renders) runCatching { stream.getGlInterface().removeFilter(r) }
        renders.clear()
    }

    private fun translate(position: Int): TranslateTo = when (position) {
        0 -> TranslateTo.TOP_LEFT
        1 -> TranslateTo.TOP
        2 -> TranslateTo.TOP_RIGHT
        3 -> TranslateTo.LEFT
        4 -> TranslateTo.CENTER
        5 -> TranslateTo.RIGHT
        6 -> TranslateTo.BOTTOM_LEFT
        7 -> TranslateTo.BOTTOM
        else -> TranslateTo.BOTTOM_RIGHT
    }

    private fun imageBitmap(item: OverlayItem): Bitmap? {
        val name = item.imageFile ?: return null
        return runCatching { BitmapFactory.decodeFile(File(dir, name).absolutePath) }.getOrNull()
    }

    private fun textBitmap(item: OverlayItem): Bitmap? {
        val text = item.text
        if (text.isBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 96f
            color = item.color
            typeface = if (item.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (!item.background) setShadowLayer(8f, 0f, 3f, 0xCC000000.toInt())
        }
        val width = min(ceil(Layout.getDesiredWidth(text, paint)).toInt(), 1600).coerceAtLeast(16)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val pad = if (item.background) 32 else 16
        val bitmap = Bitmap.createBitmap(width + pad * 2, max(layout.height, 1) + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (item.background) {
            // Fondo oscuro para letras claras y fondo claro para letras oscuras.
            val dark = ColorUtils.calculateLuminance(item.color) < 0.4
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) 0xDDFFFFFF.toInt() else 0xB3000000.toInt()
            }
            canvas.drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), 36f, 36f, bg)
        }
        canvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(canvas)
        return bitmap
    }

    private fun decodeScaled(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()
}
