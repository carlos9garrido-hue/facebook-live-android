package com.carlos.fblive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Gravedad de un aviso del teléfono. */
enum class Severity { OK, WARN, ALERT }

/** Vigila la batería y la temperatura del teléfono y avisa cuando cambian. */
class DeviceHealth(private val context: Context, private val onChange: (State) -> Unit) {

    data class State(
        val level: Int,
        val charging: Boolean,
        val tempC: Float,
        /** Estado térmico de Android (0 normal … 6 apagado de emergencia); 0 si el teléfono no lo informa. */
        val thermal: Int
    ) {
        val battery: Severity
            get() = when {
                charging -> Severity.OK
                level <= 10 -> Severity.ALERT
                level <= 20 -> Severity.WARN
                else -> Severity.OK
            }

        val heat: Severity
            get() = when {
                thermal >= 3 || tempC >= 44f -> Severity.ALERT
                thermal == 2 || tempC >= 41f -> Severity.WARN
                else -> Severity.OK
            }
    }

    private var level = 100
    private var charging = false
    private var temp = 0f
    private var thermal = 0
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            read(intent)
            onChange(state())
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermal = status
        onChange(state())
    }

    fun state() = State(level, charging, temp, thermal)

    fun start() {
        if (started) return
        started = true
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (sticky != null) read(sticky)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermal = pm.currentThermalStatus
            runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermalListener) }
        }
        onChange(state())
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            runCatching { pm.removeThermalStatusListener(thermalListener) }
        }
    }

    private fun read(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw >= 0 && scale > 0) level = raw * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }
}
