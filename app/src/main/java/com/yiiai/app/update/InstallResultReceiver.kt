package com.yiiai.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 接收 PackageInstaller 的安装结果。
 *
 * 系统会把结果广播回来（STATUS_PENDING_USER_ACTION / SUCCESS / FAILURE），
 * 这里主要处理「需要用户确认」和失败提示，避免用户点完没反应也不知道为什么。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统要求用户确认安装：把系统给的确认界面拉起来。
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                // 安装成功，系统会自动结束本进程；这里不用做事。
            }
            android.content.pm.PackageInstaller.STATUS_FAILURE,
            android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED,
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,
            android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID,
            android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT,
            android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE,
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "安装失败：${msg ?: "未知原因"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
