package com.aimonk.attendance.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import com.aimonk.attendance.MainActivity
import com.aimonk.attendance.R
import com.aimonk.attendance.databinding.ItemEmployeeBinding
import com.aimonk.attendance.model.EmployeeItem

class EmployeeAdapter(
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
                binding.tvPresenceBadge.text = "● PRESENT"
                binding.tvPresenceBadge.setTextColor(Color.parseColor("#10B981"))
                binding.tvPresenceBadge.setBackgroundColor(Color.parseColor("#0A2B1E"))
            } else {
                binding.tvPresenceBadge.text = "ABSENT"
                binding.tvPresenceBadge.setTextColor(Color.parseColor("#64748B"))
                binding.tvPresenceBadge.setBackgroundColor(Color.parseColor("#111827"))
            }

            val ctx = binding.imgEmpPhoto.context
            val actMain = (ctx as? android.app.Activity) as? MainActivity

            val rawPhoto = emp.photoUrl ?: emp.imagePath
            if (!rawPhoto.isNullOrEmpty()) {
                val fullUrl = if (rawPhoto.startsWith("http")) rawPhoto
                              else "https://49.206.228.75:9001/$rawPhoto".trimEnd('/')
                if (actMain != null) {
                    val req = ImageRequest.Builder(ctx)
                        .data(fullUrl)
                        .placeholder(R.drawable.ic_launcher_foreground)
                        .error(R.drawable.ic_launcher_foreground)
                        .crossfade(true)
                        .target(binding.imgEmpPhoto)
                        .build()
                    actMain.imageLoader.enqueue(req)
                } else {
                    binding.imgEmpPhoto.setImageResource(R.drawable.ic_launcher_foreground)
                }
            } else {
                binding.imgEmpPhoto.setImageResource(R.drawable.ic_launcher_foreground)
            }

            binding.btnDelete.setOnClickListener { onDeleteClick(emp) }
        }
    }
}
