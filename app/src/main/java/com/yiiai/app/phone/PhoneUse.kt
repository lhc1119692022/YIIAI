package com.yiiai.app.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** 无障碍服务的开启状态与跳转。 */
object PhoneUse {

    val serviceComponent: ComponentName
        get() = ComponentName(
            "com.yiiai.app",
            "com.yiiai.app.phone.PhoneUseService",
        )

    /** 服务已经连上（真正可用），而不只是用户在设置里勾了开关。 */
    fun isReady(): Boolean = PhoneUseService.isReady

    /** 用户在系统设置里是否已勾选。刚勾选、系统还没连上时为 false。 */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val flat = serviceComponent.flattenToString()
        val short = serviceComponent.flattenToShortString()
        return enabled.split(':').any { it.equals(flat, true) || it.equals(short, true) }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 能否在无障碍里截图（Android 11+）。 */
    fun canScreenshot(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

    /**
     * 曾经开通过、现在却被关掉了（多半是系统在某次重启或强杀后自动关闭）。
     * 这时值得主动提醒用户，而不是等他用「看一眼」失败才发现。
     */
    fun wasDisabled(context: Context, everReady: Boolean): Boolean =
        everReady && !isReady()

    /** 状态说明，用于设置页和通话中提示。 */
    fun statusText(context: Context): String = when {
        isReady() -> "已开启"
        isEnabled(context) -> "已勾选，等待系统连接"
        else -> "未开启"
    }

    /** 已安装的可启动应用，用于「打开某个 App」。 */
    fun launchableApps(context: Context): List<Pair<String, String>> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(manager)?.toString()?.trim().orEmpty()
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (label.isEmpty() || pkg == context.packageName) null else label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
    }

    fun launchApp(context: Context, nameOrPackage: String): String {
        val manager = context.packageManager
        val key = nameOrPackage.trim()
        if (key.isEmpty()) return "没有给出应用名"
        // 先当包名试
        if (key.contains('.')) {
            val byPackage = manager.getLaunchIntentForPackage(key)
            if (byPackage != null) {
                runCatching { context.startActivity(byPackage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return "已打开 $key"
            }
        }
        val apps = launchableApps(context)
        val hit = apps.firstOrNull { it.first == key }
            ?: apps.firstOrNull { it.first.contains(key, ignoreCase = true) }
            ?: return "没找到叫「$key」的应用"
        val intent = manager.getLaunchIntentForPackage(hit.second)
            ?: return "「${hit.first}」没有可启动的界面"
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return "已打开 ${hit.first}"
    }
}
