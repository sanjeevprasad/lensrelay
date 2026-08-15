package com.atanx.lensrelay

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.Size
import androidx.camera.core.Camera
import androidx.lifecycle.LifecycleOwner
import com.swmansion.moqkit.publish.Publisher
import com.swmansion.moqkit.publish.encoder.VideoEncoderConfig
import com.swmansion.moqkit.publish.encoder.VideoCodec
import com.swmansion.moqkit.publish.source.CameraPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URLEncoder

class MoqStreamSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val preview: SurfaceView,
    private val desktop: PairedDesktop,
    private val lens: CameraLens,
    private val targetRotation: Int,
    private val settings: StreamSettings = StreamSettings(),
    private val onVideoSize: (Int, Int) -> Unit,
    private val onCameraReady: (Camera, Size) -> Unit,
    private val onState: (State, String?) -> Unit,
) : SurfaceHolder.Callback {
    enum class State { Starting, Connected, Failed, Stopped }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: SecureMoqSession? = null
    private var camera: NegotiatedCameraCapture? = null
    private var publisher: Publisher? = null
    private var starting = false
    private var stopped = false

    fun start() {
        check(!stopped) { "This media session has already stopped." }
        onState(State.Starting, null)
        preview.holder.addCallback(this)
        preview.holder.surface.takeIf(Surface::isValid)?.let(::startWithSurface)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startWithSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!stopped) stop()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        preview.holder.removeCallback(this)
        runCatching { publisher?.stop() }
            .onFailure { Log.w(TAG, "Could not stop MoQ publisher cleanly", it) }
        runCatching { camera?.stop() }
            .onFailure { Log.w(TAG, "Could not stop MoQ camera cleanly", it) }
        runCatching { session?.close() }
            .onFailure { Log.w(TAG, "Could not close MoQ session cleanly", it) }
        publisher = null
        camera = null
        session = null
        scope.cancel()
        onState(State.Stopped, null)
    }

    private fun startWithSurface(surface: Surface) {
        if (starting || stopped || !surface.isValid) return
        starting = true
        scope.launch {
            try {
                val activeSession = SecureMoqSession(
                    mediaUrl(),
                    desktop.mediaCertificateFingerprint,
                )
                session = activeSession
                activeSession.connect()

                val capture = NegotiatedCameraCapture(
                    position = if (lens == CameraLens.Front) CameraPosition.Front else CameraPosition.Back,
                    targetRotation = targetRotation,
                    requestedSize = settings.resolution,
                    frameRate = settings.frameRate,
                    stabilization = settings.stabilization,
                    whiteBalance = settings.whiteBalance,
                )
                capture.setPreviewSurface(surface)
                camera = capture
                val cameraInfo = capture.start(context, lifecycleOwner)
                val videoSize = cameraInfo.size
                onVideoSize(videoSize.width, videoSize.height)
                onCameraReady(cameraInfo.camera, videoSize)

                val activePublisher = Publisher()
                publisher = activePublisher
                activePublisher.addVideoTrack(
                    name = "camera",
                    source = capture,
                    config = VideoEncoderConfig(
                        codec = settings.codec,
                        width = videoSize.width,
                        height = videoSize.height,
                        bitrate = settings.bitrate,
                        frameRate = settings.frameRate,
                    ),
                )
                activeSession.publish(path = "camera", publisher = activePublisher)
                activePublisher.start()
                onState(State.Connected, null)
            } catch (error: Exception) {
                Log.e(TAG, "Could not start MoQ camera stream", error)
                if (!stopped) onState(State.Failed, error.message)
            }
        }
    }

    private fun mediaUrl(): String {
        val host = if (desktop.host.contains(':')) "[${desktop.host}]" else desktop.host
        val token = URLEncoder.encode(desktop.mediaToken, Charsets.UTF_8.name())
        return "https://$host:$MEDIA_PORT/lensrelay/${desktop.receiverId}?jwt=$token"
    }

    companion object {
        private const val TAG = "LensRelayMoq"
        private const val MEDIA_PORT = 53_418
    }
}

data class StreamSettings(
    val resolution: Size? = null,
    val frameRate: Int = 30,
    val bitrate: Int = 4_000_000,
    val codec: VideoCodec = VideoCodec.H264,
    val stabilization: Boolean = false,
    val whiteBalance: String = "auto",
)
