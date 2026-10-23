package com.carlos.fblive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Mantiene viva la transmisión cuando la app pasa a segundo plano o se apaga la pantalla.
 * Muestra una notificación fija con el tiempo en vivo y un botón para detener.
 */
class LiveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmisión en vivo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fblive:live").apply {
            setReferenceCounted(false)
            acquire(MAX_LIVE_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHandler?.invoke()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val recording = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
        val startedAt = intent?.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val notification = buildNotification(recording, startedAt)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servicio en primer plano", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(recording: Boolean, startedAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle("Transmitiendo en vivo")
            .setContentText(if (recording) "Grabando una copia en el teléfono · toca para volver" else "Toca para volver a la app")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .build()
    }

    companion object {
        private const val TAG = "LiveService"
        private const val CHANNEL_ID = "live"
        private const val NOTIFICATION_ID = 7
        private const val MAX_LIVE_MS = 8 * 60 * 60 * 1000L
        private const val ACTION_STOP = "com.carlos.fblive.STOP"
        private const val EXTRA_RECORDING = "recording"
        private const val EXTRA_STARTED_AT = "startedAt"

        /** La actividad registra aquí qué hacer cuando se toca "Detener" en la notificación. */
        @Volatile
        var stopHandler: (() -> Unit)? = null

        fun start(context: Context, recording: Boolean, startedAt: Long) {
            val intent = Intent(context, LiveService::class.java)
                .putExtra(EXTRA_RECORDING, recording)
                .putExtra(EXTRA_STARTED_AT, startedAt)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }
    }
}
