package com.atanx.lensrelay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.atanx.lensrelay.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var activeLens = CameraLens.Back
    private var cameraRequested = false
    private var cameraPurpose = CameraPurpose.Preview
    private var pairingInProgress = false
    private var pendingPairingPayload: PairingPayload? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pairingStore by lazy { PairingStore(applicationContext) }
    private val phoneIdentity by lazy { PhoneIdentity() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (cameraPurpose) {
                CameraPurpose.Pairing -> {
                    showScannerSurface()
                    initializeQrScanner()
                }
                CameraPurpose.Preview -> {
                    showCameraSurface()
                    initializeCamera()
                }
            }
        } else {
            cameraRequested = false
            showPermissionRequired()
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val payload = pendingPairingPayload.also { pendingPairingPayload = null }
        if (granted && payload != null) {
            completePairing(payload)
        } else {
            pairingInProgress = false
            showMessage(getString(R.string.local_network_permission_denied))
            bindQrScanner()
        }
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureWindowInsets()
        configureControls()
        configureBackNavigation()
        showHome()
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.homePanel.visibility == View.VISIBLE) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        stopCameraAndShowHome()
                    }
                }
            },
        )
    }

    private fun configureWindowInsets() {
        val statusLayout = binding.statusCard.layoutParams as FrameLayout.LayoutParams
        val statusLeftMargin = statusLayout.leftMargin
        val statusTopMargin = statusLayout.topMargin
        val statusRightMargin = statusLayout.rightMargin

        val controlsLeftPadding = binding.cameraControls.paddingLeft
        val controlsTopPadding = binding.cameraControls.paddingTop
        val controlsRightPadding = binding.cameraControls.paddingRight
        val controlsBottomPadding = binding.cameraControls.paddingBottom

        val scannerLeftPadding = binding.scannerControls.paddingLeft
        val scannerTopPadding = binding.scannerControls.paddingTop
        val scannerRightPadding = binding.scannerControls.paddingRight
        val scannerBottomPadding = binding.scannerControls.paddingBottom

        val permissionLayout =
            binding.permissionPanel.layoutParams as FrameLayout.LayoutParams
        val permissionLeftMargin = permissionLayout.leftMargin
        val permissionRightMargin = permissionLayout.rightMargin

        val homeLeftPadding = binding.homePanel.paddingLeft
        val homeTopPadding = binding.homePanel.paddingTop
        val homeRightPadding = binding.homePanel.paddingRight
        val homeBottomPadding = binding.homePanel.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            statusLayout.setMargins(
                statusLeftMargin + safeInsets.left,
                statusTopMargin + safeInsets.top,
                statusRightMargin + safeInsets.right,
                statusLayout.bottomMargin,
            )
            binding.statusCard.layoutParams = statusLayout

            binding.cameraControls.setPadding(
                controlsLeftPadding + safeInsets.left,
                controlsTopPadding,
                controlsRightPadding + safeInsets.right,
                controlsBottomPadding + safeInsets.bottom,
            )

            binding.scannerControls.setPadding(
                scannerLeftPadding + safeInsets.left,
                scannerTopPadding,
                scannerRightPadding + safeInsets.right,
                scannerBottomPadding + safeInsets.bottom,
            )

            permissionLayout.leftMargin = permissionLeftMargin + safeInsets.left
            permissionLayout.rightMargin = permissionRightMargin + safeInsets.right
            binding.permissionPanel.layoutParams = permissionLayout

            binding.homePanel.setPadding(
                homeLeftPadding + safeInsets.left,
                homeTopPadding + safeInsets.top,
                homeRightPadding + safeInsets.right,
                homeBottomPadding + safeInsets.bottom,
            )

            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun configureControls() {
        binding.startCameraButton.setOnClickListener {
            cameraPurpose = CameraPurpose.Preview
            cameraRequested = true
            if (hasCameraPermission()) {
                showCameraSurface()
                initializeCamera()
            } else {
                showPermissionRequired()
            }
        }

        binding.scanPairingQrButton.setOnClickListener {
            cameraPurpose = CameraPurpose.Pairing
            cameraRequested = true
            if (hasCameraPermission()) {
                showScannerSurface()
                initializeQrScanner()
            } else {
                showPermissionRequired()
            }
        }

        binding.grantPermissionButton.setOnClickListener {
            cameraRequested = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.cancelPermissionButton.setOnClickListener {
            cameraRequested = false
            showHome()
        }

        binding.cancelScannerButton.setOnClickListener {
            stopCameraAndShowHome()
        }

        binding.stopCameraButton.setOnClickListener {
            stopCameraAndShowHome()
        }

        binding.switchCameraButton.setOnClickListener {
            activeLens = activeLens.opposite()
            bindCameraUseCases()
        }

        binding.torchButton.setOnClickListener {
            val activeCamera = camera ?: return@setOnClickListener
            val torchEnabled = activeCamera.cameraInfo.torchState.value == TorchState.ON
            activeCamera.cameraControl.enableTorch(!torchEnabled)
        }

        binding.zoomSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        camera?.cameraControl?.setLinearZoom(progress / 100f)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        binding.previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    private fun initializeCamera() {
        updateStatus(getString(R.string.camera_starting))
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    if (cameraRequested) {
                        bindCameraUseCases()
                    }
                } catch (error: Exception) {
                    showCameraError(error)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun initializeQrScanner() {
        updateStatus(getString(R.string.scan_qr_status))
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    if (cameraRequested && cameraPurpose == CameraPurpose.Pairing) {
                        bindQrScanner()
                    }
                } catch (error: Exception) {
                    showCameraError(error)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun bindQrScanner() {
        val provider = cameraProvider ?: return
        clearCameraObservers()
        imageAnalysis?.clearAnalyzer()

        val preview = Preview.Builder().build().also { useCase ->
            useCase.surfaceProvider = binding.previewView.surfaceProvider
        }
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(
                    analysisExecutor,
                    QrCodeAnalyzer { rawCode ->
                        runOnUiThread { handlePairingCode(rawCode) }
                    },
                )
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
        } catch (error: Exception) {
            showCameraError(error)
        }
    }

    private fun handlePairingCode(rawCode: String) {
        if (pairingInProgress) return
        val payload = try {
            PairingPayload.parse(rawCode)
        } catch (error: Exception) {
            showMessage(
                getString(
                    R.string.pairing_invalid,
                    error.message ?: getString(R.string.camera_failed),
                ),
            )
            bindQrScanner()
            return
        }

        pairingInProgress = true
        imageAnalysis?.clearAnalyzer()
        if (needsLocalNetworkPermission()) {
            pendingPairingPayload = payload
            updateStatus(getString(R.string.local_network_permission_needed))
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            completePairing(payload)
        }
    }

    private fun completePairing(payload: PairingPayload) {
        updateStatus(getString(R.string.confirming_pairing))
        analysisExecutor.execute {
            val result = runCatching {
                val phoneName = Build.MODEL.trim().ifEmpty { getString(R.string.unknown_phone) }
                    .take(80)
                val proof = phoneIdentity.createPairingProof(payload, phoneName)
                PairingClient.pair(payload, proof, phoneName)
            }

            runOnUiThread {
                result.onFailure { error ->
                    Log.e(TAG, "Unable to confirm pairing with desktop", error)
                    pairingInProgress = false
                    showMessage(
                        getString(
                            R.string.pairing_invalid,
                            error.message ?: getString(R.string.desktop_unreachable),
                        ),
                    )
                    bindQrScanner()
                    return@runOnUiThread
                }

                val desktop = runCatching { pairingStore.save(payload) }.getOrElse { error ->
                    Log.e(TAG, "Unable to store paired desktop", error)
                    pairingInProgress = false
                    showMessage(getString(R.string.pairing_store_failed))
                    bindQrScanner()
                    return@runOnUiThread
                }

                pairingInProgress = false
                stopCameraAndShowHome()
                showMessage(getString(R.string.pairing_saved, desktop.receiverName))
            }
        }
    }

    private fun needsLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) != PackageManager.PERMISSION_GRANTED

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        clearCameraObservers()

        val selector = CameraSelector.Builder()
            .requireLensFacing(activeLens.lensFacing)
            .build()

        if (!provider.hasCamera(selector)) {
            activeLens = CameraLens.Back
            showMessage(getString(R.string.requested_camera_unavailable))
            return
        }

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                    )
                    .build(),
            )
            .build()
            .also { useCase ->
                useCase.surfaceProvider = binding.previewView.surfaceProvider
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview)
            observeCamera(camera!!)
            updateAvailableControls(provider)
        } catch (error: Exception) {
            showCameraError(error)
        }
    }

    private fun observeCamera(activeCamera: Camera) {
        activeCamera.cameraInfo.cameraState.observe(this) { state ->
            val status = when (state.type) {
                CameraState.Type.PENDING_OPEN -> getString(R.string.camera_waiting)
                CameraState.Type.OPENING -> getString(R.string.camera_starting)
                CameraState.Type.OPEN -> getString(
                    R.string.camera_ready,
                    activeLensLabel(),
                )
                CameraState.Type.CLOSING -> getString(R.string.camera_stopping)
                CameraState.Type.CLOSED -> getString(R.string.camera_stopped)
            }
            updateStatus(status)

            state.error?.let { error ->
                Log.w(TAG, "CameraX state error: ${error.code}", error.cause)
                updateStatus(getString(R.string.camera_error_code, error.code))
            }
        }

        activeCamera.cameraInfo.torchState.observe(this) { state ->
            binding.torchButton.text = getString(
                if (state == TorchState.ON) R.string.torch_on else R.string.torch_off,
            )
        }

        activeCamera.cameraInfo.zoomState.observe(this) { state ->
            if (!binding.zoomSlider.isPressed) {
                binding.zoomSlider.progress = (state.linearZoom * 100).toInt()
            }
            binding.zoomValue.text = getString(R.string.zoom_value, state.zoomRatio)
        }
    }

    private fun updateAvailableControls(provider: ProcessCameraProvider) {
        val frontSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        val backSelector = CameraSelector.DEFAULT_BACK_CAMERA
        binding.switchCameraButton.isEnabled =
            provider.hasCamera(frontSelector) && provider.hasCamera(backSelector)

        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        binding.torchButton.isEnabled = hasFlash
        binding.torchButton.visibility = if (hasFlash) View.VISIBLE else View.GONE

        binding.zoomSlider.isEnabled =
            camera?.cameraInfo?.zoomState?.value?.maxZoomRatio?.let { it > 1f } == true
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        activeCamera.cameraControl.startFocusAndMetering(action)
        binding.focusHint.text = getString(R.string.focusing)
        binding.focusHint.postDelayed(
            { binding.focusHint.text = getString(R.string.focus_hint) },
            FOCUS_MESSAGE_DURATION_MS,
        )
    }

    private fun clearCameraObservers() {
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera?.cameraInfo?.torchState?.removeObservers(this)
        camera?.cameraInfo?.zoomState?.removeObservers(this)
    }

    private fun showPermissionRequired() {
        binding.homePanel.visibility = View.GONE
        binding.permissionPanel.visibility = View.VISIBLE
        binding.previewView.visibility = View.INVISIBLE
        binding.cameraControls.visibility = View.GONE
        binding.statusCard.visibility = View.GONE
        binding.scannerFrame.visibility = View.GONE
        binding.scannerControls.visibility = View.GONE
        val pairingRequest = cameraPurpose == CameraPurpose.Pairing
        binding.permissionTitle.setText(
            if (pairingRequest) R.string.qr_permission_title else R.string.permission_title,
        )
        binding.permissionExplanation.setText(
            if (pairingRequest) {
                R.string.qr_permission_explanation
            } else {
                R.string.permission_explanation
            },
        )
        updateStatus(getString(R.string.camera_permission_needed))
    }

    private fun showCameraSurface() {
        binding.homePanel.visibility = View.GONE
        binding.permissionPanel.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.cameraControls.visibility = View.VISIBLE
        binding.statusCard.visibility = View.VISIBLE
        binding.scannerFrame.visibility = View.GONE
        binding.scannerControls.visibility = View.GONE
    }

    private fun showScannerSurface() {
        binding.homePanel.visibility = View.GONE
        binding.permissionPanel.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.cameraControls.visibility = View.GONE
        binding.statusCard.visibility = View.VISIBLE
        binding.scannerFrame.visibility = View.VISIBLE
        binding.scannerControls.visibility = View.VISIBLE
        updateStatus(getString(R.string.scan_qr_status))
    }

    private fun showHome() {
        binding.homePanel.visibility = View.VISIBLE
        binding.permissionPanel.visibility = View.GONE
        binding.previewView.visibility = View.INVISIBLE
        binding.cameraControls.visibility = View.GONE
        binding.statusCard.visibility = View.GONE
        binding.scannerFrame.visibility = View.GONE
        binding.scannerControls.visibility = View.GONE
        renderPairedDesktop()
    }

    private fun renderPairedDesktop() {
        val desktop = runCatching { pairingStore.load().maxByOrNull { it.pairedAt } }
            .onFailure { error -> Log.e(TAG, "Unable to read paired desktops", error) }
            .getOrNull()
        binding.pairedDesktopCard.visibility = if (desktop == null) View.GONE else View.VISIBLE
        if (desktop != null) {
            binding.pairedDesktopName.text = desktop.receiverName
            binding.pairedDesktopStatus.setText(R.string.desktop_identity_saved)
        }
    }

    private fun stopCameraAndShowHome() {
        cameraRequested = false
        clearCameraObservers()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        camera = null
        showHome()
    }

    private fun showCameraError(error: Exception) {
        Log.e(TAG, "Unable to start camera", error)
        updateStatus(getString(R.string.camera_failed))
        showMessage(getString(R.string.camera_failed_detail))
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun updateStatus(status: String) {
        binding.statusText.text = status
    }

    private fun activeLensLabel(): String = getString(
        if (activeLens == CameraLens.Front) {
            R.string.front_camera
        } else {
            R.string.back_camera
        },
    )

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LensRelayCamera"
        private const val FOCUS_MESSAGE_DURATION_MS = 1_200L
    }

    private enum class CameraPurpose {
        Preview,
        Pairing,
    }
}
