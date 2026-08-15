package com.atanx.lensrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecList
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.atanx.lensrelay.databinding.ActivityMainBinding
import com.atanx.lensrelay.databinding.ItemPairedDesktopBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swmansion.moqkit.publish.encoder.VideoCodec
import org.json.JSONArray
import org.json.JSONObject
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
    private var pendingStreamDesktop: PairedDesktop? = null
    private var moqStreamSession: MoqStreamSession? = null
    private var streamCamera: Camera? = null
    private var controlClient: ControlClient? = null
    private var controlDesktopId: String? = null
    private var controlConnected = false
    private var controlGeneration = 0L
    private var selectedDesktopId: String? = null
    private var streamSettings = StreamSettings()
    private var streamAspectRatio: Float? = null
    private var actualStreamSize: Size? = null
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
                CameraPurpose.Stream -> startPairedStream()
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
        val streamDesktop = pendingStreamDesktop.also { pendingStreamDesktop = null }
        if (granted && payload != null) {
            completePairing(payload)
        } else if (granted && streamDesktop != null) {
            launchMoqStream(streamDesktop)
        } else {
            pairingInProgress = false
            showMessage(getString(R.string.local_network_permission_denied))
            if (payload != null) bindQrScanner() else showHome()
        }
    }

    override fun onDestroy() {
        stopControlClient()
        moqStreamSession?.stop()
        moqStreamSession = null
        imageAnalysis?.clearAnalyzer()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        startControlClient()
    }

    override fun onStop() {
        stopControlClient()
        super.onStop()
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

        val streamingLeftPadding = binding.streamingControls.paddingLeft
        val streamingTopPadding = binding.streamingControls.paddingTop
        val streamingRightPadding = binding.streamingControls.paddingRight
        val streamingBottomPadding = binding.streamingControls.paddingBottom

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

            binding.streamingControls.setPadding(
                streamingLeftPadding + safeInsets.left,
                streamingTopPadding,
                streamingRightPadding + safeInsets.right,
                streamingBottomPadding + safeInsets.bottom,
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

        binding.stopStreamingButton.setOnClickListener {
            stopStreamingAndShowHome()
        }

        binding.switchStreamingCameraButton.setOnClickListener {
            val desktop = activePairedDesktop() ?: return@setOnClickListener
            activeLens = activeLens.opposite()
            pairingStore.setPreferredCamera(desktop.receiverId, activeLens)
            restartMoqStream()
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
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_pairing_title)
            .setMessage(
                getString(
                    R.string.confirm_pairing_message,
                    payload.confirmationCode,
                    payload.receiverName,
                ),
            )
            .setNegativeButton(R.string.cancel) { _, _ ->
                pairingInProgress = false
                bindQrScanner()
            }
            .setPositiveButton(R.string.codes_match) { _, _ -> continuePairing(payload) }
            .setOnCancelListener {
                pairingInProgress = false
                bindQrScanner()
            }
            .show()
    }

    private fun continuePairing(payload: PairingPayload) {
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
                val mediaToken = PairingClient.pair(payload, proof, phoneName)
                payload.copy(mediaToken = mediaToken)
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

                val confirmedPayload = result.getOrThrow()
                val desktop = runCatching { pairingStore.save(confirmedPayload) }.getOrElse { error ->
                    Log.e(TAG, "Unable to store paired desktop", error)
                    pairingInProgress = false
                    showMessage(getString(R.string.pairing_store_failed))
                    bindQrScanner()
                    return@runOnUiThread
                }

                pairingInProgress = false
                selectedDesktopId = desktop.receiverId
                stopCameraAndShowHome()
                startControlClient(force = true)
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
            .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
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
            val color = if (state == TorchState.ON) R.color.lensrelay_green else R.color.lensrelay_white
            binding.torchButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, color))
            binding.torchButton.contentDescription = getString(
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
        renderPairedDesktops()
    }

    private fun renderPairedDesktops() {
        val desktops = pairedDesktops()
        binding.pairedDesktopsSection.visibility = if (desktops.isEmpty()) View.GONE else View.VISIBLE
        binding.pairedDesktopsList.removeAllViews()
        binding.scanPairingQrButton.text = getString(
            if (desktops.isEmpty()) R.string.scan_pairing_qr else R.string.pair_another_computer,
        )
        desktops.forEach { desktop ->
            val item = ItemPairedDesktopBinding.inflate(
                layoutInflater,
                binding.pairedDesktopsList,
                false,
            )
            item.desktopName.text = desktop.receiverName
            val hasEndpoint = desktop.host.isNotEmpty() &&
                desktop.port in 1..65535 &&
                desktop.mediaCertificateFingerprint.length == 64 &&
                desktop.mediaToken.isNotEmpty()
            item.desktopStatus.setText(
                when {
                    !hasEndpoint -> R.string.pair_again_to_stream
                    controlConnected && controlDesktopId == desktop.receiverId ->
                        R.string.desktop_control_online
                    else -> R.string.desktop_control_offline
                },
            )
            item.root.isEnabled = hasEndpoint
            item.root.setOnClickListener { selectAndStream(desktop) }
            item.desktopMenuButton.setOnClickListener { anchor ->
                showDesktopMenu(anchor, desktop)
            }
            binding.pairedDesktopsList.addView(item.root)
        }
    }

    private fun pairedDesktops(): List<PairedDesktop> =
        runCatching { pairingStore.load().sortedByDescending { it.pairedAt } }
            .onFailure { error -> Log.e(TAG, "Unable to read paired desktops", error) }
            .getOrDefault(emptyList())

    private fun activePairedDesktop(): PairedDesktop? {
        val desktops = pairedDesktops()
        return desktops.firstOrNull { it.receiverId == selectedDesktopId }
            ?: desktops.firstOrNull { it.receiverId == controlDesktopId }
            ?: desktops.firstOrNull()
    }

    private fun selectAndStream(desktop: PairedDesktop) {
        selectedDesktopId = desktop.receiverId
        startControlClient(force = true)
        cameraPurpose = CameraPurpose.Stream
        cameraRequested = true
        if (hasCameraPermission()) startPairedStream() else showPermissionRequired()
    }

    private fun showDesktopMenu(anchor: View, desktop: PairedDesktop) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.connect_and_stream).setOnMenuItemClickListener {
                selectAndStream(desktop)
                true
            }
            menu.add(
                if (desktop.allowRemoteStart) {
                    R.string.require_remote_confirmation
                } else {
                    R.string.trust_remote_start
                },
            ).setOnMenuItemClickListener {
                pairingStore.setAllowRemoteStart(desktop.receiverId, !desktop.allowRemoteStart)
                if (controlDesktopId == desktop.receiverId) publishControlState()
                renderPairedDesktops()
                true
            }
            menu.add(R.string.forget_desktop).setOnMenuItemClickListener {
                confirmForgetDesktop(desktop)
                true
            }
            show()
        }
    }

    private fun confirmForgetDesktop(desktop: PairedDesktop) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.forget_desktop_title, desktop.receiverName))
            .setMessage(R.string.forget_desktop_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.forget_desktop) { _, _ -> unpairDesktop(desktop) }
            .show()
    }

    private fun unpairDesktop(desktop: PairedDesktop) {
        showMessage(getString(R.string.unpairing_desktop))
        analysisExecutor.execute {
            val result = runCatching {
                PairingClient.unpair(
                    desktop,
                    phoneIdentity.createUnpairProof(desktop.receiverId),
                )
            }
            runOnUiThread {
                result.onSuccess {
                    forgetDesktopLocally(desktop)
                    showMessage(getString(R.string.desktop_unpaired))
                }.onFailure { error ->
                    Log.w(TAG, "Could not unpair from desktop", error)
                    Snackbar.make(
                        binding.root,
                        R.string.remote_unpair_failed,
                        Snackbar.LENGTH_LONG,
                    ).setAction(R.string.remove_locally) {
                        forgetDesktopLocally(desktop)
                        showMessage(getString(R.string.desktop_forgotten))
                    }.show()
                }
            }
        }
    }

    private fun forgetDesktopLocally(desktop: PairedDesktop) {
        if (controlDesktopId == desktop.receiverId) stopControlClient()
        if (selectedDesktopId == desktop.receiverId) selectedDesktopId = null
        pairingStore.forget(desktop.receiverId)
        renderPairedDesktops()
        startControlClient()
    }

    private fun startPairedStream() {
        val desktop = activePairedDesktop() ?: return
        activeLens = desktop.preferredCamera
        if (needsLocalNetworkPermission()) {
            pendingStreamDesktop = desktop
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            launchMoqStream(desktop)
        }
    }

    private fun launchMoqStream(desktop: PairedDesktop) {
        stopCameraOnly()
        streamAspectRatio = null
        actualStreamSize = null
        showStreamingSurface()
        binding.streamingStatus.setText(R.string.stream_starting)
        val session = MoqStreamSession(
            applicationContext,
            this,
            binding.mediaPreview,
            desktop,
            activeLens,
            // CameraX owns phone orientation; LensRelay stores no phone rotation preference.
            binding.mediaPreview.display?.rotation ?: Surface.ROTATION_0,
            settings = streamSettings,
            onVideoSize = { width, height ->
                runOnUiThread {
                    streamAspectRatio = width.toFloat() / height
                    actualStreamSize = Size(width, height)
                    fitMediaPreview()
                }
            },
            onCameraReady = { activeCamera, _ ->
                runOnUiThread {
                    streamCamera = activeCamera
                    publishControlCapabilities(activeCamera)
                    publishControlState()
                }
            },
        ) { state, detail ->
            runOnUiThread {
                when (state) {
                    MoqStreamSession.State.Starting -> binding.streamingStatus.setText(R.string.stream_starting)
                    MoqStreamSession.State.Connected -> binding.streamingStatus.text =
                        getString(R.string.stream_connected, desktop.receiverName).also {
                            publishControlState()
                        }
                    MoqStreamSession.State.Failed -> {
                        showMessage(
                            getString(R.string.stream_failed, detail ?: getString(R.string.camera_failed)),
                        )
                        stopStreamingAndShowHome()
                    }
                    MoqStreamSession.State.Stopped -> Unit
                }
            }
        }
        moqStreamSession = session
        runCatching { session.start() }.onFailure { error ->
            Log.e(TAG, "Unable to start media stream", error)
            showMessage(getString(R.string.stream_failed, error.message ?: getString(R.string.camera_failed)))
            stopStreamingAndShowHome()
        }
    }

    private fun restartMoqStream() {
        val desktop = activePairedDesktop() ?: return
        moqStreamSession?.stop()
        moqStreamSession = null
        streamCamera = null
        streamAspectRatio = null
        actualStreamSize = null
        binding.root.post { launchMoqStream(desktop) }
    }

    private fun showStreamingSurface() {
        binding.homePanel.visibility = View.GONE
        binding.permissionPanel.visibility = View.GONE
        binding.previewView.visibility = View.INVISIBLE
        binding.mediaPreview.visibility = View.VISIBLE
        binding.cameraControls.visibility = View.GONE
        binding.statusCard.visibility = View.GONE
        binding.scannerFrame.visibility = View.GONE
        binding.scannerControls.visibility = View.GONE
        binding.streamingControls.visibility = View.VISIBLE
        fitMediaPreview()
        updateStreamingCameraButton()
    }

    private fun updateStreamingCameraButton() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = runCatching { providerFuture.get() }.getOrNull()
                binding.switchStreamingCameraButton.isEnabled = provider != null &&
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) &&
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun fitMediaPreview() {
        binding.root.post {
            val containerWidth = binding.root.width
            val containerHeight = binding.root.height
            if (containerWidth == 0 || containerHeight == 0) return@post

            val containerRatio = containerWidth.toFloat() / containerHeight
            val streamAspectRatio = streamAspectRatio ?: containerRatio
            val previewWidth: Int
            val previewHeight: Int
            if (containerRatio > streamAspectRatio) {
                previewHeight = containerHeight
                previewWidth = (previewHeight * streamAspectRatio).toInt()
            } else {
                previewWidth = containerWidth
                previewHeight = (previewWidth / streamAspectRatio).toInt()
            }

            binding.mediaPreview.layoutParams =
                (binding.mediaPreview.layoutParams as FrameLayout.LayoutParams).apply {
                    width = previewWidth
                    height = previewHeight
                    gravity = Gravity.CENTER
                }
        }
    }

    private fun stopStreamingAndShowHome() {
        moqStreamSession?.stop()
        moqStreamSession = null
        streamCamera = null
        streamAspectRatio = null
        actualStreamSize = null
        binding.mediaPreview.visibility = View.GONE
        binding.streamingControls.visibility = View.GONE
        cameraRequested = false
        showHome()
        publishControlState()
    }

    private fun stopCameraOnly() {
        clearCameraObservers()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        camera = null
    }

    private fun stopCameraAndShowHome() {
        cameraRequested = false
        moqStreamSession?.stop()
        moqStreamSession = null
        streamCamera = null
        streamAspectRatio = null
        actualStreamSize = null
        binding.mediaPreview.visibility = View.GONE
        binding.streamingControls.visibility = View.GONE
        stopCameraOnly()
        showHome()
        publishControlState()
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

    private fun startControlClient(force: Boolean = false) {
        val desktop = activePairedDesktop() ?: return
        if (!force && controlDesktopId == desktop.receiverId && controlClient != null) return
        stopControlClient()
        val generation = ++controlGeneration
        controlDesktopId = desktop.receiverId
        controlClient = ControlClient(
            desktop = desktop,
            identity = phoneIdentity,
            onMediaAuthorization = { token ->
                pairingStore.setMediaToken(desktop.receiverId, token)
            },
            onConnected = { connected ->
                runOnUiThread {
                    if (generation != controlGeneration) return@runOnUiThread
                    controlConnected = connected
                    if (connected) {
                        publishControlCapabilities(streamCamera)
                        publishControlState()
                    }
                    if (binding.homePanel.visibility == View.VISIBLE) renderPairedDesktops()
                }
            },
            onCommand = { command, parameters, responder ->
                runOnUiThread { handleControlCommand(command, parameters, responder) }
            },
        ).also { client ->
            client.start()
            publishControlCapabilities(streamCamera)
            publishControlState()
        }
    }

    private fun stopControlClient() {
        controlGeneration++
        controlClient?.stop()
        controlClient = null
        controlDesktopId = null
        controlConnected = false
    }

    private fun handleControlCommand(
        command: String,
        parameters: JSONObject,
        responder: ControlClient.Responder,
    ) {
        runCatching {
            when (command) {
                "start" -> requestRemoteStart(responder)
                "stop" -> {
                    stopStreamingAndShowHome()
                    responder.respond(true, currentControlState(), null)
                }
                "unpair" -> {
                    val desktop = activePairedDesktop() ?: error("Desktop is no longer paired")
                    responder.respond(true, JSONObject().put("removed", true), null)
                    binding.root.postDelayed(
                        {
                            forgetDesktopLocally(desktop)
                            stopStreamingAndShowHome()
                            showMessage(getString(R.string.desktop_unpaired))
                        },
                        300,
                    )
                }
                "camera" -> {
                    activeLens = when (parameters.getString("value").lowercase()) {
                        "front" -> CameraLens.Front
                        "back" -> CameraLens.Back
                        else -> error("Unknown camera")
                    }
                    activePairedDesktop()?.let {
                        pairingStore.setPreferredCamera(it.receiverId, activeLens)
                    }
                    applyRestartSetting(responder)
                }
                "resolution" -> {
                    val width = parameters.getInt("width")
                    val height = parameters.getInt("height")
                    require(width in 160..7680 && height in 120..4320) { "Invalid resolution" }
                    streamSettings = streamSettings.copy(resolution = Size(width, height))
                    applyRestartSetting(responder)
                }
                "frameRate" -> {
                    val value = parameters.getInt("value")
                    require(value in 1..120) { "Invalid frame rate" }
                    streamSettings = streamSettings.copy(frameRate = value)
                    applyRestartSetting(responder)
                }
                "bitrate" -> {
                    val value = parameters.getInt("value")
                    require(value in 100_000..100_000_000) { "Invalid bitrate" }
                    streamSettings = streamSettings.copy(bitrate = value)
                    applyRestartSetting(responder)
                }
                "codec" -> {
                    streamSettings = streamSettings.copy(
                        codec = when (parameters.getString("value").lowercase()) {
                            "h264" -> VideoCodec.H264
                            "h265" -> VideoCodec.H265
                            else -> error("Unsupported codec")
                        },
                    )
                    applyRestartSetting(responder)
                }
                "stabilization" -> {
                    streamSettings = streamSettings.copy(
                        stabilization = parameters.getBoolean("enabled"),
                    )
                    applyRestartSetting(responder)
                }
                "whiteBalance" -> {
                    val value = parameters.getString("value")
                    require(value in supportedWhiteBalances(streamCamera)) {
                        "White balance mode is unavailable"
                    }
                    streamSettings = streamSettings.copy(whiteBalance = value)
                    applyRestartSetting(responder)
                }
                "torch" -> {
                    val activeCamera = requireNotNull(streamCamera) { "Camera is not streaming" }
                    require(activeCamera.cameraInfo.hasFlashUnit()) { "Torch is unavailable" }
                    activeCamera.cameraControl.enableTorch(parameters.getBoolean("enabled"))
                    responder.respond(true, currentControlState(), null)
                    binding.root.postDelayed(::publishControlState, 200)
                }
                "zoom" -> {
                    val activeCamera = requireNotNull(streamCamera) { "Camera is not streaming" }
                    val value = parameters.getDouble("value").toFloat()
                    val range = activeCamera.cameraInfo.zoomState.value
                        ?: error("Zoom state is unavailable")
                    require(value in range.minZoomRatio..range.maxZoomRatio) { "Zoom is out of range" }
                    activeCamera.cameraControl.setZoomRatio(value)
                    responder.respond(true, currentControlState(), null)
                    binding.root.postDelayed(::publishControlState, 200)
                }
                "exposure" -> {
                    val activeCamera = requireNotNull(streamCamera) { "Camera is not streaming" }
                    val value = parameters.getInt("value")
                    require(value in activeCamera.cameraInfo.exposureState.exposureCompensationRange) {
                        "Exposure is out of range"
                    }
                    activeCamera.cameraControl.setExposureCompensationIndex(value)
                    responder.respond(true, currentControlState(), null)
                    binding.root.postDelayed(::publishControlState, 200)
                }
                "focus" -> {
                    val activeCamera = requireNotNull(streamCamera) { "Camera is not streaming" }
                    val x = parameters.getDouble("x").toFloat().coerceIn(0f, 1f)
                    val y = parameters.getDouble("y").toFloat().coerceIn(0f, 1f)
                    val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
                    val point = factory.createPoint(x, y)
                    activeCamera.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build(),
                    )
                    responder.respond(true, currentControlState(), null)
                }
                else -> error("Unsupported command: $command")
            }
        }.onFailure { error ->
            responder.respond(false, null, error.message ?: "Command failed")
        }
    }

    private fun requestRemoteStart(responder: ControlClient.Responder) {
        val desktop = activePairedDesktop() ?: error("Desktop is no longer paired")
        if (!hasCameraPermission()) {
            responder.respond(false, null, "Open LensRelay and grant camera permission first")
            return
        }
        val start = {
            if (moqStreamSession == null) {
                cameraPurpose = CameraPurpose.Stream
                cameraRequested = true
                startPairedStream()
            }
            responder.respond(true, currentControlState(), null)
        }
        if (desktop.allowRemoteStart) {
            start()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_start_title)
            .setMessage(getString(R.string.remote_start_message, desktop.receiverName))
            .setNegativeButton(R.string.deny) { _, _ ->
                responder.respond(false, null, "Remote camera start was denied")
            }
            .setNeutralButton(R.string.allow_always) { _, _ ->
                pairingStore.setAllowRemoteStart(desktop.receiverId, true)
                renderPairedDesktops()
                start()
            }
            .setPositiveButton(R.string.allow_once) { _, _ -> start() }
            .setOnCancelListener { responder.respond(false, null, "Remote camera start was cancelled") }
            .show()
    }

    private fun applyRestartSetting(responder: ControlClient.Responder) {
        if (moqStreamSession != null) restartMoqStream()
        responder.respond(true, currentControlState(), null)
        publishControlState()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun publishControlCapabilities(activeCamera: Camera?) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = runCatching { providerFuture.get() }.getOrNull()
                val cameras = JSONArray().apply {
                    if (provider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) == true) put("back")
                    if (provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true) put("front")
                }
                val capabilities = JSONObject()
                    .put("commands", JSONArray(listOf("start", "stop", "unpair", "camera", "bitrate", "codec")))
                    .put("cameras", cameras)
                    .put("codecs", JSONArray(supportedCodecs()))
                    .put("bitrate", JSONObject().put("min", 100_000).put("max", 100_000_000))
                activeCamera?.let { camera ->
                    val commands = capabilities.getJSONArray("commands")
                    val zoom = camera.cameraInfo.zoomState.value
                    if (zoom != null && zoom.maxZoomRatio > zoom.minZoomRatio) {
                        commands.put("zoom")
                        capabilities.put("zoom", JSONObject().put("min", zoom.minZoomRatio).put("max", zoom.maxZoomRatio))
                    }
                    if (camera.cameraInfo.hasFlashUnit()) commands.put("torch")
                    val exposure = camera.cameraInfo.exposureState
                    if (exposure.isExposureCompensationSupported) {
                        commands.put("exposure")
                        capabilities.put(
                            "exposure",
                            JSONObject().put("min", exposure.exposureCompensationRange.lower)
                                .put("max", exposure.exposureCompensationRange.upper)
                                .put("step", exposure.exposureCompensationStep.toDouble()),
                        )
                    }
                    commands.put("focus")
                    val camera2 = Camera2CameraInfo.from(camera.cameraInfo)
                    commands.put("resolution")
                    commands.put("frameRate")
                    val sizes = camera2.getCameraCharacteristic(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                    )?.getOutputSizes(SurfaceTexture::class.java)
                        ?.distinctBy { "${it.width}x${it.height}" }
                        ?.sortedByDescending { it.width * it.height }
                        ?.take(12)
                        .orEmpty()
                    capabilities.put(
                        "resolutions",
                        JSONArray(sizes.map { JSONObject().put("width", it.width).put("height", it.height) }),
                    )
                    val frameRates = camera2.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                    ).orEmpty().flatMap { listOf(it.lower, it.upper) }
                        .filter { it in 1..120 }.distinct().sorted()
                    capabilities.put("frameRates", JSONArray(frameRates))
                    val stabilizationModes = camera2.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                    ) ?: intArrayOf()
                    if (stabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                        commands.put("stabilization")
                    }
                    val whiteBalances = supportedWhiteBalances(camera)
                    if (whiteBalances.isNotEmpty()) {
                        commands.put("whiteBalance")
                        capabilities.put("whiteBalances", JSONArray(whiteBalances))
                    }
                }
                controlClient?.updateCapabilities(capabilities)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun publishControlState() {
        controlClient?.updateState(currentControlState())
    }

    private fun currentControlState(): JSONObject {
        val zoom = streamCamera?.cameraInfo?.zoomState?.value
        val exposure = streamCamera?.cameraInfo?.exposureState
        return JSONObject()
            .put("streaming", moqStreamSession != null)
            .put("camera", if (activeLens == CameraLens.Front) "front" else "back")
            .put("torch", streamCamera?.cameraInfo?.torchState?.value == TorchState.ON)
            .put("zoom", zoom?.zoomRatio ?: 1f)
            .put("exposure", exposure?.exposureCompensationIndex ?: 0)
            .put("width", actualStreamSize?.width ?: JSONObject.NULL)
            .put("height", actualStreamSize?.height ?: JSONObject.NULL)
            .put("requestedWidth", streamSettings.resolution?.width ?: JSONObject.NULL)
            .put("requestedHeight", streamSettings.resolution?.height ?: JSONObject.NULL)
            .put("frameRate", streamSettings.frameRate)
            .put("bitrate", streamSettings.bitrate)
            .put("codec", streamSettings.codec.name.lowercase())
            .put("stabilization", streamSettings.stabilization)
            .put("whiteBalance", streamSettings.whiteBalance)
            .put("remoteStartAllowed", activePairedDesktop()?.allowRemoteStart == true)
    }

    private fun supportedCodecs(): List<String> {
        val mimeTypes = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .map(String::lowercase)
            .toSet()
        return buildList {
            if ("video/avc" in mimeTypes) add("h264")
            if ("video/hevc" in mimeTypes) add("h265")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun supportedWhiteBalances(activeCamera: Camera?): List<String> {
        if (activeCamera == null) return emptyList()
        val available = Camera2CameraInfo.from(activeCamera.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        )?.toSet().orEmpty()
        return listOf(
            "auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
            "incandescent" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            "fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            "warm-fluorescent" to CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            "daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            "cloudy-daylight" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            "twilight" to CaptureRequest.CONTROL_AWB_MODE_TWILIGHT,
            "shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,
        ).filter { (_, mode) -> mode in available }.map { it.first }
    }

    companion object {
        private const val TAG = "LensRelayCamera"
        private const val FOCUS_MESSAGE_DURATION_MS = 1_200L
    }

    private enum class CameraPurpose {
        Preview,
        Pairing,
        Stream,
    }
}
