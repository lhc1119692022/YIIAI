package com.yiiai.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.yiiai.app.ui.theme.LocalYiiPalette
import com.yiiai.app.ui.theme.YiiPalette

/**
 * 小鹿头像（方案 A：3/4 侧脸）。
 *
 * 只有头：实心叶耳、两枚小鹿角芽、口鼻朝左下的头型、闭眼和一颗鼻头。
 * 全部是实心块面，不靠线条撑轮廓；放在图标、悬浮球、灵动岛里都自带深色底板。
 * 坐标系是 512 画布，内容实际占 (90..502, 70..447)，图标版取 40..510 的视窗。
 */

/** 形象固定配色：和主题无关，保证每个入口看到的都是同一只鹿。 */
object DeerColors {
    val tile = Color(0xFF17120F)
    val body = Color(0xFFF3E4D2)
    val ear = Color(0xFFE9D3BE)
    val antler = Color(0xFFC9866F)
    val ink = Color(0xFF2A1F1B)

    // 浅色主题下大图没有底板，身体加深一点，避免和米白背景糊在一起。
    val bodyOnLight = Color(0xFFE9CDB4)
    val earOnLight = Color(0xFFDDBB9E)
}

private fun svg(d: String): Path = PathParser().parsePathString(d).toPath()

private val AntlerLeft = svg(
    "M262,176 C252,140 246,112 252,86 C256,70 276,70 278,88 C280,104 276,122 282,140 " +
        "C290,124 300,116 312,118 C326,122 322,140 310,150 C300,160 294,170 292,182 Z",
)
private val AntlerRight = svg(
    "M346,184 C350,150 360,124 376,104 C386,92 404,100 398,116 C392,130 382,142 380,156 " +
        "C392,148 406,148 414,156 C422,168 410,180 396,184 C384,188 374,192 366,198 Z",
)
private val EarLeft = svg(
    "M232,214 C196,180 150,168 118,180 C104,186 106,204 124,214 C152,230 194,236 228,236 Z",
)
private val EarRight = svg(
    "M386,214 C420,188 460,180 488,188 C502,194 500,212 484,220 C458,234 420,240 388,238 Z",
)
private val Head = svg(
    "M300,168 C384,168 436,226 436,300 C436,372 390,428 322,440 C282,447 252,436 222,424 " +
        "C186,410 138,404 112,380 C90,360 96,326 126,312 C158,298 186,282 206,250 " +
        "C228,206 252,168 300,168 Z",
)

/** 头像视觉重心 (290, 300) 相对 512 画布中心的偏移。 */
private const val VISUAL_SHIFT_X = 34f
private const val VISUAL_SHIFT_Y = 30f

private val EarLeftRoot = Offset(230f, 225f)
private val EarRightRoot = Offset(388f, 226f)
private val AntlerRoot = Offset(320f, 185f)

/** 眼睛：状态只改这里。 */
enum class DeerEyes { Closed, Open, Happy, Muted, Error }

private val EyeClosed = listOf(
    svg("M236,286 C246,306 270,306 280,288"),
    svg("M318,280 C326,300 350,300 358,282"),
)
private val EyeHappy = listOf(
    svg("M236,300 C246,278 270,278 280,298"),
    svg("M318,294 C326,272 350,272 358,292"),
)
private val EyeMuted = listOf(
    svg("M238,294 L278,294"),
    svg("M320,288 L356,288"),
)
private val EyeError = listOf(
    svg("M244,276 L272,304"), svg("M272,276 L244,304"),
    svg("M324,270 L352,298"), svg("M352,270 L324,298"),
)

/** 悬浮球、灵动岛用：直接从通话状态推出眼睛。 */
fun deerEyesFor(state: com.yiiai.app.live.CallState): DeerEyes = when {
    state.phase == com.yiiai.app.live.CallPhase.Failed -> DeerEyes.Error
    state.muted -> DeerEyes.Muted
    state.assistantSpeaking -> DeerEyes.Happy
    state.userSpeaking -> DeerEyes.Open
    else -> DeerEyes.Closed
}

fun OrbMode.toDeerEyes(): DeerEyes = when (this) {
    OrbMode.Idle, OrbMode.Connecting, OrbMode.Listening -> DeerEyes.Closed
    OrbMode.UserSpeaking -> DeerEyes.Open
    OrbMode.AssistantSpeaking -> DeerEyes.Happy
    OrbMode.Muted -> DeerEyes.Muted
    OrbMode.Error -> DeerEyes.Error
}

private fun DrawScope.drawDeerHead(
    body: Color,
    ear: Color,
    antler: Color,
    ink: Color,
    eyes: DeerEyes,
    lineScale: Float = 1f,
    earAngle: Float = 0f,
    antlerAngle: Float = 0f,
    blink: Float = 1f,
) {
    rotate(antlerAngle, pivot = AntlerRoot) {
        drawPath(AntlerLeft, antler)
        drawPath(AntlerRight, antler)
    }
    rotate(-earAngle, pivot = EarLeftRoot) { drawPath(EarLeft, ear) }
    rotate(earAngle, pivot = EarRightRoot) { drawPath(EarRight, ear) }
    drawPath(Head, body)

    // 鼻头
    drawOval(
        color = ink,
        topLeft = Offset(116f - 17f * lineScale, 352f - 15f * lineScale),
        size = Size(34f * lineScale, 30f * lineScale),
    )

    val stroke = Stroke(width = 13f * lineScale, cap = StrokeCap.Round)
    when (eyes) {
        DeerEyes.Closed -> EyeClosed.forEach { drawPath(it, ink, style = stroke) }
        DeerEyes.Happy -> EyeHappy.forEach { drawPath(it, ink, style = stroke) }
        DeerEyes.Muted -> EyeMuted.forEach { drawPath(it, ink, style = stroke) }
        DeerEyes.Error -> EyeError.forEach { drawPath(it, ink, style = stroke) }
        DeerEyes.Open -> {
            val rx = 10f * lineScale
            val ry = 16f * lineScale * blink
            drawOval(ink, Offset(258f - rx, 290f - ry), Size(rx * 2, ry * 2))
            drawOval(ink, Offset(338f - rx, 284f - ry), Size(rx * 2, ry * 2))
        }
    }
}

/**
 * 通话页上的大头像。轻点、长按由外层 [VoiceOrb] 处理。
 * 动效只动三样：耳朵（聆听时竖起、静音时垂下）、鹿角轻晃、头后的光晕随音量呼吸。
 */
@Composable
fun DeerMascot(
    mode: OrbMode,
    level: Float,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") compact: Boolean = false,
) {
    val palette = LocalYiiPalette.current
    val target = when (mode) {
        OrbMode.UserSpeaking, OrbMode.AssistantSpeaking -> level
        else -> 0f
    }
    val smooth by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
        label = "deer-level",
    )
    val transition = rememberInfiniteTransition(label = "deer")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (mode == OrbMode.Connecting) 720 else 2600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "deer-breath",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                when (mode) {
                    OrbMode.Connecting -> 9000
                    OrbMode.Muted -> 90000
                    else -> 36000
                },
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            RepeatMode.Restart,
        ),
        label = "deer-orbit",
    )
    val sway by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                when (mode) {
                    OrbMode.Connecting -> 420
                    OrbMode.AssistantSpeaking -> 900
                    else -> 2400
                },
                easing = FastOutSlowInEasing,
            ),
            RepeatMode.Reverse,
        ),
        label = "deer-sway",
    )
    val earMood by animateFloatAsState(
        targetValue = when (mode) {
            OrbMode.Listening, OrbMode.UserSpeaking -> 9f
            OrbMode.Muted -> -12f
            OrbMode.Error -> -16f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "deer-ear",
    )
    val tilt by animateFloatAsState(
        targetValue = when (mode) {
            OrbMode.AssistantSpeaking -> 3f
            OrbMode.Muted -> -4f
            OrbMode.Listening, OrbMode.UserSpeaking -> -1.5f
            OrbMode.Error -> -3f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "deer-tilt",
    )

    val glow = deerGlow(palette, mode)
    val active = mode != OrbMode.Idle && mode != OrbMode.Muted
    val body = if (palette.isDark) DeerColors.body else DeerColors.bodyOnLight
    val ear = if (palette.isDark) DeerColors.ear else DeerColors.earOnLight
    val swayAmount = when (mode) {
        OrbMode.Connecting -> 4f
        OrbMode.AssistantSpeaking -> 2.5f
        else -> 1.2f
    }

    // 不裁剪：光晕是渐变，超出画布自然消失，避免左右被方形边界切一刀。
    Canvas(modifier.fillMaxSize()) {
        val scale = minOf(size.width, size.height) / 512f
        val dx = (size.width - 512f * scale) / 2f
        val dy = (size.height - 512f * scale) / 2f
        withTransform({
            translate(dx, dy)
            scale(scale, scale, Offset.Zero)
            // 头像内容的视觉重心在 (290, 300)，往左上挪回画布中心。
            translate(-VISUAL_SHIFT_X, -VISUAL_SHIFT_Y)
        }) {
            val haloCenter = Offset(290f, 300f)
            val haloRadius = 250f + smooth * 70f + breath * 14f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glow.copy(alpha = if (active) 0.30f else 0.08f),
                        glow.copy(alpha = if (active) 0.10f else 0.025f),
                        Color.Transparent,
                    ),
                    center = haloCenter,
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = haloCenter,
            )
            // 头后一圈树叶粒子：慢慢绕着转、整体呼吸；说话时随音量向外散开、变亮。
            drawLeaves(
                center = haloCenter,
                color = glow,
                orbit = orbit,
                breath = breath,
                level = smooth,
                energy = when (mode) {
                    OrbMode.Muted -> 0.15f
                    OrbMode.Idle -> 0.45f
                    else -> 1f
                },
            )
            rotate(tilt, pivot = Offset(290f, 330f)) {
                drawDeerHead(
                    body = body,
                    ear = ear,
                    antler = DeerColors.antler,
                    ink = DeerColors.ink,
                    eyes = mode.toDeerEyes(),
                    earAngle = earMood + sway * swayAmount,
                    antlerAngle = sway * swayAmount * 0.5f,
                )
            }
        }
    }
}

/**
 * 同一只鹿，用于 App 内标题、悬浮球、灵动岛头像。
 * 自带深色底板，不依赖下面是什么背景；小于 48dp 时自动加粗眼睛和鼻头。
 */
@Composable
fun DeerMark(
    modifier: Modifier = Modifier,
    glow: Color = LocalYiiPalette.current.orbHalo,
    withTile: Boolean = false,
    tileColor: Color = DeerColors.tile,
    eyes: DeerEyes = DeerEyes.Closed,
) {
    Canvas(modifier.clipToBounds()) {
        val s = size.minDimension
        val thick = s <= 48.dp2px(this)
        val scale = s / 470f
        withTransform({
            scale(scale, scale, Offset.Zero)
            translate(-40f, -40f)
        }) {
            if (withTile) {
                drawRoundRect(
                    color = tileColor,
                    topLeft = Offset(40f, 40f),
                    size = Size(470f, 470f),
                    cornerRadius = CornerRadius(112f, 112f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(290f, 300f),
                        radius = 260f,
                    ),
                    radius = 260f,
                    center = Offset(290f, 300f),
                )
            }
            drawDeerHead(
                body = DeerColors.body,
                ear = DeerColors.ear,
                antler = DeerColors.antler,
                ink = DeerColors.ink,
                eyes = eyes,
                lineScale = if (thick) 1.8f else 1f,
            )
        }
    }
}

private fun Float.dp2px(scope: DrawScope): Float = this * scope.density
private fun Int.dp2px(scope: DrawScope): Float = this * scope.density

/** 叶片：两头收、中间鼓的杏仁形，带一条淡淡的叶脉。长轴沿 x。 */
private val LeafShape: Path = svg("M-1,0 C-0.55,-0.62 0.45,-0.58 1,0 C0.45,0.58 -0.55,0.62 -1,0 Z")

private class LeafSeed(val angle: Float, val radius: Float, val size: Float, val phase: Float, val drift: Float)

private val LeafSeeds: List<LeafSeed> = List(22) { i ->
    fun h(k: Int): Float {
        val x = kotlin.math.sin((i + 1) * 12.9898f + k * 78.233f) * 43758.547f
        return x - kotlin.math.floor(x)
    }
    LeafSeed(
        angle = (i / 22f + h(1) * 0.03f) * 6.2832f,
        radius = 196f + h(2) * 58f,
        size = 9f + h(3) * 9f,
        phase = h(4) * 6.2832f,
        drift = if (i % 2 == 0) 1f else -0.6f,
    )
}

private fun DrawScope.drawLeaves(
    center: Offset,
    color: Color,
    orbit: Float,
    breath: Float,
    level: Float,
    energy: Float,
) {
    val tau = 6.2832f
    LeafSeeds.forEach { s ->
        val t = orbit * tau
        val angle = s.angle + t * s.drift * 0.35f
        // 呼吸：整圈一起涨落；说话时按音量往外散。
        val r = s.radius * (1f + 0.05f * (breath - 0.5f) * 2f) + level * (40f + 30f * kotlin.math.sin(s.phase))
        val twinkle = 0.5f + 0.5f * kotlin.math.sin(t * 3f + s.phase)
        val alpha = (0.18f + 0.55f * twinkle) * energy * (0.75f + 0.25f * breath) + level * 0.2f * energy
        val pos = Offset(center.x + r * kotlin.math.cos(angle), center.y + r * kotlin.math.sin(angle) * 0.92f)
        // 叶尖大致顺着环绕方向，再随风轻摆。
        val heading = Math.toDegrees(angle.toDouble()).toFloat() + 90f * s.drift + 25f * kotlin.math.sin(t * 2f + s.phase)
        val len = s.size * (1f + 0.25f * level) * (0.9f + 0.2f * breath)
        withTransform({
            translate(pos.x, pos.y)
            rotate(heading, Offset.Zero)
            scale(len, len * 0.9f, Offset.Zero)
        }) {
            drawPath(LeafShape, color.copy(alpha = alpha.coerceIn(0f, 0.95f)))
            drawLine(
                color = Color.White.copy(alpha = (alpha * 0.35f).coerceIn(0f, 0.4f)),
                start = Offset(-0.75f, 0f),
                end = Offset(0.75f, 0f),
                strokeWidth = 0.09f,
            )
        }
    }
}

private fun deerGlow(palette: YiiPalette, mode: OrbMode): Color = when (mode) {
    OrbMode.Idle, OrbMode.Connecting -> palette.orbHalo
    OrbMode.Listening, OrbMode.UserSpeaking -> palette.orbUser
    OrbMode.AssistantSpeaking -> palette.orbAssistant
    OrbMode.Muted -> palette.orbIdle
    OrbMode.Error -> palette.danger
}
