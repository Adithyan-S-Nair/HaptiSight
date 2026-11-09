package com.example.haptisight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import java.util.Locale

/**
 * Data class representing a detected obstacle bounding box in normalized screen coordinates (0.0..1.0).
 */
data class ObstacleBox(
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val color: Int,
    val label: String
)

/**
 * Encapsulates detection results passed to OverlayView.
 */
data class DetectionResult(
    val boxes: List<ObstacleBox> = emptyList(),
    val leftActive: Boolean = false,
    val centerActive: Boolean = false,
    val rightActive: Boolean = false,
    val motorPatternCode: String = "000N",
    val latencyMs: Float = 0.0f
)

/**
 * Custom overlay view displaying the 85% upper-body ROI boundary, tight dynamic bounding boxes,
 * and the Virtual Glove Simulator HUD featuring 3 live motor indicators and packet telemetry.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boxes: List<ObstacleBox> = emptyList()
    private var leftActive: Boolean = false
    private var centerActive: Boolean = false
    private var rightActive: Boolean = false
    private var motorPatternCode: String = "000N"
    private var latencyMs: Float = 0.0f

    private val density = context.resources.displayMetrics.density

    // Paint for the 85% ROI dashed line
    private val roiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#8000E5FF".toColorInt() // Semi-transparent cyan/white
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f * density, 8f * density), 0f)
    }

    // Paint for the ROI label
    private val roiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CCFFFFFF".toColorInt()
        textSize = 12f * density
        isFakeBoldText = true
    }

    // Paint for bounding box borders
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
    }

    // Paint for semi-transparent bounding box background fill
    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Paint for distance label background pill
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 15, 15, 15) // Dark semi-transparent pill
        style = Paint.Style.FILL
    }

    // Paint for distance label text inside pill
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
    }

    // Virtual Glove Simulator HUD Paints
    private val hudCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CC121212".toColorInt() // Semi-transparent dark card
        style = Paint.Style.FILL
    }
    private val hudCardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#33FFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val motorActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FF1744".toColorInt() // Bright coral red when active (<= 1000 mm)
        style = Paint.Style.FILL
    }
    private val motorInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#2E2E2E".toColorInt() // Dim grey when OFF (> 1000 mm)
        style = Paint.Style.FILL
    }
    private val motorActiveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val motorInactiveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#555555".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val motorLetterActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val motorLetterInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#777777".toColorInt()
        textSize = 11f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val motorDescActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 9.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val motorDescInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#888888".toColorInt()
        textSize = 9.5f * density
        isFakeBoldText = false
        textAlign = Paint.Align.CENTER
    }

    private val telemetryPillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#1F1F1F".toColorInt()
        style = Paint.Style.FILL
    }
    private val telemetryPillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#44FFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val telemetryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#00E5FF".toColorInt() // High-contrast bright cyan telemetry
        textSize = 10.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val textBounds = Rect()
    private val boxRect = RectF()
    private val pillRect = RectF()
    private val hudCardRect = RectF()
    private val telemetryPillRect = RectF()

    init {
        // Ensure onDraw gets called on this custom View
        setWillNotDraw(false)
    }

    /**
     * Update detection boxes and live glove simulator telemetry.
     * Must be called on Main UI Thread.
     */
    fun setDetectionResult(
        boxes: List<ObstacleBox>,
        leftActive: Boolean = false,
        centerActive: Boolean = false,
        rightActive: Boolean = false,
        motorPatternCode: String = "000N",
        latencyMs: Float = 0.0f
    ) {
        this.boxes = boxes
        this.leftActive = leftActive
        this.centerActive = centerActive
        this.rightActive = rightActive
        this.motorPatternCode = motorPatternCode
        this.latencyMs = latencyMs
        invalidate()
    }

    /**
     * Overload to update detection boxes using DetectionResult container.
     * Must be called on Main UI Thread.
     */
    fun setDetectionResult(result: DetectionResult) {
        this.boxes = result.boxes
        this.leftActive = result.leftActive
        this.centerActive = result.centerActive
        this.rightActive = result.rightActive
        this.motorPatternCode = result.motorPatternCode
        this.latencyMs = result.latencyMs
        invalidate()
    }

    /**
     * Clear all detection boxes and reset motor simulator telemetry.
     * Must be called on Main UI Thread.
     */
    fun clear() {
        this.boxes = emptyList()
        this.leftActive = false
        this.centerActive = false
        this.rightActive = false
        this.motorPatternCode = "000N"
        this.latencyMs = 0.0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw 75% Detection ROI Cutoff Line (Top 75% scan boundary excluding floor)
        val roiY = h * 0.75f
        canvas.drawLine(0f, roiY, w, roiY, roiLinePaint)
        canvas.drawText("75% DETECTION ROI", 16f * density, roiY - 8f * density, roiTextPaint)

        // 2. Draw Style B Dynamic Bounding Boxes across full view
        for (box in boxes) {
            val left = (box.normLeft * w).coerceIn(0f, w)
            val right = (box.normRight * w).coerceIn(0f, w)
            val top = (box.normTop * h).coerceIn(0f, h)
            val bottom = (box.normBottom * h).coerceIn(0f, h)

            if (right <= left || bottom <= top) continue

            boxRect.set(left, top, right, bottom)

            // Draw box fill (subtle tint)
            boxFillPaint.color = (box.color and 0x00FFFFFF) or 0x26000000
            canvas.drawRoundRect(boxRect, 6f * density, 6f * density, boxFillPaint)

            // Draw box border stroke
            boxPaint.color = box.color
            canvas.drawRoundRect(boxRect, 6f * density, 6f * density, boxPaint)

            // 3. Draw Clamped Distance Pill
            val label = box.label
            labelTextPaint.getTextBounds(label, 0, label.length, textBounds)
            val textWidth = labelTextPaint.measureText(label)
            val textHeight = textBounds.height().toFloat()

            val hPad = 8f * density
            val vPad = 5f * density
            val pillWidth = textWidth + hPad * 2f
            val pillHeight = textHeight + vPad * 2f

            val pillLeft: Float
            val pillTop: Float

            // If boxTop < 60f, draw inside top-left corner
            val thresholdY = 60f * density
            if (top < thresholdY) {
                pillLeft = (left + 6f * density).coerceAtLeast(6f * density)
                    .coerceAtMost(w - pillWidth - 6f * density)
                pillTop = top + 6f * density
            } else {
                pillLeft = left.coerceAtLeast(6f * density)
                    .coerceAtMost(w - pillWidth - 6f * density)
                pillTop = top - pillHeight - 4f * density
            }

            pillRect.set(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)
            canvas.drawRoundRect(pillRect, 6f * density, 6f * density, pillPaint)

            // Draw label text inside pill
            val textX = pillLeft + hPad
            val textY = pillTop + vPad + textHeight - (textBounds.bottom / 2f)
            canvas.drawText(label, textX, textY, labelTextPaint)
        }

        // 3. Draw Virtual Glove Simulator HUD (above Mute Button)
        drawGloveSimulatorHud(canvas, w, h)
    }

    /**
     * Renders the on-screen Virtual Glove Simulator HUD featuring:
     * - 3 circular motor indicators labeled THUMB (L), INDEX (C), and RIGHT (R)
     * - Bright coral-red active highlight when zone <= 1000 mm (dim grey when OFF)
     * - Live telemetry pill reading "TX: [motorPatternCode] | Latency: X.X ms"
     */
    private fun drawGloveSimulatorHud(canvas: Canvas, w: Float, h: Float) {
        val hudBottom = h - (94f * density)
        val hudWidth = 246f * density
        val hudHeight = 72f * density
        val hudTop = hudBottom - hudHeight
        val hudLeft = (w - hudWidth) / 2f
        val hudRight = hudLeft + hudWidth

        hudCardRect.set(hudLeft, hudTop, hudRight, hudBottom)
        canvas.drawRoundRect(hudCardRect, 14f * density, 14f * density, hudCardPaint)
        canvas.drawRoundRect(hudCardRect, 14f * density, 14f * density, hudCardBorderPaint)

        // 3 Motor Indicators: THUMB (L), INDEX (C), RIGHT (R)
        val centerX = w / 2f
        val spacing = 68f * density
        val circleCenterY = hudTop + 18f * density
        val radius = 12.5f * density

        val motors = listOf(
            Triple(centerX - spacing, leftActive, Pair("L", "THUMB (L)")),
            Triple(centerX, centerActive, Pair("C", "INDEX (C)")),
            Triple(centerX + spacing, rightActive, Pair("R", "RIGHT (R)"))
        )

        for ((cx, active, labels) in motors) {
            val (letter, desc) = labels

            val fillPaint = if (active) motorActivePaint else motorInactivePaint
            val strokePaint = if (active) motorActiveStrokePaint else motorInactiveStrokePaint
            canvas.drawCircle(cx, circleCenterY, radius, fillPaint)
            canvas.drawCircle(cx, circleCenterY, radius, strokePaint)

            val letterPaint = if (active) motorLetterActivePaint else motorLetterInactivePaint
            val letterY = circleCenterY + (letterPaint.textSize / 3f)
            canvas.drawText(letter, cx, letterY, letterPaint)

            val descPaint = if (active) motorDescActivePaint else motorDescInactivePaint
            val descY = circleCenterY + radius + 11f * density
            canvas.drawText(desc, cx, descY, descPaint)
        }

        // Telemetry Pill: "TX: [motorPatternCode] | Latency: X.X ms"
        val telemetryText = String.format(Locale.US, "TX: %s | Latency: %.1f ms", motorPatternCode, latencyMs)
        val pillTop = hudTop + 45f * density
        val pillBottom = hudTop + 66f * density
        val pillWidth = telemetryTextPaint.measureText(telemetryText) + 18f * density
        val pillLeft = (w - pillWidth) / 2f
        val pillRight = pillLeft + pillWidth

        telemetryPillRect.set(pillLeft, pillTop, pillRight, pillBottom)
        canvas.drawRoundRect(telemetryPillRect, 9f * density, 9f * density, telemetryPillBgPaint)
        canvas.drawRoundRect(telemetryPillRect, 9f * density, 9f * density, telemetryPillBorderPaint)

        val pillTextY = pillTop + (pillBottom - pillTop) / 2f + (telemetryTextPaint.textSize / 3f)
        canvas.drawText(telemetryText, centerX, pillTextY, telemetryTextPaint)
    }
}
