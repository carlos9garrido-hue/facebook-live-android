package com.carlos.fblive

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import kotlin.math.roundToInt

/** Panel lateral con los ajustes de cámara, transmisión y multicámara. */
class SettingsPanel(
    private val a: MainActivity,
    private val body: LinearLayout,
    private val tabs: List<MaterialButton>
) {

    private var tab = 0
    private val dim = ContextCompat.getColor(a, R.color.text_dim)
    private val accent = ContextCompat.getColor(a, R.color.accent)

    init {
        tabs.forEachIndexed { index, button ->
            button.setOnClickListener {
                a.rememberTab(index)
                show(index)
            }
        }
    }

    fun show(index: Int = tab) {
        tab = index
        styleTabs()
        body.removeAllViews()
        try {
            when (index) {
                0 -> cameraTab()
                1 -> streamTab()
                else -> multiTab()
            }
        } catch (e: Exception) {
            note("No se pudo mostrar esta sección: ${e.message}")
        }
    }

    fun refresh() {
        if (a.isPanelOpen()) show(tab)
    }

    private fun styleTabs() {
        tabs.forEachIndexed { i, b ->
            b.backgroundTintList = ColorStateList.valueOf(if (i == tab) accent else Color.TRANSPARENT)
            b.setTextColor(Color.WHITE)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pestañas
    // ---------------------------------------------------------------------------------------------

    private fun cameraTab() {
        if (!a.stream.isOnPreview) {
            note("La cámara no está activa todavía.")
            return
        }
        val lens = a.currentLens
        note(
            if (lens != null) "Lente activo: ${lens.longName} (${lens.ratioText})"
            else "Lente activo: predeterminado"
        )
        note("Tip: pellizca la imagen para hacer zoom y toca para enfocar.")

        section("Imagen")
        val zoomRange = a.cam.getZoomRange()
        slider(
            "Zoom", zoomRange.lower, zoomRange.upper, a.camState.zoom, 0f,
            { String.format(Locale.US, "%.1f×", it) },
            { a.setZoomLevel(it) }
        )

        val chars = a.currentChars()
        val evRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        if (evRange != null && evRange.upper > evRange.lower && evStep > 0f) {
            slider(
                "Exposición", evRange.lower.toFloat(), evRange.upper.toFloat(), a.camState.ev.toFloat(), 1f,
                { String.format(Locale.US, "%+.1f EV", it * evStep) },
                { a.setEv(it.roundToInt()) }
            )
        }

        section("Enfoque")
        chips(listOf("Automático", "Manual"), if (a.camState.manualFocus) 1 else 0) { i ->
            a.setManualFocus(i == 1)
            show(tab)
        }
        if (a.camState.manualFocus) {
            val minDistance = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minDistance > 0f) {
                slider(
                    "Distancia (lejos → cerca)", 0f, minDistance, a.camState.focusDistance.coerceIn(0f, minDistance), 0f,
                    { if (it < 0.05f) "Infinito" else String.format(Locale.US, "%.0f cm", 100f / it) },
                    { a.setFocusDistance(it) }
                )
            } else {
                note("Este lente tiene enfoque fijo.")
            }
        }

        val awbModes = a.cam.getAutoWhiteBalanceModesAvailable()
        val wb = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Luz del día",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Nublado",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "Sombra",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Incandescente",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescente",
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Atardecer"
        ).filter { it.first in awbModes }
        if (wb.size > 1) {
            section("Balance de blancos")
            val current = if (a.camState.awbMode < 0) CameraMetadata.CONTROL_AWB_MODE_AUTO else a.camState.awbMode
            chips(wb.map { it.second }, wb.indexOfFirst { it.first == current }.coerceAtLeast(0)) { i ->
                a.setAwbMode(wb[i].first)
            }
        }

        section("Pro")
        toggle("Bloquear exposición", a.camState.aeLock) { a.setAeLock(it) }
        toggle("Bloquear balance de blancos", a.camState.awbLock) { a.setAwbLock(it) }
        toggle("Estabilización de imagen", a.camState.stabilization) { on ->
            if (!a.setStabilization(on)) body.post { show(tab) }
        }
        toggle("Linterna", a.camState.torch) { on ->
            if (!a.setTorch(on)) body.post { show(tab) }
        }
        toggle("Cuadrícula de encuadre", a.settings.grid) { a.setGrid(it) }
    }

    private fun streamTab() {
        section("Facebook Live")
        note("En Facebook abre Live Producer, elige «Software de transmisión» y copia la URL del servidor y la clave.")
        label("URL del servidor")
        field("rtmps://…", a.settings.url, false) { a.settings.url = it }
        label("Clave de transmisión")
        field("Pega aquí tu clave", a.streamKey, true) { a.streamKey = it }
        note("La clave no se guarda en el teléfono: hay que pegarla en cada sesión.")

        section("Video")
        if (a.isLive) {
            note("Los ajustes de video se bloquean mientras estás en vivo.")
        } else {
            label("Resolución")
            chips(listOf("720p", "1080p"), if (a.settings.height == 1080) 1 else 0) { i ->
                a.settings.height = if (i == 1) 1080 else 720
                a.reconfigure()
            }
            label("Cuadros por segundo")
            chips(listOf("30 fps", "60 fps"), if (a.settings.fps == 60) 1 else 0) { i ->
                a.settings.fps = if (i == 1) 60 else 30
                a.reconfigure()
            }
            slider(
                "Bitrate de video", 1000f, 8000f, a.settings.bitrateKbps.toFloat(), 250f,
                { String.format(Locale.US, "%.2f Mbps", it / 1000f) },
                {},
                { v ->
                    a.settings.bitrateKbps = v.roundToInt()
                    a.reconfigure()
                }
            )
            note("Facebook recomienda 1080p a 30 fps con 4 a 6 Mbps. Si tu internet es lento, baja a 720p.")
            label("Orientación")
            chips(listOf("Horizontal", "Vertical"), if (a.settings.vertical) 1 else 0) { i ->
                val vertical = i == 1
                if (vertical != a.settings.vertical) {
                    a.settings.vertical = vertical
                    a.recreate()
                }
            }
        }

        section("Audio y red")
        if (!a.isLive) {
            toggle("Reducción de ruido del micrófono", a.settings.noiseSuppression) {
                a.settings.noiseSuppression = it
                a.reconfigure()
            }
        }
        toggle("Bitrate adaptativo (baja la calidad si la red falla)", a.settings.adaptiveBitrate) {
            a.settings.adaptiveBitrate = it
            a.setupBitrateAdapter()
        }
    }

    private fun multiTab() {
        section("Dos cámaras a la vez")
        if (!a.stream.isOnPreview) {
            note("La cámara no está activa todavía.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            note("Esta función necesita Android 11 o superior.")
            return
        }
        val candidates = a.pipCandidates()
        if (candidates.isEmpty()) {
            note(
                "Con el lente actual, Android no permite abrir otra cámara al mismo tiempo en este teléfono. " +
                    "Prueba cambiando de lente (por ejemplo de trasera a frontal)."
            )
            return
        }
        note("EXPERIMENTAL: muestra una segunda cámara en una esquina de tu video. Algunos teléfonos no lo permiten.")
        toggle("Imagen en imagen", a.pipWanted) { on ->
            if (on) a.startPip() else a.stopPip()
            body.post { show(tab) }
        }
        if (!a.pipWanted) return

        section("Cámara secundaria")
        val names = candidates.map { (if (it.front) "Frontal " else "Trasera ") + it.ratioText }
        val selected = candidates.indexOfFirst { it.key == a.settings.pipCameraKey }.coerceAtLeast(0)
        chips(names, selected) { i ->
            a.settings.pipCameraKey = candidates[i].key
            a.startPip()
        }

        section("Posición")
        chips(
            listOf("Arriba izq.", "Arriba der.", "Abajo izq.", "Abajo der."),
            a.settings.pipCorner
        ) { i ->
            a.settings.pipCorner = i
            a.updatePip()
        }
        slider(
            "Tamaño", 15f, 45f, a.settings.pipSize.toFloat().coerceIn(15f, 45f), 1f,
            { "${it.roundToInt()}%" },
            {
                a.settings.pipSize = it.roundToInt()
                a.updatePip()
            }
        )
        label("Rotación de la imagen")
        chips(listOf("0°", "90°", "180°", "270°"), a.settings.pipRotation / 90) { i ->
            a.settings.pipRotation = i * 90
            a.updatePip()
        }
        note("Si la segunda cámara se ve girada o de lado, cambia la rotación.")
    }

    // ---------------------------------------------------------------------------------------------
    // Piezas de interfaz
    // ---------------------------------------------------------------------------------------------

    private fun section(text: String) {
        body.addView(TextView(a).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, a.dp(18), 0, a.dp(4))
        })
    }

    private fun label(text: String) {
        body.addView(TextView(a).apply {
            this.text = text
            setTextColor(dim)
            textSize = 12f
            setPadding(0, a.dp(10), 0, a.dp(4))
        })
    }

    private fun note(text: String) {
        body.addView(TextView(a).apply {
            this.text = text
            setTextColor(dim)
            textSize = 12f
            setPadding(0, a.dp(6), 0, a.dp(2))
        })
    }

    private fun field(hint: String, value: String, password: Boolean, onText: (String) -> Unit) {
        body.addView(EditText(a).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(dim)
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_field)
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            doAfterTextChanged { onText(it?.toString().orEmpty().trim()) }
        })
    }

    private fun slider(
        title: String,
        from: Float,
        to: Float,
        value: Float,
        step: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
        onDone: ((Float) -> Unit)? = null
    ) {
        if (to <= from) {
            note("$title: no disponible en esta cámara.")
            return
        }
        val label = TextView(a).apply {
            text = "$title: ${format(value)}"
            setTextColor(dim)
            textSize = 12f
            setPadding(0, a.dp(10), 0, 0)
        }
        val start = if (step > 0f) from + ((value.coerceIn(from, to) - from) / step).roundToInt() * step
        else value.coerceIn(from, to)
        val s = Slider(a).apply {
            valueFrom = from
            valueTo = to
            if (step > 0f) stepSize = step
            this.value = start.coerceIn(from, to)
            addOnChangeListener { _, v, fromUser ->
                label.text = "$title: ${format(v)}"
                if (fromUser) onChange(v)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    onDone?.invoke(slider.value)
                }
            })
        }
        body.addView(label)
        body.addView(s)
    }

    private fun chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val group = ChipGroup(a).apply {
            chipSpacingHorizontal = a.dp(8)
            chipSpacingVertical = a.dp(8)
        }
        val views = mutableListOf<TextView>()
        options.forEachIndexed { index, text ->
            val view = TextView(a).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
                setBackgroundResource(if (index == selected) R.drawable.bg_chip_on else R.drawable.bg_field)
            }
            view.setOnClickListener {
                views.forEachIndexed { j, v ->
                    v.setBackgroundResource(if (j == index) R.drawable.bg_chip_on else R.drawable.bg_field)
                }
                onSelect(index)
            }
            views += view
            group.addView(view)
        }
        body.addView(group)
    }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        body.addView(SwitchMaterial(a).apply {
            this.text = text
            isChecked = checked
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, a.dp(6), 0, a.dp(6))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }
}
