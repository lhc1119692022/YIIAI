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

    /**
     * 能不能知道「现在前台是哪个应用」：需要无障碍服务，或者「使用情况访问」权限。
     * 两个都没有时 [current] 永远是 null，不能拿它判断应用有没有打开。
     */
    fun canObserve(context: Context): Boolean {
        if (PhoneUseService.instance != null) return true
        return hasUsageAccess(context)
    }

    /**
     * 「使用情况访问」是否已允许（设置 → 使用情况访问 → YIIAI）。
     * 没有无障碍服务时，这是唯一能判断「前台是哪个应用」的途径。
     */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
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
