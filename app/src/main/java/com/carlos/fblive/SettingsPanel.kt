package com.carlos.fblive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private var diagVisible = false

    /** Dónde se agregan las piezas de interfaz (el cuerpo del panel o una tarjeta). */
    private var target: LinearLayout = body
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
        target = body
        try {
            when (index) {
                0 -> cameraTab()
                1 -> streamTab()
                2 -> multiTab()
                else -> capasTab()
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
        if (a.sceneActive || a.camOrNull == null) {
            note("Hay una escena de video en curso: los ajustes de cámara vuelven cuando termine.")
            button("Volver a la cámara ahora") { a.endScene() }
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
        val presets = listOf(0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f)
            .filter { it >= zoomRange.lower - 0.01f && it <= zoomRange.upper + 0.01f }
        if (presets.size > 1) {
            label("Zoom rápido")
            val selectedPreset = presets.indexOfFirst { kotlin.math.abs(it - a.camState.zoom) < 0.05f }
            chips(presets.map { String.format(Locale.US, "%s×", if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()) }, selectedPreset) { i ->
                a.setZoomLevel(presets[i])
                show(tab)
            }
        }

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

        section("Lentes de este teléfono")
        val shown = a.lenses.count { it.front == a.settings.front }
        note(
            "Lentes encontrados en la cámara ${if (a.settings.front) "frontal" else "trasera"}: $shown. " +
                "Muchos fabricantes solo dejan usar un lente a las apps; el informe muestra qué ve Android."
        )
        button(if (diagVisible) "Ocultar informe de cámaras" else "Ver informe de cámaras") {
            diagVisible = !diagVisible
            show(tab)
        }
        if (diagVisible) {
            val report = CameraCatalog.diagnostics(a, a.lenses)
            target.addView(TextView(a).apply {
                text = report
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, a.dp(8), 0, 0)
            })
            button("Copiar informe") {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Cámaras", report))
                a.toast("Informe copiado")
            }
        }
    }

    private fun streamTab() {
        section("Facebook Live")
        note("En Facebook abre Live Producer, elige «Software de transmisión» y copia la URL del servidor y la clave.")
        label("URL del servidor")
        field("rtmps://…", a.settings.url, false) { a.settings.url = it }
        label("Clave de transmisión")
        field("Pega aquí tu clave", a.streamKey, true) {
            a.streamKey = it
            if (a.settings.rememberKey) a.settings.saveStreamKey(it)
        }
        toggle("Recordar la clave en este teléfono", a.settings.rememberKey) { on ->
            if (on) {
                if (a.settings.saveStreamKey(a.streamKey)) {
                    a.settings.rememberKey = true
                } else {
                    a.settings.rememberKey = false
                    a.toast("Este teléfono no pudo guardar la clave de forma segura")
                }
            } else {
                a.settings.rememberKey = false
                a.settings.clearStreamKey()
            }
            body.post { show(tab) }
        }
        note(
            if (a.settings.rememberKey) {
                "La clave se guarda cifrada en este teléfono. Apaga esta opción para borrarla."
            } else {
                "La clave no se guarda: hay que pegarla en cada sesión. Si usas una clave persistente de Facebook, activa la opción de arriba."
            }
        )

        section("Comprobar que sí transmite")
        note(
            "Cuando la insignia diga EN VIVO, Facebook aceptó la conexión, y los MB enviados de arriba deben ir subiendo. " +
                "En unos 10 a 20 segundos verás tu video en la vista previa de Live Producer; desde ahí lo publicas y ves los comentarios."
        )
        button("Abrir Live Producer en Facebook") { a.openLiveProducer() }

        section("Grabación en el teléfono")
        toggle("Grabar una copia mientras transmito", a.settings.recordLocal) {
            a.settings.recordLocal = it
            body.post { show(tab) }
        }
        val gbPerHour = (a.settings.bitrateKbps + 128) * 3600f / 8f / 1024f / 1024f
        note(
            if (a.settings.recordLocal) {
                String.format(
                    Locale.US,
                    "Al terminar, el video se copia a la galería (carpeta Movies/FacebookLive). Con tu calidad actual ocupa unos %.1f GB por hora. Si queda poco espacio, no se graba la copia.",
                    gbPerHour
                )
            } else {
                "No se guardará copia: si falla el internet, lo que no salió en vivo se pierde."
            }
        )

        section("Segundo plano")
        note(
            "Al usar el botón de inicio, apagar la pantalla o tocar atrás, la transmisión sigue y una notificación permite detenerla. " +
                "Si cierras la app desde la lista de apps recientes, la transmisión termina."
        )

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
        if (a.sceneActive || a.camOrNull == null) {
            note("Hay una escena de video en curso: la multicámara vuelve cuando termine.")
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

    private fun capasTab() {
        section("Textos e imágenes")
        note("Se dibujan encima del video de la transmisión: tu nombre, un logo, un mensaje o un aviso.")
        buttonRow(
            "+ Texto" to {
                a.overlays.addText()
                a.saveAndRebuildOverlays()
                show(tab)
            },
            "+ Imagen" to { a.chooseImage() }
        )
        for (item in a.overlays.items.toList()) overlayCard(item)

        section("Escenas de video")
        note(
            "Videos pregrabados, por ejemplo una intro. Se transmiten en lugar de la cámara y, al terminar, vuelve la cámara sola. " +
                "Funcionan mejor en modo horizontal. Para que se escuche en la transmisión, el audio del video debe ser 44.1 kHz estéreo."
        )
        if (a.sceneActive) button("Volver a la cámara ahora") { a.endScene() }
        button("+ Agregar video") { a.chooseVideo() }
        for (scene in a.scenes.toList()) sceneCard(scene)
        if (a.scenes.isNotEmpty()) {
            label("Intro al iniciar la transmisión")
            val names = listOf("Ninguna") + a.scenes.map { it.name.take(22) }
            val selected = if (a.settings.introEnabled) {
                a.scenes.indexOfFirst { it.uri == a.settings.introUri } + 1
            } else 0
            chips(names, selected.coerceAtLeast(0)) { i ->
                if (i == 0) {
                    a.settings.introEnabled = false
                    a.settings.introUri = null
                } else {
                    a.settings.introUri = a.scenes[i - 1].uri
                    a.settings.introEnabled = true
                }
            }
            note("La intro empieza apenas Facebook acepta la conexión.")
        }
    }

    private fun overlayCard(item: OverlayItem) {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_field)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(12))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = a.dp(12)
        body.addView(card, lp)
        target = card

        toggle(if (item.isText) "Mostrar texto" else "Mostrar imagen", item.visible) {
            item.visible = it
            a.saveAndRebuildOverlays()
        }
        if (item.isText) {
            textField("Escribe el texto", item.text) {
                item.text = it
                a.saveAndRebuildOverlays()
            }
            label("Color de la letra")
            colorRow(item)
            toggle("Letra en negrita", item.bold) {
                item.bold = it
                a.saveAndRebuildOverlays()
            }
            toggle("Fondo detrás del texto", item.background) {
                item.background = it
                a.saveAndRebuildOverlays()
            }
        }
        label("Posición en el video")
        positionGrid(item.position) {
            item.position = it
            a.saveAndRebuildOverlays()
        }
        slider(
            "Tamaño", 5f, 100f, item.sizePercent.toFloat(), 1f,
            { "${it.roundToInt()}%" },
            {
                item.sizePercent = it.roundToInt()
                a.saveAndRebuildOverlays()
            }
        )
        slider(
            "Opacidad", 10f, 100f, item.opacity.toFloat(), 5f,
            { "${it.roundToInt()}%" },
            {
                item.opacity = it.roundToInt()
                a.saveAndRebuildOverlays()
            }
        )
        button("Quitar") {
            a.overlays.remove(item)
            a.rebuildOverlays()
            show(tab)
        }
        target = body
    }

    private fun sceneCard(scene: Scene) {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_field)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(12))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = a.dp(10)
        body.addView(card, lp)
        target = card
        note(scene.name)
        buttonRow(
            "▶ Reproducir" to { a.playScene(scene) },
            "Quitar" to {
                a.removeScene(scene)
                show(tab)
            }
        )
        target = body
    }

    private fun colorRow(item: OverlayItem) {
        val colors = listOf(
            Color.WHITE, Color.BLACK, 0xFFFFD60A.toInt(), 0xFFFF3B4E.toInt(), 0xFF3D8BFF.toInt(), 0xFF2ECC71.toInt()
        )
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<View>()
        fun swatch(color: Int, selected: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(a.dp(if (selected) 3 else 1), if (selected) accent else 0x66FFFFFF)
        }
        colors.forEachIndexed { index, color ->
            val v = View(a).apply {
                background = swatch(color, color == item.color)
                setOnClickListener {
                    item.color = color
                    views.forEachIndexed { j, other -> other.background = swatch(colors[j], j == index) }
                    a.saveAndRebuildOverlays()
                }
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(a.dp(34), a.dp(34)).apply {
                setMargins(0, a.dp(4), a.dp(10), a.dp(4))
            })
        }
        target.addView(row)
    }

    private fun positionGrid(selected: Int, onSelect: (Int) -> Unit) {
        val symbols = listOf("↖", "↑", "↗", "←", "●", "→", "↙", "↓", "↘")
        val views = mutableListOf<TextView>()
        for (row in 0..2) {
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..2) {
                val i = row * 3 + col
                val v = TextView(a).apply {
                    text = symbols[i]
                    gravity = Gravity.CENTER
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setBackgroundResource(if (i == selected) R.drawable.bg_chip_on else R.drawable.bg_pill)
                    setOnClickListener {
                        views.forEachIndexed { j, other ->
                            other.setBackgroundResource(if (j == i) R.drawable.bg_chip_on else R.drawable.bg_pill)
                        }
                        onSelect(i)
                    }
                }
                views += v
                line.addView(v, LinearLayout.LayoutParams(0, a.dp(40), 1f).apply {
                    setMargins(a.dp(3), a.dp(3), a.dp(3), a.dp(3))
                })
            }
            target.addView(line)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Piezas de interfaz
    // ---------------------------------------------------------------------------------------------

    private fun section(text: String) {
        target.addView(TextView(a).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, a.dp(18), 0, a.dp(4))
        })
    }

    private fun label(text: String) {
        target.addView(TextView(a).apply {
            this.text = text
            setTextColor(dim)
            textSize = 12f
            setPadding(0, a.dp(10), 0, a.dp(4))
        })
    }

    private fun note(text: String) {
        target.addView(TextView(a).apply {
            this.text = text
            setTextColor(dim)
            textSize = 12f
            setPadding(0, a.dp(6), 0, a.dp(2))
        })
    }

    private fun button(text: String, onClick: () -> Unit) {
        val b = MaterialButton(a).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = a.dp(8)
        target.addView(b, lp)
    }

    private fun buttonRow(vararg actions: Pair<String, () -> Unit>) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { index, (text, onClick) ->
            val b = MaterialButton(a).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(if (index == 0) 0 else a.dp(4), a.dp(8), if (index == actions.size - 1) 0 else a.dp(4), 0)
            row.addView(b, lp)
        }
        target.addView(row)
    }

    private fun textField(hint: String, value: String, onText: (String) -> Unit) {
        target.addView(EditText(a).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(dim)
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_pill)
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            doAfterTextChanged { onText(it?.toString().orEmpty()) }
        })
    }

    private fun field(hint: String, value: String, password: Boolean, onText: (String) -> Unit) {
        target.addView(EditText(a).apply {
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
        target.addView(label)
        target.addView(s)
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
        target.addView(group)
    }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        target.addView(SwitchMaterial(a).apply {
            this.text = text
            isChecked = checked
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, a.dp(6), 0, a.dp(6))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }
}
