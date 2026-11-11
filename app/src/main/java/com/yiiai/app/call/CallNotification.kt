package com.yiiai.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.yiiai.app.MainActivity
import com.yiiai.app.R
import com.yiiai.app.live.CallPhase
import com.yiiai.app.live.CallState
import com.yiiai.app.live.isActive

/**
 * 通话通知。
 *
 * 走 Android 16 的 Live Updates（setRequestPromotedOngoing + BigTextStyle），
 * HyperOS 3 会把它画进状态栏中间的超级岛：左边是小图标（小鹿剪影），右边是 setShortCriticalText 的短文字。
 * 之前用的 miui.focus.* 私有字段需要厂商白名单，已移除；RikkaHub 用的也是这条公开路径。
 */
object CallNotification {

    const val CHANNEL_ID = "yiiai_call"
    private const val NOTIFICATION_ID = 1001

    const val ACTION_HANGUP = "com.yiiai.app.action.HANGUP"
    const val ACTION_TOGGLE_MUTE = "com.yiiai.app.action.TOGGLE_MUTE"
    const val ACTION_TOGGLE_SPEAKER = "com.yiiai.app.action.TOGGLE_SPEAKER"
    const val ACTION_OPEN = "com.yiiai.app.action.OPEN"
    const val ACTION_STOP_SERVICE = "com.yiiai.app.action.STOP_SERVICE"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "语音通话",
            // CALL 通道不会被静音，且系统会把它当通话类处理，是进岛的前提之一。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "YIIAI 进行中的语音通话"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // 通话进行中：避免被用户误划掉。
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: CallState, assistantName: String): Notification {
        ensureChannel(context)
        val name = assistantName.ifBlank { "YIIAI" }
        val title = when (state.phase) {
            CallPhase.Reconnecting -> "$name · 正在重新连接"
            CallPhase.Connecting, CallPhase.Configuring -> "$name · 正在连接"
            CallPhase.Failed -> "$name · 连接失败"
            else -> name
        }
        val body = when {
            state.muted -> "已静音"
            state.assistantSpeaking -> "正在说话"
            state.userSpeaking -> "正在听你说"
            state.phase == CallPhase.Reconnecting -> "网络恢复后自动接回对话"
            state.phase == CallPhase.Live -> "通话中"
            else -> "轻点回到对话"
        }

        val person = Person.Builder()
            .setName(name)
            .setImportant(true)
            .setBot(true)
            .build()

        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(state.phase.isActive)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            // 和 RikkaHub 一致用 PROGRESS：CALL 类会被 HyperOS 的通话监控接管成绿色通话胶囊。
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val ongoing = state.phase.isActive && state.phase != CallPhase.Failed
        // 超级岛（Android 16 Live Updates，HyperOS 3 会把它画进状态栏中间的胶囊）：
        // 只在「活跃」时常驻——通话中且没有静音。静音、失败、结束时都不申请提升，岛自动收起。
        val promoted = ongoing && !state.muted
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        if (ongoing) {
            builder.addAction(muteAction(context, state.muted))
            builder.addAction(hangupAction(context))
        } else {
            builder.addAction(stopAction(context))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            builder.setRequestPromotedOngoing(promoted)
        }
        // 岛上图标右侧的短文字。
        if (promoted && Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(
                when {
                    state.phase == CallPhase.Reconnecting -> "重连中"
                    state.phase == CallPhase.Connecting || state.phase == CallPhase.Configuring -> "连接中"
                    state.assistantSpeaking -> "正在说"
                    state.userSpeaking -> "在听你说"
                    else -> "在听"
                },
            )
        }
        return builder.build()
    }

    private fun titleFor(state: CallState): String = when (state.phase) {
        CallPhase.Reconnecting -> "正在重新连接"
        CallPhase.Connecting, CallPhase.Configuring -> "正在连接"
        CallPhase.Failed -> "连接失败"
        else -> "通话中"
    }

    private fun muteAction(context: Context, muted: Boolean): NotificationCompat.Action {
        val intent = PendingIntent.getService(
            context,
            2,
            Intent(context, LiveCallService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, icon),
            if (muted) "取消静音" else "静音",
            intent,
        ).build()
    }

    private fun hangupAction(context: Context): NotificationCompat.Action {
        val intent = PendingIntent.getService(
            context,
            3,
            Intent(context, LiveCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_call_end),
            "挂断",
            intent,
        ).build()
    }

    private fun stopAction(context: Context): NotificationCompat.Action {
        val intent = PendingIntent.getService(
            context,
            4,
            Intent(context, LiveCallService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_call_end),
            "关闭",
            intent,
        ).build()
    }

    fun post(context: Context, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** 诊断用：这份通知是否够格被系统提升成胶囊。 */
    fun promotable(context: Context, notification: Notification): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val canPost = manager.canPostPromotedNotifications()
        val characteristics = notification.hasPromotableCharacteristics()
        return "可提升=$characteristics，系统允许=$canPost"
    }
}
