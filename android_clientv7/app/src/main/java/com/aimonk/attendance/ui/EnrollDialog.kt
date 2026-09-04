package com.aimonk.attendance.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import com.aimonk.attendance.databinding.DialogEnrollBinding
import com.aimonk.attendance.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class EnrollDialog(
    context: Context,
    private val apiService: ApiService,
    private val getCurrentCameraFrame: () -> Bitmap?,
    private val onEnrollSuccess: () -> Unit
) : Dialog(context) {

    private var capturedBitmap: Bitmap? = null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogEnrollBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        binding.btnSnapPhoto.setOnClickListener {
            val frame = getCurrentCameraFrame()
            if (frame != null) {
                capturedBitmap = frame
                binding.imgEnrollPreview.setImageBitmap(frame)
                binding.btnSnapPhoto.text = "🔄 Retake Photo"
                Toast.makeText(context, "Photo captured!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Camera frame not available yet.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTabCamera.setOnClickListener {
            binding.btnTabCamera.setBackgroundColor(Color.parseColor("#059669"))
            binding.btnTabGallery.setBackgroundColor(Color.parseColor("#1E293B"))
        }

        binding.btnTabGallery.setOnClickListener {
            binding.btnTabGallery.setBackgroundColor(Color.parseColor("#059669"))
            binding.btnTabCamera.setBackgroundColor(Color.parseColor("#1E293B"))
            Toast.makeText(context, "Using camera capture mode", Toast.LENGTH_SHORT).show()
        }

        binding.btnSubmitEnroll.setOnClickListener {
            val name = binding.etEnrollName.text.toString().trim()
            val empId = binding.etEnrollId.text.toString().trim()

            if (name.isEmpty() || empId.isEmpty()) {
                Toast.makeText(context, "Please enter Name and Employee ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val bmp = capturedBitmap ?: getCurrentCameraFrame()
            if (bmp == null) {
                Toast.makeText(context, "Please capture a photo first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnSubmitEnroll.isEnabled = false
            binding.btnSubmitEnroll.text = "Registering..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                    val bytes = stream.toByteArray()

                    val success = apiService.enrollEmployee(name, empId, bytes)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "Employee $name enrolled successfully!", Toast.LENGTH_LONG).show()
                            onEnrollSuccess()
                            dismiss()
                        } else {
                            Toast.makeText(context, "Enrollment failed on server", Toast.LENGTH_LONG).show()
                            binding.btnSubmitEnroll.isEnabled = true
                            binding.btnSubmitEnroll.text = "Register Employee"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        binding.btnSubmitEnroll.isEnabled = true
                        binding.btnSubmitEnroll.text = "Register Employee"
                    }
                }
            }
        }

        binding.btnCloseEnroll.setOnClickListener {
            dismiss()
        }
    }
}
