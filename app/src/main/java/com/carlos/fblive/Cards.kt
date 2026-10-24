package com.carlos.fblive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.base.StreamBase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

/** Una pantalla completa que tapa el video: «Ya regreso», pausa o cuenta regresiva. */
data class ScreenCard(
    val id: String,
    val kind: String, // "brb", "pause" o "countdown"
    var title: String,
    var subtitle: String,
    var color: Int,
    var minutes: Int = 5,
    var muteMic: Boolean = true
) {
    val isCountdown: Boolean get() = kind == CardManager.KIND_COUNTDOWN

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("subtitle", subtitle)
        put("color", color)
        put("minutes", minutes)
        put("mute", muteMic)
    }

    companion object {
        fun fromJson(o: JSONObject) = ScreenCard(
            id = o.getString("id"),
            kind = o.optString("kind", CardManager.KIND_BRB),
            title = o.optString("title", ""),
            subtitle = o.optString("subtitle", ""),
            color = o.optInt("color", 0xFF1E3A8A.toInt()),
            minutes = o.optInt("minutes", 5),
            muteMic = o.optBoolean("mute", true)
        )
    }
}

/**
 * Guarda las pantallas listas y muestra una a la vez sobre el video, a pantalla completa.
 * La cuenta regresiva se vuelve a dibujar cada segundo.
 */
class CardManager(private val settings: AppSettings) {

    val items: MutableList<ScreenCard> = mutableListOf()

    var activeId: String? = null
        private set

    /** Se llama cuando una pantalla se muestra u oculta (para refrescar botones y avisos). */
    var onChanged: (() -> Unit)? = null

    /** Se llama con true para silenciar el micrófono y con false para devolverlo. */
    var onMute: ((Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var render: ImageFilterRender? = null
    private var stream: StreamBase? = null
    private var width = 0
    private var height = 0
    private var endAt = 0L
    private var finished = false
    private var mutedByCard = false

    private val tick = object : Runnable {
        override fun run() {
            val card = active() ?: return
            if (!card.isCountdown) return
            val remaining = remainingSeconds()
            if (remaining <= 0 && !finished) {
                finished = true
                redraw()
                handler.postDelayed({ hide() }, 3000)
                return
            }
            redraw()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        if (settings.cardsJson.isBlank()) {
            addTemplate(KIND_BRB)
            addTemplate(KIND_PAUSE)
            addTemplate(KIND_COUNTDOWN)
        } else {
            runCatching {
                val arr = JSONArray(settings.cardsJson)
                for (i in 0 until arr.length()) items += ScreenCard.fromJson(arr.getJSONObject(i))
            }
        }
    }

    fun active(): ScreenCard? = items.firstOrNull { it.id == activeId }

    fun isActive(card: ScreenCard) = card.id == activeId

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        settings.cardsJson = arr.toString()
    }

    fun addTemplate(kind: String): ScreenCard {
        val card = when (kind) {
            KIND_PAUSE -> ScreenCard(newId(), kind, "En pausa", "Enseguida continuamos", 0xFF374151.toInt())
            KIND_COUNTDOWN -> ScreenCard(newId(), kind, "Empezamos en", "Gracias por esperar", 0xFF7C3AED.toInt(), minutes = 5)
            else -> ScreenCard(newId(), KIND_BRB, "Ya regreso", "Volvemos en un momento", 0xFF1E3A8A.toInt())
        }
        items += card
        save()
        return card
    }

    fun remove(card: ScreenCard) {
        if (isActive(card)) hide()
        items.remove(card)
        save()
    }

    fun toggle(card: ScreenCard, stream: StreamBase, w: Int, h: Int) {
        if (isActive(card)) hide() else show(card, stream, w, h)
    }

    fun show(card: ScreenCard, stream: StreamBase, w: Int, h: Int) {
        if (activeId != null) hide(notify = false)
        this.stream = stream
        width = w
        height = h
        finished = false
        activeId = card.id
        if (card.isCountdown) endAt = SystemClock.elapsedRealtime() + card.minutes.coerceAtLeast(1) * 60_000L
        attach()
        if (card.muteMic) {
            mutedByCard = true
            onMute?.invoke(true)
        }
        handler.removeCallbacks(tick)
        if (card.isCountdown) handler.postDelayed(tick, 1000)
        onChanged?.invoke()
    }

    fun hide(notify: Boolean = true) {
        handler.removeCallbacksAndMessages(null)
        detach()
        activeId = null
        if (mutedByCard) {
            mutedByCard = false
            onMute?.invoke(false)
        }
        if (notify) onChanged?.invoke()
    }

    /** Si los filtros del video se reconstruyeron, vuelve a poner la pantalla activa encima de todo. */
    fun reattach(stream: StreamBase, w: Int, h: Int) {
        if (activeId == null) return
        this.stream = stream
        width = w
        height = h
        attach()
    }

    /** Dibuja de nuevo la pantalla activa (por ejemplo después de editar su texto). */
    fun redraw() {
        val card = active() ?: return
        val r = render ?: return
        r.setImage(draw(card))
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        detach()
        activeId = null
    }

    private fun attach() {
        val s = stream ?: return
        val card = active() ?: return
        detach()
        val r = ImageFilterRender()
        r.setImage(draw(card))
        r.setScale(100f, 100f)
        r.setPosition(TranslateTo.CENTER)
        runCatching { s.getGlInterface().addFilter(r) }
        render = r
    }

    private fun detach() {
        val r = render ?: return
        runCatching { stream?.getGlInterface()?.removeFilter(r) }
        render = null
    }

    private fun remainingSeconds(): Int =
        ceil((endAt - SystemClock.elapsedRealtime()) / 1000.0).toInt().coerceAtLeast(0)

    // ---------------------------------------------------------------------------------------------
    // Dibujo
    // ---------------------------------------------------------------------------------------------

    private fun draw(card: ScreenCard): Bitmap {
        val w = width.coerceAtLeast(16)
        val h = height.coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), card.color, darken(card.color, 0.55f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val base = min(w, h).toFloat()
        val textWidth = (w * 0.84f).toInt()
        val blocks = mutableListOf<StaticLayout>()
        val gaps = mutableListOf<Float>()

        fun block(text: String, size: Float, bold: Boolean, mono: Boolean, gapAfter: Float) {
            if (text.isBlank()) return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = size
                typeface = when {
                    mono -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    bold -> Typeface.DEFAULT_BOLD
                    else -> Typeface.DEFAULT
                }
                setShadowLayer(size * 0.08f, 0f, size * 0.04f, 0x66000000)
            }
            blocks += StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            gaps += gapAfter
        }

        if (card.isCountdown) {
            val seconds = if (finished) 0 else remainingSeconds()
            block(card.title, base * 0.07f, true, false, base * 0.03f)
            block(formatTime(seconds), base * 0.22f, true, true, base * 0.03f)
            block(if (finished) "¡Ya empezamos!" else card.subtitle, base * 0.05f, false, false, 0f)
        } else {
            block(card.title, base * 0.11f, true, false, base * 0.04f)
            block(card.subtitle, base * 0.05f, false, false, 0f)
        }

        var total = 0f
        blocks.forEachIndexed { i, layout -> total += layout.height + gaps[i] }
        var y = (h - total) / 2f
        blocks.forEachIndexed { i, layout ->
            canvas.save()
            canvas.translate((w - textWidth) / 2f, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gaps[i]
        }
        return bmp
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(),
        (Color.green(color) * factor).toInt(),
        (Color.blue(color) * factor).toInt()
    )

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds / 60) % 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        const val KIND_BRB = "brb"
        const val KIND_PAUSE = "pause"
        const val KIND_COUNTDOWN = "countdown"
    }
}
