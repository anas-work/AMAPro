package com.aimonk.attendance.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.aimonk.attendance.databinding.DialogEmployeesBinding
import com.aimonk.attendance.model.EmployeeItem
import com.aimonk.attendance.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmployeeDirectoryDialog(
    context: Context,
    private val apiService: ApiService,
    private val onDataChanged: () -> Unit
) : Dialog(context) {

    private val binding = DialogEmployeesBinding.inflate(LayoutInflater.from(context))
    private var allEmployees: List<EmployeeItem> = emptyList()
    private lateinit var adapter: EmployeeAdapter

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        adapter = EmployeeAdapter { emp ->
            confirmDelete(emp)
        }

        binding.rvEmployees.layoutManager = LinearLayoutManager(context)
        binding.rvEmployees.adapter = adapter

        binding.etSearchEmployee.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCloseDirectory.setOnClickListener {
            dismiss()
        }

        loadEmployees()
    }

    private fun loadEmployees() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = apiService.fetchEmployees()
                withContext(Dispatchers.Main) {
                    allEmployees = list
                    binding.tvTotalCountBadge.text = "${list.size} Enrolled"
                    adapter.updateData(list)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error fetching employees: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            adapter.updateData(allEmployees)
        } else {
            val filtered = allEmployees.filter {
                it.name.lowercase().contains(q) || it.employeeId.lowercase().contains(q)
            }
            adapter.updateData(filtered)
        }
    }

    private fun confirmDelete(emp: EmployeeItem) {
        AlertDialog.Builder(context)
            .setTitle("Delete Employee")
            .setMessage("Are you sure you want to remove ${emp.name} (${emp.employeeId}) from database?")
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val success = apiService.deleteEmployee(emp.employeeId)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, "Deleted ${emp.name}", Toast.LENGTH_SHORT).show()
                                loadEmployees()
                                onDataChanged()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
