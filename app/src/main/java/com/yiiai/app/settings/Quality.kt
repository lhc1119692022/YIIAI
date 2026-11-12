package com.yiiai.app.settings

/**
 * 画质与音质的三档预设。音频、屏幕、摄像头各选各的。
 *
 * 档位的取值都落在 MOSS-Live 协议允许的范围内（长边 128–1920、JPEG 质量 0.1–1、
 * 帧间隔 200–10000 ms、下行 PCM 16/24/48 kHz）。两个硬上限：
 * - 协议的帧间隔最短 200 ms，所以任何一路最高每秒 5 帧；
 * - 无障碍截图系统限制两次至少间隔约 333 ms，所以屏幕最高约每秒 3 帧。
 */
enum class QualityLevel(val key: String, val label: String) {
    Saver("saver", "省流"),
    Standard("standard", "标准"),
    Clear("clear", "清晰");

    companion object {
        fun of(key: String?): QualityLevel = entries.firstOrNull { it.key == key } ?: Standard
    }
}

/** 一路画面的采集规格。 */
data class VideoQuality(
    /** 长边像素上限。 */
    val maxLongSide: Int,
    /** JPEG 质量，1–100（发给服务端时换算成 0.1–1）。 */
    val jpegQuality: Int,
    /** 两帧之间的间隔。 */
    val intervalMs: Long,
) {
    /** 给界面看的帧率说明。 */
    val rateLabel: String
        get() = if (intervalMs >= 1000L) {
            if (intervalMs == 1000L) "每秒 1 帧" else "每 ${intervalMs / 1000} 秒 1 帧"
        } else {
            "每秒 ${Math.round(1000f / intervalMs)} 帧"
        }

    val summary: String get() = "长边 $maxLongSide · 质量 $jpegQuality · $rateLabel"
}

object QualityPresets {

    /** 下行（角色说话）的 PCM 采样率。上行麦克风协议固定 16 kHz，不随档位变化。 */
    fun audioSampleRate(level: QualityLevel): Int = when (level) {
        QualityLevel.Saver, QualityLevel.Standard -> 16_000
        QualityLevel.Clear -> 24_000
    }

    /** 下行音频每秒流量（KB）：单声道 16 位 PCM，采样率 × 2 字节。 */
    fun audioKbPerSecond(level: QualityLevel): Int = audioSampleRate(level) * 2 / 1000

    /** 屏幕以文字为主，分辨率比帧率重要。 */
    fun screen(level: QualityLevel): VideoQuality = when (level) {
        QualityLevel.Saver -> VideoQuality(maxLongSide = 720, jpegQuality = 60, intervalMs = 1000)
        QualityLevel.Standard -> VideoQuality(maxLongSide = 1080, jpegQuality = 70, intervalMs = FPS_3)
        QualityLevel.Clear -> VideoQuality(maxLongSide = 1280, jpegQuality = 85, intervalMs = FPS_3)
    }

    /** 摄像头拍的是会动的实物和人，帧率比屏幕高。清晰档想要的每秒 10 帧超出协议上限，按每秒 5 帧。 */
    fun camera(level: QualityLevel): VideoQuality = when (level) {
        QualityLevel.Saver -> VideoQuality(maxLongSide = 720, jpegQuality = 60, intervalMs = FPS_3)
        QualityLevel.Standard -> VideoQuality(maxLongSide = 1080, jpegQuality = 70, intervalMs = FPS_5)
        QualityLevel.Clear -> VideoQuality(maxLongSide = 1280, jpegQuality = 85, intervalMs = FPS_5)
    }

    private const val FPS_3 = 334L
    /** 协议允许的最短帧间隔。 */
    private const val FPS_5 = 200L

    /**
     * media.configure 里的 video 只有一份，屏幕和摄像头共用。
     * 这里取两路的「外包络」：最大的长边、最高的质量、最短的间隔，两路各自再按自己的档位采集。
     */
    fun envelope(screen: VideoQuality, camera: VideoQuality) = VideoQuality(
        maxLongSide = maxOf(screen.maxLongSide, camera.maxLongSide),
        jpegQuality = maxOf(screen.jpegQuality, camera.jpegQuality),
        intervalMs = minOf(screen.intervalMs, camera.intervalMs),
    )
}
