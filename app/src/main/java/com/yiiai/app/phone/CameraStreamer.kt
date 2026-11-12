package com.yiiai.app.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.yiiai.app.settings.CameraFacing
import com.yiiai.app.settings.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 摄像头画面流：打开镜头后按画质档位的帧率连续发一段时间，到点自己关。
 *
 * 和旧版「拍 3 帧就关」的区别：
 * - 时长可控（默认 10 秒，模型可以指定或续期），帧数 = 时长 × 帧率；
 * - 同时产出一路低分辨率预览（[preview]），首页用它把小鹿换成镜头画面；
 * - 采集用 YUV，自己转 JPEG 并把像素转正，不依赖各家相机对 EXIF 方向的处理。
 *
 * 画面只在内存里编码和发送，不落盘。
 */
class CameraStreamer(
    context: Context,
    /** 发一帧已经转正的 JPEG（调用方负责加 0x01 前缀）。true 已发出；false 连接不在了；null 上行忙，跳过这一帧。 */
    private val sendFrame: (ByteArray) -> Boolean?,
    private val onLog: (String) -> Unit,
    /** 开始、续期、结束时回调，界面据此刷新。 */
    private val onChanged: () -> Unit,
    /** 镜头关闭：[expired] 表示是到时间自动关的，[problem] 非空表示异常中断。 */
    private val onEnded: (expired: Boolean, problem: String?, frames: Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val startLock = Mutex()

    @Volatile
    private var active: Run? = null

    private val _preview = MutableStateFlow<Bitmap?>(null)

    /** 最近一帧预览（已转正，前摄已镜像）。镜头没开时为 null。 */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    /** 有界面在显示预览时才解码预览帧，后台时省掉这部分开销。 */
    @Volatile
    var previewWanted: Boolean = false

    val running: Boolean get() = active != null
    val facing: CameraFacing? get() = active?.facing
    val endsAt: Long get() = active?.endsAt ?: 0L

    /** 这次调用的结果：是新开的镜头，还是给已经开着的续期。 */
    class Started(val extended: Boolean, val facing: CameraFacing, val seconds: Int, val quality: VideoQuality)

    /**
     * 打开镜头并等到第一帧发出去。同一颗镜头已经开着时只续期，不重新打开。
     * 失败时抛出带中文说明的 [IOException]。
     */
    suspend fun start(facing: CameraFacing, quality: VideoQuality, seconds: Int): Started = startLock.withLock {
        val until = System.currentTimeMillis() + seconds * 1000L
        active?.let { current ->
            if (current.facing == facing && !current.closed) {
                current.endsAt = maxOf(current.endsAt, until)
                onChanged()
                return@withLock Started(extended = true, facing = facing, seconds = seconds, quality = current.quality)
            }
            // 换镜头：先把旧的关掉，不当作一次「结束」上报。
            close(current)
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("没有相机权限，请先在设置中开启视觉并允许相机权限")
        }
        withContext(Dispatchers.IO) { open(facing, quality, until) }
        onChanged()
        Started(extended = false, facing = facing, seconds = seconds, quality = quality)
    }

    /** 主动关闭（用户或模型要求、关闭视觉、通话结束）。不触发 [onEnded]。 */
    fun stop(): Boolean {
        val run = active ?: return false
        close(run)
        onChanged()
        return true
    }

    // ---------------------------------------------------------------- 打开与采集

    private suspend fun open(facing: CameraFacing, quality: VideoQuality, until: Long): Run {
        val wantedLens = when (facing) {
            CameraFacing.Back -> CameraCharacteristics.LENS_FACING_BACK
            CameraFacing.Front -> CameraCharacteristics.LENS_FACING_FRONT
        }
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wantedLens
        } ?: throw IOException("设备没有可用的${facing.label}")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("摄像头不支持图像采集")
        val size = chooseSize(sizes, quality.maxLongSide)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val thread = HandlerThread("YIIAI-Camera").apply { start() }
        val handler = Handler(thread.looper)
        val run = Run(facing, quality, until, thread, handler, sensorOrientation)
        active = run
        // 先让界面切到镜头画面（显示「正在打开摄像头…」），不用等第一帧。
        onChanged()
        try {
            val opened = CompletableDeferred<CameraDevice>()
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (run.closed) camera.close() else opened.complete(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (!opened.isCompleted) {
                            opened.completeExceptionally(IOException("摄像头连接中断"))
                        } else {
                            finish(run, expired = false, problem = "摄像头被其他应用占用了")
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (!opened.isCompleted) {
                            opened.completeExceptionally(IOException(openError(error)))
                        } else {
                            finish(run, expired = false, problem = "摄像头出错（$error）")
                        }
                    }
                },
                handler,
            )
            val camera = withTimeout(OPEN_TIMEOUT_MS) { opened.await() }
            run.device = camera
            if (run.closed) throw IOException("摄像头已关闭")

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            run.reader = reader
            reader.setOnImageAvailableListener({ source -> onImage(run, source) }, handler)

            val configured = CompletableDeferred<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (run.closed) session.close() else configured.complete(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        configured.completeExceptionally(IOException("摄像头画面初始化失败"))
                    }
                },
                handler,
            )
            val session = withTimeout(OPEN_TIMEOUT_MS) { configured.await() }
            run.session = session
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }.build()
            run.openedAt = System.currentTimeMillis()
            session.setRepeatingRequest(request, null, handler)

            withTimeout(FIRST_FRAME_TIMEOUT_MS) { run.firstFrame.await() }
            // 到点检查放在相机线程上，画面停了也能按时关。
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (run.closed) return
                    if (System.currentTimeMillis() >= run.endsAt) {
                        finish(run, expired = true, problem = null)
                    } else {
                        handler.postDelayed(this, 500)
                    }
                }
            }, 500)
            onLog("摄像头已打开（${facing.label}，${size.width}×${size.height}，${quality.summary}）")
            return run
        } catch (t: Throwable) {
            close(run)
            onChanged()
            throw when (t) {
                is IOException -> t
                is kotlinx.coroutines.TimeoutCancellationException -> IOException("摄像头没有返回画面，请重试")
                is SecurityException -> IOException("没有相机权限")
                is kotlinx.coroutines.CancellationException -> t
                else -> IOException("摄像头打开失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** 相机线程：每来一帧，按需要转成发送帧和预览帧，其余直接丢掉。 */
    private fun onImage(run: Run, source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (run.closed) return
            val now = System.currentTimeMillis()
            // 刚打开的头几百毫秒曝光还没稳定，画面偏暗或偏色，不发。
            val settled = now - run.openedAt >= SETTLE_MS
            val dueSend = settled && now - run.lastSentAt >= run.quality.intervalMs
            val duePreview = previewWanted && now - run.lastPreviewAt >= PREVIEW_INTERVAL_MS
            if (!dueSend && !duePreview) return

            val jpeg = yuvToJpeg(image) ?: return
            val degrees = rotationDegrees(run)
            if (dueSend) {
                val frame = decodeUpright(jpeg, run.quality.maxLongSide, degrees, mirror = false)
                if (frame != null) {
                    val bytes = ByteArrayOutputStream().use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, run.quality.jpegQuality, out)
                        out.toByteArray()
                    }
                    frame.recycle()
                    // 仅 debug：把第一帧落到缓存目录，方便 adb 取出核对画面方向与色彩。
                    if (run.frames == 0 && com.yiiai.app.BuildConfig.DEBUG) {
                        runCatching {
                            java.io.File(appContext.cacheDir, "camera_frame.jpg").writeBytes(bytes)
                            java.io.File(appContext.cacheDir, "camera_raw.jpg").writeBytes(jpeg)
                        }
                    }
                    when (sendFrame(bytes)) {
                        true -> {
                            run.lastSentAt = now
                            run.frames++
                            run.bytes += bytes.size
                            run.sendFailures = 0
                            if (run.frames % 5 == 1) Log.d(TAG, "frame#${run.frames} ${bytes.size / 1024}KB")
                            if (!run.firstFrame.isCompleted) run.firstFrame.complete(Unit)
                        }
                        // 上行正忙：不算失败，稍后再试，免得每来一帧都白编码一次。
                        null -> run.lastSentAt = now - run.quality.intervalMs + BUSY_RETRY_MS
                        false -> {
                            run.lastSentAt = now
                            if (++run.sendFailures >= 3) {
                                finish(run, expired = false, problem = "画面发不出去，连接可能断了")
                                return
                            }
                        }
                    }
                }
            }
            if (duePreview) {
                run.lastPreviewAt = now
                // 前摄预览做镜像，和自拍时看到的一致；发给模型的那一帧不镜像，文字才是正的。
                decodeUpright(jpeg, PREVIEW_LONG_SIDE, degrees, mirror = run.facing == CameraFacing.Front)
                    ?.let { _preview.value = it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed", t)
        } finally {
            image.close()
        }
    }

    // ---------------------------------------------------------------- 结束

    /** 自己结束（到时间或出错）：关掉镜头并上报。 */
    private fun finish(run: Run, expired: Boolean, problem: String?) {
        if (run.closed) return
        // 第一帧都没发出去：start() 还在等，让它直接拿到失败原因，不再另外上报一次。
        val starting = !run.firstFrame.isCompleted
        if (starting) run.firstFrame.completeExceptionally(IOException(problem ?: "摄像头已关闭"))
        close(run)
        onLog(
            if (problem != null) {
                "摄像头中断：$problem"
            } else {
                "摄像头已关闭（发送 ${run.frames} 帧，共 ${run.bytes / 1024} KB，平均每帧 ${run.bytes / 1024 / maxOf(run.frames, 1)} KB）"
            },
        )
        onChanged()
        if (!starting) onEnded(expired, problem, run.frames)
    }

    private fun close(run: Run) {
        if (run.closed) return
        run.closed = true
        if (active === run) active = null
        _preview.value = null
        runCatching { run.session?.stopRepeating() }
        runCatching { run.session?.close() }
        runCatching { run.device?.close() }
        runCatching { run.reader?.close() }
        run.thread.quitSafely()
        if (!run.firstFrame.isCompleted) run.firstFrame.completeExceptionally(IOException("摄像头已关闭"))
    }

    // ---------------------------------------------------------------- 图像处理

    /** 画面要转多少度才是正的：传感器方向和手机当前朝向共同决定。 */
    private fun rotationDegrees(run: Run): Int {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val device = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (run.facing == CameraFacing.Front) {
            (run.sensorOrientation + device) % 360
        } else {
            (run.sensorOrientation - device + 360) % 360
        }
    }

    /** 把一帧 JPEG 解码成长边不超过 [maxLongSide]、已经转正的位图。 */
    private fun decodeUpright(jpeg: ByteArray, maxLongSide: Int, degrees: Int, mirror: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= maxLongSide) sample *= 2
        val raw = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scale = (maxLongSide.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        if (scale == 1f && degrees == 0 && !mirror) return raw
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (out !== raw) raw.recycle()
        return out
    }

    /** YUV_420_888 → NV21 → JPEG。各家相机的行跨度和像素跨度不一样，按平面逐行拷贝。 */
    private fun yuvToJpeg(image: Image): ByteArray? {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        var offset = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(nv21, offset, minOf(width, yBuffer.remaining()))
            offset += width
        }

        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        // 多数手机的色度平面是交错存放的（像素跨度 2、没有行填充），V 平面本身就是 NV21 要的 VUVU 顺序，整块拷贝。
        if (vPlane.pixelStride == 2 && vPlane.rowStride == width && uPlane.pixelStride == 2) {
            vBuffer.position(0)
            val n = minOf(vBuffer.remaining(), nv21.size - offset)
            vBuffer.get(nv21, offset, n)
            // 交错存放时 V 平面比 VU 序列少最后一个 U 字节，从 U 平面补上。
            if (offset + n < nv21.size && uBuffer.limit() > 0) nv21[nv21.size - 1] = uBuffer.get(uBuffer.limit() - 1)
            offset = nv21.size
        }
        if (offset < nv21.size) for (row in 0 until chromaHeight) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRow + col * uPlane.pixelStride
                val vIndex = vRow + col * vPlane.pixelStride
                if (vIndex >= vBuffer.limit() || uIndex >= uBuffer.limit()) break
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }
        return ByteArrayOutputStream(width * height / 4).use { out ->
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), INTERMEDIATE_JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    /** 选一个刚好够用的采集尺寸：长边不小于目标的最小尺寸，优先 4:3。 */
    private fun chooseSize(sizes: Array<Size>, targetLongSide: Int): Size {
        fun longSide(s: Size) = maxOf(s.width, s.height)
        fun is43(s: Size) = s.width * 3 == s.height * 4 || s.height * 3 == s.width * 4
        val usable = sizes.filter { longSide(it) <= MAX_CAPTURE_LONG_SIDE }.ifEmpty { sizes.toList() }
        val enough = usable.filter { longSide(it) >= targetLongSide }
        if (enough.isEmpty()) return usable.maxByOrNull { it.width.toLong() * it.height }!!
        val smallest = enough.minOf { longSide(it) }
        // 允许比最小够用的尺寸稍大一点，以换到 4:3（视野更完整）。
        val near = enough.filter { longSide(it) <= smallest * 5 / 4 }
        return near.filter(::is43).minByOrNull { it.width.toLong() * it.height }
            ?: near.minByOrNull { it.width.toLong() * it.height }!!
    }

    private fun openError(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "摄像头正被其他应用占用"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "已打开的摄像头太多"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "摄像头被系统禁用了（可能是隐私开关或管理策略）"
        else -> "摄像头打开失败（$code）"
    }

    private class Run(
        val facing: CameraFacing,
        val quality: VideoQuality,
        @Volatile var endsAt: Long,
        val thread: HandlerThread,
        val handler: Handler,
        val sensorOrientation: Int,
    ) {
        @Volatile var closed = false
        @Volatile var device: CameraDevice? = null
        @Volatile var session: CameraCaptureSession? = null
        @Volatile var reader: ImageReader? = null
        val firstFrame = CompletableDeferred<Unit>()
        @Volatile var openedAt = 0L
        var lastSentAt = 0L
        var lastPreviewAt = 0L
        var frames = 0
        var bytes = 0L
        var sendFailures = 0
    }

    private companion object {
        const val TAG = "YIIAI.Camera"
        const val OPEN_TIMEOUT_MS = 5_000L
        const val FIRST_FRAME_TIMEOUT_MS = 4_000L

        /** 打开后先等曝光稳定再发第一帧。 */
        const val SETTLE_MS = 350L

        /** 上行忙跳过一帧后，隔这么久再试。 */
        const val BUSY_RETRY_MS = 120L

        /** 预览约每秒 10 帧，够看清在拍什么，又不至于发热。 */
        const val PREVIEW_INTERVAL_MS = 100L
        const val PREVIEW_LONG_SIDE = 480
        const val MAX_CAPTURE_LONG_SIDE = 1920
        const val INTERMEDIATE_JPEG_QUALITY = 92
    }
}
