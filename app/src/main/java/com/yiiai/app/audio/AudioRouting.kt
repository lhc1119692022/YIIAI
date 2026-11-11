package com.yiiai.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 通话期间的音频模式、焦点与路由。
 *
 * MODE_IN_COMMUNICATION + VOICE_COMMUNICATION 用途，是系统回声消除生效的前提。
 * 路由规则：接了耳机（有线 / 蓝牙）优先走耳机；否则按用户选择走外放或听筒。
 */
class AudioRouting(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var active = false

    /** 返回当前路由的显示名。 */
    fun start(speakerPreferred: Boolean): String {
        if (!active) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { am.requestAudioFocus(it) }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            active = true
        }
        return applyRoute(speakerPreferred)
    }

    fun setSpeaker(speakerPreferred: Boolean): String =
        if (active) applyRoute(speakerPreferred) else idleLabel(speakerPreferred)

    fun stop() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        active = false
    }

    private fun applyRoute(speakerPreferred: Boolean): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSET_TYPES }
            val target = headset
                ?: if (speakerPreferred) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
            if (target != null) am.setCommunicationDevice(target) else am.clearCommunicationDevice()
            return labelOf(target?.type, speakerPreferred)
        }
        // Android 10 / 11：没有 setCommunicationDevice，有线耳机由系统自动接管。
        val wired = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speakerPreferred && !wired
        return if (wired) "耳机" else idleLabel(speakerPreferred)
    }

    private fun labelOf(type: Int?, speakerPreferred: Boolean): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "耳机"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "外放"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        else -> idleLabel(speakerPreferred)
    }

    companion object {
        fun idleLabel(speakerPreferred: Boolean) = if (speakerPreferred) "外放" else "听筒"

        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
