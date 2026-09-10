package com.aimonk.attendance

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.ImageLoader
import coil.request.CachePolicy
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.view.Choreographer
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.aimonk.attendance.engine.CropDispatcher
import com.aimonk.attendance.engine.IoUTracker
import com.aimonk.attendance.engine.UltraLightDetector
import com.aimonk.attendance.model.AttendanceRecord
import com.aimonk.attendance.model.Track
import com.aimonk.attendance.network.ApiService
import com.aimonk.attendance.ui.ActivityFeedAdapter
import com.aimonk.attendance.ui.ComparisonDetailDialog
import com.aimonk.attendance.ui.EmployeeDirectoryDialog
import com.aimonk.attendance.ui.EnrollDialog
import com.aimonk.attendance.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: UltraLightDetector
    private val tracker = IoUTracker()
    private lateinit var dispatcher: CropDispatcher
    private lateinit var cameraExecutor: ExecutorService
    val apiService = ApiService("https://aimonk-labs--amapro-attendance.modal.run")

    lateinit var imageLoader: ImageLoader
    private lateinit var feedAdapter: ActivityFeedAdapter
    private var allFeedRecords: List<AttendanceRecord> = emptyList()
    private var activeFilter = "ALL"
    private var isFeedPaused = false
    var currentSystemMode = "ENTRY"
    var latestCameraBitmap: Bitmap? = null

    private var isCameraRunning = false
    private var isCameraPaused = false
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastKnownEnrolledCount = 1
    private var onGalleryImagePickedCallback: ((Bitmap) -> Unit)? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = decodeUriToPortraitBitmap(uri)
                if (bitmap != null) {
                    onGalleryImagePickedCallback?.invoke(bitmap)
                } else {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var frameCounter = 0
    private var frameDecimationCounter = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var pollingJob: Job? = null

    // Decoupled 60 FPS VSYNC Loop (matches web app's requestAnimationFrame)
    private var isRenderLoopRunning = false
    private var cameraFrameWidth = 1280
    private var cameraFrameHeight = 720
    private var isAnalysisBusy = false

    private val renderLoopCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.currentTimeMillis()
            frameCounter++
            if (now - lastFpsTimestamp >= 1000L) {
                binding.overlayView.telemetry.fps = (frameCounter * 1000f) / (now - lastFpsTimestamp)
                frameCounter = 0
                lastFpsTimestamp = now
            }

            // Smooth 60 FPS motion extrapolation on every display tick
            val interpolatedTracks = tracker.extrapolateMotion()
            binding.overlayView.setTracks(interpolatedTracks, cameraFrameWidth, cameraFrameHeight)

            if (isRenderLoopRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Edge-to-edge + notch support ──────────────────────────────────
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets to header so content is below status bar / notch
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(systemBars.top, displayCutout.top)
            view.setPadding(0, topInset, 0, 0)
            insets
        }

        // Apply bottom inset for navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.swipeRefresh) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, nav.bottom)
            insets
        }

        // Build Coil image loader with our TLS-accepting OkHttp client
        imageLoader = ImageLoader.Builder(this)
            .okHttpClient(apiService.client)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()

        cameraExecutor = Executors.newSingleThreadExecutor()
        detector = UltraLightDetector(this)

        setupRecyclerView()
        setupHeaderAndControls()
        setupFilterTabs()

        dispatcher = CropDispatcher(apiService) { matchedTrack ->
            runOnUiThread {
                binding.overlayView.showVerifiedCard(matchedTrack)
                if (!isFeedPaused) refreshData()
            }
        }

        // Initial camera state: Offline waiting for user initiation (BUG-006)
        binding.cameraStartOverlay.visibility = View.VISIBLE
        binding.layoutCameraLiveControls.visibility = View.GONE
        binding.tvCameraLiveBadge.text = "OFFLINE"
        binding.tvCameraLiveBadge.setTextColor(Color.parseColor("#64748B"))

        startRealtimePolling()
    }

    private fun setupRecyclerView() {
        feedAdapter = ActivityFeedAdapter(imageLoader = imageLoader, baseUrl = apiService.baseUrl) { record ->
            ComparisonDetailDialog(this, record, apiService, imageLoader).show()
        }
        binding.rvActivityFeed.layoutManager = LinearLayoutManager(this)
        binding.rvActivityFeed.adapter = feedAdapter
        binding.rvActivityFeed.setHasFixedSize(false)

        binding.swipeRefresh.setColorSchemeColors(
            Color.parseColor("#10B981"),
            Color.parseColor("#06B6D4")
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1E293B"))
        binding.swipeRefresh.setOnRefreshListener { refreshData() }
    }

    private fun setupHeaderAndControls() {
        // Start Device Camera Button (BUG-006)
        binding.btnStartCamera.setOnClickListener {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        }

        // Camera Flip Button (BUG-007)
        binding.btnFlipCamera.setOnClickListener {
            flipCamera()
        }

        // Camera Pause/Resume Button (BUG-006 & hardware switch-off)
        binding.btnCameraPauseResume.setOnClickListener {
            if (!isCameraRunning) return@setOnClickListener
            isCameraPaused = !isCameraPaused
            if (isCameraPaused) {
                // 1. Capture and freeze last frame so video framing is paused cleanly
                val frozenBmp = binding.previewView.bitmap
                if (frozenBmp != null) {
                    binding.imgPausedFrame.setImageBitmap(frozenBmp)
                    binding.imgPausedFrame.visibility = View.VISIBLE
                }
                // 2. Shut off camera hardware completely by unbinding CameraX
                try {
                    cameraProvider?.unbindAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding camera on pause", e)
                }
                binding.btnCameraPauseResume.text = "Resume"
                binding.btnCameraPauseResume.setIconResource(R.drawable.ic_camera_play_corp)
                binding.tvCameraLiveBadge.text = "PAUSED"
                binding.tvCameraLiveBadge.setTextColor(Color.parseColor("#F59E0B"))
                tracker.clear()
                binding.overlayView.clearPopup()
                binding.overlayView.setTracks(emptyList(), cameraFrameWidth, cameraFrameHeight)
                Toast.makeText(this, "Camera switched off & paused", Toast.LENGTH_SHORT).show()
            } else {
                // 3. Instantly resume camera stream and remove frozen frame
                binding.imgPausedFrame.visibility = View.GONE
                bindCameraUseCases()
                binding.btnCameraPauseResume.text = "Pause"
                binding.btnCameraPauseResume.setIconResource(R.drawable.ic_camera_pause_corp)
                binding.tvCameraLiveBadge.text = "● LIVE"
                binding.tvCameraLiveBadge.setTextColor(Color.parseColor("#EF4444"))
                Toast.makeText(this, "Camera resumed", Toast.LENGTH_SHORT).show()
            }
        }

        // Mode toggle
        binding.btnModeToggle.setOnClickListener {
            val nextMode = if (currentSystemMode == "ENTRY") "EXIT" else "ENTRY"
            lifecycleScope.launch {
                try {
                    apiService.switchMode(nextMode)
                    currentSystemMode = nextMode
                    updateModeButton(nextMode)
                    binding.overlayView.setSystemMode(nextMode)
                    tracker.clear()
                    binding.overlayView.clearPopup()
                    Toast.makeText(this@MainActivity, "Switched to $nextMode mode", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Mode switch failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Enroll
        binding.btnOpenEnroll.setOnClickListener {
            EnrollDialog(
                this,
                apiService,
                getCurrentCameraFrame = { latestCameraBitmap },
                onFlipCamera = { flipCamera() },
                onRequestGallery = { callback ->
                    onGalleryImagePickedCallback = callback
                    galleryLauncher.launch("image/*")
                },
                onEnrollSuccess = { refreshData() }
            ).show()
        }

        // Employee directory
        binding.btnOpenEmployees.setOnClickListener {
            EmployeeDirectoryDialog(this, apiService, imageLoader) { refreshData() }.show()
        }

        // Stop/Resume feed
        binding.btnStopResumeFeed.setOnClickListener {
            isFeedPaused = !isFeedPaused
            if (isFeedPaused) {
                binding.btnStopResumeFeed.text = getString(R.string.action_resume)
                binding.tvFeedLiveStatus.text = getString(R.string.label_paused)
                binding.tvFeedLiveStatus.setTextColor(Color.parseColor("#F59E0B"))
            } else {
                binding.btnStopResumeFeed.text = getString(R.string.action_stop)
                binding.tvFeedLiveStatus.text = getString(R.string.label_live_feed)
                binding.tvFeedLiveStatus.setTextColor(Color.parseColor("#10B981"))
                refreshData()
            }
        }

            // Flush feed with executive glass dialog
        binding.btnFlushFeed.setOnClickListener {
            val dialog = android.app.Dialog(this)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val confirmBinding = com.aimonk.attendance.databinding.DialogConfirmBinding.inflate(layoutInflater)
            dialog.setContentView(confirmBinding.root)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation_Executive
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )

            confirmBinding.btnConfirmCancel.setOnClickListener {
                dialog.dismiss()
            }

            confirmBinding.btnConfirmAction.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    try {
                        apiService.flushAttendance()
                        allFeedRecords = emptyList()
                        feedAdapter.updateData(emptyList())
                        tracker.clear()
                        binding.overlayView.clearPopup()
                        binding.tvPresentCount.text = "0"
                        binding.tvAbsentCount.text = maxOf(1, lastKnownEnrolledCount).toString()
                        refreshData()
                        Toast.makeText(this@MainActivity, "Feed flushed", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Flush error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.show()
        }
    }

    private fun updateModeButton(mode: String) {
        binding.btnModeToggle.text = mode
        if (mode == "EXIT") {
            binding.btnModeToggle.setBackgroundColor(Color.parseColor("#D946EF"))
        } else {
            binding.btnModeToggle.setBackgroundColor(Color.parseColor("#10B981"))
        }
    }

    private fun setupFilterTabs() {
        val tabs = listOf(
            binding.tabFilterAll to "ALL",
            binding.tabFilterCheckIn to "CHECK-IN",
            binding.tabFilterCheckOut to "CHECK-OUT",
            binding.tabFilterUnknown to "UNKNOWN"
        )
        tabs.forEach { (btn, key) ->
            btn.setOnClickListener {
                activeFilter = key
                tabs.forEach { (b, k) ->
                    if (k == activeFilter) {
                        b.setBackgroundColor(Color.parseColor("#10B981"))
                        b.setTextColor(Color.parseColor("#0F172A"))
                    } else {
                        b.setBackgroundColor(Color.TRANSPARENT)
                        b.setTextColor(Color.parseColor("#94A3B8"))
                    }
                }
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val filtered = when (activeFilter) {
            "CHECK-IN" -> allFeedRecords.filter { it.eventType == "CHECK_IN" || it.eventType == "CHECK-IN" }
            "CHECK-OUT" -> allFeedRecords.filter { it.eventType == "CHECK_OUT" || it.eventType == "CHECK-OUT" }
            "UNKNOWN" -> allFeedRecords.filter { it.employeeId == "UNKNOWN" || it.name == "UNKNOWN PERSON" || it.eventType == "UNKNOWN" || (it.employeeId != null && it.employeeId.contains("UNKNOWN")) }
            else -> allFeedRecords
        }
        feedAdapter.updateData(filtered)
        binding.emptyFeedContainer.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startRealtimePolling() {
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                if (!isFeedPaused) refreshData()
                delay(2500)
            }
        }
    }

    private fun refreshData() {
        lifecycleScope.launch {
            try {
                val status = apiService.fetchStatus()
                if (status.totalEnrolled > 0) {
                    lastKnownEnrolledCount = status.totalEnrolled
                }
                val totalEnrolled = maxOf(status.totalEnrolled, lastKnownEnrolledCount)
                val effectiveAbsent = if (totalEnrolled > 0) {
                    maxOf(status.absentCount, maxOf(0, totalEnrolled - status.presentCount))
                } else {
                    status.absentCount
                }

                binding.tvPresentCount.text = status.presentCount.toString()
                binding.tvAbsentCount.text = effectiveAbsent.toString()
                binding.tvUnknownCount.text = status.unknownCount.toString()

                // Sync mode if changed from another client
                if (status.activeMode != null && status.activeMode != currentSystemMode) {
                    currentSystemMode = status.activeMode
                    updateModeButton(currentSystemMode)
                    binding.overlayView.setSystemMode(currentSystemMode)
                    tracker.clear()
                }

                val records = apiService.fetchRecentAttendance(50)
                allFeedRecords = records
                applyFilter()
            } catch (e: Exception) {
                // Fail silently to keep camera smooth
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    fun flipCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        tracker.clear()
        binding.overlayView.clearPopup()
        binding.overlayView.setTracks(emptyList(), cameraFrameWidth, cameraFrameHeight)
        if (isCameraRunning && !isCameraPaused) {
            bindCameraUseCases()
        }
        val lensName = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "Front" else "Rear"
        Toast.makeText(this, "Switched to $lensName Camera", Toast.LENGTH_SHORT).show()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            isCameraRunning = true
            isCameraPaused = false

            binding.imgPausedFrame.visibility = View.GONE
            binding.cameraStartOverlay.visibility = View.GONE
            binding.layoutCameraLiveControls.visibility = View.VISIBLE
            binding.tvCameraLiveBadge.text = "● LIVE"
            binding.tvCameraLiveBadge.setTextColor(Color.parseColor("#EF4444"))
            binding.btnCameraPauseResume.text = "Pause"
            binding.btnCameraPauseResume.setIconResource(R.drawable.ic_camera_pause_corp)

            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        // 1. Preview configured with Camera2 high-speed target FPS and auto white balance (BUG-003)
        val preview = Preview.Builder().also { builder ->
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(30, 60)
                )
        }.build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        // 2. ImageAnalysis configured with Camera2 high-speed target FPS and optimized 640x480 resolution
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .also { builder ->
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(30, 60)
                    )
            }
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val tStart = System.currentTimeMillis()

            if (isCameraPaused || !isCameraRunning) {
                imageProxy.close()
                return@setAnalyzer
            }

            if (!isAnalysisBusy) {
                // 1/3 Temporal Decimation - evaluate BEFORE heavy Bitmap allocation & YUV conversion
                frameDecimationCounter = (frameDecimationCounter + 1) % 3
                val isRealKeyframe = (frameDecimationCounter == 0)

                if (isRealKeyframe) {
                    isAnalysisBusy = true
                    val rawBitmap: Bitmap? = runCatching { imageProxy.toBitmap() }.getOrNull()

                    if (rawBitmap != null) {
                        val isFront = (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                        val orientedBitmap = orientBitmap(rawBitmap, imageProxy.imageInfo.rotationDegrees, isFront)

                        latestCameraBitmap = orientedBitmap
                        cameraFrameWidth = orientedBitmap.width
                        cameraFrameHeight = orientedBitmap.height

                        val tDetStart = System.currentTimeMillis()
                        val dets = detector.detect(orientedBitmap, orientedBitmap.width, orientedBitmap.height)
                        binding.overlayView.telemetry.detectMs = (System.currentTimeMillis() - tDetStart).toFloat()

                        val tTrackStart = System.currentTimeMillis()
                        val activeTracks = tracker.update(dets)
                        binding.overlayView.telemetry.trackMs = (System.currentTimeMillis() - tTrackStart).toFloat()

                        dispatcher.evaluateAndDispatch(orientedBitmap, activeTracks, currentSystemMode)
                        binding.overlayView.telemetry.e2eMs = (System.currentTimeMillis() - tStart).toFloat()
                    }
                    isAnalysisBusy = false
                }
            }
            imageProxy.close()
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, currentCameraSelector, preview, imageAnalysis)
        } catch (exc: Exception) {
            Toast.makeText(this, "Camera error: ${exc.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun orientBitmap(raw: Bitmap, rotationDegrees: Int, isFront: Boolean): Bitmap {
        if (rotationDegrees == 0 && !isFront) return raw
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (isFront) {
                postScale(-1f, 1f) // Mirror horizontally matching front preview
            }
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun decodeUriToPortraitBitmap(uri: Uri): Bitmap? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val rawBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (rawBitmap == null) return null

        var rotation = 0
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                rotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            // Fallback: 0
        }

        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isRenderLoopRunning) {
            isRenderLoopRunning = true
            Choreographer.getInstance().postFrameCallback(renderLoopCallback)
        }
    }

    override fun onPause() {
        super.onPause()
        isRenderLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(renderLoopCallback)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to start live recognition.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRenderLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(renderLoopCallback)
        pollingJob?.cancel()
        detector.close()
        cameraExecutor.shutdown()
    }
}
