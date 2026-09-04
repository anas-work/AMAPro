package com.aimonk.attendance.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import com.aimonk.attendance.R
import com.aimonk.attendance.databinding.ItemActivityCardBinding
import com.aimonk.attendance.model.AttendanceRecord
import kotlin.math.roundToInt

class ActivityFeedAdapter(
    private var records: List<AttendanceRecord> = emptyList(),
    private val imageLoader: ImageLoader,
    private val onItemClick: (AttendanceRecord) -> Unit
) : RecyclerView.Adapter<ActivityFeedAdapter.FeedViewHolder>() {

    fun updateData(newRecords: List<AttendanceRecord>) {
        this.records = newRecords
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val binding = ItemActivityCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FeedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount(): Int = records.size

    inner class FeedViewHolder(private val binding: ItemActivityCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: AttendanceRecord) {
            val isUnknown = record.employeeId == "UNKNOWN" || record.name == "UNKNOWN PERSON" || record.name == null

            // Name
            binding.tvName.text = if (isUnknown) "Unknown Person" else (record.name ?: "—")
            binding.tvEmployeeId.text = if (isUnknown) "FLAGGED" else (record.employeeId ?: "—")

            // Format timestamp to be compact
            val rawTs = record.timestamp ?: ""
            binding.tvTimestamp.text = if (rawTs.contains("T")) {
                val parts = rawTs.split("T")
                "${parts.getOrElse(1) { rawTs }.take(8)} · ${parts.getOrElse(0) { "" }}"
            } else rawTs

            // Colours and decision pill
            if (isUnknown) {
                binding.tvName.setTextColor(Color.parseColor("#EF4444"))
                binding.tvDecisionPill.text = "⚠ UNKNOWN"
                binding.tvDecisionPill.setTextColor(Color.parseColor("#EF4444"))
                binding.tvConfidence.text = "Unrecognised Incident"
                binding.imgPhoto.strokeColor = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            } else {
                binding.tvName.setTextColor(Color.parseColor("#F8FAFC"))

                val score = (record.confidence * 100).roundToInt()
                binding.tvConfidence.text = if (score > 0) "$score% Match" else "✓ Verified"

                when (val et = record.eventType ?: "CHECK-IN") {
                    "CHECK_OUT", "CHECK-OUT" -> {
                        binding.tvDecisionPill.text = "CHECK-OUT"
                        binding.tvDecisionPill.setTextColor(Color.parseColor("#D946EF"))
                        binding.imgPhoto.strokeColor = ColorStateList.valueOf(Color.parseColor("#D946EF"))
                    }
                    "RE_ENTRY" -> {
                        binding.tvDecisionPill.text = "RE-ENTRY"
                        binding.tvDecisionPill.setTextColor(Color.parseColor("#F59E0B"))
                        binding.imgPhoto.strokeColor = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                    }
                    else -> {
                        binding.tvDecisionPill.text = "CHECK-IN"
                        binding.tvDecisionPill.setTextColor(Color.parseColor("#10B981"))
                        binding.imgPhoto.strokeColor = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    }
                }
            }

            // Photo - use imageLoader (our SSL-trusting coil instance)
            val photoUrl = record.enrolledPhotoPath ?: record.capturedFramePath
            if (!photoUrl.isNullOrEmpty()) {
                val ctx = binding.imgPhoto.context
                val fullUrl = if (photoUrl.startsWith("http")) photoUrl
                              else "https://49.206.228.75:9001/$photoUrl".trimEnd('/')
                val req = ImageRequest.Builder(ctx)
                    .data(fullUrl)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .crossfade(true)
                    .target(binding.imgPhoto)
                    .build()
                imageLoader.enqueue(req)
            } else {
                binding.imgPhoto.setImageResource(R.drawable.ic_launcher_foreground)
            }

            binding.root.setOnClickListener { onItemClick(record) }
        }
    }
}
