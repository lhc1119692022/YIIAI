package com.yiiai.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.yiiai.app.YiiApp
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.CallState
import com.yiiai.app.live.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 通话前台服务。
 *
 * 进程内唯一，负责三件事：
 * 1. 以 foregroundServiceType=microphone 运行，Android 14+ 起麦克风必须有前台服务；
 * 2. 把 LiveSession 的状态映射到 CallStyle 通知（含静音 / 挂断按钮，供岛与通知栏使用）；
 * 3. 界面与悬浮窗被销毁后，让通话继续跑。
 */
class LiveCallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var lastNotificationKey: String? = null
    /** 这次服务生命周期里是否真的进入过通话。用来区分“还没开始”和“已经打完”。 */
    private var everActive = false

    private val app get() = application as YiiApp
    private val live get() = app.live

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CallNotification.ACTION_HANGUP -> {
                live.hangup()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            CallNotification.ACTION_STOP_SERVICE -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            CallNotification.ACTION_TOGGLE_MUTE -> {
                live.toggleMute()
                return START_STICKY
            }
            CallNotification.ACTION_TOGGLE_SPEAKER -> {
                live.toggleSpeaker()
                return START_STICKY
            }
            CallNotification.ACTION_OPEN, null -> {
                // 从通知点进来，服务已经在跑，无需额外动作。
            }
        }

        // 进入前台。麦克风类型必须在真正开始采集前声明好。
        enterForeground(live.state.value)
        observe()

        // 界面发起通话时会先 startService 再调 live.start()，这里兜底。
        if (intent?.getBooleanExtra(EXTRA_START_CALL, false) == true) {
            live.start()
        }
        return START_STICKY
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            live.state.collect { state ->
                if (state.phase.isActive) everActive = true
                // 只有“已经打过一通电话、现在真的结束了”才收掉服务。
                // 否则启动瞬间读到的 Idle 会把服务直接关掉，前台服务和通知就都没了。
                val finished = !state.phase.isActive && state.phase != CallPhase.Failed &&
                    (everActive || state.error != null)
                if (finished) {
                    stopSelfSafely()
                    return@collect
                }
                refreshNotification(state)
            }
        }
    }

    private fun enterForeground(state: CallState) {
        val notification = CallNotification.build(this, state, app.settings.current.personaName)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelfSafely()
        }
    }

    private fun refreshNotification(state: CallState) {
        val key = "${state.phase}|${state.muted}|${state.userSpeaking}|${state.assistantSpeaking}|${state.notice}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val notification = CallNotification.build(this, state, app.settings.current.personaName)
        Log.d(TAG, "通知 ${state.phase}｜${CallNotification.promotable(this, notification)}")
        CallNotification.post(this, notification)
    }

    private fun stopSelfSafely() {
        observeJob?.cancel()
        observeJob = null
        lastNotificationKey = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YIIAI.Service"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_START_CALL = "start_call"

        /** 仅用于让悬浮窗/界面拿到“服务是否在跑”。 */
        @Volatile
        var instance: LiveCallService? = null
            private set

        fun start(context: Context, startCall: Boolean) {
            val intent = Intent(context, LiveCallService::class.java).apply {
                action = CallNotification.ACTION_OPEN
                putExtra(EXTRA_START_CALL, startCall)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveCallService::class.java).setAction(CallNotification.ACTION_STOP_SERVICE),
            )
        }
    }
}
