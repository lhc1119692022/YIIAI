package com.yiiai.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yiiai.app.phone.PhoneUse
import com.yiiai.app.overlay.OverlayService
import com.yiiai.app.ui.theme.LocalYiiPalette

/**
 * 首次启动的权限引导。
 *
 * 一次把四件事说清楚并当场申请：麦克风、通知、悬浮窗、无障碍。
 * 后两个必须跳系统页面，这里只负责说明和带路；回到 App 时自动刷新状态。
 */
@Composable
fun PermissionSetupScreen(
    onMicRequest: () -> Unit,
    onNotificationRequest: () -> Unit,
    onDone: () -> Unit,
) {
    val palette = LocalYiiPalette.current
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val micGranted = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(tick) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
    val overlayGranted = remember(tick) { OverlayService.canDraw(context) }
    val a11yReady = remember(tick) { PhoneUse.isReady() }
    val a11yEnabled = remember(tick) { PhoneUse.isEnabled(context) }

    val done = micGranted && notifGranted

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("先给YIIAI几个权限", color = palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "只申请确实会用到的，不开也能用，只是少了对应能力。随时可以在设置里改。",
            color = palette.textDim,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(24.dp))

        GrantRow(
            title = "麦克风",
            detail = "通话要用它听你说话。",
            granted = micGranted,
            required = true,
            action = "允许",
            onAction = onMicRequest,
        )
        GrantRow(
            title = "通知",
            detail = "通话时在状态栏显示超级岛和挂断入口。",
            granted = notifGranted,
            required = true,
            action = "允许",
            onAction = onNotificationRequest,
        )
        GrantRow(
            title = "悬浮窗",
            detail = "让你在别的 App 里也能看到小鹿，随时说话。",
            granted = overlayGranted,
            required = false,
            action = "去开启",
            onAction = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
        )
        GrantRow(
            title = "无障碍",
            detail = "让YIIAI能看到你的屏幕、帮你点一下。不开就用不了「看一眼」和帮你操作。",
            granted = a11yReady || a11yEnabled,
            required = false,
            action = if (a11yEnabled && !a11yReady) "等待系统连接" else "去开启",
            onAction = { PhoneUse.openSettings(context) },
        )

        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (done) palette.accent else palette.surfaceRaised)
                .clickable(onClick = onDone)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "开始使用" else "先这样，进去再说",
                color = if (done) {
                    if (palette.isDark) androidx.compose.ui.graphics.Color(0xFF10140E) else androidx.compose.ui.graphics.Color.White
                } else {
                    palette.text
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "无障碍是系统级权限，部分机型会提示风险；YIIAI只在你说话或要求时读取屏幕，截图不保存。",
            color = palette.textDim,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun GrantRow(
    title: String,
    detail: String,
    granted: Boolean,
    required: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    val palette = LocalYiiPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(if (granted) palette.positive else palette.textDim.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (!required) {
                    Spacer(Modifier.width(6.dp))
                    Text("可选", color = palette.textDim, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(detail, color = palette.textDim, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (granted) {
            Text("已开启", color = palette.positive, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        } else {
            Text(
                action,
                color = palette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
