package com.aimonk.attendance.engine

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import com.aimonk.attendance.model.Track
import com.aimonk.attendance.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

class CropDispatcher(
    private val apiService: ApiService,
    private val minRecognitionSize: Float = 120f,
    private val settleDelayMs: Long = 1000L,
    private val samplingIntervalMs: Long = 600L,
    private val maxAttempts: Int = 5,
    private val totalEvaluationWindowMs: Long = 4000L,
    private val onMatch: (Track) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val stream = ByteArrayOutputStream()

    fun evaluateAndDispatch(bitmap: Bitmap, tracks: List<Track>, systemMode: String) {
        val now = System.currentTimeMillis()

        tracks.forEach { track ->
            val faceW = track.bbox.width()
            val faceH = track.bbox.height()
            val faceSize = max(faceW, faceH)

            if (track.recognitionState == "MATCHED") return@forEach

            if (faceSize < minRecognitionSize) {
                track.firstSeenAt120px = 0L
                if (track.recognitionState != "NOT_RECOGNIZED") {
                    track.recognitionState = "WAITING_FOR_SIZE"
                }
                return@forEach
            }

            // Ignore border crops entering edge of frame
            if (track.bbox.left <= 8f || track.bbox.top <= 8f ||
                track.bbox.right >= bitmap.width - 8f || track.bbox.bottom >= bitmap.height - 8f) {
                return@forEach
            }

            // 1. Initial Gate Trigger: Mark the timestamp when person reaches >=120px
            if (track.firstSeenAt120px == 0L) {
                track.firstSeenAt120px = now
                track.evalAttempts = 0
                track.lastAttemptTime = 0L
                track.recognitionState = "SETTLING"
            }

            val elapsedSinceGate = now - track.firstSeenAt120px

            // 2. Settle-Down Delay: Wait for 1 full second to let the person settle down
            if (elapsedSinceGate < settleDelayMs) {
                track.recognitionState = "SETTLING"
                return@forEach
            }

            // 3. Controlled Sampling: Send at most 5 frames spaced by ~600ms within the 3-second evaluation window
            val canSample = !track.inFlight &&
                (track.evalAttempts < maxAttempts) &&
                (now - track.lastAttemptTime >= samplingIntervalMs)

            if (canSample) {
                track.inFlight = true
                track.lastAttemptTime = now
                track.evalAttempts++
                track.recognitionState = "RECOGNIZING"

                scope.launch {
                    try {
                        val crop = extract224Crop(bitmap, track.bbox)
                        val base64Crop = bitmapToBase64(crop)
                        val response = apiService.processCrop(base64Crop)

                        if (response.matched) {
                            track.recognitionState = "MATCHED"
                            track.assignedIdentity = response.name
                            track.employeeId = response.employeeId
                            track.confidence = if (response.confidence > 0f) response.confidence else 0.88f
                            track.decision = response.decision ?: "CHECK_IN"
                            track.photoUrl = response.photoUrl
                            onMatch(track)
                        } else {
                            val totalEvalTime = System.currentTimeMillis() - track.firstSeenAt120px
                            if (track.evalAttempts >= maxAttempts && totalEvalTime >= totalEvaluationWindowMs) {
                                if (track.recognitionState != "NOT_RECOGNIZED") {
                                    track.recognitionState = "NOT_RECOGNIZED"
                                    track.assignedIdentity = "UNKNOWN PERSON"
                                    track.employeeId = "UNKNOWN"
                                    track.confidence = 0f
                                    track.decision = "UNKNOWN"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silent retry on next frame
                    } finally {
                        track.inFlight = false
                    }
                }
            }
        }
    }

    private fun extract224Crop(bitmap: Bitmap, bbox: RectF): Bitmap {
        val padX = max(10, (bbox.width() * 0.25f).toInt())
        val padY = max(10, (bbox.height() * 0.25f).toInt())
        val x = max(0, (bbox.left - padX).toInt())
        val y = max(0, (bbox.top - padY).toInt())
        val w = min(bitmap.width - x, (bbox.width() + padX * 2).toInt())
        val h = min(bitmap.height - y, (bbox.height() + padY * 2).toInt())

        val cropped = Bitmap.createBitmap(bitmap, x, y, max(1, w), max(1, h))
        return Bitmap.createScaledBitmap(cropped, 224, 224, true)
    }

    @Synchronized
    private fun bitmapToBase64(bitmap: Bitmap): String {
        stream.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
