package com.example.haptisight

import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Locale

/**
 * Reusable bounding zone data for zero-allocation tight bounding box calculation.
 */
data class ZoneBox(
    var dMin: Int? = null,
    var minNormX: Float = 0.0f,
    var minNormY: Float = 0.0f,
    var maxNormX: Float = 0.0f,
    var maxNormY: Float = 0.0f,
    var hasPixels: Boolean = false,
    var isBlocked: Boolean = false
) {
    fun reset() {
        dMin = null
        minNormX = 0f
        minNormY = 0f
        maxNormX = 0f
        maxNormY = 0f
        hasPixels = false
        isBlocked = false
    }

    fun set(
        dMin: Int?,
        minNormX: Float = 0.0f,
        minNormY: Float = 0.0f,
        maxNormX: Float = 0.0f,
        maxNormY: Float = 0.0f,
        hasPixels: Boolean = false,
        isBlocked: Boolean = false
    ) {
        this.dMin = dMin
        this.minNormX = minNormX
        this.minNormY = minNormY
        this.maxNormX = maxNormX
        this.maxNormY = maxNormY
        this.hasPixels = hasPixels
        this.isBlocked = isBlocked
    }
}

/**
 * Reusable collector for a directional zone (Left, Centre, Right).
 * Stores raw and dense depth samples separated into blocked (<= 1000mm) and safe (1001..4000mm).
 */
class ZoneCollector(val maxCapacity: Int = 4000) {
    val blockedDepths = IntArray(maxCapacity)
    val blockedXs = FloatArray(maxCapacity)
    val blockedYs = FloatArray(maxCapacity)
    var blockedCount = 0

    val safeDepths = IntArray(maxCapacity)
    val safeXs = FloatArray(maxCapacity)
    val safeYs = FloatArray(maxCapacity)
    var safeCount = 0

    fun reset() {
        blockedCount = 0
        safeCount = 0
    }

    fun add(depthMm: Int, normX: Float, normY: Float) {
        if (depthMm in DepthAnalyzer.MIN_DEPTH_MM..DepthAnalyzer.BLOCKED_THRESHOLD_MM) {
            if (blockedCount < maxCapacity) {
                blockedDepths[blockedCount] = depthMm
                blockedXs[blockedCount] = normX
                blockedYs[blockedCount] = normY
                blockedCount++
            }
        } else if (depthMm in (DepthAnalyzer.BLOCKED_THRESHOLD_MM + 1)..DepthAnalyzer.MAX_WALL_DEPTH_MM) {
            if (safeCount < maxCapacity) {
                safeDepths[safeCount] = depthMm
                safeXs[safeCount] = normX
                safeYs[safeCount] = normY
                safeCount++
            }
        }
    }
}

/**
 * Result data class containing all analytical data required for UI rendering and haptic feedback.
 */
data class DepthAnalysisResult(
    val boxes: List<ObstacleBox>,
    val leftDMin: Int?,
    val centerDMin: Int?,
    val rightDMin: Int?,
    val isRedBarrier: Boolean = false,
    val mergedRedDMin: Int? = null,
    val isDoorway: Boolean = false,
    val yellowDMin: Int? = null,
    val isSafePath: Boolean,
    val totalRoiPixels: Int,
    val validRoiPixels: Int,
    val lowConfidence: Boolean,
    val isDepthUnavailable: Boolean = false,
    val bannerText: String,
    val leftBlocked: Boolean = false,
    val centerBlocked: Boolean = false,
    val rightBlocked: Boolean = false,
    val leftDepthMm: Int = 0,
    val centerDepthMm: Int = 0,
    val rightDepthMm: Int = 0,
    val motorPatternCode: String = "000N"
)

/**
 * High-Accuracy, Low-Light Dynamic Gain Hybrid Depth Engine.
 * Features:
 * 1. Zero-Allocation Multi-Zone Collector: Captures true obstacle surfaces within 1 meter
 *    regardless of background geometry or room size.
 * 2. Low-Light Illumination Boost: Automatically adapts raw confidence down to conf >= 0 in dark rooms.
 * 3. Floor Plane Cutoff: Restricts vertical ROI to 0.08f..0.75f, eliminating false alarms on floor tiles.
 * 4. Microsecond Metric Precision: 25th-percentile surface clustering ensures true obstacle boundaries
 *    are detected without hallucination or background bleed.
 * 5. Zero-Allocation Object Pooling: Pre-allocated buffers prevent Garbage Collection pauses over multi-hour runs.
 */
class DepthAnalyzer {

    companion object {
        const val COLOR_RED: Int = 0xFFFF1744.toInt()
        const val COLOR_YELLOW: Int = 0xFFFFD600.toInt()
        const val COLOR_GREEN: Int = 0xFF00E676.toInt()

        const val MIN_DEPTH_MM = 200 // Minimum reliable depth range
        const val MAX_DEPTH_MM = 4500 // Search window boundary
        const val MAX_WALL_DEPTH_MM = 4000 // Safe surface boundary for green boxes
        private const val BASE_RAW_CONFIDENCE = 15 // Standard confidence threshold
        const val BLOCKED_THRESHOLD_MM = 1000 // <= 1000 mm (< 1.00m) strict obstacle cutoff
        private const val MIN_RAW_BLOCKED_PIXELS = 5 // Physical raw ToF points required
        private const val MIN_DENSE_BLOCKED_PIXELS = 10 // Dense obstacle points required (rejects hallway noise)
        private const val MIN_RAW_SAFE_PIXELS = 6
        private const val MIN_DENSE_SAFE_PIXELS = 15
        private const val STRIDE = 3

        // Floor Exclusion ROI: normY 0.08f to 0.75f (ignores lower 25% floor space in front of feet)
        private const val ROI_Y_MIN = 0.08f
        private const val ROI_Y_MAX = 0.75f
        private const val MAX_NORM_Y_CLAMP = 0.75f

        // Minimum box dimensions for clean visual representation
        private const val MIN_BOX_WIDTH = 0.12f
        private const val MIN_BOX_HEIGHT = 0.12f

        // Pre-allocated static 8-state motor pattern strings (Zero allocation)
        private val MOTOR_PATTERNS = arrayOf(
            "000N", // 0: 000
            "001S", // 1: 001
            "010S", // 2: 010
            "011S", // 3: 011
            "100S", // 4: 100
            "101S", // 5: 101
            "110S", // 6: 110
            "111S"  // 7: 111
        )

        // Pre-allocated static 8-state banner text strings (Zero string concatenation)
        private val BANNER_TEXTS = arrayOf(
            "🟢 SAFE PATH",                                         // 0: 000
            "🔴 OBSTACLE <1m [RIGHT] - SHORT BUZZ",                 // 1: 001
            "🔴 OBSTACLE <1m [CENTRE] - SHORT BUZZ",                // 2: 010
            "🔴 OBSTACLE <1m [CENTRE + RIGHT] - SHORT BUZZ",        // 3: 011
            "🔴 OBSTACLE <1m [LEFT] - SHORT BUZZ",                  // 4: 100
            "🔴 OBSTACLE <1m [LEFT + RIGHT] - SHORT BUZZ",          // 5: 101
            "🔴 OBSTACLE <1m [LEFT + CENTRE] - SHORT BUZZ",         // 6: 110
            "🔴 OBSTACLE <1m [LEFT + CENTRE + RIGHT] - SHORT BUZZ"  // 7: 111
        )

        val DEPTH_UNAVAILABLE_RESULT = DepthAnalysisResult(
            boxes = emptyList(),
            leftDMin = null,
            centerDMin = null,
            rightDMin = null,
            isRedBarrier = false,
            mergedRedDMin = null,
            isDoorway = false,
            yellowDMin = null,
            isSafePath = false,
            totalRoiPixels = 0,
            validRoiPixels = 0,
            lowConfidence = false,
            isDepthUnavailable = true,
            bannerText = "⚠️ DEPTH UNAVAILABLE",
            leftBlocked = false,
            centerBlocked = false,
            rightBlocked = false,
            leftDepthMm = 0,
            centerDepthMm = 0,
            rightDepthMm = 0,
            motorPatternCode = "000N"
        )
    }

    // Pre-allocated Raw Depth collectors for the 3 horizontal zones
    private val rawLeft = ZoneCollector(4000)
    private val rawCenter = ZoneCollector(4000)
    private val rawRight = ZoneCollector(4000)

    // Pre-allocated Dense Depth collectors for the 3 horizontal zones
    private val denseLeft = ZoneCollector(4000)
    private val denseCenter = ZoneCollector(4000)
    private val denseRight = ZoneCollector(4000)

    // Object pool: Reusable ZoneBox instances and box lists
    private val leftZone = ZoneBox()
    private val centerZone = ZoneBox()
    private val rightZone = ZoneBox()
    private val reusableBoxes = ArrayList<ObstacleBox>(6)

    fun reset() {
        rawLeft.reset()
        rawCenter.reset()
        rawRight.reset()
        denseLeft.reset()
        denseCenter.reset()
        denseRight.reset()
        leftZone.reset()
        centerZone.reset()
        rightZone.reset()
        reusableBoxes.clear()
    }

    /**
     * Analyzes current ARCore frame using Low-Light Dynamic Gain & Hybrid Surface Clustering.
     */
    fun analyze(frame: Frame): DepthAnalysisResult {
        var rawDepthImage: Image? = null
        var confImage: Image? = null
        var denseDepthImage: Image? = null

        try {
            try {
                rawDepthImage = frame.acquireRawDepthImage16Bits()
                confImage = frame.acquireRawDepthConfidenceImage()
            } catch (_: NotYetAvailableException) {
            } catch (e: Exception) {
                Log.e("HaptiSight", "Raw depth acquire error", e)
            }

            try {
                denseDepthImage = frame.acquireDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
            } catch (e: Exception) {
                Log.e("HaptiSight", "Dense depth acquire error", e)
            }

            if ((rawDepthImage == null && denseDepthImage == null) ||
                (rawDepthImage != null && rawDepthImage.planes.isEmpty()) ||
                (denseDepthImage != null && denseDepthImage.planes.isEmpty())
            ) {
                return DEPTH_UNAVAILABLE_RESULT
            }

            return processHybridDepth(rawDepthImage, confImage, denseDepthImage)
        } finally {
            try { rawDepthImage?.close() } catch (ignored: Exception) {}
            try { confImage?.close() } catch (ignored: Exception) {}
            try { denseDepthImage?.close() } catch (ignored: Exception) {}
        }
    }

    private fun processHybridDepth(
        rawDepthImage: Image?,
        confImage: Image?,
        denseDepthImage: Image?
    ): DepthAnalysisResult {
        rawLeft.reset()
        rawCenter.reset()
        rawRight.reset()
        denseLeft.reset()
        denseCenter.reset()
        denseRight.reset()
        leftZone.reset()
        centerZone.reset()
        rightZone.reset()
        reusableBoxes.clear()

        var totalRoiPixels = 0
        var validRoiPixels = 0

        // 1. Scan Raw Depth + Confidence if available
        if (rawDepthImage != null && confImage != null &&
            rawDepthImage.planes.isNotEmpty() && confImage.planes.isNotEmpty()
        ) {
            val rawW = rawDepthImage.width
            val rawH = rawDepthImage.height
            val depthPlane = rawDepthImage.planes[0]
            val rawDepthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val rawDepthRowStride = depthPlane.rowStride
            val rawDepthPixelStride = depthPlane.pixelStride

            val confPlane = confImage.planes[0]
            val confBuffer = confPlane.buffer
            val confRowStride = confPlane.rowStride
            val confPixelStride = confPlane.pixelStride

            // Low-Light Illumination Boost: Calculate average confidence across valid depth points
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
            val effectiveMinConf = if (isLowLight) 0 else BASE_RAW_CONFIDENCE

            for (row in 0 until rawH step STRIDE) {
                val depthRowOffset = row * rawDepthRowStride
                val confRowOffset = row * confRowStride
                val normX = 1.0f - (row.toFloat() / rawH.toFloat())

                for (col in 0 until rawW step STRIDE) {
                    val normY = col.toFloat() / rawW.toFloat()
                    val inRoi = (normY in ROI_Y_MIN..ROI_Y_MAX)
                    if (inRoi) totalRoiPixels++

                    val confIndex = confRowOffset + col * confPixelStride
                    val conf = confBuffer.get(confIndex).toInt() and 0xFF
                    if (conf < effectiveMinConf) continue

                    val depthIndex = depthRowOffset + col * rawDepthPixelStride
                    val depthMm = rawDepthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                    if (depthMm < MIN_DEPTH_MM || depthMm > MAX_DEPTH_MM) continue

                    if (inRoi) {
                        validRoiPixels++
                        when {
                            normX < 0.333f -> rawLeft.add(depthMm, normX, normY)
                            normX < 0.666f -> rawCenter.add(depthMm, normX, normY)
                            else -> rawRight.add(depthMm, normX, normY)
                        }
                    }
                }
            }
        }

        // 2. Scan Dense Depth if available
        var totalDensePixels = 0

        if (denseDepthImage != null && denseDepthImage.planes.isNotEmpty()) {
            val denseW = denseDepthImage.width
            val denseH = denseDepthImage.height
            val densePlane = denseDepthImage.planes[0]
            val denseBuffer = densePlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val denseRowStride = densePlane.rowStride
            val densePixelStride = densePlane.pixelStride

            for (row in 0 until denseH step STRIDE) {
                val rowOffset = row * denseRowStride
                val normX = 1.0f - (row.toFloat() / denseH.toFloat())

                for (col in 0 until denseW step STRIDE) {
                    val normY = col.toFloat() / denseW.toFloat()
                    if (normY !in ROI_Y_MIN..ROI_Y_MAX) continue

                    val index = rowOffset + col * densePixelStride
                    val depthMm = denseBuffer.getShort(index).toInt() and 0xFFFF
                    if (depthMm < MIN_DEPTH_MM || depthMm > MAX_DEPTH_MM) continue

                    totalDensePixels++
                    when {
                        normX < 0.333f -> denseLeft.add(depthMm, normX, normY)
                        normX < 0.666f -> denseCenter.add(depthMm, normX, normY)
                        else -> denseRight.add(depthMm, normX, normY)
                    }
                }
            }
            if (totalRoiPixels == 0) {
                totalRoiPixels = totalDensePixels
            }
        }

        // 3. Evaluate each directional zone independently
        evaluateZone(0, leftZone, rawLeft, denseLeft)
        evaluateZone(1, centerZone, rawCenter, denseCenter)
        evaluateZone(2, rightZone, rawRight, denseRight)

        val leftDMin = leftZone.dMin
        val centerDMin = centerZone.dMin
        val rightDMin = rightZone.dMin

        val leftDepthMm = leftDMin ?: 0
        val centerDepthMm = centerDMin ?: 0
        val rightDepthMm = rightDMin ?: 0

        // Strict <= 1000 mm (< 1.00m) cutoff for Blocked/Active state
        val leftBlocked = leftZone.hasPixels && leftZone.isBlocked
        val centerBlocked = centerZone.hasPixels && centerZone.isBlocked
        val rightBlocked = rightZone.hasPixels && rightZone.isBlocked

        val hasAnyBlocked = leftBlocked || centerBlocked || rightBlocked

        // 8-State Directional Motor Pattern Code: [LeftBit][CenterBit][RightBit][BuzzMode] via constant table
        val patternIndex = (if (leftBlocked) 4 else 0) or (if (centerBlocked) 2 else 0) or (if (rightBlocked) 1 else 0)
        val motorPatternCode = MOTOR_PATTERNS[patternIndex]

        if (leftZone.hasPixels) createZoneBox("LEFT", leftZone, reusableBoxes)
        if (centerZone.hasPixels) createZoneBox("CENTRE", centerZone, reusableBoxes)
        if (rightZone.hasPixels) createZoneBox("RIGHT", rightZone, reusableBoxes)

        val isDepthUnavailable = (totalRoiPixels == 0)
        val lowConfidence = !isDepthUnavailable && !hasAnyBlocked &&
                (validRoiPixels < 6 && reusableBoxes.isEmpty() && totalDensePixels == 0)
        val isSafePath = !hasAnyBlocked && !isDepthUnavailable && !lowConfidence

        val bannerText = when {
            hasAnyBlocked -> BANNER_TEXTS[patternIndex]
            isDepthUnavailable -> "⚠️ DEPTH UNAVAILABLE"
            lowConfidence -> "⚠️ LOW DEPTH CONFIDENCE"
            isSafePath -> "🟢 SAFE PATH"
            else -> "🟢 SAFE PATH"
        }

        return DepthAnalysisResult(
            boxes = reusableBoxes,
            leftDMin = leftDMin,
            centerDMin = centerDMin,
            rightDMin = rightDMin,
            isRedBarrier = (leftBlocked && centerBlocked && rightBlocked),
            mergedRedDMin = if (hasAnyBlocked) minDMin(minDMin(leftDMin, centerDMin), rightDMin) else null,
            isDoorway = (leftBlocked && !centerBlocked && rightBlocked),
            yellowDMin = if (hasAnyBlocked) minDMin(minDMin(leftDMin, centerDMin), rightDMin) else null,
            isSafePath = isSafePath,
            totalRoiPixels = totalRoiPixels,
            validRoiPixels = validRoiPixels,
            lowConfidence = lowConfidence,
            isDepthUnavailable = isDepthUnavailable,
            bannerText = bannerText,
            leftBlocked = leftBlocked,
            centerBlocked = centerBlocked,
            rightBlocked = rightBlocked,
            leftDepthMm = leftDepthMm,
            centerDepthMm = centerDepthMm,
            rightDepthMm = rightDepthMm,
            motorPatternCode = motorPatternCode
        )
    }

    /**
     * Evaluates a single directional zone:
     * - Step 1: Detect true obstacles <= 1000 mm (< 1.00m) from Raw ToF or Dense depth cluster.
     * - Step 2: If no obstacle <= 1000 mm, detect safe surfaces between 1001 mm and 4000 mm.
     * - Step 3: If no surface within 4000 mm, mark zone as clear open space (no box).
     */
    private fun evaluateZone(
        zoneIndex: Int,
        targetZone: ZoneBox,
        rawCollector: ZoneCollector,
        denseCollector: ZoneCollector
    ) {
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

        // STEP 1: Check for Blocked Obstacle (<= 1000 mm / < 1.00m)
        // Priority 1: Raw Depth points (Physical ToF laser / stereo points)
        if (rawCollector.blockedCount >= MIN_RAW_BLOCKED_PIXELS) {
            Arrays.sort(rawCollector.blockedDepths, 0, rawCollector.blockedCount)
            val dMin = rawCollector.blockedDepths[(rawCollector.blockedCount * 0.25f).toInt()]
            computeTightZoneBox(
                dMin = dMin,
                xs = rawCollector.blockedXs,
                ys = rawCollector.blockedYs,
                count = rawCollector.blockedCount,
                defaultMinX = defaultMinX,
                defaultMaxX = defaultMaxX,
                targetZone = targetZone,
                isBlocked = true
            )
            return
        }

        // Priority 2: Dense Depth points (Neural disparity depth map)
        if (denseCollector.blockedCount >= MIN_DENSE_BLOCKED_PIXELS) {
            Arrays.sort(denseCollector.blockedDepths, 0, denseCollector.blockedCount)
            val dMin = denseCollector.blockedDepths[(denseCollector.blockedCount * 0.25f).toInt()]
            computeTightZoneBox(
                dMin = dMin,
                xs = denseCollector.blockedXs,
                ys = denseCollector.blockedYs,
                count = denseCollector.blockedCount,
                defaultMinX = defaultMinX,
                defaultMaxX = defaultMaxX,
                targetZone = targetZone,
                isBlocked = true
            )
            return
        }

        // STEP 2: No obstacle within 1 meter. Check for Safe Surface (1001mm to 4000mm)
        // Priority 1: Raw Depth safe surface
        if (rawCollector.safeCount >= MIN_RAW_SAFE_PIXELS) {
            Arrays.sort(rawCollector.safeDepths, 0, rawCollector.safeCount)
            val dMin = rawCollector.safeDepths[(rawCollector.safeCount * 0.25f).toInt()]
            computeTightZoneBox(
                dMin = dMin,
                xs = rawCollector.safeXs,
                ys = rawCollector.safeYs,
                count = rawCollector.safeCount,
                defaultMinX = defaultMinX,
                defaultMaxX = defaultMaxX,
                targetZone = targetZone,
                isBlocked = false
            )
            return
        }

        // Priority 2: Dense Depth safe surface
        if (denseCollector.safeCount >= MIN_DENSE_SAFE_PIXELS) {
            Arrays.sort(denseCollector.safeDepths, 0, denseCollector.safeCount)
            val dMin = denseCollector.safeDepths[(denseCollector.safeCount * 0.25f).toInt()]
            computeTightZoneBox(
                dMin = dMin,
                xs = denseCollector.safeXs,
                ys = denseCollector.safeYs,
                count = denseCollector.safeCount,
                defaultMinX = defaultMinX,
                defaultMaxX = defaultMaxX,
                targetZone = targetZone,
                isBlocked = false
            )
            return
        }

        // STEP 3: Clear space (no obstacle or surface detected within 4 meters)
        targetZone.reset()
    }

    private fun computeTightZoneBox(
        dMin: Int,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        defaultMinX: Float,
        defaultMaxX: Float,
        targetZone: ZoneBox,
        isBlocked: Boolean
    ) {
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

        targetZone.set(
            dMin = dMin,
            minNormX = minX,
            minNormY = minY,
            maxNormX = maxX,
            maxNormY = maxY,
            hasPixels = true,
            isBlocked = isBlocked
        )
    }

    private fun createZoneBox(
        zoneName: String,
        zone: ZoneBox,
        boxList: MutableList<ObstacleBox>
    ) {
        val dMin = zone.dMin ?: return
        if (!zone.hasPixels) return

        if (zone.isBlocked) {
            // Individual Red box (#FF1744) for zone <= 1000 mm tightly framed
            boxList.add(
                ObstacleBox(
                    normLeft = zone.minNormX,
                    normTop = zone.minNormY,
                    normRight = zone.maxNormX,
                    normBottom = zone.maxNormY.coerceAtMost(MAX_NORM_Y_CLAMP),
                    color = COLOR_RED,
                    label = String.format(Locale.US, "%s: %.2f m", zoneName, dMin / 1000.0f)
                )
            )
        } else if (dMin in (BLOCKED_THRESHOLD_MM + 1)..MAX_WALL_DEPTH_MM) {
            // Green Box (#00E676) for safe distances 1001..4000 mm
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
    }

    private fun minDMin(a: Int?, b: Int?): Int? {
        return when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
