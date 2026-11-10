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
    UNCERTAIN,
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

    companion object {
        // Set to false to restore the old behaviour (phone silent in uncertain states).
        private const val SIGNAL_WHEN_UNCERTAIN = true
        private const val STARTUP_GRACE_MS = 3000L
        private const val UNCERTAIN_REPEAT_MS = 3000L

        // Three short, light ticks: clearly different from the strong 200 ms obstacle buzz.
        private val UNCERTAIN_TIMINGS = longArrayOf(0, 60, 90, 60, 90, 60)
        private val UNCERTAIN_AMPLITUDES = intArrayOf(0, 140, 0, 140, 0, 140)
    }

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
    private var lastUncertainSignalMs: Long = 0L
    private var sessionStartMs: Long = 0L

    /**
     * Resets session timing when camera/tracking starts or resumes.
     */
    fun startSession() {
        lastVibrationTimeMs = 0L
        currentCooldownMs = 0L
        lastObstacleDetectedTimeMs = 0L
        lastState = HapticState.SAFE
        lastUncertainSignalMs = 0L
        sessionStartMs = SystemClock.uptimeMillis()
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
        if (lastState == HapticState.ACTIVE_OBSTACLE && (now - lastObstacleDetectedTimeMs >= 400L)) {
            cancel()
            lastState = HapticState.UNCERTAIN
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
     * Applies the stabilized decision from [StateStabilizer] and returns the banner text.
     *
     *  - ACTIVE    : uniform Short Buzz (200 ms ON / 300 ms cooldown) while any zone is blocked
     *  - SAFE      : silence (running vibration is cancelled once)
     *  - UNCERTAIN : obstacle vibration stops; if the uncertainty persists (signalUncertain) three
     *                light ticks play at most every 3 s, so silence only ever means SAFE.
     *                Ticks are suppressed for the first 3 s after launch.
     */
    fun onStableOutput(output: StableOutput): String {
        val now = SystemClock.uptimeMillis()

        if (isMuted) {
            cancel()
            lastState = HapticState.SAFE
            return "${output.banner} (MUTED)"
        }

        when (output.verdict) {
            Verdict.ACTIVE -> {
                lastObstacleDetectedTimeMs = now
                val entering = (lastState != HapticState.ACTIVE_OBSTACLE)
                if (entering || (now - lastVibrationTimeMs >= currentCooldownMs)) {
                    playOneShot(200L, 255, 300L, now)
                }
                lastState = HapticState.ACTIVE_OBSTACLE
            }
            Verdict.SAFE -> {
                if (lastState != HapticState.SAFE) {
                    cancel()
                }
                lastState = HapticState.SAFE
            }
            Verdict.UNCERTAIN -> {
                if (lastState == HapticState.ACTIVE_OBSTACLE) {
                    cancel()
                }
                lastState = HapticState.UNCERTAIN

                val pastStartup = (now - sessionStartMs >= STARTUP_GRACE_MS)
                val cooledDown = (lastUncertainSignalMs == 0L) ||
                        (now - lastUncertainSignalMs >= UNCERTAIN_REPEAT_MS)
                if (SIGNAL_WHEN_UNCERTAIN && output.signalUncertain && pastStartup && cooledDown) {
                    playWaveform(UNCERTAIN_TIMINGS, UNCERTAIN_AMPLITUDES, UNCERTAIN_REPEAT_MS, now)
                    lastUncertainSignalMs = now
                }
            }
        }
        return output.banner
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
