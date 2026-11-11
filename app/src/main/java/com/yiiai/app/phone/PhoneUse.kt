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

    /** 已安装的可启动应用（全量，含被隐藏/冻结的）。 */
    fun launchableApps(context: Context): List<Pair<String, String>> =
        AppCatalog.all(context).map { it.label to it.packageName }

    fun launchApp(context: Context, nameOrPackage: String): String =
        AppCatalog.launch(context, nameOrPackage)

    /** 按关键词找应用，给模型「查一下装没装」用。 */
    fun searchApps(context: Context, query: String, limit: Int = 12): List<Pair<String, String>> =
        AppCatalog.search(context, query, limit).map { it.label to it.packageName }

    /** 应用总数，用于诊断。 */
    fun appCount(context: Context): Int = AppCatalog.all(context).size
}
