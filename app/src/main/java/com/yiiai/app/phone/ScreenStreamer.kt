package com.yiiai.app.phone

import android.content.Context
import android.util.Log
import com.yiiai.app.live.LiveProtocol
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
 * 节奏：约 1 秒一帧（和 media.configure 里声明的 interval_ms 一致），
 * 只在通话中、且用户停在别的 App 里时才跑；App 自己在前台时不推，避免把自己的界面发给模型。
 */
class ScreenStreamer(
    private val context: Context,
    private val sendEvent: (String) -> Boolean,
    private val sendFrame: (ByteArray) -> Boolean,
    private val onLog: (String) -> Unit,
) {

    private val appContext = context.applicationContext
    private var job: Job? = null
    private val busy = AtomicBoolean(false)

    /** 正在推流。 */
    val running: Boolean get() = job?.isActive == true

    /**
     * 开始 / 停止。stop 时会关掉服务端的屏幕通道。
     * [intervalMs] 建议 1000；太小会挤占上行带宽，影响语音。
     */
    fun start(scope: CoroutineScope, intervalMs: Long = 1000L, maxSide: Int = 1024) {
        if (running) return
        if (!PhoneUse.isReady()) {
            onLog("屏幕画面不可用：无障碍服务没开")
            return
        }
        sendEvent(LiveProtocol.videoState("screen", true))
        job = scope.launch {
            onLog("屏幕画面已开启（约 ${1000 / intervalMs} 帧/秒）")
            var failures = 0
            while (isActive) {
                // 自己的界面不推：用户看到的是YIIAI自己，发过去没有意义。
                if (shouldStream()) {
                    if (busy.compareAndSet(false, true)) {
                        val svc = PhoneUseService.instance
                        if (svc == null) {
                            if (failures++ > 2) break
                        } else {
                            val (bytes, why) = svc.screenshot(maxSide = maxSide, quality = 70)
                            if (bytes != null) {
                                sendFrame(bytes)
                                failures = 0
                            } else {
                                // 连续失败多半页面禁止截屏，歇一会儿再试，别刷屏。
                                if (failures++ == 3) onLog("屏幕截图失败：$why")
                            }
                        }
                        busy.set(false)
                    }
                }
                delay(intervalMs)
            }
            Log.d(TAG, "stream loop ended")
        }
    }

    fun stop(reason: String? = null) {
        val was = running
        job?.cancel()
        job = null
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
    }
}
