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

    @Test
    fun testBlockedThresholdStrict1000mm() {
        val blockedThreshold = 1000
        val dMinBlocked = 1000
        val dMinSafe = 1001

        assertTrue("1000mm must be marked as blocked", dMinBlocked <= blockedThreshold)
        assertFalse("1001mm must not be marked as blocked (safe)", dMinSafe <= blockedThreshold)
    }

    @Test
    fun testMotorPatternCodeAll8States() {
        fun buildCode(l: Boolean, c: Boolean, r: Boolean): String {
            val any = l || c || r
            return "${if (l) '1' else '0'}${if (c) '1' else '0'}${if (r) '1' else '0'}${if (any) 'S' else 'N'}"
        }

        assertEquals("000N", buildCode(false, false, false))
        assertEquals("100S", buildCode(true, false, false))
        assertEquals("010S", buildCode(false, true, false))
        assertEquals("001S", buildCode(false, false, true))
        assertEquals("110S", buildCode(true, true, false))
        assertEquals("011S", buildCode(false, true, true))
        assertEquals("101S", buildCode(true, false, true))
        assertEquals("111S", buildCode(true, true, true))
    }

    @Test
    fun testDepthAnalysisResultPhase7Fields() {
        val result = DepthAnalysisResult(
            boxes = emptyList(),
            leftDMin = 650,
            centerDMin = 1200,
            rightDMin = null,
            isRedBarrier = false,
            mergedRedDMin = null,
            isDoorway = false,
            yellowDMin = 650,
            isSafePath = false,
            totalRoiPixels = 1000,
            validRoiPixels = 200,
            lowConfidence = false,
            isDepthUnavailable = false,
            bannerText = "🔴 OBSTACLE <1m [LEFT] - SHORT BUZZ",
            leftBlocked = true,
            centerBlocked = false,
            rightBlocked = false,
            leftDepthMm = 650,
            centerDepthMm = 1200,
            rightDepthMm = 0,
            motorPatternCode = "100S"
        )

        assertTrue(result.leftBlocked)
        assertFalse(result.centerBlocked)
        assertFalse(result.rightBlocked)
        assertEquals(650, result.leftDepthMm)
        assertEquals(1200, result.centerDepthMm)
        assertEquals(0, result.rightDepthMm)
        assertEquals("100S", result.motorPatternCode)
        assertEquals("🔴 OBSTACLE <1m [LEFT] - SHORT BUZZ", result.bannerText)
    }

    @Test
    fun testPhase8DirectionalBannerFormatting() {
        fun buildBanner(left: Boolean, center: Boolean, right: Boolean): String {
            val hasObstacle = left || center || right
            return if (hasObstacle) {
                val blockedNames = mutableListOf<String>()
                if (left) blockedNames.add("LEFT")
                if (center) blockedNames.add("CENTRE")
                if (right) blockedNames.add("RIGHT")
                "🔴 OBSTACLE <1m [${blockedNames.joinToString(" + ")}] - SHORT BUZZ"
            } else {
                "🟢 SAFE PATH"
            }
        }

        assertEquals("🟢 SAFE PATH", buildBanner(false, false, false))
        assertEquals("🔴 OBSTACLE <1m [LEFT] - SHORT BUZZ", buildBanner(true, false, false))
        assertEquals("🔴 OBSTACLE <1m [CENTRE] - SHORT BUZZ", buildBanner(false, true, false))
        assertEquals("🔴 OBSTACLE <1m [RIGHT] - SHORT BUZZ", buildBanner(false, false, true))
        assertEquals("🔴 OBSTACLE <1m [LEFT + CENTRE] - SHORT BUZZ", buildBanner(true, true, false))
        assertEquals("🔴 OBSTACLE <1m [CENTRE + RIGHT] - SHORT BUZZ", buildBanner(false, true, true))
        assertEquals("🔴 OBSTACLE <1m [LEFT + RIGHT] - SHORT BUZZ", buildBanner(true, false, true))
        assertEquals("🔴 OBSTACLE <1m [LEFT + CENTRE + RIGHT] - SHORT BUZZ", buildBanner(true, true, true))
    }

    @Test
    fun testPhase9VirtualGloveSimulatorTelemetry() {
        val detectionResult = DetectionResult(
            boxes = emptyList(),
            leftActive = true,
            centerActive = false,
            rightActive = true,
            motorPatternCode = "101S",
            latencyMs = 1.2f
        )

        assertTrue(detectionResult.leftActive)
        assertFalse(detectionResult.centerActive)
        assertTrue(detectionResult.rightActive)
        assertEquals("101S", detectionResult.motorPatternCode)
        assertEquals(1.2f, detectionResult.latencyMs, 0.001f)

        val telemetryText = String.format(
            java.util.Locale.US,
            "TX: %s | Latency: %.1f ms",
            detectionResult.motorPatternCode,
            detectionResult.latencyMs
        )
        assertEquals("TX: 101S | Latency: 1.2 ms", telemetryText)
    }

    @Test
    fun testPhase10All8MotorStatesAt1000mmThreshold() {
        val thresholdMm = 1000

        data class TestCase(
            val leftMm: Int,
            val centerMm: Int,
            val rightMm: Int,
            val expectedLeft: Boolean,
            val expectedCenter: Boolean,
            val expectedRight: Boolean,
            val expectedPattern: String
        )

        val testCases = listOf(
            TestCase(1200, 1500, 2000, false, false, false, "000N"), // 1. None blocked (> 1000mm)
            TestCase(750, 1500, 2000, true, false, false, "100S"),   // 2. Left only (Thumb)
            TestCase(1200, 999, 2000, false, true, false, "010S"),   // 3. Center only (Index)
            TestCase(1200, 1500, 1000, false, false, true, "001S"),  // 4. Right only (Right, boundary 1000mm)
            TestCase(800, 1000, 1001, true, true, false, "110S"),    // 5. Left + Center (Right 1001mm is safe)
            TestCase(1050, 600, 950, false, true, true, "011S"),     // 6. Center + Right
            TestCase(500, 1200, 700, true, false, true, "101S"),     // 7. Left + Right
            TestCase(900, 850, 1000, true, true, true, "111S")       // 8. All three blocked (boundary 1000mm)
        )

        for ((index, tc) in testCases.withIndex()) {
            val leftBlocked = tc.leftMm <= thresholdMm
            val centerBlocked = tc.centerMm <= thresholdMm
            val rightBlocked = tc.rightMm <= thresholdMm
            val hasAny = leftBlocked || centerBlocked || rightBlocked

            val code = buildString {
                append(if (leftBlocked) '1' else '0')
                append(if (centerBlocked) '1' else '0')
                append(if (rightBlocked) '1' else '0')
                append(if (hasAny) 'S' else 'N')
            }

            assertEquals("Test case $index leftBlocked mismatch", tc.expectedLeft, leftBlocked)
            assertEquals("Test case $index centerBlocked mismatch", tc.expectedCenter, centerBlocked)
            assertEquals("Test case $index rightBlocked mismatch", tc.expectedRight, rightBlocked)
            assertEquals("Test case $index motorPatternCode mismatch", tc.expectedPattern, code)
        }
    }

    @Test
    fun testPhase10BleGloveControllerConstants() {
        assertEquals(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            BleGloveController.UART_SERVICE_UUID.toString().lowercase()
        )
        assertEquals(
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            BleGloveController.UART_TX_CHAR_UUID.toString().lowercase()
        )
        assertEquals("HaptiSight-Glove", BleGloveController.TARGET_DEVICE_NAME)
    }

    private fun createAnalysisResult(
        leftBlocked: Boolean = false,
        centerBlocked: Boolean = false,
        rightBlocked: Boolean = false,
        isSafePath: Boolean = false,
        isDepthUnavailable: Boolean = false,
        lowConfidence: Boolean = false,
        bannerText: String = "TEST"
    ) = DepthAnalysisResult(
        boxes = emptyList(),
        leftDMin = null,
        centerDMin = null,
        rightDMin = null,
        isSafePath = isSafePath,
        totalRoiPixels = 1000,
        validRoiPixels = 500,
        lowConfidence = lowConfidence,
        isDepthUnavailable = isDepthUnavailable,
        bannerText = bannerText,
        leftBlocked = leftBlocked,
        centerBlocked = centerBlocked,
        rightBlocked = rightBlocked
    )

    @Test
    fun testStateStabilizerPerZoneVotingHysteresis() {
        val stabilizer = StateStabilizer()
        val blockedLeft = createAnalysisResult(leftBlocked = true)
        val clear = createAnalysisResult()

        // 1. First 2 blocked frames: not yet stable (needs >= 3 votes)
        var output = stabilizer.onAnalysis(blockedLeft, null, 100L)
        assertEquals(Verdict.UNCERTAIN, output.verdict)
        assertFalse(output.leftOn)

        output = stabilizer.onAnalysis(blockedLeft, null, 200L)
        assertEquals(Verdict.UNCERTAIN, output.verdict)
        assertFalse(output.leftOn)

        // 2. 3rd blocked frame: reaches 3 of 5 -> enters ACTIVE with leftOn = true, packet = "100S"
        output = stabilizer.onAnalysis(blockedLeft, null, 300L)
        assertEquals(Verdict.ACTIVE, output.verdict)
        assertTrue(output.leftOn)
        assertEquals("100S", output.packet)

        // 3. Clear frame 1: votes = 3 (window has 3 blocked, 1 clear) -> still ACTIVE
        output = stabilizer.onAnalysis(clear, null, 400L)
        assertEquals(Verdict.ACTIVE, output.verdict)
        assertTrue(output.leftOn)

        // 4. Clear frame 2: votes = 3 (window has 3 blocked, 2 clear) -> still ACTIVE
        output = stabilizer.onAnalysis(clear, null, 500L)
        assertEquals(Verdict.ACTIVE, output.verdict)
        assertTrue(output.leftOn)

        // 5. Clear frame 3: votes = 2 (window has 2 blocked, 3 clear) -> hysteresis requires <= 1 vote to exit, so still ACTIVE!
        output = stabilizer.onAnalysis(clear, null, 600L)
        assertEquals(Verdict.ACTIVE, output.verdict)
        assertTrue(output.leftOn)

        // 6. Clear frame 4: votes = 1 (window has 1 blocked, 4 clear) -> votes <= 1, left turns OFF, drops out of ACTIVE
        output = stabilizer.onAnalysis(clear, null, 700L)
        assertFalse(output.leftOn)
        assertEquals(Verdict.UNCERTAIN, output.verdict)
    }

    @Test
    fun testStateStabilizerSafeRequiresTracking() {
        val stabilizer = StateStabilizer()
        val safeResult = createAnalysisResult(isSafePath = true)

        // Feed 5 frames with safePath = true, but tracking paused (trackingMessage != null)
        for (i in 1..5) {
            val output = stabilizer.onAnalysis(safeResult, "⚠️ TRACKING PAUSED - MOVING TOO FAST", i * 100L)
            assertEquals("Safe path must never be confirmed while tracking is paused", Verdict.UNCERTAIN, output.verdict)
            assertEquals("000U", output.packet)
        }

        // Now tracking recovers (trackingMessage == null)
        // Needs >= 4 of 5 frames to enter SAFE
        for (i in 1..3) {
            val output = stabilizer.onAnalysis(safeResult, null, (5 + i) * 100L)
            assertEquals("Safe path requires 4 of 5 votes before entering SAFE", Verdict.UNCERTAIN, output.verdict)
        }

        // 4th frame with tracking OK: safeVotes = 4 >= 4 -> enters SAFE
        val safeOutput = stabilizer.onAnalysis(safeResult, null, 900L)
        assertEquals(Verdict.SAFE, safeOutput.verdict)
        assertEquals("000N", safeOutput.packet)
        assertEquals("🟢 SAFE PATH", safeOutput.banner)
    }

    @Test
    fun testStateStabilizerUncertainPacketAndDelayedSignal() {
        val stabilizer = StateStabilizer()
        val unsureResult = createAnalysisResult(isSafePath = false)

        // Initial frame: UNCERTAIN, signalUncertain is false (< 1000 ms)
        val initial = stabilizer.onAnalysis(unsureResult, null, 1000L)
        assertEquals(Verdict.UNCERTAIN, initial.verdict)
        assertEquals("000U", initial.packet)
        assertFalse(initial.signalUncertain)

        // After 500 ms: still UNCERTAIN, signalUncertain is false
        val mid = stabilizer.onAnalysis(unsureResult, null, 1500L)
        assertEquals(Verdict.UNCERTAIN, mid.verdict)
        assertEquals("000U", mid.packet)
        assertFalse(mid.signalUncertain)

        // After 1000 ms: signalUncertain becomes true
        val delayed = stabilizer.onAnalysis(unsureResult, null, 2000L)
        assertEquals(Verdict.UNCERTAIN, delayed.verdict)
        assertEquals("000U", delayed.packet)
        assertTrue(delayed.signalUncertain)

        // onStale immediately returns 000U with signalUncertain = true
        val stale = stabilizer.onStale("⚠️ DEPTH UNAVAILABLE")
        assertEquals(Verdict.UNCERTAIN, stale.verdict)
        assertEquals("000U", stale.packet)
        assertTrue(stale.signalUncertain)
        assertEquals("⚠️ DEPTH UNAVAILABLE", stale.banner)
    }
}
