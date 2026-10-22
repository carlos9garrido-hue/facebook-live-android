package com.carlos.fblive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.base.StreamBase
import kotlin.math.abs

/**
 * Segunda cámara en una esquina del video (imagen en imagen). EXPERIMENTAL.
 *
 * Abre una segunda cámara por su cuenta y dibuja su imagen sobre la transmisión con un filtro.
 * Solo funciona si Android permite abrir las dos cámaras a la vez (ver CameraCatalog.concurrentPartners).
 */
class PipCamera(
    private val context: Context,
    private val onProblem: (String) -> Unit
) {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var filter: SurfaceFilterRender? = null
    private var stream: StreamBase? = null
    private var bufferSize: Size? = null

    var cameraId: String? = null
        private set

    val isActive: Boolean get() = filter != null

    @SuppressLint("MissingPermission")
    fun start(
        stream: StreamBase,
        id: String,
        sizePercent: Int,
        corner: Int,
        rotation: Int,
        streamWidth: Int,
        streamHeight: Int
    ) {
        stop()
        this.stream = stream
        this.cameraId = id

        val size = pickSize(id)
        bufferSize = size

        val f = SurfaceFilterRender { texture -> main.post { openCamera(id, texture, size) } }
        filter = f
        layout(f, sizePercent, corner, rotation, streamWidth, streamHeight)
        stream.getGlInterface().addFilter(f)
    }

    /** Cambia tamaño, esquina y rotación sin reabrir la cámara. */
    fun update(sizePercent: Int, corner: Int, rotation: Int, streamWidth: Int, streamHeight: Int) {
        val f = filter ?: return
        layout(f, sizePercent, corner, rotation, streamWidth, streamHeight)
    }

    private fun layout(
        f: SurfaceFilterRender,
        sizePercent: Int,
        corner: Int,
        rotation: Int,
        streamWidth: Int,
        streamHeight: Int
    ) {
        val buf = bufferSize ?: Size(16, 9)
        val rotated = rotation == 90 || rotation == 270
        // Proporción con la que se ve la imagen después de girarla.
        val aspect = if (rotated) buf.height.toFloat() / buf.width else buf.width.toFloat() / buf.height
        val sx = sizePercent.toFloat()
        val sy = (sx * streamWidth / (streamHeight * aspect)).coerceAtMost(100f)
        f.setScale(sx, sy)
        f.rotation = rotation
        f.setPosition(
            when (corner) {
                0 -> TranslateTo.TOP_LEFT
                1 -> TranslateTo.TOP_RIGHT
                2 -> TranslateTo.BOTTOM_LEFT
                else -> TranslateTo.BOTTOM_RIGHT
            }
        )
    }

    fun stop() {
        val f = filter
        if (f != null) {
            runCatching { stream?.getGlInterface()?.removeFilter(f) }
        }
        filter = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
        cameraId = null
    }

    private fun pickSize(id: String): Size {
        val sizes = CameraCatalog.characteristics(cm, id)
            ?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return Size(640, 360)
        // Un tamaño pequeño y de proporción 16:9 gasta poco procesador.
        val target = 16f / 9f
        return sizes
            .filter { it.width in 480..960 }
            .minByOrNull { abs(it.width.toFloat() / it.height - target) }
            ?: sizes.minByOrNull { it.width * it.height }!!
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String, texture: SurfaceTexture, size: Size) {
        if (filter == null) return
        try {
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = filter?.surface ?: Surface(texture)
            val t = HandlerThread("pip-camera").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (filter == null) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera, surface, h)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    problem("La cámara secundaria se desconectó")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    problem("No se pudo abrir la segunda cámara (código $error). Es posible que tu teléfono no permita dos cámaras a la vez.")
                }
            }, h)
        } catch (e: Exception) {
            problem("No se pudo abrir la segunda cámara: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, surface: Surface, h: Handler) {
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        }
                        s.setRepeatingRequest(request.build(), null, h)
                    } catch (e: Exception) {
                        problem("Error en la segunda cámara: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    problem("La segunda cámara no pudo iniciar con esta configuración")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    { command -> h.post(command) },
                    callback
                )
                camera.createCaptureSession(config)
            } else {
                camera.createCaptureSession(listOf(surface), callback, h)
            }
        } catch (e: Exception) {
            problem("No se pudo crear la sesión de la segunda cámara: ${e.message}")
        }
    }

    private fun problem(message: String) {
        main.post {
            stop()
            onProblem(message)
        }
    }
}
