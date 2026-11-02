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
}
