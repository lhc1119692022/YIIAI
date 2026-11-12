package com.yiiai.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * 悬浮小鹿在「没人说话」时自己做的小动作。
 * 只保留轻微张望和抖耳朵，动作之间留出较长的安静时间。
 */
enum class IdleAct(val durationMs: Int, val label: String) {
    None(0, "安静"),
    Glance(2400, "张望"),
    EarFlick(900, "抖耳朵"),
    Hum(3000, "哼歌"),
    Yawn(2800, "打哈欠"),
    Ponder(3200, "发呆"),
    Stretch(2200, "伸懒腰"),
    Doze(3600, "打盹"),
    Peek(2400, "醒来看看"),
}

/** 某个小动作进行到 [p]（0–1）时，在基础表情上叠加的变化。 */
private class IdlePose(
    val eyes: DeerEyes? = null,
    val blink: Float = 1f,
    val look: Float = 0f,
    val mouth: Float = 0f,
    val ear: Float = 0f,
    val tilt: Float = 0f,
    val stretch: Float = 1f,
    val bubble: String? = null,
    val bubbleAlpha: Float = 0f,
)

private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 先升到 1、保持、再落回 0 的包络。 */
private fun bell(p: Float, rise: Float = 0.25f, fall: Float = 0.75f): Float = when {
    p <= 0f || p >= 1f -> 0f
    p < rise -> smooth(p / rise)
    p > fall -> smooth((1f - p) / (1f - fall))
    else -> 1f
}

private const val TAU = (2 * PI).toFloat()

private fun idlePose(act: IdleAct, p: Float): IdlePose = when (act) {
    IdleAct.None -> IdlePose()
    IdleAct.Glance -> IdlePose(
        eyes = DeerEyes.Open,
        look = sin(p * TAU),
        blink = if (p in 0.46f..0.54f) 0.15f else 1f,
        ear = 5f * bell(p),
    )
    IdleAct.EarFlick -> IdlePose(ear = 16f * sin(p * TAU * 2f) * (1f - p))
    IdleAct.Hum -> IdlePose(
        eyes = DeerEyes.Happy,
        tilt = 5f * sin(p * TAU * 2f) * bell(p),
        bubble = "♪",
        bubbleAlpha = bell(p, 0.15f, 0.8f),
    )
    IdleAct.Yawn -> {
        val k = bell(p, 0.4f, 0.72f)
        IdlePose(
            eyes = if (k > 0.25f) DeerEyes.Squeeze else DeerEyes.Closed,
            mouth = k,
            tilt = -8f * k,
            stretch = 1f + 0.05f * k,
            ear = -10f * k,
            bubble = "哈～",
            bubbleAlpha = bell(p, 0.42f, 0.8f),
        )
    }
    IdleAct.Ponder -> IdlePose(
        eyes = DeerEyes.Open,
        look = -0.8f * bell(p),
        tilt = 6f * bell(p),
        bubble = when {
            p < 0.33f -> "·"
            p < 0.6f -> "··"
            else -> "···"
        },
        bubbleAlpha = bell(p, 0.1f, 0.85f),
    )
    IdleAct.Stretch -> {
        val k = bell(p, 0.35f, 0.7f)
        IdlePose(
            eyes = if (k > 0.3f) DeerEyes.Squeeze else DeerEyes.Closed,
            stretch = 1f + 0.12f * k,
            ear = 12f * k,
        )
    }
    IdleAct.Doze -> IdlePose(
        eyes = DeerEyes.Closed,
        tilt = 9f + 2f * sin(p * TAU),
        ear = -8f,
        bubble = when {
            p < 0.33f -> "z"
            p < 0.66f -> "zZ"
            else -> "zZz"
        },
        bubbleAlpha = if (p > 0.9f) (1f - p) / 0.1f else smooth(p / 0.1f),
    )
    IdleAct.Peek -> IdlePose(
        eyes = DeerEyes.Open,
        blink = when {
            p < 0.12f -> p / 0.12f
            p in 0.5f..0.56f -> 0.15f
            else -> 1f
        },
        look = 0.8f * sin(p * TAU),
        ear = 10f * bell(p),
        bubble = "?",
        bubbleAlpha = bell(p, 0.2f, 0.7f),
    )
}

/** 安静多久之后会做哪些动作：越久越困。 */
private fun pickIdleAct(last: IdleAct): IdleAct =
    listOf(IdleAct.Glance, IdleAct.Glance, IdleAct.Glance, IdleAct.EarFlick)
        .filter { it != last }
        .random()

/**
 * 悬浮球用的异形小鹿：和桌面图标是同一只鹿（同一套头、耳、角、脸），没有底板、描边和投影。
 *
 * - [idle] 为 true（通话中、没人说话）时，隔几秒自己做一个小动作，见 [IdleAct]；
 * - 有人说话时随音量起伏，身后有声波；
 * - [scale]、[lean]、[faceRight] 由外层按手势驱动：按住缩一下、拖动时歪头、贴哪边就朝屏幕里看；
 * - [stateBubble] 是状态气泡（连接中、静音、出错），优先于小动作的气泡。
 * - [preview] 只给设计预览页用：固定在某个动作的某个进度。
 */
@Composable
fun DeerFloat(
    eyes: DeerEyes,
    glow: Color,
    level: Float,
    /** 0 = 静音/出错（不出声波），1 = 正常。 */
    energy: Float,
    /** 耳朵的情绪角度：竖起为正，垂下为负。 */
    earMood: Float,
    faceRight: Boolean,
    lean: Float,
    scale: Float,
    idle: Boolean,
    modifier: Modifier = Modifier,
    stateBubble: String? = null,
    preview: Pair<IdleAct, Float>? = null,
) {
    val transition = rememberInfiniteTransition(label = "deer-float")
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "deer-float-ripple",
    )
    val ear by animateFloatAsState(
        targetValue = earMood,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "deer-float-ear",
    )
    val mirror by animateFloatAsState(
        targetValue = if (faceRight) -1f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "deer-float-mirror",
    )

    // ---- 小动作调度：只在安静时运行，有人说话立刻收起。
    var act by remember { mutableStateOf(IdleAct.None) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(idle, preview != null) {
        act = IdleAct.None
        progress.snapTo(0f)
        if (!idle || preview != null) return@LaunchedEffect
        var last = IdleAct.None
        while (true) {
            act = IdleAct.None
            delay((8000L..16000L).random())
            val next = pickIdleAct(last)
            last = next
            act = next
            progress.snapTo(0f)
            progress.animateTo(1f, tween(next.durationMs, easing = LinearEasing))
        }
    }
    val pose = if (preview != null) idlePose(preview.first, preview.second) else idlePose(act, progress.value)

    val bubbleText = stateBubble ?: pose.bubble
    val bubbleAlpha = if (stateBubble != null) 1f else pose.bubbleAlpha

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            // 鹿放在偏下的位置，上面留给气泡。
            val c = Offset(size.width / 2f, size.height * 0.60f)
            // 声波：两圈错开半个周期，音量越大越明显。
            if (energy > 0f && level > 0.02f) {
                for (i in 0..1) {
                    val p = (ripple + i * 0.5f) % 1f
                    val alpha = (1f - p) * (0.10f + level * 0.55f) * energy
                    drawCircle(
                        color = glow.copy(alpha = alpha.coerceIn(0f, 0.7f)),
                        radius = s * (0.24f + 0.16f * p),
                        center = c,
                        style = Stroke(width = s * 0.02f),
                    )
                }
            }
            val k = s * 0.66f / 412f
            val earAngle = ear + pose.ear
            withTransform({
                translate(c.x, c.y)
                scale(scale * mirror, scale * pose.stretch, Offset.Zero)
                rotate(lean + pose.tilt, Offset.Zero)
                scale(k, k, Offset.Zero)
                // 鹿头内容的外接框中心。
                translate(-296f, -258f)
            }) {
                drawDeerHead(
                    body = DeerColors.body,
                    ear = DeerColors.ear,
                    antler = DeerColors.antler,
                    ink = DeerColors.ink,
                    eyes = pose.eyes ?: eyes,
                    lineScale = 1.7f,
                    earAngle = earAngle,
                    antlerAngle = 0f,
                    blink = pose.blink,
                    look = pose.look,
                    mouth = pose.mouth,
                )
            }
        }
        if (bubbleText != null && bubbleAlpha > 0.02f) {
            // 气泡在朝屏幕里面的那一侧，也就是小鹿口鼻所在的一侧。
            DeerBubble(
                text = bubbleText,
                alpha = bubbleAlpha,
                tailOnRight = !faceRight,
                modifier = Modifier.align(if (faceRight) Alignment.TopEnd else Alignment.TopStart),
            )
        }
    }
}

/** 小鹿头顶的小气泡：一两个字或符号。 */
@Composable
private fun DeerBubble(text: String, alpha: Float, tailOnRight: Boolean, modifier: Modifier = Modifier) {
    val a = alpha.coerceIn(0f, 1f)
    Box(
        modifier
            .graphicsLayer {
                this.alpha = a
                scaleX = 0.7f + 0.3f * a
                scaleY = 0.7f + 0.3f * a
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (tailOnRight) 0.8f else 0.2f, 1f)
            }
            .drawBehind {
                val tail = 5.dp.toPx()
                val body = Size(size.width, size.height - tail)
                val r = CornerRadius(body.height / 2f, body.height / 2f)
                drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(0f, 1.5.dp.toPx()), body, r)
                drawRoundRect(Color.White, Offset.Zero, body, r)
                val x = if (tailOnRight) size.width * 0.70f else size.width * 0.30f
                val path = Path().apply {
                    moveTo(x - tail * 0.8f, body.height - 1f)
                    lineTo(x + tail * 0.8f, body.height - 1f)
                    lineTo(x + (if (tailOnRight) tail * 0.5f else -tail * 0.5f), size.height)
                    close()
                }
                drawPath(path, Color.White)

            }
            .padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = DeerColors.ink,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}