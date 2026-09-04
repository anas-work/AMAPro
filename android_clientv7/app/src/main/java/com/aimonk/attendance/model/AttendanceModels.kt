package com.aimonk.attendance.model

import android.graphics.RectF
import com.google.gson.annotations.SerializedName

data class Detection(
    val bbox: RectF,
    val score: Float
)

data class Track(
    val trackId: Int,
    var bbox: RectF,
    var score: Float,
    var age: Int = 1,
    var hits: Int = 1,
    var timeSinceUpdate: Int = 0,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var recognitionState: String = "WAITING_FOR_SIZE",
    var assignedIdentity: String? = null,
    var employeeId: String? = null,
    var confidence: Float = 0f,
    var decision: String? = null,
    var photoUrl: String? = null,
    var firstSeenAt120px: Long = 0L,
    var lastAttemptTime: Long = 0L,
    var evalAttempts: Int = 0,
    var inFlight: Boolean = false
)

data class SystemStatus(
    @SerializedName("status") val status: String? = "ONLINE",
    @SerializedName("device") val device: String? = "cuda",
    @SerializedName("total_enrolled") val totalEnrolled: Int = 0,
    @SerializedName("present_count") val presentCount: Int = 0,
    @SerializedName("absent_count") val absentCount: Int = 0,
    @SerializedName("unknown_count") val unknownCount: Int = 0,
    @SerializedName("active_mode") val activeMode: String? = "ENTRY"
)

data class AttendanceRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("employee_id") val employeeId: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("event_type") val eventType: String? = null,
    @SerializedName("confidence") val confidence: Float = 0f,
    @SerializedName("enrolled_photo_path") val enrolledPhotoPath: String? = null,
    @SerializedName("captured_frame_path") val capturedFramePath: String? = null
)

data class RecentAttendanceResponse(
    @SerializedName("attendance_records") val attendanceRecords: List<AttendanceRecord>? = null,
    @SerializedName("events") val events: List<AttendanceRecord>? = null,
    @SerializedName("active_mode") val activeMode: String? = null
)

data class EmployeeItem(
    @SerializedName("employee_id") val employeeId: String,
    @SerializedName("name") val name: String,
    @SerializedName("department") val department: String? = "General",
    @SerializedName("photo_url") val photoUrl: String? = null,
    @SerializedName("image_path") val imagePath: String? = null,
    @SerializedName("is_present") val isPresent: Boolean = false,
    @SerializedName("enrolled_at") val enrolledAt: String? = null
)

data class EmployeesResponse(
    @SerializedName("status") val status: String,
    @SerializedName("total_enrolled") val totalEnrolled: Int,
    @SerializedName("employees") val employees: List<EmployeeItem>
)

data class ProcessCropRequest(
    @SerializedName("crop_base64") val cropBase64: String,
    @SerializedName("full_frame_base64") val fullFrameBase64: String? = null
)

data class ProcessCropResponse(
    @SerializedName("status") val status: String,
    @SerializedName("matched") val matched: Boolean,
    @SerializedName("employee_id") val employeeId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("decision") val decision: String?,
    @SerializedName("photo_url") val photoUrl: String?,
    @SerializedName("event_recorded") val eventRecorded: Boolean?
)

data class Telemetry(
    var fps: Float = 30.0f,
    var detectMs: Float = 2.0f,
    var trackMs: Float = 0.1f,
    var e2eMs: Float = 2.5f
)
