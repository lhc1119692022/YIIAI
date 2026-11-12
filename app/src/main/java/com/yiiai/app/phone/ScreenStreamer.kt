package com.yiiai.app.phone

import android.content.Context
import android.util.Log
import com.yiiai.app.live.LiveProtocol
import com.yiiai.app.settings.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕实时画面流。
 *
 * 模型「看到」用户屏幕的主要通道是这条视频流，控件树只是辅助（用来精确点击）。
 * 原因是控件树只描述结构，用户看到的画面、图片、视频帧、弹窗样式它都读不到；
 * 而截图能直接给出「用户此刻看到的东西」。
 *
 * 节奏由画质档位决定（每 2 秒 1 帧到每秒 2 帧）。画面没有变化时不重复发送，
 * 只隔几秒补一帧，省流量也省模型的上下文。
 * 只在通话中、且用户停在别的 App 里时才跑；App 自己在前台时不推，避免把自己的界面发给模型。
 */
class ScreenStreamer(
    private val context: Context,
    private val sendEvent: (String) -> Boolean,
    /** true 已发出；false 连接不在了；null 上行忙，这一帧跳过。 */
    private val sendFrame: (ByteArray) -> Boolean?,
    private val onLog: (String) -> Unit,
) {

    private val appContext = context.applicationContext
    private var job: Job? = null
    private val busy = AtomicBoolean(false)

    /** 正在推流。 */
    val running: Boolean get() = job?.isActive == true

    /** YIIAI 自己在前台，暂时没有发画面。计时方据此停表。 */
    @Volatile
    var paused: Boolean = false
        private set

    /** 这一轮观看里实际发出去多少帧、因为画面没变省掉多少帧。 */
    @Volatile var framesSent = 0
        private set
    @Volatile var framesSkipped = 0
        private set

    /** 开始推流。已经在推时不重复开。 */
    fun start(scope: CoroutineScope, currentQuality: () -> VideoQuality) {
        if (running) return
        if (!PhoneUse.isReady()) {
            onLog("屏幕画面不可用：无障碍服务没开")
            return
        }
        sendEvent(LiveProtocol.videoState("screen", true))
        sendEvent(LiveProtocol.videoStreamFocus("screen"))
        framesSent = 0
        framesSkipped = 0
        paused = false
        job = scope.launch {
            onLog("屏幕画面已开启（${currentQuality().summary}）")
            var failures = 0
            var lastHash = 0
            var lastSize = -1
            var lastSentAt = 0L
            var sent: Boolean? = null
            while (isActive) {
                val loopStart = android.os.SystemClock.elapsedRealtime()
                // 每一帧都读当前档位：通话中在设置里改画质，下一帧就生效。
                val quality = currentQuality()
                // 自己的界面不推：用户看到的是YIIAI自己，发过去没有意义。
                val streaming = shouldStream()
                paused = !streaming
                if (streaming && busy.compareAndSet(false, true)) {
                    try {
                        val svc = PhoneUseService.instance
                        if (svc == null) {
                            if (failures++ > 2) {
                                onLog("屏幕画面中断：无障碍服务被系统关掉了")
                                break
                            }
                        } else {
                            val (bytes, why) = svc.screenshot(maxSide = quality.maxLongSide, quality = quality.jpegQuality)
                            if (bytes != null) {
                                failures = 0
                                val now = System.currentTimeMillis()
                                val hash = bytes.contentHashCode()
                                val same = bytes.size == lastSize && hash == lastHash
                                if (same && now - lastSentAt < STILL_REFRESH_MS) {
                                    framesSkipped++
                                } else if (sendFrame(bytes).also { sent = it } == null) {
                                    // 上行正忙：这一帧不发，也不记成「已发过」，下一轮再试。
                                    framesSkipped++
                                } else {
                                    val ok = sent
                                    lastHash = hash
                                    lastSize = bytes.size
                                    lastSentAt = now
                                    framesSent++
                                    if (framesSent % 10 == 1) {
                                        Log.d(
                                            TAG,
                                            "frame#$framesSent ${bytes.size / 1024}KB sent=$ok skipped=$framesSkipped " +
                                                "top=${ForegroundApp.current(appContext)}",
                                        )
                                    }
                                }
                            } else {
                                // 连续失败多半页面禁止截屏，歇一会儿再试，别刷屏。
                                if (failures++ == 3) onLog("屏幕截图失败：$why")
                            }
                        }
                    } finally {
                        busy.set(false)
                    }
                }
                // 扣掉截图和编码花的时间，实际帧率才对得上档位。
                delay((quality.intervalMs - (android.os.SystemClock.elapsedRealtime() - loopStart)).coerceAtLeast(40L))
            }
            Log.d(TAG, "stream loop ended")
        }
    }

    fun stop(reason: String? = null) {
        val was = running
        job?.cancel()
        job = null
        paused = false
        if (was) {
            sendEvent(LiveProtocol.videoState("screen", false))
            reason?.let(onLog)
        }
    }

    private fun shouldStream(): Boolean {
        val top = ForegroundApp.current(appContext)
        // 自己在前台（含悬浮面板）时不推。
        return top != null && top != appContext.packageName
    }

    private companion object {
        const val TAG = "YIIAI.ScreenStream"

        /** 画面一直不变时，隔这么久也补发一帧，让服务端知道画面还在。 */
        const val STILL_REFRESH_MS = 5_000L
    }
}
