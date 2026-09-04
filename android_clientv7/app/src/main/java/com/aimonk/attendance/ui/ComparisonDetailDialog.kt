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
import kotlin.math.roundToInt

class ComparisonDetailDialog(
    context: Context,
    private val record: AttendanceRecord,
    private val apiService: ApiService? = null
) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogComparisonBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val isUnknown = record.employeeId == "UNKNOWN" || record.name == "UNKNOWN PERSON"

        binding.tvCompareName.text = if (isUnknown) {
            "⚠ Flagged Unknown Person Incident"
        } else {
            "${record.name ?: "N/A"}  ·  ${record.employeeId ?: "N/A"}"
        }

        val score = (record.confidence * 100).roundToInt()
        binding.tvCompareScore.text = if (isUnknown) {
            "Status: No match found in employee database"
        } else {
            "Match Score: $score% similarity"
        }

        binding.tvCompareTime.text = record.timestamp ?: "Just now"

        // Use SSL-trusting image loader if available
        val activity = context as? android.app.Activity
        val actMain = activity as? com.aimonk.attendance.MainActivity

        fun loadPhoto(url: String?, target: android.widget.ImageView) {
            if (url.isNullOrEmpty()) {
                target.setImageResource(R.drawable.ic_launcher_foreground)
                return
            }
            val fullUrl = if (url.startsWith("http")) url
                          else "https://49.206.228.75:9001/$url".trimEnd('/')
            if (actMain != null) {
                val req = ImageRequest.Builder(context)
                    .data(fullUrl)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .crossfade(true)
                    .target(target)
                    .build()
                actMain.imageLoader.enqueue(req)
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
