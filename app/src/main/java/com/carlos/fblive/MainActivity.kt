package com.carlos.fblive

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.encoder.input.sources.audio.AudioFileSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoFileSource
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Valores de cámara que se vuelven a aplicar cada vez que se abre un lente. */
class CamState {
    var zoom = 1f
    var ev = 0
    var aeLock = false
    var awbLock = false
    var awbMode = -1
    var stabilization = false
    var manualFocus = false
    var focusDistance = 0f
    var torch = false
}

/**
 * Facebook Live de bolsillo: transmite a Facebook por RTMPS con control de lentes y cámara.
 * La URL del servidor y la clave se obtienen en Facebook Live Producer.
 */
class MainActivity : AppCompatActivity(), ConnectChecker {

    private enum class Status { IDLE, CONNECTING, LIVE, ERROR }

    internal lateinit var settings: AppSettings
    internal lateinit var stream: RtmpStream
    internal val camState = CamState()
    internal var lenses: List<Lens> = emptyList()
    internal var currentLens: Lens? = null
    internal var streamKey: String = ""
    internal var pipWanted = false
    internal val pip by lazy { PipCamera(this) { message -> toast(message); onPipStopped() } }

    internal val cam: Camera2Source get() = stream.videoSource as Camera2Source
    internal val camOrNull: Camera2Source? get() = stream.videoSource as? Camera2Source
    internal lateinit var overlays: OverlayManager
    internal var scenes: MutableList<Scene> = mutableListOf()
    internal lateinit var cards: CardManager
    private var micMutedBeforeCard = false
    private lateinit var health: DeviceHealth
    private lateinit var deviceBadge: TextView
    private var lastHealth: DeviceHealth.State? = null
    private var batteryAlerted = Severity.OK
    private var heatAlerted = Severity.OK

    /** true mientras se transmite un video pregrabado en lugar de la cámara. */
    internal var sceneActive = false
    private var sceneName: String? = null
    private var pendingIntro = false
    private var micMutedBeforeScene = false

    // Medidor del micrófono y avisos de transmisión
    private val micLevel = MicLevel()
    private val monitor = MicMonitor(micLevel)
    private var resumed = false
    private var liveAnim: ValueAnimator? = null
    private var shownDb = -90f
    private var zeroSince = 0L
    private var lowSince = 0L
    private var noSendSeconds = 0
    private var sendWarn = false
    internal val isLive: Boolean get() = stream.isStreaming

    private lateinit var surfaceView: SurfaceView
    private lateinit var gridView: GridOverlayView
    private lateinit var badge: LinearLayout
    private lateinit var badgeDot: View
    private lateinit var badgeText: TextView
    private lateinit var timerText: TextView
    private lateinit var statsText: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var btnFlip: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnLive: TextView
    private lateinit var btnTune: ImageButton
    private lateinit var sceneBar: TextView
    private lateinit var audioMeter: AudioMeterView
    private lateinit var micStatus: TextView
    private lateinit var liveBorder: View
    private lateinit var recBadge: TextView
    private lateinit var quickScroll: HorizontalScrollView
    private lateinit var quickRow: LinearLayout
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    internal var activeInput: AudioInput? = null
    private var btRouted = false
    private var btAsked = false
    private var recordingFile: File? = null
    private lateinit var panel: LinearLayout
    private lateinit var panelController: SettingsPanel

    private val ui = Handler(Looper.getMainLooper())
    private var prepared = false
    private var surfaceReady = false
    private var physicalActive = false
    private var liveStartMs = 0L
    private var status = Status.IDLE
    private var bitrateAdapter: QueueAwareBitrateAdapter? = null
    private var lastTab = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermissions()) {
                if (prepareStream()) startPreviewIfReady()
                refreshMonitor()
            } else {
                toast("Sin permiso de cámara y micrófono no se puede transmitir")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val bluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) applyAudioInput() else toast("Sin el permiso de Bluetooth no se puede usar ese micrófono")
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            addedDevices.firstOrNull { it.isSource }?.let { inputsChanged(it, true) }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            removedDevices.firstOrNull { it.isSource }?.let { inputsChanged(it, false) }
        }
    }

    private val micTick = object : Runnable {
        override fun run() {
            if (resumed) updateMicMeter()
            ui.postDelayed(this, 80)
        }
    }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addScene(uri)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val item = overlays.addImage(uri)
                if (item == null) toast("No se pudo abrir esa imagen") else rebuildOverlays()
                panelController.refresh()
            }
        }

    internal fun chooseVideo() = pickVideo.launch(arrayOf("video/*"))

    internal fun chooseImage() = pickImage.launch(arrayOf("image/*"))

    private val timerTick = object : Runnable {
        override fun run() {
            if (stream.isStreaming) {
                val s = (SystemClock.elapsedRealtime() - liveStartMs) / 1000
                timerText.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                ui.postDelayed(this, 1000)
            }
        }
    }

    private val cameraCallbacks = object : CameraCallbacks {
        override fun onCameraChanged(facing: CameraHelper.Facing) {}
        override fun onCameraOpened() {}
        override fun onCameraError(error: String) {
            runOnUiThread {
                Log.e(TAG, "Camera error: $error")
                recoverFromCameraFailure()
            }
        }

        override fun onCameraDisconnected() {
            runOnUiThread { toast("La cámara se desconectó") }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        if (settings.rememberKey) streamKey = settings.loadStreamKey()
        requestedOrientation = if (settings.vertical) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        LiveService.stopHandler = { runOnUiThread { if (stream.isStreaming) stopLive() } }
        RecordingStore.exportPending(this)
        lenses = CameraCatalog.scan(this)
        overlays = OverlayManager(this, settings)
        scenes = SceneStore.load(settings.scenesJson)
        cards = CardManager(settings).also { c ->
            c.onChanged = { refreshQuickRow() }
            c.onMute = { mute -> setMicMutedByCard(mute) }
        }
        health = DeviceHealth(this) { state -> runOnUiThread { onHealthChanged(state) } }
        stream = RtmpStream(this, this)
        audioManager.registerAudioDeviceCallback(deviceCallback, ui)
        stream.getGlInterface().autoHandleOrientation = !settings.vertical
        stream.getStreamClient().setReTries(10)

        panelController = SettingsPanel(
            this,
            findViewById(R.id.panelBody),
            listOf(
                findViewById<MaterialButton>(R.id.tabCamera),
                findViewById<MaterialButton>(R.id.tabStream),
                findViewById<MaterialButton>(R.id.tabMulti),
                findViewById<MaterialButton>(R.id.tabCapas),
                findViewById<MaterialButton>(R.id.tabAudio)
            )
        )
        setupPanelSize()
        setupButtons()
        setupGestures()
        gridView.visibility = if (settings.grid) View.VISIBLE else View.GONE

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startPreviewIfReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (prepared) stream.getGlInterface().setPreviewResolution(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                if (!stream.isStreaming) pip.stop()
                if (stream.isOnPreview) stream.stopPreview()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panel.visibility == View.VISIBLE) {
                    closePanel()
                } else if (stream.isStreaming) {
                    toast("La transmisión sigue en segundo plano. Toca STOP o «Detener» en la notificación para terminar.")
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setStatus(Status.IDLE)
        updateMicUi()
        refreshStats(null)
        ui.post(micTick)
        if (hasPermissions()) {
            prepareStream()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            permissionLauncher.launch(permissionsToRequest())
        }
    }

    override fun onStart() {
        super.onStart()
        health.start()
    }

    override fun onStop() {
        if (!stream.isStreaming) health.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshMonitor()
    }

    override fun onPause() {
        resumed = false
        monitor.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        liveAnim?.cancel()
        monitor.stop()
        LiveService.stopHandler = null
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        stopRecording()
        LiveService.stop(this)
        pip.stop()
        cards.release()
        health.stop()
        stream.release()
        super.onDestroy()
    }

    private fun bindViews() {
        surfaceView = findViewById(R.id.surfaceView)
        gridView = findViewById(R.id.gridView)
        badge = findViewById(R.id.badge)
        badgeDot = findViewById(R.id.badgeDot)
        badgeText = findViewById(R.id.badgeText)
        timerText = findViewById(R.id.timerText)
        statsText = findViewById(R.id.statsText)
        lensRow = findViewById(R.id.lensRow)
        btnFlip = findViewById(R.id.btnFlip)
        btnMic = findViewById(R.id.btnMic)
        btnLive = findViewById(R.id.btnLive)
        btnTune = findViewById(R.id.btnTune)
        sceneBar = findViewById(R.id.sceneBar)
        audioMeter = findViewById(R.id.audioMeter)
        micStatus = findViewById(R.id.micStatus)
        liveBorder = findViewById(R.id.liveBorder)
        recBadge = findViewById(R.id.recBadge)
        deviceBadge = findViewById(R.id.deviceBadge)
        quickScroll = findViewById(R.id.quickScroll)
        quickRow = findViewById(R.id.quickRow)
        sceneBar.setOnClickListener { endScene() }
        panel = findViewById(R.id.panel)
        findViewById<View>(R.id.panelClose).setOnClickListener { closePanel() }
    }

    private fun setupPanelSize() {
        val screenWidth = resources.displayMetrics.widthPixels
        val lp = panel.layoutParams
        lp.width = if (settings.vertical) (screenWidth * 0.9f).toInt() else dp(360)
        panel.layoutParams = lp
    }

    private fun setupButtons() {
        btnLive.setOnClickListener { onLiveClicked() }
        btnFlip.setOnClickListener { toggleFacing() }
        btnMic.setOnClickListener { toggleMic() }
        btnTune.setOnClickListener {
            if (panel.visibility == View.VISIBLE) closePanel() else openPanel(lastTab)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!stream.isOnPreview || sceneActive) return false
                val range = cam.getZoomRange()
                setZoomLevel((camState.zoom * detector.scaleFactor).coerceIn(range.lower, range.upper))
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (panel.visibility == View.VISIBLE) {
                    closePanel()
                } else if (stream.isOnPreview && !sceneActive) {
                    camState.manualFocus = false
                    runCatching { cam.tapToFocus(surfaceView, e) }
                }
                return true
            }
        })
        surfaceView.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) tap.onTouchEvent(event)
            true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Preparación de la transmisión y de la vista previa
    // ---------------------------------------------------------------------------------------------

    /** Cámara y micrófono son obligatorios; el permiso de notificaciones solo hace visible el aviso de segundo plano. */
    private fun permissionsToRequest(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasPermissions(): Boolean =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Ancho y alto del video que sale en la transmisión (ya con la orientación aplicada). */
    internal fun streamSize(): Pair<Int, Int> {
        val long = if (settings.height == 1080) 1920 else 1280
        val short = settings.height
        return if (settings.vertical) Pair(short, long) else Pair(long, short)
    }

    private fun prepareStream(): Boolean {
        val h = settings.height
        val w = if (h == 1080) 1920 else 1280
        val rotation = if (settings.vertical) 90 else 0
        val videoBitrate = settings.bitrateKbps * 1000
        prepared = try {
            stream.prepareVideo(w, h, videoBitrate, settings.fps, 2, rotation) &&
                stream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE, false, settings.noiseSuppression)
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
        if (!prepared) {
            toast("Tu teléfono no admite esta configuración. Prueba 720p a 30 fps.")
        } else {
            setupBitrateAdapter()
            attachMicEffect()
        }
        refreshStats(null)
        return prepared
    }

    internal fun setupBitrateAdapter() {
        bitrateAdapter = if (settings.adaptiveBitrate) {
            QueueAwareBitrateAdapter(settings.bitrateKbps * 1000 + AUDIO_BITRATE) {
                stream.setVideoBitrateOnFly(it - AUDIO_BITRATE)
            }
        } else null
    }

    private fun startPreviewIfReady() {
        if (!prepared || !surfaceReady || stream.isOnPreview) return
        val resuming = stream.isStreaming || stream.isRecording
        try {
            stream.startPreview(surfaceView)
            if (resuming) onPreviewResumed() else onPreviewStarted()
        } catch (e: Exception) {
            Log.e(TAG, "startPreview", e)
            toast("No se pudo iniciar la cámara: ${e.message}")
        }
    }

    /** La pantalla volvió con la transmisión en marcha: solo se reconecta la vista previa, sin tocar la cámara. */
    private fun onPreviewResumed() {
        refreshLensRow()
        updateMicUi()
        updateSceneUi()
        updateRecUi()
    }

    private fun onPreviewStarted() {
        runCatching { cam.setCameraCallback(cameraCallbacks) }
        val lens = pickInitialLens()
        if (lens != null) {
            selectLens(lens)
        } else {
            currentLens = null
            scheduleReapply()
        }
        refreshLensRow()
        updateMicUi()
        refreshStats(null)
        rebuildOverlays()
    }

    /**
     * Cambia resolución, fps, bitrate, ruido o orientación. Solo se puede hacer sin transmitir.
     */
    internal fun reconfigure() {
        if (stream.isStreaming) {
            toast("Detén la transmisión para cambiar esto")
            return
        }
        if (sceneActive) endScene()
        cards.hide()
        pip.stop()
        val wasPreview = stream.isOnPreview
        if (wasPreview) stream.stopPreview()
        stream.release()
        if (physicalActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // La cámara ya está cerrada: esto solo borra el lente físico guardado en la librería.
            runCatching { cam.openPhysicalCamera(null) }
        }
        physicalActive = false
        stream.getGlInterface().autoHandleOrientation = !settings.vertical
        if (prepareStream() && surfaceReady) startPreviewIfReady()
    }

    // ---------------------------------------------------------------------------------------------
    // Lentes
    // ---------------------------------------------------------------------------------------------

    private fun defaultLens(front: Boolean): Lens? =
        lenses.filter { it.front == front && it.zoom == null }
            .minByOrNull { if (it.ratio > 0f) abs(it.ratio - 1f) else 5f }
            ?: lenses.firstOrNull { it.front == front }

    private fun pickInitialLens(): Lens? {
        val front = settings.front
        val saved = settings.lensKey(front)
        return lenses.firstOrNull { it.front == front && it.key == saved } ?: defaultLens(front)
    }

    internal fun selectLens(lens: Lens) {
        if (sceneActive) {
            toast("Termina la escena de video para cambiar de cámara")
            return
        }
        if (!stream.isOnPreview && !stream.isStreaming) return
        val hadPip = pip.isActive
        if (hadPip) pip.stop()
        try {
            val src = cam
            if (!src.isRunning()) return
            val canPhysical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            if (lens.parentId != null && canPhysical) {
                if (src.getCurrentCameraId() != lens.parentId) src.openCameraId(lens.parentId)
                src.openPhysicalCamera(lens.id)
                physicalActive = true
            } else {
                if (physicalActive && canPhysical) {
                    src.openPhysicalCamera(null)
                    physicalActive = false
                }
                if (src.getCurrentCameraId() != lens.openId) src.openCameraId(lens.openId)
            }
            currentLens = lens
            settings.front = lens.front
            settings.setLensKey(lens.front, lens.key)
            camState.zoom = lens.zoom ?: 1f
            camState.torch = false
            scheduleReapply()
        } catch (e: Exception) {
            Log.e(TAG, "selectLens", e)
            recoverFromCameraFailure()
            return
        }
        refreshLensRow()
        panelController.refresh()
        if (pipWanted) startPip()
    }

    private fun toggleFacing() {
        if (!stream.isOnPreview) return
        val toFront = !settings.front
        val saved = settings.lensKey(toFront)
        val lens = lenses.firstOrNull { it.front == toFront && it.key == saved } ?: defaultLens(toFront)
        if (lens == null) {
            toast(if (toFront) "Este teléfono no tiene cámara frontal" else "Este teléfono no tiene cámara trasera")
            return
        }
        selectLens(lens)
    }

    private fun recoverFromCameraFailure() {
        if (sceneActive) return
        toast("No se pudo usar esa cámara. Volviendo a la principal.")
        pip.stop()
        pipWanted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { cam.openPhysicalCamera(null) }
        physicalActive = false
        settings.front = false
        defaultLens(false)?.let { settings.setLensKey(false, it.key) }
        if (stream.isStreaming) {
            runCatching { cam.restart() }
        } else {
            reconfigure()
        }
    }

    private fun refreshLensRow() {
        lensRow.removeAllViews()
        val front = settings.front
        val list = lenses.filter { it.front == front }
        for (lens in list) {
            val selected = lens.key == currentLens?.key
            val chip = TextView(this).apply {
                text = lensLabel(lens)
                gravity = Gravity.CENTER
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundResource(if (selected) R.drawable.bg_pill_on else R.drawable.bg_pill)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                minWidth = dp(64)
                setOnClickListener { selectLens(lens) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(4), 0, dp(4), 0)
            lensRow.addView(chip, lp)
        }
    }

    private fun lensLabel(lens: Lens): CharSequence {
        val sb = SpannableStringBuilder(lens.ratioText)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, 0)
        sb.setSpan(RelativeSizeSpan(1.4f), 0, sb.length, 0)
        sb.append("\n").append(lens.shortName)
        return sb
    }

    // ---------------------------------------------------------------------------------------------
    // Controles de cámara (los usa el panel de ajustes)
    // ---------------------------------------------------------------------------------------------

    /** Las opciones se aplican un momento después de abrir la cámara, cuando ya está lista. */
    private fun scheduleReapply() {
        ui.postDelayed({ applyCamState() }, 400)
        ui.postDelayed({ applyCamState() }, 1200)
    }

    internal fun applyCamState() {
        if (sceneActive || (!stream.isOnPreview && !stream.isStreaming)) return
        val c = camOrNull ?: return
        if (!c.isRunning()) return
        runCatching {
            val range = c.getZoomRange()
            camState.zoom = camState.zoom.coerceIn(range.lower, range.upper)
            if (abs(camState.zoom - 1f) > 0.001f) c.setZoom(camState.zoom)
        }
        runCatching { if (camState.ev != 0) c.setExposure(camState.ev) }
        runCatching { if (camState.aeLock) c.enableExposureLock() }
        runCatching { if (camState.awbLock) c.enableWhiteBalanceLock() }
        runCatching { if (camState.awbMode >= 0) c.enableAutoWhiteBalance(camState.awbMode) }
        runCatching { if (camState.stabilization) c.enableOpticalVideoStabilization() || c.enableVideoStabilization() }
        runCatching { if (camState.manualFocus) applyManualFocus() }
    }

    internal fun currentChars(): CameraCharacteristics? {
        val id = runCatching { cam.getCurrentCameraId() }.getOrNull() ?: return null
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return CameraCatalog.characteristics(cm, id)
    }

    internal fun setZoomLevel(zoom: Float) {
        camState.zoom = zoom
        runCatching { cam.setZoom(zoom) }
    }

    internal fun setEv(index: Int) {
        camState.ev = index
        runCatching { cam.setExposure(index) }
    }

    internal fun setAeLock(on: Boolean) {
        camState.aeLock = on
        runCatching { if (on) cam.enableExposureLock() else cam.disableExposureLock() }
    }

    internal fun setAwbLock(on: Boolean) {
        camState.awbLock = on
        runCatching { if (on) cam.enableWhiteBalanceLock() else cam.disableWhiteBalanceLock() }
    }

    internal fun setAwbMode(mode: Int) {
        camState.awbMode = mode
        runCatching { cam.enableAutoWhiteBalance(mode) }
    }

    internal fun setStabilization(on: Boolean): Boolean {
        val c = camOrNull ?: return false
        return if (on) {
            val ok = runCatching { c.enableOpticalVideoStabilization() || c.enableVideoStabilization() }
                .getOrDefault(false)
            camState.stabilization = ok
            if (!ok) toast("Este lente no admite estabilización")
            ok
        } else {
            runCatching {
                c.disableOpticalVideoStabilization()
                c.disableVideoStabilization()
            }
            camState.stabilization = false
            true
        }
    }

    internal fun setManualFocus(on: Boolean) {
        camState.manualFocus = on
        runCatching { if (on) applyManualFocus() else cam.enableAutoFocus() }
    }

    private fun applyManualFocus() {
        cam.disableAutoFocus()
        val distance = camState.focusDistance
        cam.setCustomRequest { it.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance) }
    }

    internal fun setFocusDistance(distance: Float) {
        camState.focusDistance = distance
        if (camState.manualFocus) {
            runCatching { cam.setCustomRequest { it.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance) } }
        }
    }

    internal fun setTorch(on: Boolean): Boolean {
        return try {
            if (on) cam.enableLantern() else cam.disableLantern()
            camState.torch = on
            true
        } catch (e: Exception) {
            camState.torch = false
            toast("Este lente no tiene linterna")
            false
        }
    }

    internal fun setGrid(on: Boolean) {
        settings.grid = on
        gridView.visibility = if (on) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------------
    // Imagen en imagen (dos cámaras)
    // ---------------------------------------------------------------------------------------------

    /** Lentes que Android permite abrir junto al lente principal actual. */
    internal fun pipCandidates(): List<Lens> {
        val primary = runCatching { cam.getCurrentCameraId() }.getOrNull() ?: return emptyList()
        val partners = CameraCatalog.concurrentPartners(this, primary)
        return lenses.filter { it.parentId == null && it.zoom == null && it.id in partners }
    }

    internal fun startPip(): Boolean {
        if (sceneActive || (!stream.isOnPreview && !stream.isStreaming)) return false
        val candidates = pipCandidates()
        if (candidates.isEmpty()) {
            pipWanted = false
            toast("Con este lente tu teléfono no permite abrir una segunda cámara")
            return false
        }
        val chosen = candidates.firstOrNull { it.key == settings.pipCameraKey } ?: candidates.first()
        settings.pipCameraKey = chosen.key
        val (w, h) = streamSize()
        pipWanted = true
        pip.start(stream, chosen.id, settings.pipSize, settings.pipCorner, settings.pipRotation, w, h)
        return true
    }

    internal fun stopPip() {
        pipWanted = false
        pip.stop()
    }

    internal fun updatePip() {
        val (w, h) = streamSize()
        pip.update(settings.pipSize, settings.pipCorner, settings.pipRotation, w, h)
    }

    private fun onPipStopped() {
        pipWanted = false
        panelController.refresh()
    }

    // ---------------------------------------------------------------------------------------------
    // Transmisión
    // ---------------------------------------------------------------------------------------------

    private fun onLiveClicked() {
        if (stream.isStreaming) {
            stopLive()
            return
        }
        if (!prepared || !stream.isOnPreview) {
            toast("La cámara aún no está lista")
            return
        }
        val base = settings.url.trim().trimEnd('/')
        if (!base.startsWith("rtmps://") && !base.startsWith("rtmp://")) {
            toast("La URL del servidor debe empezar con rtmps://")
            openPanel(1)
            return
        }
        val key = streamKey.trim()
        if (key.isEmpty()) {
            toast("Escribe la clave de transmisión")
            openPanel(1)
            return
        }
        closePanel()
        if (settings.qualityProfile == QualityProfiles.AUTO) {
            val p = QualityProfiles.forNetwork(this)
            if (settings.height != p.height || settings.fps != p.fps || settings.bitrateKbps != p.bitrateKbps) {
                applyQuality(p.height, p.fps, p.bitrateKbps)
                toast("Perfil automático: ${p.label} (${p.description})")
                ui.postDelayed({ onLiveClicked() }, 900)
                return
            }
        }
        pendingIntro = settings.introEnabled && introScene() != null
        setStatus(Status.CONNECTING)
        liveStartMs = SystemClock.elapsedRealtime()
        monitor.stop() // libera el micrófono para que lo use la transmisión
        val recording = settings.recordLocal && startRecording()
        LiveService.start(this, recording, System.currentTimeMillis())
        try {
            stream.startStream("$base/$key")
        } catch (e: Exception) {
            stopRecording()
            LiveService.stop(this)
            setStatus(Status.ERROR)
            toast("No se pudo iniciar: ${e.message}")
            refreshMonitor()
            return
        }
        ui.removeCallbacks(timerTick)
        ui.post(timerTick)
    }

    private fun stopLive() {
        pendingIntro = false
        cards.hide()
        stopRecording()
        if (stream.isStreaming) stream.stopStream()
        LiveService.stop(this)
        ui.removeCallbacks(timerTick)
        timerText.text = "00:00:00"
        setStatus(Status.IDLE)
        refreshStats(null)
        refreshMonitor()
    }

    private fun setStatus(newStatus: Status) {
        val becameLive = newStatus == Status.LIVE && status != Status.LIVE
        status = newStatus
        if (newStatus != Status.LIVE) {
            sendWarn = false
            noSendSeconds = 0
        }
        renderBadge()
        val active = newStatus == Status.LIVE || newStatus == Status.CONNECTING
        btnLive.setBackgroundResource(if (active) R.drawable.bg_live_on else R.drawable.bg_live)
        btnLive.text = if (active) "STOP" else "LIVE"
        updateLiveEffects()
        if (becameLive) btnLive.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Dibuja la insignia de estado; avisa si dejó de salir video aunque la conexión siga abierta. */
    private fun renderBadge() {
        val (label, dotColor) = when (status) {
            Status.IDLE -> "LISTO" to ContextCompat.getColor(this, R.color.idle)
            Status.CONNECTING -> "CONECTANDO" to ContextCompat.getColor(this, R.color.warn)
            Status.LIVE -> if (sendWarn) {
                "SIN ENVÍO" to ContextCompat.getColor(this, R.color.warn)
            } else {
                "EN VIVO" to Color.WHITE
            }
            Status.ERROR -> "ERROR" to ContextCompat.getColor(this, R.color.live)
        }
        badgeText.text = label
        badgeDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        val liveOk = status == Status.LIVE && !sendWarn
        badge.setBackgroundResource(if (liveOk) R.drawable.bg_pill_live else R.drawable.bg_pill)
    }

    /** Marco de color y punto parpadeante: se nota a simple vista que la transmisión está activa. */
    private fun updateLiveEffects() {
        val live = status == Status.LIVE
        val waiting = status == Status.CONNECTING
        liveBorder.visibility = if (live || waiting) View.VISIBLE else View.GONE
        liveBorder.setBackgroundResource(if (live) R.drawable.bg_border_live else R.drawable.bg_border_wait)
        liveAnim?.cancel()
        liveAnim = null
        liveBorder.alpha = 1f
        badgeDot.alpha = 1f
        if (live || waiting) {
            val anim = ValueAnimator.ofFloat(1f, 0.3f).apply {
                duration = if (live) 800 else 400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    val v = it.animatedValue as Float
                    badgeDot.alpha = v
                    liveBorder.alpha = if (live) 0.45f + 0.55f * v else v
                }
            }
            liveAnim = anim
            anim.start()
        }
    }

    private fun refreshStats(report: StreamingStatsReport?) {
        val base = "${settings.height}p${settings.fps}"
        val mbps = if (report != null && report.throughput != Throughput.UNKNOWN) {
            report.smoothedBitrate / 1_000_000f
        } else {
            settings.bitrateKbps / 1000f
        }
        statsText.text = if (report != null && report.throughput != Throughput.UNKNOWN) {
            val mb = report.totalBytesOut / 1_048_576f
            String.format(Locale.US, "%s · %.1f Mbps · %.1f MB enviados", base, mbps, mb)
        } else {
            String.format(Locale.US, "%s · %.1f Mbps", base, mbps)
        }
        val weak = report?.throughput == Throughput.INSUFFICIENT
        statsText.setTextColor(if (weak) ContextCompat.getColor(this, R.color.warn) else Color.WHITE)
    }

    private fun toggleMic() {
        val mic = stream.audioSource as? MicrophoneSource ?: return
        if (mic.isMuted()) mic.unMute() else mic.mute()
        updateMicUi()
    }

    private fun updateMicUi() {
        val muted = (stream.audioSource as? MicrophoneSource)?.isMuted() ?: false
        btnMic.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnMic.setBackgroundResource(if (muted) R.drawable.bg_round_alert else R.drawable.bg_round)
    }

    // ---------------------------------------------------------------------------------------------
    // Grabación local
    // ---------------------------------------------------------------------------------------------

    /** Empieza a grabar una copia en el teléfono. Devuelve false si no se pudo (la transmisión sigue igual). */
    private fun startRecording(): Boolean {
        if (stream.isRecording) return true
        if (RecordingStore.freeBytes(this) < RecordingStore.MIN_FREE_BYTES) {
            toast("Queda muy poco espacio: no se grabará la copia en el teléfono")
            return false
        }
        val file = RecordingStore.newFile(this)
        return try {
            RecordingStore.current = file
            stream.startRecord(file.absolutePath) { }
            recordingFile = file
            updateRecUi()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecord", e)
            RecordingStore.current = null
            toast("No se pudo grabar la copia: ${e.message}")
            false
        }
    }

    /** Termina la grabación y copia el video a la galería. */
    private fun stopRecording() {
        val file = recordingFile
        if (stream.isRecording) runCatching { stream.stopRecord() }
        recordingFile = null
        RecordingStore.current = null
        updateRecUi()
        if (file != null) {
            RecordingStore.export(this, file) { message -> toast(message) }
        }
    }

    private fun updateRecUi() {
        recBadge.visibility = if (stream.isRecording) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------------
    // Medidor del micrófono
    // ---------------------------------------------------------------------------------------------

    /** Conecta el medidor al micrófono de la transmisión. */
    private fun attachMicEffect() {
        (stream.audioSource as? MicrophoneSource)?.setAudioEffect(MicLevelEffect(micLevel))
        applyAudioInput()
    }

    /** Elige la entrada de audio según la preferencia y aplica el volumen del micrófono. */
    internal fun applyAudioInput() {
        val chosen = AudioInput.resolve(AudioInput.list(this), settings.audioInput)
        activeInput = chosen
        routeBluetooth(chosen)
        val device = chosen?.takeIf { !it.isBuiltIn }?.device
        (stream.audioSource as? MicrophoneSource)?.let { mic ->
            runCatching { mic.setPreferredDevice(device) }
            mic.microphoneVolume = settings.micGainPercent / 100f
        }
        micLevel.gain = settings.micGainPercent / 100f
        monitor.preferred = device
        if (monitor.isRunning) {
            monitor.stop()
            refreshMonitor()
        }
    }

    internal fun setMicGain(percent: Int) {
        settings.micGainPercent = percent
        micLevel.gain = percent / 100f
        (stream.audioSource as? MicrophoneSource)?.microphoneVolume = percent / 100f
    }

    /** Para un micrófono Bluetooth hay que pedirle a Android que use ese dispositivo para la voz. */
    @Suppress("DEPRECATION")
    private fun routeBluetooth(input: AudioInput?) {
        val wantBt = input?.isBluetooth == true
        try {
            if (wantBt) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        if (!btAsked) {
                            btAsked = true
                            bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        return
                    }
                    val target = audioManager.availableCommunicationDevices.firstOrNull { it.id == input!!.device.id }
                    if (target != null) audioManager.setCommunicationDevice(target)
                } else {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                btRouted = true
            } else if (btRouted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
                btRouted = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo enrutar el audio Bluetooth", e)
        }
    }

    /** Se conectó o desconectó un micrófono: se vuelve a elegir la entrada y se avisa. */
    private fun inputsChanged(device: AudioDeviceInfo, added: Boolean) {
        val input = AudioInput(device)
        applyAudioInput()
        panelController.refresh()
        if (input.isExternal) {
            toast(
                if (added) "Micrófono conectado: ${input.label}. Se usa según Ajustes → Audio."
                else "Micrófono desconectado: ${input.label}"
            )
        }
    }

    /** Antes de transmitir se escucha el micrófono aparte; al transmitir lo usa la propia transmisión. */
    private fun refreshMonitor() {
        val want = hasPermissions() && resumed && !stream.isStreaming && !sceneActive
        if (want && !monitor.isRunning) monitor.start()
        else if (!want && monitor.isRunning) monitor.stop()
    }

    private fun updateMicMeter() {
        val now = SystemClock.elapsedRealtime()
        val p = micLevel.take()
        val mic = stream.audioSource as? MicrophoneSource
        val color = { id: Int -> ContextCompat.getColor(this, id) }

        if (sceneActive) {
            audioMeter.setLevel(-60f, false)
            setMicStatus("AUDIO DEL VIDEO", color(R.color.text_dim))
            return
        }
        if (mic?.isMuted() == true) {
            shownDb = -90f
            audioMeter.setLevel(-60f, true)
            setMicStatus("MIC SILENCIADO", color(R.color.live))
            return
        }
        if (!hasPermissions()) {
            audioMeter.setLevel(-60f, true)
            setMicStatus("MIC SIN PERMISO", color(R.color.live))
            return
        }
        if (now - micLevel.updatedAt > 1200) {
            shownDb = -90f
            audioMeter.setLevel(-60f, false)
            setMicStatus("MIC SIN LECTURA", color(R.color.warn))
            return
        }

        if (p >= 0) {
            val db = if (p <= 0) -90f else 20f * log10(p / 32768f)
            shownDb = if (db > shownDb) db else maxOf(db, shownDb - 4f)
            if (p == 0) {
                if (zeroSince == 0L) zeroSince = now
            } else zeroSince = 0L
            if (p < 33) {
                if (lowSince == 0L) lowSince = now
            } else lowSince = 0L
        }
        val blocked = zeroSince != 0L && now - zeroSince > 2000
        val tooLow = lowSince != 0L && now - lowSince > 5000
        audioMeter.setLevel(shownDb, blocked)
        when {
            blocked -> setMicStatus("MIC SIN SEÑAL · ¿bloqueado?", color(R.color.live))
            tooLow -> setMicStatus("MIC MUY BAJO", color(R.color.warn))
            else -> {
                val src = activeInput?.shortType ?: "MIC"
                val label = if (shownDb <= -89f) "$src · silencio" else "$src · ${shownDb.toInt()} dB"
                setMicStatus(label, color(R.color.ok))
            }
        }
    }

    private fun setMicStatus(text: String, colorInt: Int) {
        if (micStatus.text.toString() != text) micStatus.text = text
        micStatus.setTextColor(colorInt)
    }

    // ---------------------------------------------------------------------------------------------
    // Capas (textos e imágenes) y escenas de video
    // ---------------------------------------------------------------------------------------------

    /** Vuelve a dibujar los textos e imágenes sobre el video. */
    internal fun rebuildOverlays() {
        refreshQuickRow()
        if (!stream.isOnPreview && !stream.isStreaming) return
        val (w, h) = streamSize()
        runCatching { overlays.attach(stream, w, h) }
            .onFailure { Log.e(TAG, "overlays", it) }
        runCatching { cards.reattach(stream, w, h) }
            .onFailure { Log.e(TAG, "cards", it) }
    }

    /** Muestra u oculta una pantalla lista (Ya regreso, pausa, cuenta regresiva). */
    internal fun toggleCard(card: ScreenCard) {
        if (!cards.isActive(card) && !stream.isOnPreview && !stream.isStreaming) {
            toast("La cámara aún no está lista")
            return
        }
        val (w, h) = streamSize()
        cards.toggle(card, stream, w, h)
    }

    private fun setMicMutedByCard(mute: Boolean) {
        val mic = stream.audioSource as? MicrophoneSource
        if (mic != null) {
            if (mute) {
                micMutedBeforeCard = mic.isMuted()
                mic.mute()
            } else if (!micMutedBeforeCard) {
                mic.unMute()
            }
        }
        updateMicUi()
    }

    internal fun saveAndRebuildOverlays() {
        overlays.save()
        overlays.scheduleRebuild { rebuildOverlays() }
    }

    private fun addScene(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val scene = Scene(SceneStore.displayName(this, uri), uri.toString())
        if (scenes.none { it.uri == scene.uri }) scenes.add(scene)
        settings.scenesJson = SceneStore.toJson(scenes)
        refreshQuickRow()
        panelController.refresh()
    }

    internal fun removeScene(scene: Scene) {
        scenes.remove(scene)
        if (settings.introUri == scene.uri) {
            settings.introUri = null
            settings.introEnabled = false
        }
        settings.scenesJson = SceneStore.toJson(scenes)
        runCatching {
            contentResolver.releasePersistableUriPermission(Uri.parse(scene.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        refreshQuickRow()
    }

    internal fun introScene(): Scene? = scenes.firstOrNull { it.uri == settings.introUri }

    /** Transmite el video elegido en lugar de la cámara. Al terminar vuelve la cámara sola. */
    internal fun playScene(scene: Scene) {
        if (!stream.isOnPreview && !stream.isStreaming) {
            toast("La cámara aún no está lista")
            return
        }
        if (sceneActive) endScene()
        pip.stop()
        val uri = Uri.parse(scene.uri)
        val mic = stream.audioSource as? MicrophoneSource
        micMutedBeforeScene = mic?.isMuted() ?: false
        try {
            val video = VideoFileSource(this, uri, false) { isLoop ->
                if (!isLoop) runOnUiThread { endScene() }
            }
            stream.changeVideoSource(video)
        } catch (e: Exception) {
            Log.e(TAG, "playScene", e)
            toast("No se pudo abrir ese video: ${e.message}")
            return
        }
        sceneActive = true
        sceneName = scene.name
        physicalActive = false
        refreshMonitor()
        try {
            stream.changeAudioSource(AudioFileSource(this, uri, false))
        } catch (e: Exception) {
            Log.w(TAG, "scene audio", e)
            toast("El sonido del video no coincide con la transmisión (se necesita 44.1 kHz estéreo): se verá sin su sonido")
        }
        updateSceneUi()
        panelController.refresh()
    }

    /** Termina la escena de video y vuelve a la cámara y al micrófono. */
    internal fun endScene() {
        if (!sceneActive) return
        sceneActive = false
        sceneName = null
        try {
            stream.changeVideoSource(Camera2Source(this))
            stream.getGlInterface().autoHandleOrientation = !settings.vertical
            runCatching { cam.setCameraCallback(cameraCallbacks) }
        } catch (e: Exception) {
            Log.e(TAG, "endScene video", e)
            toast("No se pudo volver a la cámara: ${e.message}")
        }
        if (stream.audioSource !is MicrophoneSource) {
            try {
                stream.changeAudioSource(MicrophoneSource())
                (stream.audioSource as? MicrophoneSource)?.let {
                    if (micMutedBeforeScene) it.mute()
                }
                attachMicEffect()
            } catch (e: Exception) {
                Log.e(TAG, "endScene audio", e)
                toast("No se pudo volver al micrófono: ${e.message}")
            }
        }
        updateSceneUi()
        updateMicUi()
        refreshMonitor()
        val lens = currentLens
        ui.postDelayed({
            if (!sceneActive && lens != null) selectLens(lens) else scheduleReapply()
            panelController.refresh()
        }, 300)
    }

    private fun updateSceneUi() {
        sceneBar.visibility = if (sceneActive) View.VISIBLE else View.GONE
        sceneBar.text = "ESCENA: ${sceneName ?: "video"} · toca para volver a la cámara"
        refreshQuickRow()
    }

    /** Botones rápidos: mostrar u ocultar cada capa y lanzar cada escena con un toque. */
    internal fun refreshQuickRow() {
        quickRow.removeAllViews()
        for (card in cards.items) {
            val icon = when (card.kind) {
                CardManager.KIND_COUNTDOWN -> "⏱  "
                CardManager.KIND_PAUSE -> "⏸  "
                else -> "◼  "
            }
            quickRow.addView(quickChip(icon + card.title.trim().take(14).ifEmpty { "Pantalla" }, cards.isActive(card)) { toggleCard(card) })
        }
        for (item in overlays.items) {
            val name = if (item.isText) item.text.trim().replace("\n", " ").take(14).ifEmpty { "Texto" } else "Imagen"
            quickRow.addView(quickChip((if (item.isText) "T  " else "▣  ") + name, item.visible) {
                item.visible = !item.visible
                saveAndRebuildOverlays()
                refreshQuickRow()
                panelController.refresh()
            })
        }
        for (scene in scenes) {
            val playing = sceneActive && sceneName == scene.name
            quickRow.addView(quickChip("▶  " + scene.name.take(14), playing) { playScene(scene) })
        }
        quickScroll.visibility = if (quickRow.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun quickChip(text: String, on: Boolean, onClick: () -> Unit): TextView {
        val chip = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill)
            alpha = if (on) 1f else 0.8f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), 0, dp(4), 0)
        chip.layoutParams = lp
        return chip
    }

    // ---------------------------------------------------------------------------------------------
    // Batería y temperatura
    // ---------------------------------------------------------------------------------------------

    private fun onHealthChanged(state: DeviceHealth.State) {
        lastHealth = state
        val text = StringBuilder()
        var worst = Severity.OK
        if (state.battery != Severity.OK) {
            text.append("🔋 ${state.level}%")
            worst = state.battery
        }
        if (state.heat != Severity.OK) {
            if (text.isNotEmpty()) text.append("  ")
            text.append(if (state.heat == Severity.ALERT) "🌡 MUY CALIENTE" else "🌡 CALIENTE")
            if (state.heat.ordinal > worst.ordinal) worst = state.heat
        }
        if (text.isEmpty()) {
            deviceBadge.visibility = View.GONE
        } else {
            deviceBadge.visibility = View.VISIBLE
            deviceBadge.text = text.toString()
            deviceBadge.setTextColor(
                ContextCompat.getColor(this, if (worst == Severity.ALERT) R.color.live else R.color.warn)
            )
        }
        // Aviso solo cuando empeora, para no repetirlo en cada cambio de 1 %.
        if (state.battery.ordinal > batteryAlerted.ordinal) {
            val msg = if (state.battery == Severity.ALERT) {
                "Batería muy baja (${state.level} %). Conecta el cargador o la transmisión se cortará."
            } else {
                "Batería baja (${state.level} %). Conviene conectar el cargador."
            }
            toast(msg)
            if (stream.isStreaming) LiveService.alert(this, "Batería baja", msg)
        }
        batteryAlerted = state.battery
        if (state.heat.ordinal > heatAlerted.ordinal) {
            val msg = if (state.heat == Severity.ALERT) {
                "El teléfono está muy caliente. Baja a 720p, quítale la funda o ponlo en la sombra para que no se apague la cámara."
            } else {
                "El teléfono se está calentando. Si sigue subiendo, baja la calidad a 720p."
            }
            toast(msg)
            if (stream.isStreaming) LiveService.alert(this, "Teléfono caliente", msg)
        }
        heatAlerted = state.heat
    }

    // ---------------------------------------------------------------------------------------------
    // Calidad y red
    // ---------------------------------------------------------------------------------------------

    /** Aplica un perfil de calidad (o el que toca según la red si está en automático). */
    internal fun applyQualityProfile(key: String) {
        if (stream.isStreaming) {
            toast("Detén la transmisión para cambiar esto")
            return
        }
        settings.qualityProfile = key
        val profile = if (key == QualityProfiles.AUTO) QualityProfiles.forNetwork(this) else QualityProfiles.byKey(key)
        if (profile != null) applyQuality(profile.height, profile.fps, profile.bitrateKbps)
        panelController.refresh()
    }

    /** Pone resolución, fps y bitrate de golpe y reinicia la vista previa una sola vez. */
    internal fun applyQuality(height: Int, fps: Int, bitrateKbps: Int) {
        if (stream.isStreaming) return
        val changed = settings.height != height || settings.fps != fps || settings.bitrateKbps != bitrateKbps
        settings.height = height
        settings.fps = fps
        settings.bitrateKbps = bitrateKbps
        if (changed) reconfigure() else refreshStats(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Verificación de la transmisión
    // ---------------------------------------------------------------------------------------------

    internal fun openLiveProducer() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/live/producer")))
        } catch (e: Exception) {
            toast("No se pudo abrir Facebook")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Panel de ajustes
    // ---------------------------------------------------------------------------------------------

    internal fun isPanelOpen(): Boolean = panel.visibility == View.VISIBLE

    internal fun openPanel(tab: Int) {
        lastTab = tab
        panel.visibility = View.VISIBLE
        panelController.show(tab)
    }

    internal fun closePanel() {
        panel.visibility = View.GONE
        currentFocus?.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(panel.windowToken, 0)
    }

    internal fun rememberTab(tab: Int) {
        lastTab = tab
    }

    // ---------------------------------------------------------------------------------------------
    // ConnectChecker (se llama desde otros hilos)
    // ---------------------------------------------------------------------------------------------

    override fun onConnectionStarted(url: String) {
        bitrateAdapter?.reset()
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            setStatus(Status.LIVE)
            if (pendingIntro) {
                pendingIntro = false
                introScene()?.let { playScene(it) }
            }
        }
    }

    override fun onConnectionFailed(reason: String) {
        if (stream.getStreamClient().reTry(5000, reason, null)) {
            runOnUiThread {
                setStatus(Status.CONNECTING)
                toast("Reconectando…")
            }
        } else {
            runOnUiThread {
                stopLive()
                setStatus(Status.ERROR)
                toast("No se pudo conectar: $reason")
            }
        }
    }

    override fun onDisconnect() {
        runOnUiThread { if (!stream.isStreaming) setStatus(Status.IDLE) }
    }

    override fun onAuthError() {
        runOnUiThread {
            stopLive()
            setStatus(Status.ERROR)
            toast("Error de autenticación: revisa la clave de transmisión")
        }
    }

    override fun onAuthSuccess() {}

    override fun onStreamingStats(report: StreamingStatsReport) {
        runOnUiThread {
            bitrateAdapter?.onStreamingStats(report)
            if (status == Status.LIVE) {
                refreshStats(report)
                noSendSeconds = if (report.bytesOutPerSecond < 500) noSendSeconds + 1 else 0
                val warn = noSendSeconds >= 4
                if (warn != sendWarn) {
                    sendWarn = warn
                    renderBadge()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------------------

    internal fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "FacebookLive"
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128 * 1000
    }
}
