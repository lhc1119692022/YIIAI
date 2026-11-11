package com.yiiai.app.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import com.yiiai.app.settings.CameraFacing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Opens a camera only while collecting a small burst of JPEG frames. */
class CameraFrameCapture(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val busy = AtomicBoolean(false)

    @Volatile
    private var active: CaptureHandle? = null

    suspend fun capture(
        facing: CameraFacing,
        maxFrames: Int = 3,
        onFrame: (ByteArray) -> Boolean,
    ): Int {
        if (!busy.compareAndSet(false, true)) throw IOException("摄像头正在采集，请稍后再试")
        val handle = CaptureHandle()
        active = handle
        try {
            return withContext(Dispatchers.IO) {
                captureInternal(handle, facing, maxFrames.coerceIn(1, MAX_FRAMES), onFrame)
            }
        } finally {
            handle.stop()
            if (active === handle) active = null
            busy.set(false)
        }
    }

    /** Stops an in-flight burst when the user disables visual access. */
    fun stopActiveCapture() {
        active?.stop()
    }

    private suspend fun captureInternal(
        handle: CaptureHandle,
        facing: CameraFacing,
        maxFrames: Int,
        onFrame: (ByteArray) -> Boolean,
    ): Int {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("没有相机权限，请先在设置中开启视觉并允许相机权限")
        }

        val desiredFacing = when (facing) {
            CameraFacing.Back -> CameraCharacteristics.LENS_FACING_BACK
            CameraFacing.Front -> CameraCharacteristics.LENS_FACING_FRONT
        }
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desiredFacing
        } ?: throw IOException("设备没有可用的${facing.label}")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("摄像头不支持图像采集")
        val size = streamMap.getOutputSizes(ImageFormat.JPEG)
            ?.chooseCaptureSize()
            ?: throw IOException("摄像头没有可用的 JPEG 输出格式")
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val thread = HandlerThread("YIIAI-CameraCapture").apply { start() }
        val handler = Handler(thread.looper)
        handle.thread = thread
        val frames = Channel<ByteArray>(Channel.CONFLATED)
        handle.frames = frames

        try {
            val opened = CompletableDeferred<CameraDevice>()
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (handle.stopped) {
                            camera.close()
                        } else {
                            handle.device = camera
                            opened.complete(camera)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        opened.completeExceptionally(IOException("摄像头连接中断"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        opened.completeExceptionally(IOException("摄像头打开失败（$error）"))
                    }
                },
                handler,
            )
            val camera = withTimeout(OPEN_TIMEOUT_MS) { opened.await() }
            if (handle.stopped) return 0

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
            handle.reader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes.firstOrNull()?.buffer ?: return@setOnImageAvailableListener
                    val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                    if (!handle.stopped) frames.trySend(jpeg)
                } finally {
                    image.close()
                }
            }, handler)

            val configured = CompletableDeferred<CameraCaptureSession>()
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (handle.stopped) {
                            session.close()
                        } else {
                            handle.session = session
                            configured.complete(session)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        configured.completeExceptionally(IOException("摄像头画面初始化失败"))
                    }
                },
                handler,
            )
            val session = withTimeout(OPEN_TIMEOUT_MS) { configured.await() }
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, 80.toByte())
            }.build()
            session.setRepeatingRequest(request, null, handler)

            var sent = 0
            while (sent < maxFrames && !handle.stopped) {
                val frame = withTimeoutOrNull(FRAME_TIMEOUT_MS) {
                    frames.receiveCatching().getOrNull()
                } ?: break
                if (!onFrame(frame)) throw IOException("摄像头画面没有发出，连接可能已断开")
                sent++
                if (sent < maxFrames) delay(FRAME_INTERVAL_MS)
            }
            if (sent == 0 && !handle.stopped) throw IOException("摄像头没有返回画面，请重试")
            return sent
        } finally {
            handle.stop()
        }
    }

    private fun Array<Size>.chooseCaptureSize(): Size? {
        val candidates = filter { maxOf(it.width, it.height) <= MAX_LONG_SIDE }
        return candidates.maxByOrNull { it.width.toLong() * it.height } ?: minByOrNull { it.width.toLong() * it.height }
    }

    private class CaptureHandle {
        @Volatile var stopped = false
            private set
        @Volatile var device: CameraDevice? = null
            set(value) {
                field = value
                if (stopped) value?.close()
            }
        @Volatile var session: CameraCaptureSession? = null
            set(value) {
                field = value
                if (stopped) value?.close()
            }
        @Volatile var reader: ImageReader? = null
            set(value) {
                field = value
                if (stopped) value?.close()
            }
        @Volatile var thread: HandlerThread? = null
            set(value) {
                field = value
                if (stopped) value?.quitSafely()
            }
        @Volatile var frames: Channel<ByteArray>? = null
            set(value) {
                field = value
                if (stopped) value?.close()
            }

        fun stop() {
            if (stopped) return
            stopped = true
            frames?.close()
            runCatching { session?.stopRepeating() }
            session?.close()
            reader?.close()
            device?.close()
            thread?.quitSafely()
        }
    }

    private companion object {
        const val MAX_FRAMES = 3
        const val MAX_LONG_SIDE = 1024
        const val OPEN_TIMEOUT_MS = 5_000L
        const val FRAME_TIMEOUT_MS = 2_500L
        const val FRAME_INTERVAL_MS = 1000L
    }
}
