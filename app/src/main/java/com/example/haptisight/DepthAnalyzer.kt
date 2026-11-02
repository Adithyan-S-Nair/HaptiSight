package com.example.haptisight

import android.media.Image
import android.util.Log
import androidx.core.graphics.toColorInt
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Locale

/**
 * Bounding zone data used for tight bounding box calculation.
 */
data class ZoneBox(
    val dMin: Int?,
    var minNormX: Float = 0.0f,
    var minNormY: Float = 0.0f,
    var maxNormX: Float = 0.0f,
    var maxNormY: Float = 0.0f,
    var hasPixels: Boolean = false
)

/**
 * Result data class containing all analytical data required for UI rendering and haptic feedback.
 */
data class DepthAnalysisResult(
    val boxes: List<ObstacleBox>,
    val leftDMin: Int?,
    val centerDMin: Int?,
    val rightDMin: Int?,
    val isRedBarrier: Boolean,
    val mergedRedDMin: Int?,
    val isDoorway: Boolean,
    val yellowDMin: Int?,
    val isSafePath: Boolean,
    val totalRoiPixels: Int,
    val validRoiPixels: Int,
    val lowConfidence: Boolean,
    val bannerText: String
)

/**
 * Per-Zone Hybrid Depth Scanner & Low-Light Dynamic Gain Engine.
 * Independently analyzes each horizontal zone using confidence-verified raw depth points
 * with automatic fallback to dense smoothed depth for smooth painted walls and low-light objects.
 */
class DepthAnalyzer {

    companion object {
        val COLOR_RED: Int = "#FF1744".toColorInt()
        val COLOR_YELLOW: Int = "#FFD600".toColorInt()
        val COLOR_GREEN: Int = "#00E676".toColorInt()

        private const val MIN_DEPTH_MM = 200 // Expanded search window
        private const val MAX_DEPTH_MM = 5000 // Expanded search window
        private const val MAX_WALL_DEPTH_MM = 4000 // Safe surface boundary for walls
        private const val BASE_RAW_CONFIDENCE = 10 // Minimum confidence threshold for raw depth
        private const val BLOCKED_THRESHOLD_MM = 1200 // 1.20 meters
        private const val ADJACENCY_TOLERANCE_MM = 250 // within 250 mm for Red merge
        private const val DEPTH_CLUSTER_TOLERANCE_MM = 220 // D_min to D_min + 220 mm
        private const val MIN_RAW_PIXELS_PER_ZONE = 8 // At stride 3
        private const val STRIDE = 3

        // Active scanning ROI: normY from 0.08f to 0.85f (ignoring bottom 15% floor/cane space)
        private const val ROI_Y_MIN = 0.08f
        private const val ROI_Y_MAX = 0.85f
        private const val MAX_NORM_Y_CLAMP = 0.85f

        // Minimum box dimensions for clean visual representation
        private const val MIN_BOX_WIDTH = 0.12f
        private const val MIN_BOX_HEIGHT = 0.12f
    }

    // Reusable raw depth buffers
    private var leftDepths = IntArray(30000)
    private var centerDepths = IntArray(30000)
    private var rightDepths = IntArray(30000)

    // Reusable dense depth fallback buffers per zone
    private var leftDenseDepths = IntArray(30000)
    private var centerDenseDepths = IntArray(30000)
    private var rightDenseDepths = IntArray(30000)

    // Reusable coordinate buffers for percentile clustering
    private var leftXs = FloatArray(15000)
    private var leftYs = FloatArray(15000)
    private var centerXs = FloatArray(15000)
    private var centerYs = FloatArray(15000)
    private var rightXs = FloatArray(15000)
    private var rightYs = FloatArray(15000)

    fun reset() {
        // Stateless between frames
    }

    private fun ensureCapacity(totalPixels: Int) {
        if (leftDepths.size < totalPixels) {
            leftDepths = IntArray(totalPixels)
            centerDepths = IntArray(totalPixels)
            rightDepths = IntArray(totalPixels)
            leftDenseDepths = IntArray(totalPixels)
            centerDenseDepths = IntArray(totalPixels)
            rightDenseDepths = IntArray(totalPixels)
        }
        val coordCap = totalPixels / 2
        if (leftXs.size < coordCap) {
            leftXs = FloatArray(coordCap)
            leftYs = FloatArray(coordCap)
            centerXs = FloatArray(coordCap)
            centerYs = FloatArray(coordCap)
            rightXs = FloatArray(coordCap)
            rightYs = FloatArray(coordCap)
        }
    }

    /**
     * Analyzes current ARCore frame using Per-Zone Hybrid Depth Fusion.
     * Independently acquires Raw Depth (+ Confidence) and Dense Depth in separate try/catch blocks.
     * All non-null Image objects are strictly closed in finally block.
     */
    fun analyze(frame: Frame): DepthAnalysisResult {
        var rawDepthImage: Image? = null
        var confImage: Image? = null
        var denseDepthImage: Image? = null

        try {
            // Independent acquisition of Raw Depth & Confidence
            try {
                rawDepthImage = frame.acquireRawDepthImage16Bits()
                confImage = frame.acquireRawDepthConfidenceImage()
            } catch (_: NotYetAvailableException) {
                // Expected during initial camera warmup
            } catch (e: Exception) {
                Log.e("HaptiSight", "Raw depth acquire error", e)
            }

            // Independent acquisition of Dense Depth
            try {
                denseDepthImage = frame.acquireDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                // Expected during initial camera warmup
            } catch (e: Exception) {
                Log.e("HaptiSight", "Dense depth acquire error", e)
            }

            return processPerZoneHybrid(rawDepthImage, confImage, denseDepthImage)
        } finally {
            rawDepthImage?.close()
            confImage?.close()
            denseDepthImage?.close()
        }
    }

    private fun processPerZoneHybrid(
        rawDepthImage: Image?,
        confImage: Image?,
        denseDepthImage: Image?
    ): DepthAnalysisResult {
        var totalRoiPixels = 0
        var validRoiPixels = 0

        var leftRawCount = 0
        var centerRawCount = 0
        var rightRawCount = 0

        var rawW = 0
        var rawH = 0

        var rawDepthBuffer: ByteBuffer? = null
        var rawDepthRowStride = 0
        var rawDepthPixelStride = 0

        var confBuffer: ByteBuffer? = null
        var confRowStride = 0
        var confPixelStride = 0

        val hasRaw = (rawDepthImage != null && confImage != null &&
                rawDepthImage.planes.isNotEmpty() && confImage.planes.isNotEmpty())

        if (hasRaw) {
            rawW = rawDepthImage!!.width
            rawH = rawDepthImage.height
            ensureCapacity(rawW * rawH)

            val depthPlane = rawDepthImage.planes[0]
            rawDepthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            rawDepthRowStride = depthPlane.rowStride
            rawDepthPixelStride = depthPlane.pixelStride

            val confPlane = confImage!!.planes[0]
            confBuffer = confPlane.buffer
            confRowStride = confPlane.rowStride
            confPixelStride = confPlane.pixelStride

            // Low-Light Illumination Boost: Compute average confidence across valid depth points
            var totalConfSum = 0L
            var validConfPoints = 0

            for (row in 0 until rawH step STRIDE) {
                val confRowOffset = row * confRowStride
                val depthRowOffset = row * rawDepthRowStride

                for (col in 0 until rawW step STRIDE) {
                    val normY = col.toFloat() / rawW.toFloat()
                    if (normY < ROI_Y_MIN || normY > ROI_Y_MAX) continue

                    val depthIndex = depthRowOffset + col * rawDepthPixelStride
                    val depthMm = rawDepthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                    if (depthMm < MIN_DEPTH_MM || depthMm > MAX_DEPTH_MM) continue

                    val confIndex = confRowOffset + col * confPixelStride
                    val conf = confBuffer.get(confIndex).toInt() and 0xFF

                    totalConfSum += conf
                    validConfPoints++
                }
            }

            val avgConf = if (validConfPoints > 0) (totalConfSum / validConfPoints).toInt() else 0
            val isLowLight = (validConfPoints == 0 || avgConf < 30)

            // In low-light rooms (conf < 30), accept points down to conf >= 0 (spatial cluster checked later)
            val effectiveMinConf = if (isLowLight) 0 else BASE_RAW_CONFIDENCE

            // Accumulate valid raw depth points per zone
            for (row in 0 until rawH step STRIDE) {
                val depthRowOffset = row * rawDepthRowStride
                val confRowOffset = row * confRowStride
                val normX = 1.0f - (row.toFloat() / rawH.toFloat())

                for (col in 0 until rawW step STRIDE) {
                    val normY = col.toFloat() / rawW.toFloat()

                    val inRoi = (normY in ROI_Y_MIN..ROI_Y_MAX)
                    if (inRoi) {
                        totalRoiPixels++
                    }

                    val confIndex = confRowOffset + col * confPixelStride
                    val conf = confBuffer.get(confIndex).toInt() and 0xFF
                    if (conf < effectiveMinConf) continue

                    val depthIndex = depthRowOffset + col * rawDepthPixelStride
                    val depthMm = rawDepthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                    if (depthMm < MIN_DEPTH_MM || depthMm > MAX_DEPTH_MM) continue

                    if (inRoi) {
                        validRoiPixels++
                        when {
                            normX < 0.333f -> leftDepths[leftRawCount++] = depthMm
                            normX < 0.666f -> centerDepths[centerRawCount++] = depthMm
                            else -> rightDepths[rightRawCount++] = depthMm
                        }
                    }
                }
            }
        }

        // Per-Zone Independent Evaluation: Case A (Raw Depth) or Case B (Dense Depth Fallback)
        val leftZone = evaluateZone(
            0, leftDepths, leftRawCount, rawDepthBuffer, rawDepthRowStride, rawDepthPixelStride,
            confBuffer, confRowStride, confPixelStride, rawW, rawH, denseDepthImage, leftDenseDepths, leftXs, leftYs
        )
        val centerZone = evaluateZone(
            1, centerDepths, centerRawCount, rawDepthBuffer, rawDepthRowStride, rawDepthPixelStride,
            confBuffer, confRowStride, confPixelStride, rawW, rawH, denseDepthImage, centerDenseDepths, centerXs, centerYs
        )
        val rightZone = evaluateZone(
            2, rightDepths, rightRawCount, rawDepthBuffer, rawDepthRowStride, rawDepthPixelStride,
            confBuffer, confRowStride, confPixelStride, rawW, rawH, denseDepthImage, rightDenseDepths, rightXs, rightYs
        )

        val leftDMin = leftZone.dMin
        val centerDMin = centerZone.dMin
        val rightDMin = rightZone.dMin

        val leftBlocked = leftZone.hasPixels && leftDMin != null && leftDMin <= BLOCKED_THRESHOLD_MM
        val centerBlocked = centerZone.hasPixels && centerDMin != null && centerDMin <= BLOCKED_THRESHOLD_MM
        val rightBlocked = rightZone.hasPixels && rightDMin != null && rightDMin <= BLOCKED_THRESHOLD_MM

        // 3-Zone Adjacency Rule: Two or three adjacent zones with D_min <= 1200 mm within 250 mm of each other
        val allThreeMerge = leftBlocked && centerBlocked && rightBlocked &&
                Math.abs(leftDMin!! - centerDMin!!) <= ADJACENCY_TOLERANCE_MM &&
                Math.abs(rightDMin!! - centerDMin!!) <= ADJACENCY_TOLERANCE_MM

        val leftCenterMerge = leftBlocked && centerBlocked &&
                Math.abs(leftDMin!! - centerDMin!!) <= ADJACENCY_TOLERANCE_MM

        val rightCenterMerge = rightBlocked && centerBlocked &&
                Math.abs(rightDMin!! - centerDMin!!) <= ADJACENCY_TOLERANCE_MM

        val isRedBarrier = allThreeMerge || leftCenterMerge || rightCenterMerge

        val boxes = mutableListOf<ObstacleBox>()
        var mergedRedDMin: Int? = null
        var yellowDMin: Int? = null

        if (allThreeMerge) {
            val minX = minOf(leftZone.minNormX, centerZone.minNormX, rightZone.minNormX)
            val maxX = maxOf(leftZone.maxNormX, centerZone.maxNormX, rightZone.maxNormX)
            val minY = minOf(leftZone.minNormY, centerZone.minNormY, rightZone.minNormY)
            val maxY = maxOf(leftZone.maxNormY, centerZone.maxNormY, rightZone.maxNormY)
            val dMerged = minOf(leftDMin!!, centerDMin!!, rightDMin!!)
            mergedRedDMin = dMerged
            boxes.add(
                ObstacleBox(
                    normLeft = minX,
                    normTop = minY,
                    normRight = maxX,
                    normBottom = maxY.coerceAtMost(MAX_NORM_Y_CLAMP),
                    color = COLOR_RED,
                    label = String.format(Locale.US, "WIDE BARRIER: %.2f m", dMerged / 1000.0f)
                )
            )
        } else if (isRedBarrier) {
            var minX = centerZone.minNormX
            var minY = centerZone.minNormY
            var maxX = centerZone.maxNormX
            var maxY = centerZone.maxNormY
            var dMerged = centerDMin!!

            if (leftCenterMerge) {
                minX = Math.min(minX, leftZone.minNormX)
                minY = Math.min(minY, leftZone.minNormY)
                maxX = Math.max(maxX, leftZone.maxNormX)
                maxY = Math.max(maxY, leftZone.maxNormY)
                dMerged = Math.min(dMerged, leftDMin!!)
            }
            if (rightCenterMerge) {
                minX = Math.min(minX, rightZone.minNormX)
                minY = Math.min(minY, rightZone.minNormY)
                maxX = Math.max(maxX, rightZone.maxNormX)
                maxY = Math.max(maxY, rightZone.maxNormY)
                dMerged = Math.min(dMerged, rightDMin!!)
            }

            mergedRedDMin = dMerged
            boxes.add(
                ObstacleBox(
                    normLeft = minX,
                    normTop = minY,
                    normRight = maxX,
                    normBottom = maxY.coerceAtMost(MAX_NORM_Y_CLAMP),
                    color = COLOR_RED,
                    label = String.format(Locale.US, "WIDE BARRIER: %.2f m", dMerged / 1000.0f)
                )
            )

            if (!leftCenterMerge && leftZone.hasPixels) {
                createNonMergedBox(leftZone, leftDMin, leftBlocked, boxes)?.let {
                    if (leftBlocked) yellowDMin = minDMin(yellowDMin, leftDMin)
                }
            }
            if (!rightCenterMerge && rightZone.hasPixels) {
                createNonMergedBox(rightZone, rightDMin, rightBlocked, boxes)?.let {
                    if (rightBlocked) yellowDMin = minDMin(yellowDMin, rightDMin)
                }
            }
        } else {
            // Draw individual Green or Yellow boxes for any detected zone
            if (leftZone.hasPixels) {
                createNonMergedBox(leftZone, leftDMin, leftBlocked, boxes)?.let {
                    if (leftBlocked) yellowDMin = minDMin(yellowDMin, leftDMin)
                }
            }
            if (centerZone.hasPixels) {
                createNonMergedBox(centerZone, centerDMin, centerBlocked, boxes)?.let {
                    if (centerBlocked) yellowDMin = minDMin(yellowDMin, centerDMin)
                }
            }
            if (rightZone.hasPixels) {
                createNonMergedBox(rightZone, rightDMin, rightBlocked, boxes)?.let {
                    if (rightBlocked) yellowDMin = minDMin(yellowDMin, rightDMin)
                }
            }
        }

        val hasObstacle = isRedBarrier || yellowDMin != null
        val hasAnyBoxes = boxes.isNotEmpty()
        val lowConfidence = !hasAnyBoxes && totalRoiPixels > 0 && ((validRoiPixels.toFloat() / totalRoiPixels.toFloat()) < 0.03f)
        val isSafePath = !hasObstacle

        val bannerText = when {
            isRedBarrier -> "🔴 WIDE BARRIER - LONG BUZZ"
            yellowDMin != null -> "🟡 SINGLE OBJECT - SHORT BUZZ"
            lowConfidence -> "⚠️ LOW DEPTH CONFIDENCE"
            else -> "🟢 SAFE PATH"
        }

        return DepthAnalysisResult(
            boxes = boxes,
            leftDMin = leftDMin,
            centerDMin = centerDMin,
            rightDMin = rightDMin,
            isRedBarrier = isRedBarrier,
            mergedRedDMin = mergedRedDMin,
            isDoorway = false,
            yellowDMin = yellowDMin,
            isSafePath = isSafePath,
            totalRoiPixels = totalRoiPixels,
            validRoiPixels = validRoiPixels,
            lowConfidence = lowConfidence,
            bannerText = bannerText
        )
    }

    /**
     * Evaluates a single zone independently:
     * - Case A (Rich Raw Depth): If valid raw depth pixels >= 8, compute D_min and tight bounding box from raw depth.
     * - Case B (Smooth Wall / Low-Light Fallback): If raw depth < 8, scan dense depth in region (0.15f..0.80f normY,
     *   normX inset by 0.02f). If p15 in 200..4000 mm and (p75 - p15) <= 600 mm, accept p15 and draw clean bounding box.
     */
    private fun evaluateZone(
        zoneIndex: Int,
        rawDepths: IntArray,
        rawCount: Int,
        rawDepthBuffer: ByteBuffer?,
        rawDepthRowStride: Int,
        rawDepthPixelStride: Int,
        confBuffer: ByteBuffer?,
        confRowStride: Int,
        confPixelStride: Int,
        rawW: Int,
        rawH: Int,
        denseDepthImage: Image?,
        denseDepths: IntArray,
        matchingXs: FloatArray,
        matchingYs: FloatArray
    ): ZoneBox {
        val defaultMinX = when (zoneIndex) {
            0 -> 0.00f
            1 -> 0.333f
            else -> 0.666f
        }
        val defaultMaxX = when (zoneIndex) {
            0 -> 0.333f
            1 -> 0.666f
            else -> 1.00f
        }

        // Case A: Rich Raw Depth Zone (>= 8 valid raw depth pixels)
        if (rawCount >= MIN_RAW_PIXELS_PER_ZONE && rawDepthBuffer != null && confBuffer != null) {
            Arrays.sort(rawDepths, 0, rawCount)
            val dMin = rawDepths[(rawCount * 0.15f).toInt().coerceIn(0, rawCount - 1)]

            // Collect matching raw depth pixels within dMin..dMin + 220 mm
            var matchCount = 0
            for (row in 0 until rawH step STRIDE) {
                val depthRowOffset = row * rawDepthRowStride
                val normX = 1.0f - (row.toFloat() / rawH.toFloat())

                if (normX < defaultMinX || normX >= defaultMaxX) continue

                for (col in 0 until rawW step STRIDE) {
                    val normY = col.toFloat() / rawW.toFloat()
                    if (normY < ROI_Y_MIN || normY > MAX_NORM_Y_CLAMP) continue

                    val depthIndex = depthRowOffset + col * rawDepthPixelStride
                    val depthMm = rawDepthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                    if (depthMm in dMin..(dMin + DEPTH_CLUSTER_TOLERANCE_MM)) {
                        if (matchCount < matchingXs.size) {
                            matchingXs[matchCount] = normX
                            matchingYs[matchCount] = normY
                            matchCount++
                        }
                    }
                }
            }

            if (matchCount >= MIN_RAW_PIXELS_PER_ZONE) {
                return computeTightZoneBox(dMin, matchingXs, matchingYs, matchCount, defaultMinX, defaultMaxX)
            }
        }

        // Case B: Smooth Wall / Low-Light Fallback using Dense Depth for this specific zone
        if (denseDepthImage != null && denseDepthImage.planes.isNotEmpty()) {
            val densePlane = denseDepthImage.planes[0]
            val denseBuffer = densePlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val denseRowStride = densePlane.rowStride
            val densePixelStride = densePlane.pixelStride
            val denseW = denseDepthImage.width
            val denseH = denseDepthImage.height

            val insetMinX = defaultMinX + 0.02f
            val insetMaxX = defaultMaxX - 0.02f

            var denseCount = 0
            for (row in 0 until denseH step STRIDE) {
                val depthRowOffset = row * denseRowStride
                val normX = 1.0f - (row.toFloat() / denseH.toFloat())
                if (normX < insetMinX || normX > insetMaxX) continue

                for (col in 0 until denseW step STRIDE) {
                    val normY = col.toFloat() / denseW.toFloat()
                    if (normY < 0.15f || normY > 0.80f) continue

                    val depthIndex = depthRowOffset + col * densePixelStride
                    val depthMm = denseBuffer.getShort(depthIndex).toInt() and 0xFFFF
                    if (depthMm in MIN_DEPTH_MM..MAX_WALL_DEPTH_MM) {
                        if (denseCount < denseDepths.size) {
                            denseDepths[denseCount++] = depthMm
                        }
                    }
                }
            }

            if (denseCount >= 10) {
                Arrays.sort(denseDepths, 0, denseCount)
                val p15 = denseDepths[(denseCount * 0.15f).toInt().coerceIn(0, denseCount - 1)]
                val p75 = denseDepths[(denseCount * 0.75f).toInt().coerceIn(0, denseCount - 1)]
                val spread = p75 - p15

                // Smooth wall / real obstacle check: p15 between 200..4000 mm and spread <= 600 mm
                if (p15 in MIN_DEPTH_MM..MAX_WALL_DEPTH_MM && spread <= 600) {
                    return ZoneBox(
                        dMin = p15,
                        minNormX = insetMinX,
                        maxNormX = insetMaxX,
                        minNormY = 0.15f,
                        maxNormY = 0.85f,
                        hasPixels = true
                    )
                }
            }
        }

        return ZoneBox(dMin = null, hasPixels = false)
    }

    private fun computeTightZoneBox(
        dMin: Int,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        defaultMinX: Float,
        defaultMaxX: Float
    ): ZoneBox {
        Arrays.sort(xs, 0, count)
        Arrays.sort(ys, 0, count)

        val p10Index = (count * 0.10f).toInt().coerceIn(0, count - 1)
        val medianIndex = (count * 0.50f).toInt().coerceIn(0, count - 1)
        val p90Index = (count * 0.90f).toInt().coerceIn(0, count - 1)

        val medianX = xs[medianIndex]
        val medianY = ys[medianIndex]

        var minX = xs[p10Index]
        var maxX = xs[p90Index]
        var minY = ys[p10Index]
        var maxY = ys[p90Index]

        if (maxX - minX < MIN_BOX_WIDTH) {
            minX = medianX - MIN_BOX_WIDTH / 2f
            maxX = medianX + MIN_BOX_WIDTH / 2f
        }
        if (maxY - minY < MIN_BOX_HEIGHT) {
            minY = medianY - MIN_BOX_HEIGHT / 2f
            maxY = medianY + MIN_BOX_HEIGHT / 2f
        }

        minX = minX.coerceIn(defaultMinX, defaultMaxX)
        maxX = maxX.coerceIn(minX, defaultMaxX)
        minY = minY.coerceIn(ROI_Y_MIN, MAX_NORM_Y_CLAMP)
        maxY = maxY.coerceIn(minY, MAX_NORM_Y_CLAMP)

        return ZoneBox(
            dMin = dMin,
            minNormX = minX,
            minNormY = minY,
            maxNormX = maxX,
            maxNormY = maxY,
            hasPixels = true
        )
    }

    private fun createNonMergedBox(
        zone: ZoneBox,
        dMin: Int?,
        isBlocked: Boolean,
        boxList: MutableList<ObstacleBox>
    ): Int? {
        if (dMin == null || !zone.hasPixels) return null

        if (isBlocked) {
            // Yellow Box (Single Obstacle <= 1200 mm)
            boxList.add(
                ObstacleBox(
                    normLeft = zone.minNormX,
                    normTop = zone.minNormY,
                    normRight = zone.maxNormX,
                    normBottom = zone.maxNormY.coerceAtMost(MAX_NORM_Y_CLAMP),
                    color = COLOR_YELLOW,
                    label = String.format(Locale.US, "SINGLE: %.2f m", dMin / 1000.0f)
                )
            )
            return dMin
        } else if (dMin <= MAX_WALL_DEPTH_MM) {
            // GREEN Box (Safe Surface: 1200 mm < D_min <= 4000 mm) -> Always draw Green box!
            boxList.add(
                ObstacleBox(
                    normLeft = zone.minNormX,
                    normTop = zone.minNormY,
                    normRight = zone.maxNormX,
                    normBottom = zone.maxNormY.coerceAtMost(MAX_NORM_Y_CLAMP),
                    color = COLOR_GREEN,
                    label = String.format(Locale.US, "SAFE: %.2f m", dMin / 1000.0f)
                )
            )
        }
        return null
    }

    private fun minDMin(current: Int?, candidate: Int?): Int? {
        if (current == null) return candidate
        if (candidate == null) return current
        return Math.min(current, candidate)
    }
}
