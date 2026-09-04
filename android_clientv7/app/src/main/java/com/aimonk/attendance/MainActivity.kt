package com.aimonk.attendance

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
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
    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: UltraLightDetector
    private val tracker = IoUTracker()
    private lateinit var dispatcher: CropDispatcher
    private lateinit var cameraExecutor: ExecutorService
    val apiService = ApiService("https://amapro--amapro-attendance.modal.run")

    lateinit var imageLoader: ImageLoader
    private lateinit var feedAdapter: ActivityFeedAdapter
    private var allFeedRecords: List<AttendanceRecord> = emptyList()
    private var activeFilter = "ALL"
    private var isFeedPaused = false
    var currentSystemMode = "ENTRY"
    var latestCameraBitmap: Bitmap? = null

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

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        startRealtimePolling()
    }

    private fun setupRecyclerView() {
        feedAdapter = ActivityFeedAdapter(imageLoader = imageLoader) { record ->
            ComparisonDetailDialog(this, record, apiService).show()
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
            EnrollDialog(this, apiService,
                getCurrentCameraFrame = { latestCameraBitmap },
                onEnrollSuccess = { refreshData() }
            ).show()
        }

        // Employee directory
        binding.btnOpenEmployees.setOnClickListener {
            EmployeeDirectoryDialog(this, apiService) { refreshData() }.show()
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

        // Flush feed
        binding.btnFlushFeed.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Flush Live Feed?")
                .setMessage("This clears all in-memory attendance events and resets live presence counters.")
                .setPositiveButton("Flush Now") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            apiService.flushAttendance()
                            allFeedRecords = emptyList()
                            feedAdapter.updateData(emptyList())
                            tracker.clear()
                            binding.overlayView.clearPopup()
                            refreshData()
                            Toast.makeText(this@MainActivity, "Feed flushed", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Flush error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
                        b.setTextColor(Color.WHITE)
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
            "UNKNOWN" -> allFeedRecords.filter { it.employeeId == "UNKNOWN" || it.name == "UNKNOWN PERSON" }
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
                binding.tvPresentCount.text = status.presentCount.toString()
                binding.tvAbsentCount.text = status.absentCount.toString()
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

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 1. Preview configured with Camera2 high-speed target FPS
            val preview = Preview.Builder().also { builder ->
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(30, 60)
                )
            }.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // 2. ImageAnalysis configured with Camera2 high-speed target FPS
            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .also { builder ->
                    Camera2Interop.Extender(builder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(30, 60)
                    )
                }
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val tStart = System.currentTimeMillis()
                cameraFrameWidth = imageProxy.width
                cameraFrameHeight = imageProxy.height

                if (!isAnalysisBusy) {
                    isAnalysisBusy = true
                    val bitmap: Bitmap? = runCatching { imageProxy.toBitmap() }.getOrNull()

                    if (bitmap != null) {
                        latestCameraBitmap = bitmap

                        // 1/3 Temporal Decimation (matching web app)
                        frameDecimationCounter = (frameDecimationCounter + 1) % 3
                        val isRealKeyframe = (frameDecimationCounter == 0)

                        if (isRealKeyframe) {
                            val tDetStart = System.currentTimeMillis()
                            val dets = detector.detect(bitmap, imageProxy.width, imageProxy.height)
                            binding.overlayView.telemetry.detectMs = (System.currentTimeMillis() - tDetStart).toFloat()

                            val tTrackStart = System.currentTimeMillis()
                            val activeTracks = tracker.update(dets)
                            binding.overlayView.telemetry.trackMs = (System.currentTimeMillis() - tTrackStart).toFloat()

                            dispatcher.evaluateAndDispatch(bitmap, activeTracks, currentSystemMode)
                        }
                        binding.overlayView.telemetry.e2eMs = (System.currentTimeMillis() - tStart).toFloat()
                    }
                    isAnalysisBusy = false
                }
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera error: ${exc.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
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
            if (allPermissionsGranted()) startCamera()
            else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish()
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
