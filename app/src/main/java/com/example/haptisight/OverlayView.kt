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
    val boxes: List<ObstacleBox> = emptyList()
)

/**
 * Custom overlay view displaying the 75% upper-body ROI boundary and tight dynamic bounding boxes
 * with distance pills according to Style B specification.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boxes: List<ObstacleBox> = emptyList()

    private val density = context.resources.displayMetrics.density

    // Paint for the 75% ROI dashed line
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

    private val textBounds = Rect()
    private val boxRect = RectF()
    private val pillRect = RectF()

    init {
        // Ensure onDraw gets called on this custom View
        setWillNotDraw(false)
    }

    /**
     * Update detection boxes using a list of ObstacleBox.
     * Must be called on Main UI Thread.
     */
    fun setDetectionResult(boxes: List<ObstacleBox>) {
        this.boxes = boxes
        invalidate()
    }

    /**
     * Overload to update detection boxes using DetectionResult container.
     * Must be called on Main UI Thread.
     */
    fun setDetectionResult(result: DetectionResult) {
        this.boxes = result.boxes
        invalidate()
    }

    /**
     * Clear all detection boxes and redraw immediately.
     * Must be called on Main UI Thread.
     */
    fun clear() {
        this.boxes = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw 85% Detection ROI Cutoff Line (Top 85% scan boundary)
        val roiY = h * 0.85f
        canvas.drawLine(0f, roiY, w, roiY, roiLinePaint)
        canvas.drawText("85% DETECTION ROI", 16f * density, roiY - 8f * density, roiTextPaint)

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

            // Section 4.4 Rule: If boxTop < 60f, draw inside top-left corner
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
    }
}

