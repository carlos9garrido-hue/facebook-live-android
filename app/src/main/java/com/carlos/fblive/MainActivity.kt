package com.carlos.fblive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * Prototipo: transmite la cámara del celular a Facebook Live por RTMPS.
 * La URL del servidor y la clave se obtienen en Facebook Live Producer.
 */
class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var glView: OpenGlView
    private lateinit var statusText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var startButton: Button
    private lateinit var switchButton: Button
    private lateinit var muteButton: Button
    private lateinit var camera: RtmpCamera2

    private var muted = false

    private val prefs by lazy { getSharedPreferences("fblive", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermissions()) {
                setStatus(getString(R.string.status_ready))
                startPreviewIfPossible()
            } else {
                setStatus(getString(R.string.status_need_permissions))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        statusText = findViewById(R.id.statusText)
        bitrateText = findViewById(R.id.bitrateText)
        urlInput = findViewById(R.id.urlInput)
        keyInput = findViewById(R.id.keyInput)
        startButton = findViewById(R.id.startButton)
        switchButton = findViewById(R.id.switchButton)
        muteButton = findViewById(R.id.muteButton)

        // Solo se guarda la URL del servidor; la clave nunca se guarda.
        urlInput.setText(prefs.getString("url", getString(R.string.default_url)))

        camera = RtmpCamera2(glView, this)
        glView.holder.addCallback(this)

        startButton.setOnClickListener { if (camera.isStreaming) stopLive() else startLive() }
        switchButton.setOnClickListener {
            if (camera.isOnPreview || camera.isStreaming) camera.switchCamera()
        }
        muteButton.setOnClickListener { toggleMute() }

        if (!hasPermissions()) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun hasPermissions(): Boolean =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startPreviewIfPossible() {
        if (hasPermissions() && !camera.isOnPreview) {
            camera.startPreview(CameraHelper.Facing.BACK)
        }
    }

    private fun startLive() {
        if (!hasPermissions()) {
            setStatus(getString(R.string.status_need_permissions))
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
            return
        }
        val base = urlInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (!base.startsWith("rtmps://") && !base.startsWith("rtmp://")) {
            setStatus(getString(R.string.status_bad_url))
            return
        }
        if (key.isEmpty()) {
            setStatus(getString(R.string.status_no_key))
            return
        }
        prefs.edit().putString("url", urlInput.text.toString().trim()).apply()

        val rotation = CameraHelper.getCameraOrientation(this)
        // Video 720p, 30 fps, ~2.5 Mbps; audio AAC 128 kbps estéreo.
        val ready = camera.prepareAudio(128 * 1024, 44100, true) &&
            camera.prepareVideo(1280, 720, 30, 2_500_000, rotation)
        if (!ready) {
            setStatus(getString(R.string.status_prepare_failed))
            return
        }
        setStatus(getString(R.string.status_connecting))
        startButton.text = getString(R.string.btn_stop)
        camera.startStream("$base/$key")
    }

    private fun stopLive() {
        if (camera.isStreaming) camera.stopStream()
        startButton.text = getString(R.string.btn_start)
        bitrateText.text = ""
        setStatus(getString(R.string.status_stopped))
    }

    private fun toggleMute() {
        muted = !muted
        if (muted) camera.disableAudio() else camera.enableAudio()
        muteButton.text = getString(if (muted) R.string.btn_unmute else R.string.btn_mute)
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        startPreviewIfPossible()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (camera.isStreaming) stopLive()
        if (camera.isOnPreview) camera.stopPreview()
    }

    // --- ConnectChecker (se llama desde otros hilos) ---

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        runOnUiThread { setStatus(getString(R.string.status_live)) }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            if (camera.isStreaming) camera.stopStream()
            startButton.text = getString(R.string.btn_start)
            setStatus("Error de conexión: $reason")
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread { bitrateText.text = "${bitrate / 1000} kbps" }
    }

    override fun onDisconnect() {
        runOnUiThread {
            startButton.text = getString(R.string.btn_start)
            bitrateText.text = ""
            setStatus(getString(R.string.status_stopped))
        }
    }

    override fun onAuthError() {
        runOnUiThread { setStatus("Error de autenticación: revisa la clave") }
    }

    override fun onAuthSuccess() {}

    override fun onDestroy() {
        if (camera.isStreaming) camera.stopStream()
        if (camera.isOnPreview) camera.stopPreview()
        super.onDestroy()
    }
}
