package com.yiiai.app.audio

import kotlin.math.log10
import kotlin.math.sqrt

internal object AudioMath {
    /** PCM16 小端片段的响度，映射到 0..1（约 -55 dB ~ -5 dB），用于界面动效。 */
    fun level(pcm: ByteArray, offset: Int, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            count++
            i += 8 // 每 4 个采样取 1 个，足够画动效
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / 32768.0
        val db = 20.0 * log10(rms.coerceAtLeast(1e-5))
        return ((db + 55.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
    }
}
