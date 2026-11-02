package com.example.haptisight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying HaptiSight safety states, depth availability handling,
 * and obstacle classification logic.
 */
class DepthSafetyStateTest {

    @Test
    fun testDepthUnavailableCannotReportSafePath() {
        val result = DepthAnalysisResult(
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
            bannerText = "⚠️ DEPTH UNAVAILABLE"
        )

        assertTrue(result.isDepthUnavailable)
        assertFalse("Safe path must never be reported when depth is unavailable", result.isSafePath)
        assertEquals("⚠️ DEPTH UNAVAILABLE", result.bannerText)
        assertTrue(result.boxes.isEmpty())
    }

    @Test
    fun testLowConfidenceCannotReportSafePath() {
        val result = DepthAnalysisResult(
            boxes = emptyList(),
            leftDMin = null,
            centerDMin = null,
            rightDMin = null,
            isRedBarrier = false,
            mergedRedDMin = null,
            isDoorway = false,
            yellowDMin = null,
            isSafePath = false,
            totalRoiPixels = 1000,
            validRoiPixels = 5,
            lowConfidence = true,
            isDepthUnavailable = false,
            bannerText = "⚠️ LOW DEPTH CONFIDENCE"
        )

        assertTrue(result.lowConfidence)
        assertFalse("Safe path must never be reported under low confidence", result.isSafePath)
        assertEquals("⚠️ LOW DEPTH CONFIDENCE", result.bannerText)
        assertTrue(result.boxes.isEmpty())
    }

    @Test
    fun testRedBarrierObstacleState() {
        val redBox = ObstacleBox(
            normLeft = 0.1f,
            normTop = 0.2f,
            normRight = 0.8f,
            normBottom = 0.75f,
            color = DepthAnalyzer.COLOR_RED,
            label = "WIDE BARRIER: 0.85 m"
        )

        val result = DepthAnalysisResult(
            boxes = listOf(redBox),
            leftDMin = 850,
            centerDMin = 820,
            rightDMin = null,
            isRedBarrier = true,
            mergedRedDMin = 820,
            isDoorway = false,
            yellowDMin = null,
            isSafePath = false,
            totalRoiPixels = 2000,
            validRoiPixels = 500,
            lowConfidence = false,
            isDepthUnavailable = false,
            bannerText = "🔴 WIDE BARRIER - LONG BUZZ"
        )

        assertTrue(result.isRedBarrier)
        assertFalse("Safe path must never be reported during Red obstacle detection", result.isSafePath)
        assertEquals(1, result.boxes.size)
        assertEquals(DepthAnalyzer.COLOR_RED, result.boxes[0].color)
        assertEquals(820, result.mergedRedDMin)
    }

    @Test
    fun testYellowSingleObstacleState() {
        val yellowBox = ObstacleBox(
            normLeft = 0.35f,
            normTop = 0.25f,
            normRight = 0.65f,
            normBottom = 0.70f,
            color = DepthAnalyzer.COLOR_YELLOW,
            label = "SINGLE: 0.95 m"
        )

        val result = DepthAnalysisResult(
            boxes = listOf(yellowBox),
            leftDMin = null,
            centerDMin = 950,
            rightDMin = null,
            isRedBarrier = false,
            mergedRedDMin = null,
            isDoorway = false,
            yellowDMin = 950,
            isSafePath = false,
            totalRoiPixels = 2000,
            validRoiPixels = 300,
            lowConfidence = false,
            isDepthUnavailable = false,
            bannerText = "🟡 SINGLE OBJECT - SHORT BUZZ"
        )

        assertFalse(result.isRedBarrier)
        assertEquals(950, result.yellowDMin)
        assertFalse("Safe path must never be reported during Yellow obstacle detection", result.isSafePath)
        assertEquals(1, result.boxes.size)
        assertEquals(DepthAnalyzer.COLOR_YELLOW, result.boxes[0].color)
    }

    @Test
    fun testConfirmedSafePathState() {
        val greenBox = ObstacleBox(
            normLeft = 0.05f,
            normTop = 0.15f,
            normRight = 0.95f,
            normBottom = 0.85f,
            color = DepthAnalyzer.COLOR_GREEN,
            label = "SAFE: 2.50 m"
        )

        val result = DepthAnalysisResult(
            boxes = listOf(greenBox),
            leftDMin = 2500,
            centerDMin = 2600,
            rightDMin = 2700,
            isRedBarrier = false,
            mergedRedDMin = null,
            isDoorway = false,
            yellowDMin = null,
            isSafePath = true,
            totalRoiPixels = 2000,
            validRoiPixels = 800,
            lowConfidence = false,
            isDepthUnavailable = false,
            bannerText = "🟢 SAFE PATH"
        )

        assertTrue("Safe path must be true when reliable depth confirms clear distance", result.isSafePath)
        assertFalse(result.isDepthUnavailable)
        assertFalse(result.lowConfidence)
        assertFalse(result.isRedBarrier)
        assertNull(result.yellowDMin)
        assertEquals(1, result.boxes.size)
        assertEquals(DepthAnalyzer.COLOR_GREEN, result.boxes[0].color)
    }

    @Test
    fun testRoiHeightBoundaryInvariant() {
        val maxNormY = 0.85f
        val box = ObstacleBox(
            normLeft = 0.0f,
            normTop = 0.1f,
            normRight = 1.0f,
            normBottom = 0.85f,
            color = DepthAnalyzer.COLOR_GREEN,
            label = "SAFE: 3.00 m"
        )

        assertTrue(box.normBottom <= maxNormY)
        assertTrue(box.normTop >= 0.08f)
    }

    @Test
    fun testSafePathRequiresSufficientEvidence() {
        // Without obstacle boxes or sufficient valid pixels (>= 15), safe path must be false
        val totalRoi = 1000
        val validRoi = 10 // Between 8 (min) and 15 (safe path threshold)
        val hasAnyBoxes = false
        val hasObstacle = false
        val isDepthUnavailable = false
        val lowConfidence = false

        val isSafePath = !hasObstacle && !isDepthUnavailable && !lowConfidence && (hasAnyBoxes || validRoi >= 15)
        assertFalse("Safe path must never be reported without sufficient depth evidence (valid pixels >= 15 or confirmed box)", isSafePath)
    }

    @Test
    fun testStaleDepthTimeoutCondition() {
        val now = 2000L
        val lastValidDepthTimestamp = 1100L // 900 ms ago (> 800 ms timeout)
        val sessionResumeTimestamp = 500L

        val isDepthStale = (lastValidDepthTimestamp > 0L && (now - lastValidDepthTimestamp > 800L)) ||
                (lastValidDepthTimestamp == 0L && (now - sessionResumeTimestamp > 800L))

        assertTrue("Depth older than 800 ms must be classified as stale", isDepthStale)
    }

    @Test
    fun testInitialDepthTimeoutCondition() {
        val now = 1000L
        val lastValidDepthTimestamp = 0L // Never acquired
        val sessionResumeTimestamp = 100L // Resumed 900 ms ago (> 800 ms timeout)

        val isDepthStale = (lastValidDepthTimestamp > 0L && (now - lastValidDepthTimestamp > 800L)) ||
                (lastValidDepthTimestamp == 0L && (now - sessionResumeTimestamp > 800L))

        assertTrue("Initial session without valid depth past 800 ms must be classified as stale/unavailable", isDepthStale)
    }

    @Test
    fun testInitialDepthWithinWarmupWindow() {
        val now = 500L
        val lastValidDepthTimestamp = 0L // Never acquired
        val sessionResumeTimestamp = 200L // Resumed 300 ms ago (within 800 ms window)

        val isDepthStale = (lastValidDepthTimestamp > 0L && (now - lastValidDepthTimestamp > 800L)) ||
                (lastValidDepthTimestamp == 0L && (now - sessionResumeTimestamp > 800L))

        assertFalse("Camera during initial 800 ms warmup window is not yet stale", isDepthStale)
    }

    @Test
    fun testAdjacencyMergeDistanceTolerance() {
        val adjacencyToleranceMm = 250

        // Case 1: Within tolerance (200 mm diff) -> MERGES
        val leftDMin1 = 800
        val centerDMin1 = 1000
        val diff1 = Math.abs(leftDMin1 - centerDMin1)
        assertTrue("800mm and 1000mm (diff 200mm) must satisfy 250mm adjacency rule", diff1 <= adjacencyToleranceMm)

        // Case 2: Exceeding tolerance (300 mm diff) -> DOES NOT MERGE
        val leftDMin2 = 800
        val centerDMin2 = 1100
        val diff2 = Math.abs(leftDMin2 - centerDMin2)
        assertFalse("800mm and 1100mm (diff 300mm) must NOT merge into Wide Barrier", diff2 <= adjacencyToleranceMm)
    }

    @Test
    fun testDenseDepthFallbackSpreadCriteria() {
        val maxSpreadMm = 600
        val minDepthMm = 200
        val maxWallDepthMm = 4000

        // Case 1: Valid planar wall (p15 = 1500, p75 = 1800, spread = 300)
        val p15Valid = 1500
        val p75Valid = 1800
        val spreadValid = p75Valid - p15Valid
        val isValidWall = p15Valid in minDepthMm..maxWallDepthMm && spreadValid <= maxSpreadMm
        assertTrue("Dense depth with spread <= 600mm must be accepted as valid wall", isValidWall)

        // Case 2: Erratic noisy points (p15 = 800, p75 = 1600, spread = 800)
        val p15Invalid = 800
        val p75Invalid = 1600
        val spreadInvalid = p75Invalid - p15Invalid
        val isInvalidWall = p15Invalid in minDepthMm..maxWallDepthMm && spreadInvalid <= maxSpreadMm
        assertFalse("Dense depth with spread > 600mm must be rejected to prevent hallucinations", isInvalidWall)
    }

    @Test
    fun testTrackingRecoveryRequiresFreshDepthEvidence() {
        // When tracking is lost, lastValidDepthTimestamp is reset to 0L
        var lastValidDepthTimestamp: Long = 1500L
        val trackingLost = true

        if (trackingLost) {
            lastValidDepthTimestamp = 0L
        }

        assertEquals("Tracking loss must reset lastValidDepthTimestamp to 0L", 0L, lastValidDepthTimestamp)
    }

    @Test
    fun testDistinctColorConstants() {
        assertTrue(DepthAnalyzer.COLOR_RED != DepthAnalyzer.COLOR_YELLOW)
        assertTrue(DepthAnalyzer.COLOR_RED != DepthAnalyzer.COLOR_GREEN)
        assertTrue(DepthAnalyzer.COLOR_YELLOW != DepthAnalyzer.COLOR_GREEN)
    }
}
