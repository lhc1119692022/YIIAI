package com.yiiai.app.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiiai.app.BuildConfig
import com.yiiai.app.update.Updater
import com.yiiai.app.ui.theme.LocalYiiPalette
import kotlinx.coroutines.launch

/**
 * 应用内的检查更新块。
 *
 * 状态机：idle → checking → (upToDate | available) → downloading → readyToInstall。
 * 没有后台自动检查，避免每次启动都打一次 GitHub API（它限流很严）。
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    val palette = LocalYiiPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<Updater.UpdateResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = downloaded
        when {
            apk == null -> message = "安装包已不可用，请重新下载。"
            Updater.canInstall(context) -> install(context, apk) { message = it }
            else -> message = "尚未允许 YIIAI 安装未知应用。开启权限后返回并再次点击安装。"
        }
    }

    val release = (state as? Updater.UpdateResult.Available)?.release

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("版本", color = palette.text, fontSize = 15.sp)
                Text(
                    "当前 ${BuildConfig.VERSION_NAME}",
                    color = palette.textDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            when {
                busy -> Text("处理中…", color = palette.textDim, fontSize = 13.sp)
                downloaded != null -> Text(
                    "安装",
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val apk = downloaded ?: return@clickable
                            if (Updater.canInstall(context)) {
                                install(context, apk) { message = it }
                            } else {
                                message = "请在系统设置中开启“允许来自此来源的应用”，返回后会继续安装。"
                                runCatching { installPermissionLauncher.launch(Updater.installPermissionIntent(context)) }
                                    .onFailure { message = "无法打开安装权限设置：${it.message ?: "请手动前往系统设置开启"}" }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                else -> Text(
                    "检查更新",
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            scope.launch {
                                busy = true
                                message = null
                                downloaded = null
                                progress = 0f
                                val r = Updater.check(BuildConfig.VERSION_NAME)
                                state = r
                                busy = false
                                when (r) {
                                    is Updater.UpdateResult.UpToDate -> message = "已经是最新版本"
                                    is Updater.UpdateResult.Failed -> message = r.message
                                    else -> Unit
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (release != null && downloaded == null) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surfaceRaised)
                    .padding(12.dp),
            ) {
                Text(
                    "发现新版本 ${release.version}",
                    color = palette.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(release.notes, color = palette.textDim, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 5)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.accent)
                        .clickable(enabled = !busy) {
                            scope.launch {
                                busy = true
                                progress = 0.001f
                                Updater.download(context, release) { progress = it }
                                    .onSuccess { downloaded = it; message = "下载完成，点上方「安装」" }
                                    .onFailure { message = it.message ?: "下载失败"; progress = 0f }
                                busy = false
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "下载中 ${(progress * 100).toInt()}%" else "下载（${release.apkSize / 1024 / 1024} MB）",
                        color = if (palette.isDark) androidx.compose.ui.graphics.Color(0xFF10140E) else androidx.compose.ui.graphics.Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (busy && progress > 0f) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = palette.textDim, fontSize = 12.sp)
        }
    }
}

/** 交给系统安装器；失败兜底到浏览器。 */
private fun install(context: android.content.Context, apk: java.io.File, onMessage: (String) -> Unit) {
    val id = Updater.requestInstall(context, apk) { msg -> onMessage(msg) }
    if (id < 0) {
        onMessage("应用内安装没成功，已改为在浏览器打开下载页，请手动下载安装。")
        Updater.openInBrowser(context, Updater.Release("", "", "", 0, "https://github.com/lhc1119692022/YIIAI/releases/latest"))
    }
}
