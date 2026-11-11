package com.yiiai.app.phone

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** 当前最前台的应用包名。用于判断「用户正在看别的 App」。 */
object ForegroundApp {

    private var cachedAt = 0L
    private var cached: String? = null

    /** 启动应用后强制重新查，别用缓存。 */
    fun invalidate() {
        cachedAt = 0L
        cached = null
    }

    fun current(context: Context): String? {
        val now = System.currentTimeMillis()
        // 一秒内复用，避免每帧都查系统。
        if (now - cachedAt < 900) return cached
        cachedAt = now
        cached = runCatching { query(context) }.getOrNull()
        return cached
    }

    private fun query(context: Context): String? {
        // 无障碍服务能直接拿到活动窗口，最准。
        PhoneUseService.instance?.rootInActiveWindow?.packageName?.toString()?.let { return it }
        // 退而求其次用用法统计（需要 PACKAGE_USAGE_STATS，未授权时为 null）。
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - 10_000, now) ?: return null
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.packageName
        }
        return last
    }
}
