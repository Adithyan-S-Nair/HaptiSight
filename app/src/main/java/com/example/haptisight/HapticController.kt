package com.example.haptisight

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class HapticState {
    SAFE,
    ACTIVE_OBSTACLE,
    @Deprecated("Legacy state retained for compatibility")
    SINGLE_OBSTACLE,
    @Deprecated("Removed long buzz in Phase 8")
    WIDE_BARRIER
}

/**
 * Version-Safe Haptic Engine supporting API 26-34 with direct vibration calls for Xiaomi MIUI compatibility.
 * Strictly triggers ONLY a uniform Short Buzz (200 ms pulse with 300 ms cooldown at amplitude 255)
 * whenever any zone is <= 1000 mm (motorPatternCode != "000N").
 * All Long Buzz waveforms are completely removed.
 * Immediately calls vibrator.cancel() when all zones are > 1000 mm ("000N"), muted, low confidence, or stale.
 */
class HapticController(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
            ?: @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var isMuted: Boolean = false
        set(value) {
            field = value
            if (value) {
                cancel()
            }
        }

    private var lastVibrationTimeMs: Long = 0L
    private var currentCooldownMs: Long = 0L
    private var lastObstacleDetectedTimeMs: Long = 0L
    private var lastState: HapticState = HapticState.SAFE

    /**
     * Resets session timing when camera/tracking starts or resumes.
     */
    fun startSession() {
        lastVibrationTimeMs = 0L
        currentCooldownMs = 0L
        lastObstacleDetectedTimeMs = 0L
        lastState = HapticState.SAFE
    }

    /**
     * Immediately stops active vibrations (called when user toggles MUTE ON, enters SAFE state, or watchdog fires).
     */
    fun cancel() {
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            Log.e("HaptiSight", "Error cancelling vibration", e)
        }
        currentCooldownMs = 0L
    }

    /**
     * Stops all active vibrations and resets state to SAFE.
     */
    fun stopAll() {
        cancel()
        lastState = HapticState.SAFE
    }

    /**
     * Watchdog: If no valid obstacle <= 1000 mm has been freshly computed within the last 400 ms,
     * force state to SAFE and cancel any running vibration.
     * Returns true if stale state was cleared to SAFE.
     */
    fun checkWatchdog(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (lastState != HapticState.SAFE && (now - lastObstacleDetectedTimeMs >= 400L)) {
            cancel()
            lastState = HapticState.SAFE
            return true
        }
        return false
    }

    /**
     * Immediate 200ms test buzz whenever app launches or user unmutes.
     */
    fun triggerTestPulse() {
        if (!vibrator.hasVibrator()) return
        try {
            val effect = VibrationEffect.createOneShot(200L, 255)
            vibrateInternal(effect)
        } catch (e: Exception) {
            Log.e("HaptiSight", "Error triggering test pulse", e)
        }
    }

    /**
     * Compatibility alias for onDetectionUpdated.
     */
    fun processResult(result: DepthAnalysisResult): String = onDetectionUpdated(result)

    /**
     * Evaluates depth analysis result, triggers uniform Short Buzz (200ms ON / 300ms cooldown)
     * if any zone is <= 1000 mm (motorPatternCode != "000N"), and returns the status banner text.
     *
     * STRICT SAFETY & SILENCE RULES:
     * - Trigger ONLY uniform Short Buzz (200 ms pulse with 300 ms cooldown at 255 amplitude)
     *   whenever any zone is <= 1000 mm (motorPatternCode != "000N").
     * - All Long Buzz waveforms are completely removed.
     * - Immediately calls vibrator.cancel() when all zones are > 1000 mm ("000N"), muted,
     *   low confidence, depth unavailable, or stale.
     */
    fun onDetectionUpdated(result: DepthAnalysisResult): String {
        val now = SystemClock.uptimeMillis()

        // 1. MUTED: immediately cancel vibration and report muted status
        if (isMuted) {
            cancel()
            lastState = HapticState.SAFE
            return "${result.bannerText} (MUTED)"
        }

        // 2. STALE / UNAVAILABLE / LOW CONFIDENCE: immediately cancel vibration
        if (result.isDepthUnavailable) {
            cancel()
            lastState = HapticState.SAFE
            return "⚠️ DEPTH UNAVAILABLE"
        }

        if (result.lowConfidence || (!result.isSafePath && result.boxes.isEmpty() && result.motorPatternCode == "000N")) {
            cancel()
            lastState = HapticState.SAFE
            return if (result.bannerText.isNotEmpty() && result.bannerText != "🟢 SAFE PATH") {
                result.bannerText
            } else {
                "⚠️ LOW DEPTH CONFIDENCE"
            }
        }

        // 3. ACTIVE OBSTACLE (<= 1000 mm / < 1.00m): Trigger ONLY uniform Short Buzz
        val isObstacleActive = result.motorPatternCode != "000N" ||
                result.leftBlocked || result.centerBlocked || result.rightBlocked

        if (isObstacleActive) {
            lastObstacleDetectedTimeMs = now
            val stateTransitionFromSafe = (lastState == HapticState.SAFE)
            if (stateTransitionFromSafe || (now - lastVibrationTimeMs >= currentCooldownMs)) {
                // Uniform Short Buzz: 200 ms pulse with 300 ms cooldown at amplitude 255
                playOneShot(200L, 255, 300L, now)
            }
            lastState = HapticState.ACTIVE_OBSTACLE
            return result.bannerText
        }

        // 4. SAFE / ALL ZONES > 1000 mm ("000N"): Immediately cancel vibration once on transition and stay silent
        if (lastState != HapticState.SAFE) {
            cancel()
            lastState = HapticState.SAFE
        }

        return if (result.isSafePath) "🟢 SAFE PATH" else result.bannerText
    }

    private fun playOneShot(durationMs: Long, amplitude: Int, cooldownMs: Long, now: Long) {
        if (!vibrator.hasVibrator()) return
        lastVibrationTimeMs = now
        currentCooldownMs = cooldownMs

        val effect = VibrationEffect.createOneShot(durationMs, amplitude)
        vibrateInternal(effect)
    }

    private fun vibrateInternal(effect: VibrationEffect) {
        try {
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.e("HaptiSight", "Vibration failed", e)
        }
    }
}
