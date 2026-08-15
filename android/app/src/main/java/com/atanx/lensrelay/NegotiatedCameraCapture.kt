package com.atanx.lensrelay

import android.content.Context
import android.annotation.SuppressLint
import android.util.Log
import android.util.Size
import android.util.Range
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2Interop
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.swmansion.moqkit.publish.source.CameraPosition
import com.swmansion.moqkit.publish.source.VideoFrameSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Camera source whose dimensions and transform come from CameraX negotiation. */
@SuppressLint("UnsafeOptInUsageError")
internal class NegotiatedCameraCapture(
    private val position: CameraPosition,
    private val targetRotation: Int,
    private val requestedSize: Size? = null,
    private val frameRate: Int = 30,
    private val stabilization: Boolean = false,
    private val whiteBalance: String = "auto",
) : VideoFrameSource {
    private val processor = CameraFrameProcessor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewSurface: Surface? = null
    private var started = false

    suspend fun start(context: Context, lifecycleOwner: LifecycleOwner): CameraSessionInfo {
        check(!started) { "Camera capture is already running" }
        checkNotNull(previewSurface) { "Preview surface must be set before starting capture" }
        started = true
        processor.initialize()
        cameraProvider = ProcessCameraProvider.getInstance(context).awaitResult()

        val previewBuilder = Preview.Builder()
            .setTargetRotation(targetRotation)
            .setTargetFrameRate(Range(frameRate, frameRate))
            .setPreviewStabilizationEnabled(stabilization)
        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            whiteBalance.toAwbMode(),
        )
        requestedSize?.let(previewBuilder::setTargetResolution)
        val preview = previewBuilder.build()
            .also { useCase ->
                useCase.setSurfaceProvider { request ->
                    val surface = previewSurface
                    if (surface == null || !surface.isValid) {
                        request.willNotProvideSurface()
                    } else {
                        request.provideSurface(surface, processor.executor) { result ->
                            Log.d(TAG, "Preview surface released: ${result.resultCode}")
                        }
                    }
                }
            }

        val effect = object : CameraEffect(
            PREVIEW,
            processor.executor,
            processor,
            Consumer { error -> Log.e(TAG, "CameraX processing failed", error) },
        ) {}
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addEffect(effect)
            .build()
        val selector = when (position) {
            CameraPosition.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraPosition.Back -> CameraSelector.DEFAULT_BACK_CAMERA
        }

        cameraProvider?.unbindAll()
        val camera = checkNotNull(cameraProvider?.bindToLifecycle(lifecycleOwner, selector, group))
        return CameraSessionInfo(processor.awaitOutputSize(), camera)
    }

    fun stop() {
        if (!started) return
        started = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        processor.release()
    }

    override fun attachEncoderSurface(surface: Surface) = processor.attachEncoderSurface(surface)

    override fun detachEncoderSurface() = processor.detachEncoderSurface()

    override fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
    }

    companion object {
        private const val TAG = "LensRelayCamera"
    }
}

private fun String.toAwbMode(): Int = when (lowercase()) {
    "incandescent" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
    "fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
    "warm-fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
    "daylight" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
    "cloudy-daylight" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    "twilight" -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
    "shade" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
}

internal data class CameraSessionInfo(val size: Size, val camera: Camera)

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: ExecutionException) {
                    continuation.resumeWithException(error.cause ?: error)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            { command -> command.run() },
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
