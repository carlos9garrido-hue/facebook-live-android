package com.carlos.fblive

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Una entrada de audio que Android ve: el micrófono del teléfono, un micrófono USB, de cable o Bluetooth. */
class AudioInput(val device: AudioDeviceInfo) {

    val type: Int get() = device.type

    val isBuiltIn: Boolean get() = type == AudioDeviceInfo.TYPE_BUILTIN_MIC

    val isBluetooth: Boolean
        get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    val isExternal: Boolean get() = !isBuiltIn

    /** Identifica la entrada entre sesiones (el número interno de Android cambia). */
    val key: String get() = "$type:${device.productName}"

    /** Nombre corto del tipo, para el medidor. */
    val shortType: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> "CABLE"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "MIC"
            else -> if (isBluetooth) "BT" else "EXT"
        }

    val typeName: String
        get() = when (shortType) {
            "USB" -> "USB"
            "CABLE" -> "Cable"
            "BT" -> "Bluetooth"
            "MIC" -> "Teléfono"
            else -> "Externo"
        }

    val label: String
        get() {
            if (isBuiltIn) return "Micrófono del teléfono"
            val name = device.productName?.toString().orEmpty().trim()
            return if (name.isEmpty()) typeName else "$typeName · $name"
        }

    companion object {
        const val AUTO = "auto"
        const val BUILTIN = "builtin"

        private val SUPPORTED = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_LINE_ANALOG
        )

        fun list(context: Context): List<AudioInput> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSource && (it.type in SUPPORTED || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)) }
                .map { AudioInput(it) }
        }

        /** Elige la entrada según la preferencia: automática (el externo si hay uno), del teléfono, o una en concreto. */
        fun resolve(all: List<AudioInput>, preference: String): AudioInput? {
            val builtIn = all.firstOrNull { it.isBuiltIn }
            return when (preference) {
                BUILTIN -> builtIn
                AUTO -> externalPriority(all) ?: builtIn
                else -> all.firstOrNull { it.key == preference } ?: externalPriority(all) ?: builtIn
            }
        }

        /** USB y cable primero (mejor calidad); Bluetooth al final por su sonido de llamada. */
        private fun externalPriority(all: List<AudioInput>): AudioInput? =
            all.filter { it.isExternal && !it.isBluetooth }.firstOrNull()
                ?: all.firstOrNull { it.isBluetooth }
    }
}
