package com.example.haptisight

/**
 * Stable decision about the path ahead, after smoothing over several analyses.
 *
 * SAFE      - nothing within 1.00 m and the path is positively confirmed (the ONLY silent state)
 * ACTIVE    - at least one zone has a stable obstacle <= 1.00 m (per-zone bits in leftOn/centerOn/rightOn)
 * UNCERTAIN - low confidence, depth unavailable, tracking not confirmed, or still stabilizing
 */
enum class Verdict {
    SAFE,
    ACTIVE,
    UNCERTAIN
}

/**
 * Final output handed to the glove (packet), the phone haptics (verdict) and the UI (banner).
 * signalUncertain is true once UNCERTAIN has lasted long enough to warrant a warning pattern.
 */
data class StableOutput(
    val verdict: Verdict,
    val leftOn: Boolean,
    val centerOn: Boolean,
    val rightOn: Boolean,
    val packet: String,
    val banner: String,
    val signalUncertain: Boolean = false
)

/**
 * Temporal stabilizer for the 3-zone motor design.
 *
 * DepthAnalyzer gives a fresh (noisy) answer every ~80 ms. Here every zone is smoothed on its own
 * with a majority vote over the last 5 analyses (about 0.4 s):
 *  - a zone turns ON when >= 3 of 5 frames show it blocked, and OFF when <= 1 of 5 does;
 *  - SAFE needs >= 4 of 5 frames that are positively safe (tracking OK, depth OK, nothing blocked);
 *    it is dropped when <= 2 of 5 are;
 *  - anything else is UNCERTAIN, which is never reported as SAFE.
 *
 * UNCERTAIN becomes "signalUncertain" after 1 s, so one bad frame does not trigger a warning.
 * Not thread safe: call from the GL thread only (reset() while paused is fine).
 */
class StateStabilizer {

    companion object {
        private const val WINDOW = 5
        private const val ENTER_VOTES = 3
        private const val EXIT_VOTES = 1
        private const val SAFE_ENTER_VOTES = 4
        private const val SAFE_EXIT_VOTES = 2
        private const val UNCERTAIN_SIGNAL_DELAY_MS = 1000L

        private const val BIT_LEFT = 4
        private const val BIT_CENTER = 2
        private const val BIT_RIGHT = 1

        const val PACKET_SAFE = "000N"
        const val PACKET_UNCERTAIN = "000U"

        const val TEXT_SAFE = "🟢 SAFE PATH"
        const val TEXT_STABILIZING = "🔄 STABILIZING DEPTH..."
        const val TEXT_INITIALIZING = "🔄 INITIALIZING DEPTH..."

        private val PACKETS = arrayOf("000N", "001S", "010S", "011S", "100S", "101S", "110S", "111S")

        private val BANNERS = arrayOf(
            "🟢 SAFE PATH",
            "🔴 OBSTACLE <1m [RIGHT] - SHORT BUZZ",
            "🔴 OBSTACLE <1m [CENTRE] - SHORT BUZZ",
            "🔴 OBSTACLE <1m [CENTRE + RIGHT] - SHORT BUZZ",
            "🔴 OBSTACLE <1m [LEFT] - SHORT BUZZ",
            "🔴 OBSTACLE <1m [LEFT + RIGHT] - SHORT BUZZ",
            "🔴 OBSTACLE <1m [LEFT + CENTRE] - SHORT BUZZ",
            "🔴 OBSTACLE <1m [LEFT + CENTRE + RIGHT] - SHORT BUZZ"
        )
    }

    private val masks = IntArray(WINDOW)
    private val safeFlags = BooleanArray(WINDOW)
    private var filled = 0
    private var head = 0

    private var stableMask = 0
    private var verdict = Verdict.UNCERTAIN
    private var uncertainSinceMs = -1L

    fun reset() {
        filled = 0
        head = 0
        stableMask = 0
        verdict = Verdict.UNCERTAIN
        uncertainSinceMs = -1L
    }

    /**
     * Feed one analysis.
     * trackingMessage: null when ARCore tracking is OK, otherwise the "TRACKING PAUSED..." text.
     * Obstacle detection keeps working without tracking, but SAFE is never confirmed without it,
     * because depth may be stale while tracking is paused.
     */
    fun onAnalysis(result: DepthAnalysisResult, trackingMessage: String?, nowMs: Long): StableOutput {
        var mask = 0
        if (result.leftBlocked) mask = mask or BIT_LEFT
        if (result.centerBlocked) mask = mask or BIT_CENTER
        if (result.rightBlocked) mask = mask or BIT_RIGHT

        val safeFrame = (mask == 0) &&
                (trackingMessage == null) &&
                result.isSafePath &&
                !result.isDepthUnavailable &&
                !result.lowConfidence
        val unsureFrame = (mask == 0) && !safeFrame

        val unsureText = if (result.isDepthUnavailable || result.lowConfidence) {
            trackingMessage ?: result.bannerText
        } else {
            trackingMessage ?: TEXT_STABILIZING
        }

        masks[head] = mask
        safeFlags[head] = safeFrame
        head = (head + 1) % WINDOW
        if (filled < WINDOW) {
            filled++
        }

        var leftVotes = 0
        var centerVotes = 0
        var rightVotes = 0
        var safeVotes = 0
        for (i in 0 until filled) {
            val m = masks[i]
            if ((m and BIT_LEFT) != 0) leftVotes++
            if ((m and BIT_CENTER) != 0) centerVotes++
            if ((m and BIT_RIGHT) != 0) rightVotes++
            if (safeFlags[i]) safeVotes++
        }

        stableMask = updateBit(stableMask, BIT_LEFT, leftVotes)
        stableMask = updateBit(stableMask, BIT_CENTER, centerVotes)
        stableMask = updateBit(stableMask, BIT_RIGHT, rightVotes)

        val previous = verdict
        verdict = if (stableMask != 0) {
            Verdict.ACTIVE
        } else if (previous == Verdict.SAFE) {
            if (safeVotes <= SAFE_EXIT_VOTES) Verdict.UNCERTAIN else Verdict.SAFE
        } else {
            if (safeVotes >= SAFE_ENTER_VOTES) Verdict.SAFE else Verdict.UNCERTAIN
        }

        if (verdict == Verdict.UNCERTAIN) {
            if (uncertainSinceMs < 0L) {
                uncertainSinceMs = nowMs
            }
        } else {
            uncertainSinceMs = -1L
        }

        return when (verdict) {
            Verdict.ACTIVE -> StableOutput(
                verdict = Verdict.ACTIVE,
                leftOn = (stableMask and BIT_LEFT) != 0,
                centerOn = (stableMask and BIT_CENTER) != 0,
                rightOn = (stableMask and BIT_RIGHT) != 0,
                packet = PACKETS[stableMask],
                banner = BANNERS[stableMask]
            )
            Verdict.SAFE -> StableOutput(
                verdict = Verdict.SAFE,
                leftOn = false,
                centerOn = false,
                rightOn = false,
                packet = PACKET_SAFE,
                banner = TEXT_SAFE
            )
            Verdict.UNCERTAIN -> StableOutput(
                verdict = Verdict.UNCERTAIN,
                leftOn = false,
                centerOn = false,
                rightOn = false,
                packet = PACKET_UNCERTAIN,
                banner = if (unsureFrame) unsureText else TEXT_STABILIZING,
                signalUncertain = (nowMs - uncertainSinceMs) >= UNCERTAIN_SIGNAL_DELAY_MS
            )
        }
    }

    /** Depth has been stale too long: clear all evidence and report UNCERTAIN right away. */
    fun onStale(text: String): StableOutput {
        filled = 0
        head = 0
        stableMask = 0
        verdict = Verdict.UNCERTAIN
        return StableOutput(
            verdict = Verdict.UNCERTAIN,
            leftOn = false,
            centerOn = false,
            rightOn = false,
            packet = PACKET_UNCERTAIN,
            banner = text,
            signalUncertain = true
        )
    }

    private fun updateBit(mask: Int, bit: Int, votes: Int): Int {
        val isOn = (mask and bit) != 0
        return if (isOn) {
            if (votes <= EXIT_VOTES) (mask and bit.inv()) else mask
        } else {
            if (votes >= ENTER_VOTES) (mask or bit) else mask
        }
    }
}
