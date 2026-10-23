package com.carlos.fblive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.pedro.encoder.input.audio.CustomAudioEffect
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Guarda el volumen más alto que llegó del micrófono desde la última lectura. */
class MicLevel {
    private val frames = AtomicInteger(0)
    private val peakMax = AtomicInteger(0)

    /** Momento (reloj del sistema) en que llegó el último audio. */
    @Volatile
    var updatedAt = 0L
        private set

    /** Volumen aplicado al medir el micrófono que se escucha antes de transmitir (la transmisión ya lo trae aplicado). */
    @Volatile
    var gain = 1f

    fun feed(buffer: ByteArray, size: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            val a = abs(sample)
            if (a > peak) peak = a
            i += 2
        }
        push(peak)
    }

    fun feed(buffer: ShortArray, size: Int) {
        var peak = 0
        for (i in 0 until size) {
            val a = abs(buffer[i].toInt())
            if (a > peak) peak = a
        }
        push(minOf(32767f, peak * gain).toInt())
    }

    private fun push(peak: Int) {
        peakMax.accumulateAndGet(peak) { x, y -> if (x > y) x else y }
        frames.incrementAndGet()
        updatedAt = SystemClock.elapsedRealtime()
    }

    /** Devuelve el pico desde la última lectura, o -1 si no llegó audio nuevo. */
    fun take(): Int {
        if (frames.getAndSet(0) == 0) return -1
        return peakMax.getAndSet(0)
    }
}

/** Se conecta al micrófono de la transmisión y mide el volumen sin cambiar el sonido. */
class MicLevelEffect(private val level: MicLevel) : CustomAudioEffect() {
    override fun process(pcmBuffer: ByteArray): ByteArray {
        level.feed(pcmBuffer, pcmBuffer.size)
        return pcmBuffer
    }
}

/** Escucha el micrófono para mostrar el nivel mientras todavía no se transmite. */
class MicMonitor(private val level: MicLevel) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Entrada de audio que se escucha (null = la predeterminada del teléfono). */
    @Volatile
    var preferred: android.media.AudioDeviceInfo? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val t = Thread {
            var record: AudioRecord? = null
            try {
                val rate = 44100
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) return@Thread
                record = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) return@Thread
                preferred?.let { runCatching { record.setPreferredDevice(it) } }
                record.startRecording()
                val buffer = ShortArray(min / 2)
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) level.feed(buffer, n) else if (n < 0) break
                }
            } catch (e: Exception) {
                // Si el micrófono no se puede abrir, el medidor mostrará "sin lectura".
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
            }
        }
        thread = t
        t.start()
    }

    /** Detiene la escucha y libera el micrófono antes de seguir (para que lo use la transmisión). */
    fun stop() {
        running = false
        runCatching { thread?.join(800) }
        thread = null
    }
}

/** Barra vertical de nivel de audio, con tramos verde, amarillo y rojo. */
class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val segments = 24
    private var level = 0f
    private var peak = 0f
    private var peakAt = 0L
    private var warn = false

    /** [db] va de -60 (silencio) a 0 (máximo). [problem] pinta la barra de rojo. */
    fun setLevel(db: Float, problem: Boolean) {
        val f = ((db + 60f) / 60f).coerceIn(0f, 1f)
        level = f
        val now = SystemClock.elapsedRealtime()
        if (f >= peak || now - peakAt > 900) {
            peak = f
            peakAt = now
        }
        warn = problem
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = h * 0.012f
        val segH = (h - gap * (segments - 1)) / segments
        val lit = (level * segments).toInt()
        val peakIndex = (peak * segments).toInt().coerceAtMost(segments - 1)
        for (i in 0 until segments) {
            val fromBottom = i
            val top = h - (fromBottom + 1) * segH - fromBottom * gap
            rect.set(0f, top, w, top + segH)
            val frac = fromBottom / segments.toFloat()
            val base = when {
                warn -> Color.rgb(255, 59, 78)
                frac > 0.85f -> Color.rgb(255, 59, 78)
                frac > 0.65f -> Color.rgb(255, 176, 32)
                else -> Color.rgb(46, 204, 113)
            }
            val on = fromBottom < lit || (peak > 0f && fromBottom == peakIndex)
            paint.color = if (on) base else Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(rect, w / 3f, w / 3f, paint)
        }
    }
}
