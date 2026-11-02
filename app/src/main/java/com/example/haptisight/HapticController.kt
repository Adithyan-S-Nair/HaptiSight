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
    SINGLE_OBSTACLE,
    WIDE_BARRIER
}

/**
 * Version-Safe Haptic Engine supporting API 26-34 with direct vibration calls for Xiaomi MIUI compatibility.
 * Strictly restricts vibration exclusively to Yellow (<= 1200mm) and Red (<= 1000mm) obstacles.
 * Enforces zero-beep on Green and immediately cancels vibration when entering SAFE state.
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
     * Watchdog: If no valid obstacle <= 1200 mm has been freshly computed within the last 400 ms,
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
     * Immediate 250ms test buzz whenever app launches or user unmutes.
     */
    fun triggerTestPulse() {
        if (!vibrator.hasVibrator()) return
        try {
            val effect = VibrationEffect.createOneShot(250L, 255)
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
     * Evaluates depth analysis result, triggers corresponding vibration pattern,
     * and returns the active status banner text.
     *
     * STRICT SAFETY & SILENCE RULES:
     * - Vibrate ONLY when:
     *   - RED (D_merged <= 1000 mm): Long continuous pulse (650 ms, 1100 ms cooldown)
     *   - YELLOW (D_min <= 1200 mm): Short pulse (250 ms if 800-1200 mm, double-pulse [0, 180, 100, 180] if < 800 mm, 900 ms cooldown)
     * - If state is SAFE (only Green boxes or no boxes <= 1200 mm), immediately cancels vibration once and NEVER vibrates.
     * - Zero-vibration for low-confidence fallback or distant objects (> 1200 mm).
     */
    fun onDetectionUpdated(result: DepthAnalysisResult): String {
        val now = SystemClock.uptimeMillis()

        if (isMuted) {
            return "${result.bannerText} (MUTED)"
        }

        // 1. RED Wide Barrier (D_merged <= 1000 mm)
        if (result.isRedBarrier) {
            lastObstacleDetectedTimeMs = now
            val stateTransitionFromSafe = (lastState == HapticState.SAFE)
            if (stateTransitionFromSafe || (now - lastVibrationTimeMs >= currentCooldownMs)) {
                // Long continuous pulse: 650 ms, 1100 ms cooldown
                playOneShot(650L, 255, 1100L, now)
            }
            lastState = HapticState.WIDE_BARRIER
            return "🔴 WIDE BARRIER - LONG BUZZ"
        }

        // 2. YELLOW Single Obstacle (D_min <= 1200 mm)
        if (result.yellowDMin != null && result.yellowDMin <= 1200) {
            lastObstacleDetectedTimeMs = now
            val dMin = result.yellowDMin
            val stateTransitionFromSafe = (lastState == HapticState.SAFE)
            if (stateTransitionFromSafe || (now - lastVibrationTimeMs >= currentCooldownMs)) {
                if (dMin < 800) {
                    // Double pulse: [0, 180, 100, 180] at 255 amplitude (900 ms cooldown)
                    val timings = longArrayOf(0, 180, 100, 180)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    playWaveform(timings, amplitudes, 900L, now)
                } else {
                    // Single short pulse: 250 ms at 255 amplitude (900 ms cooldown)
                    playOneShot(250L, 255, 900L, now)
                }
            }
            lastState = HapticState.SINGLE_OBSTACLE
            return "🟡 SINGLE OBJECT - SHORT BUZZ"
        }

        // 3. SAFE / UNCERTAIN STATE: Only Green boxes (> 1200 mm), clear path, or non-obstacle fallback
        // Immediately call cancel() once on transition and NEVER vibrate.
        if (lastState != HapticState.SAFE) {
            cancel()
            lastState = HapticState.SAFE
        }

        if (result.isDepthUnavailable) {
            return "⚠️ DEPTH UNAVAILABLE"
        }

        if (result.lowConfidence) {
            return "⚠️ LOW DEPTH CONFIDENCE"
        }
        return "🟢 SAFE PATH"
    }

    private fun playOneShot(durationMs: Long, amplitude: Int, cooldownMs: Long, now: Long) {
        if (!vibrator.hasVibrator()) return
        lastVibrationTimeMs = now
        currentCooldownMs = cooldownMs

        val effect = VibrationEffect.createOneShot(durationMs, amplitude)
        vibrateInternal(effect)
    }

    private fun playWaveform(timings: LongArray, amplitudes: IntArray, cooldownMs: Long, now: Long) {
        if (!vibrator.hasVibrator()) return
        lastVibrationTimeMs = now
        currentCooldownMs = cooldownMs

        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
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
