package com.yiiai.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yiiai.app.live.LiveProtocol

/**
 * 以 16 kHz / 单声道 / PCM16 持续采集麦克风，每 20 ms 产出一帧。
 * 每帧已带 tagged-v1 音频前缀 0x00，可直接作为 WebSocket 二进制消息发送。
 *
 * 使用 VOICE_COMMUNICATION 音源，让系统对通话链路做回声消除和降噪；
 * 全双工下 AI 说话时麦克风也必须一直开着，用户才能打断它。
 * 静音时仍按实时速度发送全 0 帧，服务端需要连续的时间轴来判断停顿。
 */
class MicStreamer(
    private val onFrame: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
    private val onDiag: (String) -> Unit = {},
) {
    @Volatile var muted = false
    @Volatile private var running = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val effects = mutableListOf<AudioEffect>()

    /** 系统回声消除是否已在这条采集链路上启用。 */
    var echoCancelerActive = false
        private set
    var noiseSuppressorActive = false
        private set

    /** 成功返回 null，失败返回给用户看的原因。调用方需事先确认已获得 RECORD_AUDIO 权限。 */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return null
        val rate = LiveProtocol.INPUT_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "麦克风不可用（getMinBufferSize=$minBuf）"
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 10))
                .build()
        } catch (e: Exception) {
            return "无法打开麦克风：${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "无法打开麦克风（可能被其他应用占用）"
        }
        enableEffects(rec.audioSessionId)
        try {
            rec.startRecording()
        } catch (e: Exception) {
            releaseEffects()
            rec.release()
            return "麦克风启动失败：${e.message}"
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseEffects()
            rec.release()
            return "麦克风启动失败（可能被其他应用占用）"
        }
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "yiiai-mic").also { it.start() }
        return null
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        releaseEffects()
        onLevel(0f)
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var lastLevelAt = 0L
        var diagFrames = 0
        var diagPeak = 0
        var diagSilent = 0
        var diagAt = SystemClock.elapsedRealtime()
        try {
            while (running) {
                val frame = ByteArray(1 + FRAME_BYTES)
                frame[0] = LiveProtocol.TAG_AUDIO
                var got = 0
                while (got < FRAME_BYTES && running) {
                    val n = rec.read(frame, 1 + got, FRAME_BYTES - got)
                    if (n < 0) {
                        if (running) onError("麦克风读取失败（错误码 $n）")
                        running = false
                        break
                    }
                    got += n
                }
                if (!running) break
                val isMuted = muted
                if (isMuted) frame.fill(0, 1, frame.size)
                onFrame(frame)

                // 诊断：采样期间的最大幅度和电平方差，用来区分“麦克风没数据”和“环境就是安静的”。
                var peak = 0
                var i = 1
                while (i < frame.size - 1) {
                    val v = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toInt()
                    if (v > peak) peak = v else if (-v > peak) peak = -v
                    i += 2
                }
                diagFrames++
                if (peak > diagPeak) diagPeak = peak
                if (peak <= 50) diagSilent++

                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= LEVEL_INTERVAL_MS) {
                    lastLevelAt = now
                    onLevel(if (isMuted) 0f else AudioMath.level(frame, 1, FRAME_BYTES))
                }
                if (now - diagAt >= DIAG_INTERVAL_MS) {
                    val secs = (now - diagAt) / 1000.0
                    onDiag(
                        "麦克风 ${(diagFrames / secs).toInt()} 帧/秒｜峰值 $diagPeak/32767｜" +
                            "近静音帧 ${diagSilent}/${diagFrames}｜静音=${isMuted}",
                    )
                    diagFrames = 0
                    diagPeak = 0
                    diagSilent = 0
                    diagAt = now
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "mic loop crashed", t)
            if (running) onError("麦克风线程异常：${t.message}")
        }
    }

    private fun enableEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let {
                it.enabled = true
                echoCancelerActive = it.enabled
                effects += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let {
                it.enabled = true
                noiseSuppressorActive = it.enabled
                effects += it
            }
        }
    }

    private fun releaseEffects() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }

    companion object {
        private const val TAG = "YIIAI.Mic"
        /** 20 ms @ 16 kHz PCM16 = 320 采样 = 640 字节。 */
        const val FRAME_BYTES = 640
        private const val LEVEL_INTERVAL_MS = 50L
        private const val DIAG_INTERVAL_MS = 5_000L
    }
}
