package com.yiiai.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 播放 MOSS-Live 下行的 PCM16 单声道语音，并按**真实播放进度**产生回执。
 *
 * 原理：每个片段写入 AudioTrack 时记下它在轨道时间轴上的起止帧，
 * 播放线程轮询 [AudioTrack.getPlaybackHeadPosition]，播放头越过片段终点即视为“实际播完”。
 * 打断时直接重建 AudioTrack 丢弃已写入但未播放的数据，避免 flush 后播放头归零时机不确定。
 *
 * 所有回调都在内部锁内同步触发，保证回执顺序：片段完成 → 整段完成 / 停止。
 * 回调里不要再调用本类的方法。
 */
class VoicePlayer(private val listener: Listener) {

    interface Listener {
        fun onChunkPlayed(runId: String, turnId: String, chunkId: String)
        fun onRunPlayed(runId: String, turnId: String)
        fun onPlayingChanged(playing: Boolean)
        fun onLevel(level: Float)
    }

    private class Chunk(val runId: String, val turnId: String, val chunkId: String?, val data: ByteArray) {
        val size = data.size and 1.inv()
        val frames = (size / 2).toLong()
        var offset = 0
        var startFrame = -1L
        val endFrame: Long get() = startFrame + frames
    }

    private class RunState(val turnId: String) {
        var done = false
    }

    private val lock = ReentrantLock()
    private val pending = ArrayDeque<Chunk>() // 尚未完整写入轨道（队首可能写了一半）
    private val written = ArrayDeque<Chunk>() // 已完整写入、等待播完
    private val runs = LinkedHashMap<String, RunState>()
    private val finished = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 256
    }
    private var track: AudioTrack? = null
    private var sampleRate = 48_000
    private var framesWritten = 0L
    private var gateOpen = false
    private var waitingSince = 0L
    private var playing = false
    private var lastLevelAt = 0L
    private var silence: ByteArray? = null
    private var lastHead = 0L
    private var lastHeadAt = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    /** 诊断用统计，见 [stats]。 */
    private var statChunksIn = 0L
    private var statBytesIn = 0L
    private var statBytesOut = 0L
    private var statSilenceBytes = 0L

    fun stats(): String =
        "收 ${statChunksIn} 块/${statBytesIn / 1024}KB 写入 ${statBytesOut / 1024}KB 补静音 ${statSilenceBytes / 1024}KB " +
            "轨道头 ${lastHead} 帧 已写 ${framesWritten} 帧 待播 ${pending.size} 已写待播 ${written.size}"

    fun start(rate: Int) {
        lock.withLock {
            if (running) return
            sampleRate = rate
            resetLocked()
            statsResetLocked()
            track = createTrack(rate)
            lastHeadAt = SystemClock.elapsedRealtime()
            running = true
        }
        thread = Thread(::loop, "yiiai-player").also { it.start() }
    }

    /** 会话准备阶段按 media.configured 的实际采样率调整。 */
    fun setSampleRate(rate: Int) {
        lock.withLock {
            if (!running || rate == sampleRate) return
            sampleRate = rate
            silence = null
            rebuildTrackLocked(preserveQueue = false)
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        lock.withLock {
            resetLocked()
            track?.let {
                runCatching { it.pause(); it.flush() }
                it.release()
            }
            track = null
            setPlayingLocked(false)
        }
    }

    fun openRun(runId: String, turnId: String) {
        lock.withLock {
            if (!running || runId in finished) return
            runs.getOrPut(runId) { RunState(turnId) }
        }
    }

    fun enqueue(runId: String, turnId: String, chunkId: String?, pcm: ByteArray) {
        lock.withLock {
            if (!running || runId in finished) return
            statChunksIn++
            statBytesIn += pcm.size
            runs.getOrPut(runId) { RunState(turnId) }
            if (pending.isEmpty() && !gateOpen) waitingSince = SystemClock.elapsedRealtime()
            pending.addLast(Chunk(runId, turnId, chunkId, pcm))
        }
    }

    /** 收到 response.audio.done：该回复不会再有新片段，播完后回调 onRunPlayed。 */
    fun markRunDone(runId: String, turnId: String) {
        lock.withLock {
            if (!running || runId in finished) return
            val rs = runs.getOrPut(runId) { RunState(turnId) }
            rs.done = true
            if (!hasChunksLocked(runId)) completeRunLocked(runId, rs)
        }
    }

    /**
     * 停止某个回复并丢弃其未播部分（response.audio.stop / 打断）。
     * 先对已经播完的片段触发回执，再返回该回复队首未播完片段已播放的毫秒数，用于 playback.stopped。
     * 该回复未知或已经播完时返回 null（无需再发 playback.stopped）。
     */
    fun stopRun(runId: String): Long? {
        lock.withLock {
            if (runs[runId] == null) return null
            val t = track
            val head = if (t != null) headFrames(t) else 0L
            collectPlayedLocked(head)
            if (!runs.containsKey(runId)) return null // 恰好刚播完

            val first = written.firstOrNull { it.runId == runId } ?: pending.firstOrNull { it.runId == runId }
            val playedFrames =
                if (first != null && first.startFrame >= 0) (head - first.startFrame).coerceIn(0L, first.frames) else 0L
            val inTrack = written.any { it.runId == runId } ||
                pending.firstOrNull()?.let { it.runId == runId && it.startFrame >= 0 } == true

            runs.remove(runId)
            finished[runId] = true
            if (inTrack) {
                val keep = (written + pending).filter { it.runId != runId }
                written.clear()
                pending.clear()
                keep.forEach { pending.addLast(it) }
                rebuildTrackLocked(preserveQueue = true)
            } else {
                pending.removeAll { it.runId == runId }
            }
            setPlayingLocked(written.isNotEmpty() || pending.isNotEmpty())
            return playedFrames * 1000L / sampleRate
        }
    }

    /** 当前尚未结束的回复（run_id 到 turn_id）。 */
    fun openRuns(): List<Pair<String, String>> = lock.withLock { runs.map { it.key to it.value.turnId } }

    // ---------------------------------------------------------------- 播放线程

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                var busy = false
                var active = false
                lock.withLock {
                    val t = track
                    if (t != null) {
                        var head = headFrames(t)
                        // 播放头长时间不动而仍有待播片段：轨道被系统摘出活动列表（BUFFER TIMEOUT）或卡死，重建。
                        if (head != lastHead) {
                            lastHead = head
                            lastHeadAt = SystemClock.elapsedRealtime()
                        } else if (written.isNotEmpty() &&
                            SystemClock.elapsedRealtime() - lastHeadAt > STALL_REBUILD_MS
                        ) {
                            Log.w(TAG, "playback head stalled at $head frames, rebuilding track. ${stats()}")
                            rebuildTrackLocked(preserveQueue = true)
                            head = 0L
                            lastHead = 0L
                            lastHeadAt = SystemClock.elapsedRealtime()
                        }
                        collectPlayedLocked(head)
                        maybeOpenGateLocked(head)
                        if (gateOpen && pending.isNotEmpty()) {
                            busy = writeLocked(t)
                        } else if (gateOpen && written.isNotEmpty()) {
                            // 待播片段还没写完但网络暂时没有新数据：补静音，
                            // 保持轨道活跃、播放头继续前进，片段回执才能按真实进度触发。
                            paddingLocked(t, head)
                        }
                        if (pending.isEmpty() && written.isEmpty()) gateOpen = false
                        active = written.isNotEmpty() || pending.isNotEmpty()
                        setPlayingLocked(active)
                    }
                }
                if (!busy) Thread.sleep(if (active) 5L else 15L)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.e(TAG, "player loop crashed", t)
        }
    }

    /** 轨道空闲时先攒够约 [PREBUFFER_MS] 再开播，减少网络抖动造成的卡顿。 */
    private fun maybeOpenGateLocked(head: Long) {
        if (gateOpen || pending.isEmpty()) return
        val buffered = framesWritten - head
        val queuedFrames = pending.sumOf { (it.size - it.offset) / 2L }
        val runDone = runs[pending.first().runId]?.done == true
        val waited = SystemClock.elapsedRealtime() - waitingSince
        if (buffered > 0 || queuedFrames >= sampleRate * PREBUFFER_MS / 1000 || runDone || waited >= MAX_WAIT_MS) {
            gateOpen = true
        }
    }

    private fun writeLocked(t: AudioTrack): Boolean {
        val c = pending.first()
        if (c.startFrame < 0) c.startFrame = framesWritten
        val remaining = c.size - c.offset
        if (remaining > 0) {
            val n = t.write(c.data, c.offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) {
                Log.w(TAG, "AudioTrack.write failed: $n, rebuilding track")
                rebuildTrackLocked(preserveQueue = true)
                return false
            }
            if (n == 0) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelAt >= LEVEL_INTERVAL_MS) {
                lastLevelAt = now
                listener.onLevel(AudioMath.level(c.data, c.offset, n))
            }
            c.offset += n
            framesWritten += n / 2
            statBytesOut += n
        }
        if (c.offset >= c.size) {
            pending.removeFirst()
            written.addLast(c)
            Log.d(TAG, "片段写完 ${c.chunkId ?: "(无id)"} ${c.frames} 帧，轨道位置 ${c.endFrame}${runSuffix(c.runId)}")
        }
        return true
    }

    /**
     * 用静音填补播放缓冲的空档。
     * 静止的轨道会被 AudioFlinger 判定欠载并摘出活动列表，播放头随之冻结，
     * 片段回执就永远不会触发；补静音让播放头保持匀速前进。
     */
    private fun paddingLocked(t: AudioTrack, head: Long) {
        val buffered = framesWritten - head
        val target = sampleRate * TARGET_BUFFER_MS / 1000
        if (buffered >= target) return
        val needBytes = ((target - buffered).coerceAtMost((sampleRate * SILENCE_STEP_MS / 1000).toLong()) * 2).toInt()
        if (needBytes <= 0) return
        val zero = silenceFor(needBytes)
        val n = t.write(zero, 0, zero.size, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) {
            framesWritten += n / 2
            statBytesOut += n
            statSilenceBytes += n
        }
    }

    private fun silenceFor(bytes: Int): ByteArray {
        val cached = silence
        if (cached != null && cached.size >= bytes) return cached
        val fresh = ByteArray(maxOf(bytes, sampleRate * SILENCE_STEP_MS / 1000 * 2))
        silence = fresh
        return fresh
    }

    private fun runSuffix(runId: String) = "（run ${runId.takeLast(6)}）"

    // ---------------------------------------------------------------- 内部工具（均需持锁）

    private fun collectPlayedLocked(head: Long) {
        while (written.isNotEmpty() && written.first().endFrame <= head) {
            val c = written.removeFirst()
            c.chunkId?.let { listener.onChunkPlayed(c.runId, c.turnId, it) }
            val rs = runs[c.runId]
            if (rs != null && rs.done && !hasChunksLocked(c.runId)) completeRunLocked(c.runId, rs)
        }
    }

    private fun completeRunLocked(runId: String, rs: RunState) {
        runs.remove(runId)
        finished[runId] = true
        listener.onRunPlayed(runId, rs.turnId)
    }

    private fun hasChunksLocked(runId: String) =
        written.any { it.runId == runId } || pending.any { it.runId == runId }

    private fun setPlayingLocked(now: Boolean) {
        if (now == playing) return
        playing = now
        listener.onPlayingChanged(now)
        if (!now) listener.onLevel(0f)
    }

    private fun resetLocked() {
        pending.clear()
        written.clear()
        runs.clear()
        finished.clear()
        framesWritten = 0L
        gateOpen = false
        silence = null
    }

    private fun statsResetLocked() {
        statChunksIn = 0
        statBytesIn = 0
        statBytesOut = 0
        statSilenceBytes = 0
        lastHead = 0L
    }

    private fun rebuildTrackLocked(preserveQueue: Boolean = true) {
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        if (preserveQueue) {
            // 已写入但未播完的片段放回队列重写，从头播放该片段；未播部分由 playback.stopped 兜底。
            val keep = written + pending
            written.clear()
            pending.clear()
            keep.forEach {
                it.offset = 0
                it.startFrame = -1L
                pending.addLast(it)
            }
        } else {
            pending.clear()
            written.clear()
            runs.clear()
            finished.clear()
        }
        track = createTrack(sampleRate)
        lastHead = 0L
        lastHeadAt = SystemClock.elapsedRealtime()
    }

    private fun createTrack(rate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf * 2, rate * 2 * BUFFER_MS / 1000)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufBytes)
            .build()
        t.play()
        framesWritten = 0L
        gateOpen = false
        return t
    }

    private fun headFrames(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private companion object {
        const val TAG = "YIIAI.Player"
        /** AudioTrack 缓冲区：太小时网络抖动直接变成欠载。 */
        const val BUFFER_MS = 400
        /** 开播前先攒这么久的音频。 */
        const val PREBUFFER_MS = 200
        const val MAX_WAIT_MS = 200L
        /** 补静音时维持的缓冲水位（须小于 BUFFER_MS）。 */
        const val TARGET_BUFFER_MS = 250
        /** 每次补静音的步长，约 20 ms。 */
        const val SILENCE_STEP_MS = 20
        /** 播放头停滞多久判定为卡死/被摘出活动列表，需要重建轨道。 */
        const val STALL_REBUILD_MS = 500L
        const val LEVEL_INTERVAL_MS = 50L
    }
}
