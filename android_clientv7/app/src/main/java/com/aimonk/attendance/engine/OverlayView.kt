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
        color = COLOR_HUD_BG
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // Pre-allocated drawing objects to guarantee ZERO allocations in onDraw
    private val hudRect = RectF(16f, 16f, 540f, 180f)
    private val cardRect = RectF()
    private val hudTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    companion object {
        private const val COLOR_CHECK_OUT = 0xFFD946EF.toInt()      // Purple
        private const val COLOR_RE_ENTRY = 0xFFF59E0B.toInt()       // Orange
        private const val COLOR_MATCHED = 0xFF10B981.toInt()        // Green
        private const val COLOR_NOT_RECOGNIZED = 0xFFEF4444.toInt() // Red
        private const val COLOR_SETTLING = 0xFFA855F7.toInt()       // Purple Settle
        private const val COLOR_RECOGNIZING = 0xFF06B6D4.toInt()    // Cyan
        private const val COLOR_DEFAULT = 0xFF64748B.toInt()        // Slate Gray
        private const val COLOR_HUD_BG = 0xE00A0F1D.toInt()
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
                            boxColor = COLOR_CHECK_OUT
                            labelText = "CHECK-OUT: ${track.assignedIdentity}$scorePct"
                        }
                        "RE_ENTRY" -> {
                            boxColor = COLOR_RE_ENTRY
                            labelText = "RE-ENTRY: ${track.assignedIdentity}$scorePct"
                        }
                        else -> {
                            boxColor = COLOR_MATCHED
                            labelText = "${track.assignedIdentity}$scorePct"
                        }
                    }
                }
                "NOT_RECOGNIZED" -> {
                    boxColor = COLOR_NOT_RECOGNIZED
                    labelText = "⚠️ NOT RECOGNIZED"
                }
                "SETTLING" -> {
                    boxColor = COLOR_SETTLING
                    labelText = "HOLD STEADY..."
                }
                "RECOGNIZING" -> {
                    boxColor = COLOR_RECOGNIZING
                    val attempt = if (track.evalAttempts > 0) " (${track.evalAttempts}/5)" else ""
                    labelText = "ANALYZING$attempt..."
                }
                else -> {
                    boxColor = COLOR_DEFAULT
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
        canvas.drawRoundRect(hudRect, 14f, 14f, hudPaint)
        hudBorderPaint.color = if (systemMode == "EXIT") COLOR_CHECK_OUT else COLOR_MATCHED
        canvas.drawRoundRect(hudRect, 14f, 14f, hudBorderPaint)

        hudTextPaint.color = Color.WHITE
        canvas.drawText("MODE: $systemMode | FPS: ${"%.1f".format(telemetry.fps)} | Tracks: ${tracks.size}", 32f, 60f, hudTextPaint)
        canvas.drawText("Detect: ${"%.1f".format(telemetry.detectMs)}ms | Track: ${"%.1f".format(telemetry.trackMs)}ms", 32f, 105f, hudTextPaint)
        canvas.drawText("End-to-End Latency: ${"%.1f".format(telemetry.e2eMs)}ms", 32f, 150f, hudTextPaint)

        // 3. Draw Verified ID Card Popup (Top Right)
        if (activePopupTrack != null && now < popupExpiryTime) {
            val popup = activePopupTrack!!
            val cardW = 540f
            val cardH = 170f
            cardRect.set(width - cardW - 16f, 16f, width - 16f, cardH + 16f)

            val isUnknown = popup.employeeId == "UNKNOWN"
            val borderColor = if (isUnknown) COLOR_NOT_RECOGNIZED else COLOR_MATCHED

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
