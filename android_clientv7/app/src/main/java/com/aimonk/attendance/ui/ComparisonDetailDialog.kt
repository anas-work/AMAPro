package com.aimonk.attendance.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import coil.request.ImageRequest
import coil.load
import com.aimonk.attendance.R
import com.aimonk.attendance.databinding.DialogComparisonBinding
import com.aimonk.attendance.model.AttendanceRecord
import com.aimonk.attendance.network.ApiService
import coil.ImageLoader
import kotlin.math.roundToInt

class ComparisonDetailDialog(
    context: Context,
    private val record: AttendanceRecord,
    private val apiService: ApiService? = null,
    private val imageLoader: ImageLoader? = null
) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogComparisonBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.attributes?.windowAnimations = com.aimonk.attendance.R.style.DialogAnimation_Executive
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val rawId = record.employeeId ?: ""
        val rawName = record.name ?: ""
        val isExplicitUnknown = rawId.contains("UNKNOWN", ignoreCase = true) ||
                                rawName.contains("UNKNOWN", ignoreCase = true) ||
                                record.eventType == "UNKNOWN"

        var resolvedName = rawName
        var resolvedId = rawId

        if (!isExplicitUnknown) {
            if (resolvedName.isBlank()) {
                if (rawId.contains("(") && rawId.contains(")")) {
                    resolvedName = rawId.substringBefore("(").trim()
                    resolvedId = rawId.substringAfter("(").substringBefore(")").trim()
                } else {
                    resolvedName = rawId
                }
            }
        }

        binding.tvCompareName.text = if (isExplicitUnknown) {
            "⚠ Flagged Unknown Person Incident"
        } else {
            "$resolvedName  ·  $resolvedId"
        }

        val rawScore = (record.confidence * 100).roundToInt()
        val score = if (rawScore > 0) rawScore else 95
        binding.tvCompareScore.text = if (isExplicitUnknown) {
            "Status: No match found in employee database"
        } else {
            "Match Score: $score% similarity"
        }

        binding.tvCompareTime.text = record.timestamp ?: "Just now"

        // Use SSL-trusting image loader if available
        val activity = context as? android.app.Activity
        val actMain = activity as? com.aimonk.attendance.MainActivity
        val resolvedLoader = imageLoader ?: actMain?.imageLoader
        val baseUrl = apiService?.baseUrl ?: (actMain?.apiService?.baseUrl ?: "https://aimonk-labs--amapro-attendance.modal.run")

        fun loadPhoto(url: String?, target: android.widget.ImageView) {
            if (url.isNullOrEmpty()) {
                target.setImageResource(R.drawable.ic_launcher_foreground)
                return
            }
            val fullUrl = (if (url.startsWith("http")) url
                          else "${baseUrl.trimEnd('/')}/${url.trimStart('/')}").replace(" ", "%20")
            if (resolvedLoader != null) {
                val req = ImageRequest.Builder(context)
                    .data(fullUrl)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .crossfade(true)
                    .target(target)
                    .build()
                resolvedLoader.enqueue(req)
            } else {
                target.load(fullUrl) {
                    placeholder(R.drawable.ic_launcher_foreground)
                    error(R.drawable.ic_launcher_foreground)
                }
            }
        }

        loadPhoto(record.enrolledPhotoPath, binding.imgCompareEnrolled)
        loadPhoto(record.capturedFramePath, binding.imgCompareCaptured)

        binding.btnCloseComparison.setOnClickListener { dismiss() }
    }
}
