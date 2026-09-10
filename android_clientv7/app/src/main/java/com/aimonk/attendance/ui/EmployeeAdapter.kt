package com.aimonk.attendance.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import com.aimonk.attendance.R
import com.aimonk.attendance.databinding.ItemEmployeeBinding
import com.aimonk.attendance.model.EmployeeItem

class EmployeeAdapter(
    private val imageLoader: ImageLoader? = null,
    private val baseUrl: String = "",
    private var employees: List<EmployeeItem> = emptyList(),
    private val onDeleteClick: (EmployeeItem) -> Unit
) : RecyclerView.Adapter<EmployeeAdapter.EmployeeViewHolder>() {

    fun updateData(newEmployees: List<EmployeeItem>) {
        this.employees = newEmployees
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmployeeViewHolder {
        val binding = ItemEmployeeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EmployeeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EmployeeViewHolder, position: Int) {
        holder.bind(employees[position])
    }

    override fun getItemCount(): Int = employees.size

    inner class EmployeeViewHolder(private val binding: ItemEmployeeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(emp: EmployeeItem) {
            binding.tvEmpName.text = emp.name
            binding.tvEmpId.text = emp.employeeId

            if (emp.isPresent) {
                binding.tvPresenceBadge.text = "PRESENT"
                binding.tvPresenceBadge.setTextColor(Color.parseColor("#10B981"))
                binding.tvPresenceBadge.setBackgroundResource(R.drawable.bg_pill_verified)
            } else {
                binding.tvPresenceBadge.text = "ABSENT"
                binding.tvPresenceBadge.setTextColor(Color.parseColor("#94A3B8"))
                binding.tvPresenceBadge.setBackgroundResource(R.drawable.bg_pill_absent)
            }

            val rawPhoto = emp.photoUrl ?: emp.imagePath
            val fullBaseUrl = if (baseUrl.isNotEmpty()) baseUrl else "https://aimonk-labs--amapro-attendance.modal.run"
            if (!rawPhoto.isNullOrEmpty()) {
                val fullUrl = (if (rawPhoto.startsWith("http")) rawPhoto
                              else "${fullBaseUrl.trimEnd('/')}/${rawPhoto.trimStart('/')}").replace(" ", "%20")
                if (imageLoader != null) {
                    val req = ImageRequest.Builder(binding.imgEmpPhoto.context)
                        .data(fullUrl)
                        .placeholder(R.drawable.ic_launcher_foreground)
                        .error(R.drawable.ic_launcher_foreground)
                        .crossfade(true)
                        .target(binding.imgEmpPhoto)
                        .build()
                    imageLoader.enqueue(req)
                } else {
                    binding.imgEmpPhoto.load(fullUrl) {
                        placeholder(R.drawable.ic_launcher_foreground)
                        error(R.drawable.ic_launcher_foreground)
                    }
                }
            } else {
                binding.imgEmpPhoto.setImageResource(R.drawable.ic_launcher_foreground)
            }

            binding.btnDelete.setOnClickListener { onDeleteClick(emp) }
        }
    }
}
