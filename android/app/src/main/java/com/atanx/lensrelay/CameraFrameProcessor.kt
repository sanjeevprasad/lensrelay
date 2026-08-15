package com.atanx.lensrelay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * CameraX GL processor that applies CameraX's negotiated transform to both preview and encoder.
 */
internal class CameraFrameProcessor : SurfaceProcessor {
    private val thread = HandlerThread("LensRelayCameraGL")
    private lateinit var handler: Handler
    private val outputSize = CompletableDeferred<Size>()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var inputTextureId = 0
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var previewOutput: SurfaceOutput? = null
    private var previewSurface: Surface? = null
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var shaderProgram = 0
    private var positionHandle = 0
    private var textureCoordinateHandle = 0
    private var textureMatrixHandle = 0
    private val cameraTransform = FloatArray(16)
    private val outputTransform = FloatArray(16)
    private var released = false

    private val vertices = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            position(0)
        }

    val executor: Executor = Executor { command ->
        if (::handler.isInitialized) handler.post(command)
    }

    fun initialize() {
        thread.start()
        handler = Handler(thread.looper)
        val ready = CountDownLatch(1)
        handler.post {
            runCatching {
                setupEgl()
                setupShaders()
            }.onFailure { error ->
                Log.e(TAG, "Unable to initialize camera GL processor", error)
            }
            ready.countDown()
        }
        ready.await()
        check(eglDisplay != EGL14.EGL_NO_DISPLAY && shaderProgram != 0) {
            "Unable to initialize camera GL processor"
        }
    }

    suspend fun awaitOutputSize(): Size = outputSize.await()

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        releaseInput()

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        inputTextureId = textureIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        val texture = SurfaceTexture(inputTextureId).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
            setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        val surface = Surface(texture)
        inputTexture = texture
        inputSurface = surface
        request.provideSurface(surface, executor) { result ->
            Log.d(TAG, "Camera input surface released: ${result.resultCode}")
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released) {
            output.close()
            return
        }
        releasePreviewOutput()
        previewOutput = output
        previewSurface = output.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                releasePreviewOutput()
            }
        }
        previewEglSurface = createWindowSurface(previewSurface)
        outputSize.complete(output.size)
        Log.i(TAG, "CameraX negotiated ${output.size.width}x${output.size.height}")
    }

    fun attachEncoderSurface(surface: Surface) {
        handler.post {
            destroySurface(encoderEglSurface)
            encoderEglSurface = createWindowSurface(surface)
        }
    }

    fun detachEncoderSurface() {
        if (!::handler.isInitialized) return
        handler.post {
            destroySurface(encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    fun release() {
        if (released) return
        released = true
        if (!::handler.isInitialized) return
        val done = CountDownLatch(1)
        handler.post {
            releaseInput()
            releasePreviewOutput()
            destroySurface(encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
            if (shaderProgram != 0) GLES20.glDeleteProgram(shaderProgram)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            done.countDown()
        }
        done.await()
        thread.quitSafely()
    }

    private fun renderFrame() {
        val texture = inputTexture ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(cameraTransform)
        val output = previewOutput
        if (output != null) {
            output.updateTransformMatrix(outputTransform, cameraTransform)
        } else {
            cameraTransform.copyInto(outputTransform)
        }
        renderToSurface(previewEglSurface, outputTransform)
        renderToSurface(encoderEglSurface, outputTransform)
    }

    private fun renderToSurface(surface: EGLSurface, transform: FloatArray) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) return

        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_HEIGHT, height, 0)
        GLES20.glViewport(0, 0, width[0], height[0])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(shaderProgram)

        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertices.position(2)
        GLES20.glVertexAttribPointer(
            textureCoordinateHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            16,
            vertices,
        )
        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle)
        EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1))

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configurations = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                attributes,
                0,
                configurations,
                0,
                1,
                count,
                0,
            ),
        )
        eglConfig = configurations[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT)

        val dummy = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(EGL14.eglMakeCurrent(eglDisplay, dummy, dummy, eglContext))
    }

    private fun setupShaders() {
        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
                attribute vec4 aPosition;
                attribute vec2 aTextureCoordinate;
                uniform mat4 uTextureMatrix;
                varying vec2 vTextureCoordinate;
                void main() {
                    gl_Position = aPosition;
                    vTextureCoordinate =
                        (uTextureMatrix * vec4(aTextureCoordinate, 0.0, 1.0)).xy;
                }
            """.trimIndent(),
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTexture;
                varying vec2 vTextureCoordinate;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoordinate);
                }
            """.trimIndent(),
        )
        shaderProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(shaderProgram, vertexShader)
        GLES20.glAttachShader(shaderProgram, fragmentShader)
        GLES20.glLinkProgram(shaderProgram)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] != 0) { GLES20.glGetProgramInfoLog(shaderProgram) }
        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        textureCoordinateHandle =
            GLES20.glGetAttribLocation(shaderProgram, "aTextureCoordinate")
        textureMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uTextureMatrix")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun createWindowSurface(surface: Surface?): EGLSurface {
        if (surface == null) return EGL14.EGL_NO_SURFACE
        return EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
    }

    private fun releaseInput() {
        inputTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release()
        inputTexture?.release()
        inputSurface = null
        inputTexture = null
        if (inputTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
        inputTextureId = 0
    }

    private fun releasePreviewOutput() {
        destroySurface(previewEglSurface)
        previewEglSurface = EGL14.EGL_NO_SURFACE
        previewOutput?.close()
        previewOutput = null
        previewSurface = null
    }

    private fun destroySurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, surface)
    }

    companion object {
        private const val TAG = "LensRelayCameraGL"
    }
}
