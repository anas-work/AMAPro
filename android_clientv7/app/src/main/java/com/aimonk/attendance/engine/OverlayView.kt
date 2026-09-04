package com.aimonk.attendance.engine

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.aimonk.attendance.model.Telemetry
import com.aimonk.attendance.model.Track
import kotlin.math.roundToInt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var tracks: List<Track> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private var systemMode = "ENTRY"
    val telemetry = Telemetry()

    private var activePopupTrack: Track? = null
    private var popupExpiryTime = 0L

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val bannerPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val hudPaint = Paint().apply {
        color = Color.parseColor("#E00A0F1D")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    fun setSystemMode(mode: String) {
        this.systemMode = mode
        invalidate()
    }

    fun clearPopup() {
        this.activePopupTrack = null
        this.popupExpiryTime = 0L
        postInvalidateOnAnimation()
    }

    fun setTracks(newTracks: List<Track>, srcWidth: Int, srcHeight: Int) {
        this.tracks = newTracks
        this.imageWidth = srcWidth
        this.imageHeight = srcHeight
        postInvalidateOnAnimation()
    }

    fun showVerifiedCard(track: Track) {
        this.activePopupTrack = track
        this.popupExpiryTime = System.currentTimeMillis() + 3500L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth == 0 || imageHeight == 0) return

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val now = System.currentTimeMillis()

        // 1. Draw Bounding Boxes with status tags
        for (track in tracks) {
            val left = track.bbox.left * scaleX
            val top = track.bbox.top * scaleY
            val right = track.bbox.right * scaleX
            val bottom = track.bbox.bottom * scaleY

            val boxColor: Int
            val labelText: String

            when (track.recognitionState) {
                "MATCHED" -> {
                    val scorePct = if (track.confidence > 0f) " [${(track.confidence * 100).roundToInt()}%]" else " [VERIFIED]"
                    when (track.decision) {
                        "CHECK_OUT" -> {
                            boxColor = Color.parseColor("#D946EF") // Purple
                            labelText = "CHECK-OUT: ${track.assignedIdentity}$scorePct"
                        }
                        "RE_ENTRY" -> {
                            boxColor = Color.parseColor("#F59E0B") // Orange
                            labelText = "RE-ENTRY: ${track.assignedIdentity}$scorePct"
                        }
                        else -> {
                            boxColor = Color.parseColor("#10B981") // Green
                            labelText = "${track.assignedIdentity}$scorePct"
                        }
                    }
                }
                "NOT_RECOGNIZED" -> {
                    boxColor = Color.parseColor("#EF4444") // Red
                    labelText = "⚠️ NOT RECOGNIZED"
                }
                "SETTLING" -> {
                    boxColor = Color.parseColor("#A855F7") // Purple Settle
                    labelText = "HOLD STEADY..."
                }
                "RECOGNIZING" -> {
                    boxColor = Color.parseColor("#06B6D4") // Cyan
                    val attempt = if (track.evalAttempts > 0) " (${track.evalAttempts}/5)" else ""
                    labelText = "ANALYZING$attempt..."
                }
                else -> {
                    boxColor = Color.parseColor("#64748B") // Slate Gray
                    labelText = "APPROACH CAMERA"
                }
            }

            boxPaint.color = boxColor
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Label banner
            val textWidth = textPaint.measureText(labelText)
            val bannerH = 44f
            val bannerY = (top - bannerH).coerceAtLeast(0f)
            bannerPaint.color = boxColor
            canvas.drawRect(left, bannerY, left + textWidth + 20f, top, bannerPaint)
            canvas.drawText(labelText, left + 10f, top - 12f, textPaint)
        }

        // 2. Draw Diagnostics HUD (Top Left)
        val hudRect = RectF(16f, 16f, 540f, 180f)
        canvas.drawRoundRect(hudRect, 14f, 14f, hudPaint)
        hudBorderPaint.color = if (systemMode == "EXIT") Color.parseColor("#D946EF") else Color.parseColor("#10B981")
        canvas.drawRoundRect(hudRect, 14f, 14f, hudBorderPaint)

        val hudTextPaint = Paint(textPaint).apply { textSize = 24f }
        canvas.drawText("MODE: $systemMode | FPS: ${"%.1f".format(telemetry.fps)} | Tracks: ${tracks.size}", 32f, 60f, hudTextPaint)
        canvas.drawText("Detect: ${"%.1f".format(telemetry.detectMs)}ms | Track: ${"%.1f".format(telemetry.trackMs)}ms", 32f, 105f, hudTextPaint)
        canvas.drawText("End-to-End Latency: ${"%.1f".format(telemetry.e2eMs)}ms", 32f, 150f, hudTextPaint)

        // 3. Draw Verified ID Card Popup (Top Right)
        if (activePopupTrack != null && now < popupExpiryTime) {
            val popup = activePopupTrack!!
            val cardW = 540f
            val cardH = 170f
            val cardRect = RectF(width - cardW - 16f, 16f, width - 16f, cardH + 16f)

            val isUnknown = popup.employeeId == "UNKNOWN"
            val borderColor = if (isUnknown) Color.parseColor("#EF4444") else Color.parseColor("#10B981")

            canvas.drawRoundRect(cardRect, 14f, 14f, hudPaint)
            hudBorderPaint.color = borderColor
            canvas.drawRoundRect(cardRect, 14f, 14f, hudBorderPaint)

            val title = if (isUnknown) "UNKNOWN PERSON FLAGGED" else "OFFICIAL ID VERIFIED"
            hudTextPaint.color = borderColor
            canvas.drawText(title, cardRect.left + 20f, cardRect.top + 48f, hudTextPaint)

            hudTextPaint.color = Color.WHITE
            canvas.drawText("Name: ${popup.assignedIdentity ?: "N/A"}", cardRect.left + 20f, cardRect.top + 92f, hudTextPaint)

            val scoreStr = if (popup.confidence > 0f) "${(popup.confidence * 100).roundToInt()}%" else "VERIFIED"
            canvas.drawText("ID: ${popup.employeeId ?: "N/A"} ($scoreStr)", cardRect.left + 20f, cardRect.top + 136f, hudTextPaint)
        }
    }
}
