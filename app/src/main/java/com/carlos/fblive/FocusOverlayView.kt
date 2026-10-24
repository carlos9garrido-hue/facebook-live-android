package com.carlos.fblive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Dibuja sobre la vista previa el anillo de enfoque y el nivel de zoom. No recibe toques. */
class FocusOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * density, 0f, 0f, 0xCC000000.toInt())
    }

    private var fx = 0f
    private var fy = 0f
    private var focusAt = 0L
    private var focusOk = true
    private var locked = false
    private var focusShown = false

    private var zoomText: String? = null
    private var zoomAt = 0L

    fun showFocus(x: Float, y: Float, lock: Boolean, ok: Boolean) {
        fx = x
        fy = y
        locked = lock
        focusOk = ok
        focusAt = SystemClock.uptimeMillis()
        focusShown = true
        postInvalidateOnAnimation()
    }

    fun clearFocus() {
        focusShown = false
        locked = false
        postInvalidateOnAnimation()
    }

    fun showZoom(zoom: Float) {
        zoomText = String.format(java.util.Locale.US, "%.1f×", zoom)
        zoomAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var again = false

        if (focusShown) {
            val t = now - focusAt
            val alpha = if (locked) 1f else (1f - max(0f, (t - 1000f) / 500f)).coerceIn(0f, 1f)
            if (alpha <= 0f) {
                focusShown = false
            } else {
                val scale = 1f + 0.35f * (1f - min(1f, t / 220f))
                val r = 38f * density * scale
                val base = if (!focusOk) 0xFFFFB020.toInt() else if (locked) 0xFFFFD60A.toInt() else Color.WHITE
                ringPaint.color = base
                ringPaint.alpha = (alpha * 255).toInt()
                canvas.drawCircle(fx, fy, r, ringPaint)
                val tick = 6f * density
                canvas.drawLine(fx - r, fy, fx - r + tick, fy, ringPaint)
                canvas.drawLine(fx + r, fy, fx + r - tick, fy, ringPaint)
                canvas.drawLine(fx, fy - r, fx, fy - r + tick, ringPaint)
                canvas.drawLine(fx, fy + r, fx, fy + r - tick, ringPaint)
                if (locked) {
                    textPaint.textSize = 12f * density
                    textPaint.alpha = 255
                    canvas.drawText("ENFOQUE Y LUZ BLOQUEADOS", fx, fy + r + 18f * density, textPaint)
                    textPaint.textSize = 10f * density
                    canvas.drawText("toca para soltar", fx, fy + r + 32f * density, textPaint)
                } else if (!focusOk) {
                    textPaint.textSize = 11f * density
                    textPaint.alpha = (alpha * 255).toInt()
                    canvas.drawText("este lente no enfoca por toque", fx, fy + r + 18f * density, textPaint)
                }
                again = true
            }
        }

        val z = zoomText
        if (z != null) {
            val t = now - zoomAt
            val alpha = (1f - max(0f, (t - 700f) / 400f)).coerceIn(0f, 1f)
            if (alpha <= 0f) {
                zoomText = null
            } else {
                textPaint.textSize = 30f * density
                textPaint.alpha = (alpha * 255).toInt()
                canvas.drawText(z, width / 2f, height * 0.22f, textPaint)
                again = true
            }
        }
        textPaint.alpha = 255
        if (again) postInvalidateOnAnimation()
    }
}
