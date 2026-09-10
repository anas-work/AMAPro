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

    // Hairline bounding box
    private val boxHairlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    // High-tech corner bracket reticle
    private val boxCornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Label banner pill background & stroke
    private val bannerBgPaint = Paint().apply {
        color = COLOR_BANNER_BG
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bannerStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        isAntiAlias = true
    }

    private val statusDotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.02f
        isAntiAlias = true
    }

    private val hudPaint = Paint().apply {
        color = COLOR_HUD_BG
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    // Pre-allocated drawing objects to guarantee ZERO allocations in onDraw
    private val hudRect = RectF(16f, 16f, 540f, 180f)
    private val cardRect = RectF()
    private val bannerRect = RectF()
    private val hudTextPaint = Paint().apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }

    companion object {
        private const val COLOR_CHECK_OUT = 0xFF8B5CF6.toInt()      // Royal Violet
        private const val COLOR_RE_ENTRY = 0xFFF59E0B.toInt()       // Champagne Amber
        private const val COLOR_MATCHED = 0xFF10B981.toInt()        // Precision Emerald
        private const val COLOR_NOT_RECOGNIZED = 0xFFEF4444.toInt() // Deep Crimson
        private const val COLOR_SETTLING = 0xFF38BDF8.toInt()       // Ice Cyan
        private const val COLOR_RECOGNIZING = 0xFF06B6D4.toInt()    // Electric Cyan
        private const val COLOR_DEFAULT = 0xFF64748B.toInt()        // Slate Gray
        private const val COLOR_HUD_BG = 0xE60A101D.toInt()         // Obsidian Frosted Glass
        private const val COLOR_BANNER_BG = 0xEE0B1220.toInt()      // Translucent Banner Glass
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

        // Uniform aspect-ratio scaling matching CameraX PreviewView.ScaleType.FILL_CENTER
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f
        val now = System.currentTimeMillis()

        // 1. Draw Bounding Boxes with Architectural Reticle Corners
        for (track in tracks) {
            val left = track.bbox.left * scale + offsetX
            val top = track.bbox.top * scale + offsetY
            val right = track.bbox.right * scale + offsetX
            val bottom = track.bbox.bottom * scale + offsetY

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
                    labelText = "UNRECOGNIZED"
                }
                "SETTLING" -> {
                    boxColor = COLOR_SETTLING
                    labelText = "HOLD STEADY"
                }
                "RECOGNIZING" -> {
                    boxColor = COLOR_RECOGNIZING
                    val attempt = if (track.evalAttempts > 0) " (${track.evalAttempts}/5)" else ""
                    labelText = "ANALYZING$attempt"
                }
                else -> {
                    boxColor = COLOR_DEFAULT
                    labelText = "POSITION FACE"
                }
            }

            // Hairline base bounding box
            boxHairlinePaint.color = boxColor
            boxHairlinePaint.alpha = 70
            canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, boxHairlinePaint)

            // Reticle corner brackets
            val cornerLen = minOf(24f, (right - left) / 4f, (bottom - top) / 4f)
            boxCornerPaint.color = boxColor

            // Top-Left Corner
            canvas.drawLine(left, top, left + cornerLen, top, boxCornerPaint)
            canvas.drawLine(left, top, left, top + cornerLen, boxCornerPaint)
            // Top-Right Corner
            canvas.drawLine(right, top, right - cornerLen, top, boxCornerPaint)
            canvas.drawLine(right, top, right, top + cornerLen, boxCornerPaint)
            // Bottom-Left Corner
            canvas.drawLine(left, bottom, left + cornerLen, bottom, boxCornerPaint)
            canvas.drawLine(left, bottom, left, bottom - cornerLen, boxCornerPaint)
            // Bottom-Right Corner
            canvas.drawLine(right, bottom, right - cornerLen, bottom, boxCornerPaint)
            canvas.drawLine(right, bottom, right, bottom - cornerLen, boxCornerPaint)

            // Executive Label Banner Pill
            val textWidth = textPaint.measureText(labelText)
            val bannerH = 38f
            val bannerY = if (top >= bannerH + 8f) (top - bannerH - 4f) else (bottom + 8f)
            val totalPillW = textWidth + 38f
            val bannerLeft = left.coerceIn(8f, (width - totalPillW - 8f).coerceAtLeast(8f))
            val bannerRight = (bannerLeft + totalPillW).coerceAtMost(width - 8f)

            bannerRect.set(bannerLeft, bannerY, bannerRight, bannerY + bannerH)

            // Frosted pill background & hairline accent border
            canvas.drawRoundRect(bannerRect, 10f, 10f, bannerBgPaint)
            bannerStrokePaint.color = boxColor
            canvas.drawRoundRect(bannerRect, 10f, 10f, bannerStrokePaint)

            // Status indicator dot
            statusDotPaint.color = boxColor
            canvas.drawCircle(bannerLeft + 14f, bannerY + bannerH / 2f, 4f, statusDotPaint)

            // Label text
            canvas.drawText(labelText, bannerLeft + 24f, bannerY + 26f, textPaint)
        }

        // 2. Executive Diagnostics HUD (Top Left)
        val line1 = "SYS: $systemMode | FPS: ${"%.1f".format(telemetry.fps)}"
        val line2 = "DET: ${"%.1f".format(telemetry.detectMs)}ms | TRK: ${"%.1f".format(telemetry.trackMs)}ms"
        val line3 = "E2E: ${"%.1f".format(telemetry.e2eMs)}ms | TRK-CNT: ${tracks.size}"

        val maxTextW = maxOf(
            hudTextPaint.measureText(line1),
            hudTextPaint.measureText(line2),
            hudTextPaint.measureText(line3)
        )
        val hudW = (maxTextW + 36f).coerceAtMost(width - 32f)
        hudRect.set(16f, 16f, 16f + hudW, 164f)

        canvas.drawRoundRect(hudRect, 12f, 12f, hudPaint)
        hudBorderPaint.color = if (systemMode == "EXIT") COLOR_CHECK_OUT else COLOR_MATCHED
        canvas.drawRoundRect(hudRect, 12f, 12f, hudBorderPaint)

        hudTextPaint.color = Color.parseColor("#F8FAFC")
        canvas.drawText(line1, 32f, 54f, hudTextPaint)
        canvas.drawText(line2, 32f, 96f, hudTextPaint)
        canvas.drawText(line3, 32f, 138f, hudTextPaint)

        // 3. Draw Verified ID Card Popup (Executive Glass Badge)
        if (activePopupTrack != null && now < popupExpiryTime) {
            val popup = activePopupTrack!!
            val popupW = minOf(460f, width - 32f)
            if (width >= 1000f) {
                cardRect.set(width - popupW - 16f, 16f, width - 16f, 164f)
            } else {
                cardRect.set(16f, hudRect.bottom + 12f, 16f + popupW, hudRect.bottom + 12f + 150f)
            }

            val isUnknown = popup.employeeId == "UNKNOWN"
            val borderColor = if (isUnknown) COLOR_NOT_RECOGNIZED else COLOR_MATCHED

            canvas.drawRoundRect(cardRect, 12f, 12f, hudPaint)
            hudBorderPaint.color = borderColor
            canvas.drawRoundRect(cardRect, 12f, 12f, hudBorderPaint)

            val title = if (isUnknown) "SECURITY ALERT: UNRECOGNIZED" else "IDENTITY VERIFIED"
            hudTextPaint.color = borderColor
            canvas.drawText(title, cardRect.left + 20f, cardRect.top + 42f, hudTextPaint)

            hudTextPaint.color = Color.WHITE
            canvas.drawText("Name: ${popup.assignedIdentity ?: "N/A"}", cardRect.left + 20f, cardRect.top + 82f, hudTextPaint)

            val scoreStr = if (popup.confidence > 0f) "${(popup.confidence * 100).roundToInt()}% MATCH" else "VERIFIED"
            canvas.drawText("ID: ${popup.employeeId ?: "N/A"} ($scoreStr)", cardRect.left + 20f, cardRect.top + 122f, hudTextPaint)
        }
    }
}
