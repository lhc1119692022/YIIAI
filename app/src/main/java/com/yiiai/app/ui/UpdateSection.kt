package com.yiiai.app.ui

import android.text.format.DateFormat
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
    var showNotes by remember { mutableStateOf(false) }

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
                busy && progress > 0f -> Text(
                    "下载 ${(progress * 100).toInt()}%",
                    color = palette.accent,
                    fontSize = 13.sp,
                )
                downloaded != null -> Text(
                    "安装",
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (!Updater.canInstall(context)) {
                                message = "需要先允许「安装未知应用」，已为你打开设置"
                                Updater.openInstallPermission(context)
                            } else if (!Updater.install(context, downloaded!!)) {
                                message = "调起安装器失败，可以到文件管理器里手动安装"
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                else -> Text(
                    if (busy) "检查中…" else "检查更新",
                    color = if (busy) palette.textDim else palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) {
                            scope.launch {
                                busy = true
                                message = null
                                downloaded = null
                                progress = 0f
                                val r = Updater.check(BuildConfig.VERSION_NAME)
                                state = r
                                busy = false
                                when (r) {
                                    is Updater.UpdateResult.Available -> showNotes = true
                                    is Updater.UpdateResult.UpToDate -> message = "已经是最新版本"
                                    is Updater.UpdateResult.Failed -> message = r.message
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // 有新版本：展示版本号 + 更新说明 + 下载按钮
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
                    Text(
                        release.notes,
                        color = palette.textDim,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = 6,
                    )
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
                                val r = Updater.download(context, release) { p -> progress = p }
                                busy = false
                                r.onSuccess { file ->
                                    downloaded = file
                                    message = "下载完成，点「安装」继续"
                                }.onFailure { e ->
                                    message = e.message ?: "下载失败"
                                    progress = 0f
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "下载中 ${(progress * 100).toInt()}%" else "下载更新（${release.apkSize / 1024 / 1024} MB）",
                        color = if (palette.isDark) androidx.compose.ui.graphics.Color(0xFF10140E) else androidx.compose.ui.graphics.Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = palette.textDim, fontSize = 12.sp)
        }
    }

    if (showNotes && release != null) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text("更新到 ${release.version}") },
            text = {
                Text(
                    release.notes.ifBlank { "这个版本没有写更新说明。" },
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showNotes = false }) { Text("稍后") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotes = false
                    scope.launch {
                        busy = true
                        progress = 0.001f
                        Updater.download(context, release) { p -> progress = p }
                            .onSuccess { downloaded = it; message = "下载完成，点「安装」继续" }
                            .onFailure { message = it.message ?: "下载失败"; progress = 0f }
                        busy = false
                    }
                }) { Text("下载") }
            },
        )
    }
}
