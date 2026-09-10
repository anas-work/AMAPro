package com.aimonk.attendance.network

import com.aimonk.attendance.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiService(val baseUrl: String = "https://aimonk-labs--amapro-attendance.modal.run") {
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)   // Modal cold start can take ~10-15s
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun fetchStatus(): SystemStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/status?_t=${System.currentTimeMillis()}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response")
            gson.fromJson(body, SystemStatus::class.java)
        }
    }

    suspend fun fetchRecentAttendance(limit: Int = 50): List<AttendanceRecord> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/attendance/recent?limit=$limit&_t=${System.currentTimeMillis()}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@withContext emptyList()
            val parsed = gson.fromJson(body, RecentAttendanceResponse::class.java)
            parsed.attendanceRecords ?: parsed.events ?: emptyList()
        }
    }

    suspend fun fetchEmployees(): List<EmployeeItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/employees?_t=${System.currentTimeMillis()}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@withContext emptyList()
            val parsed = gson.fromJson(body, EmployeesResponse::class.java)
            parsed.employees ?: emptyList()
        }
    }

    suspend fun switchMode(mode: String): String = withContext(Dispatchers.IO) {
        val json = gson.toJson(mapOf("mode" to mode))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/mode")
            .post(json.toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    suspend fun flushAttendance(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/attendance/flush")
            .post("{}".toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun processCrop(cropBase64: String): ProcessCropResponse = withContext(Dispatchers.IO) {
        val payload = ProcessCropRequest(cropBase64 = cropBase64, fullFrameBase64 = cropBase64)
        val jsonString = gson.toJson(payload)

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/process_crop")
            .post(jsonString.toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("API Error ${response.code}")
            val respBody = response.body?.string() ?: throw Exception("Empty response")
            gson.fromJson(respBody, ProcessCropResponse::class.java)
        }
    }

    suspend fun enrollEmployee(name: String, employeeId: String, imageBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", name.trim())
            .addFormDataPart("employee_id", employeeId.trim())
            .addFormDataPart("department", "General")
            .addFormDataPart(
                "photo",
                "enroll_${employeeId.trim()}.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/enroll")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "Enrollment failed"
                throw Exception(errBody)
            }
            true
        }
    }

    suspend fun deleteEmployee(employeeId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/employees/${employeeId.trim()}")
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun recordUnknown(cropBase64: String): Boolean = withContext(Dispatchers.IO) {
        val payload = mapOf("crop_base64" to cropBase64, "full_frame_base64" to cropBase64)
        val jsonString = gson.toJson(payload)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/record_unknown")
            .post(jsonString.toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }
}
