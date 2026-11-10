package com.example.haptisight

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.google.android.material.button.MaterialButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.EnumSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        // Glove warning ticks are suppressed while depth initializes after launch/resume.
        private const val UNCERTAIN_STARTUP_GRACE_MS = 3000L
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var overlayView: OverlayView
    private lateinit var statusBanner: TextView
    private lateinit var bleStatusBadge: TextView
    private lateinit var muteButton: MaterialButton
    private lateinit var hapticController: HapticController
    private lateinit var bleGloveController: BleGloveController

    private var session: Session? = null
    private var installRequested = false
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val depthAnalyzer = DepthAnalyzer()
    private val stabilizer = StateStabilizer()
    @Volatile private var lastBanner: String = ""
    @Volatile private var lastSentPacket: String = StateStabilizer.PACKET_SAFE

    private var lastAnalysisTime: Long = 0L
    private var lastFrameTimestamp: Long = 0L
    private var lastValidDepthTimestamp: Long = 0L
    private var sessionResumeTimestamp: Long = 0L
    private var skipFrames: Int = 0

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            runOnUiThread {
                statusBanner.text = "🔄 CAMERA READY - INITIALIZING..."
            }
            resumeSession()
        } else {
            runOnUiThread {
                statusBanner.text = "⚠️ CAMERA PERMISSION REQUIRED"
            }
            Toast.makeText(this, "Camera permission is required to detect obstacles", Toast.LENGTH_LONG).show()
        }
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleGloveController.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        overlayView = findViewById(R.id.overlayView)
        statusBanner = findViewById(R.id.statusBanner)
        bleStatusBadge = findViewById(R.id.bleStatusBadge)
        muteButton = findViewById(R.id.muteButton)
        hapticController = HapticController(this)
        bleGloveController = BleGloveController(this)
        bleGloveController.onStateChangedListener = { state ->
            updateBleBadge(state)
        }

        muteButton.setOnClickListener {
            hapticController.isMuted = !hapticController.isMuted
            if (hapticController.isMuted) {
                muteButton.text = "UNMUTE HAPTICS"
                muteButton.setBackgroundColor("#B71C1C".toColorInt())
                val currentText = statusBanner.text.toString()
                if (!currentText.endsWith("(MUTED)")) {
                    statusBanner.text = "$currentText (MUTED)"
                }
            } else {
                muteButton.text = "MUTE HAPTICS"
                muteButton.setBackgroundColor("#212121".toColorInt())
                val currentText = statusBanner.text.toString()
                statusBanner.text = currentText.removeSuffix(" (MUTED)")
                hapticController.triggerTestPulse()
            }
        }

        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun updateBleBadge(state: BleConnectionState) {
        runOnUiThread {
            when (state) {
                BleConnectionState.CONNECTED -> {
                    bleStatusBadge.text = "🧤 GLOVE: CONNECTED"
                    bleStatusBadge.setTextColor("#00E676".toColorInt())
                    bleStatusBadge.setBackgroundColor("#D91B5E20".toColorInt())
                }
                BleConnectionState.CONNECTING -> {
                    bleStatusBadge.text = "🧤 GLOVE: CONNECTING"
                    bleStatusBadge.setTextColor("#FFB74D".toColorInt())
                    bleStatusBadge.setBackgroundColor("#D9E65100".toColorInt())
                }
                BleConnectionState.SCANNING -> {
                    bleStatusBadge.text = "🧤 GLOVE: SCANNING"
                    bleStatusBadge.setTextColor("#80D8FF".toColorInt())
                    bleStatusBadge.setBackgroundColor("#D9212121".toColorInt())
                }
                BleConnectionState.DISCONNECTED -> {
                    bleStatusBadge.text = "🧤 PHONE HAPTICS"
                    bleStatusBadge.setTextColor("#B0BEC5".toColorInt())
                    bleStatusBadge.setBackgroundColor("#D9212121".toColorInt())
                }
            }
        }
    }

    private fun checkAndRequestBlePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            blePermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            bleGloveController.startScan()
        }
    }

    override fun onResume() {
        super.onResume()
        sessionResumeTimestamp = SystemClock.elapsedRealtime()
        lastValidDepthTimestamp = 0L
        lastAnalysisTime = 0L
        lastFrameTimestamp = 0L
        skipFrames = 0
        hapticController.startSession()
        stabilizer.reset()
        lastBanner = ""
        depthAnalyzer.reset()
        checkAndRequestBlePermissions()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        resumeSession()
    }

    private fun resumeSession() {
        var message: String? = null
        sessionResumeTimestamp = SystemClock.elapsedRealtime()

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                val newSession = Session(this)

                // Select balanced 30 FPS camera config supported by ARCore to eliminate overheating
                try {
                    val filter = CameraConfigFilter(newSession)
                        .setFacingDirection(CameraConfig.FacingDirection.BACK)
                        .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                    val supportedConfigs = newSession.getSupportedCameraConfigs(filter)
                    val bestConfig = supportedConfigs.minByOrNull {
                        val pixels = it.imageSize.width * it.imageSize.height
                        Math.abs(pixels - (1280 * 720))
                    } ?: supportedConfigs.firstOrNull()
                    if (bestConfig != null) {
                        newSession.cameraConfig = bestConfig
                    }
                } catch (e: Exception) {
                    Log.e("HaptiSight", "Error configuring camera config", e)
                }

                val config = Config(newSession)

                // Enable AUTOMATIC depth mode with fallback to RAW_DEPTH_ONLY
                if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                } else if (newSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                } else {
                    config.depthMode = Config.DepthMode.DISABLED
                }

                newSession.configure(config)
                session = newSession
            } catch (e: UnavailableArcoreNotInstalledException) {
                Log.e("HaptiSight", "ARCore not installed", e)
                message = "Please install Google Play Services for AR"
            } catch (e: UnavailableUserDeclinedInstallationException) {
                Log.e("HaptiSight", "User declined AR installation", e)
                message = "AR installation declined by user"
            } catch (e: UnavailableApkTooOldException) {
                Log.e("HaptiSight", "ARCore APK too old", e)
                message = "Please update ARCore"
            } catch (e: UnavailableSdkTooOldException) {
                Log.e("HaptiSight", "App SDK too old", e)
                message = "Please update this app"
            } catch (e: UnavailableDeviceNotCompatibleException) {
                Log.e("HaptiSight", "Device not compatible with AR", e)
                message = "This device does not support AR"
            } catch (e: Exception) {
                Log.e("HaptiSight", "Failed to create AR session", e)
                message = "Failed to create AR session: ${e.message}"
            }

            if (message != null) {
                runOnUiThread {
                    statusBanner.text = "⚠️ $message"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
            glSurfaceView.onResume()
            runOnUiThread {
                statusBanner.text = "🔄 INITIALIZING DEPTH..."
            }
        } catch (e: CameraNotAvailableException) {
            Log.e("HaptiSight", "Camera not available", e)
            runOnUiThread {
                statusBanner.text = "⚠️ CAMERA NOT AVAILABLE"
            }
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        bleGloveController.sendPacket("000N")
        bleGloveController.stopScan()
        stabilizer.reset()
        hapticController.stopAll()
        glSurfaceView.onPause()
        session?.pause()
        overlayView.clear()
        lastValidDepthTimestamp = 0L
    }

    override fun onDestroy() {
        super.onDestroy()
        bleGloveController.close()
        hapticController.stopAll()
        session?.close()
        session = null
    }

    // --- GLSurfaceView.Renderer implementation ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        if (viewportChanged) {
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            currentSession.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }

        // Set texture ID for ARCore camera feed
        currentSession.setCameraTextureName(backgroundRenderer.textureId)

        val frame = try {
            currentSession.update()
        } catch (e: Exception) {
            Log.e("HaptiSight", "Session update error", e)
            return
        }

        // 1. Draw camera feed onto full-screen surface with zero black bars (smooth 60 FPS)
        backgroundRenderer.draw(frame)

        // 2. Camera Tracking State Info.
        // Obstacle detection keeps running without tracking, but SAFE is never confirmed without it.
        val camera = frame.camera
        val isTracking = (camera.trackingState == TrackingState.TRACKING)
        val trackingMessage: String? = if (isTracking) null else trackingMessageFor(camera.trackingFailureReason)

        // 3. Recovery check after ResourceExhaustedException
        if (skipFrames > 0) {
            skipFrames--
            return
        }

        // 4. Frame Change Check (only analyze when camera frame changes)
        val frameTimestamp = frame.timestamp
        if (frameTimestamp == lastFrameTimestamp) {
            return
        }
        lastFrameTimestamp = frameTimestamp

        // 5. Throttle depth analysis to once every 80ms (~12.5 FPS) for cool, sustainable operation
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTime < 80L) {
            if (isDepthStale(now)) {
                publishStale(trackingMessage ?: "⚠️ DEPTH UNAVAILABLE", now)
            } else {
                hapticController.checkWatchdog()
            }
            return
        }
        lastAnalysisTime = now

        // 6. Synchronous Hybrid Depth Analysis on GLThread, then temporal stabilization
        try {
            val startTimeNs = System.nanoTime()
            val result = depthAnalyzer.analyze(frame)
            val latencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0f
            if (!result.isDepthUnavailable) {
                lastValidDepthTimestamp = now
            }

            val output = stabilizer.onAnalysis(result, trackingMessage, now)
            val bannerText = deliver(output, now)
            val showBoxes = (output.verdict != Verdict.UNCERTAIN)
            val boxesCopy = if (showBoxes) result.boxes.toList() else emptyList()
            val hudPacket = lastSentPacket
            lastBanner = bannerText

            runOnUiThread {
                overlayView.setDetectionResult(
                    boxes = boxesCopy,
                    leftActive = output.leftOn,
                    centerActive = output.centerOn,
                    rightActive = output.rightOn,
                    motorPatternCode = hudPacket,
                    latencyMs = latencyMs
                )
                statusBanner.text = bannerText
            }
        } catch (e: ResourceExhaustedException) {
            Log.e("HaptiSight", "ResourceExhaustedException in depth analysis", e)
            System.gc()
            skipFrames = 2
        } catch (e: Exception) {
            Log.e("HaptiSight", "Depth frame processing exception", e)
            if (isDepthStale(now)) {
                publishStale(trackingMessage ?: "⚠️ DEPTH UNAVAILABLE", now)
            }
        }
    }

    private fun trackingMessageFor(reason: TrackingFailureReason): String {
        return when (reason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "⚠️ TRACKING PAUSED - TOO DARK"
            TrackingFailureReason.EXCESSIVE_MOTION -> "⚠️ TRACKING PAUSED - MOVING TOO FAST"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "⚠️ TRACKING PAUSED - POOR TEXTURE"
            else -> "⚠️ TRACKING PAUSED - MOVE PHONE SLOWLY"
        }
    }

    /** Same rule as before: no depth for 800 ms (or none at all 800 ms after session start). */
    private fun isDepthStale(now: Long): Boolean {
        return (lastValidDepthTimestamp > 0L && (now - lastValidDepthTimestamp > 800L)) ||
                (lastValidDepthTimestamp == 0L && (now - sessionResumeTimestamp > 800L))
    }

    /**
     * Sends the decision to the glove (BLE packet) or the phone vibrator and returns the banner text.
     *  - glove packets: "xxxS" obstacle, "000N" safe/silent, "000U" uncertain warning
     *  - uncertain warnings are held back during startup and while muted
     */
    private fun deliver(output: StableOutput, now: Long): String {
        val startupDone = (now - sessionResumeTimestamp) >= UNCERTAIN_STARTUP_GRACE_MS
        val packet = if (hapticController.isMuted) {
            StateStabilizer.PACKET_SAFE
        } else if (output.verdict == Verdict.UNCERTAIN && !(output.signalUncertain && startupDone)) {
            StateStabilizer.PACKET_SAFE
        } else {
            output.packet
        }
        lastSentPacket = packet
        bleGloveController.sendPacket(packet)

        return if (bleGloveController.isConnected) {
            hapticController.cancel()
            if (hapticController.isMuted) "${output.banner} (MUTED)" else output.banner
        } else {
            hapticController.onStableOutput(output)
        }
    }

    /** Depth stale: report UNCERTAIN immediately. UI is only touched when the text changes. */
    private fun publishStale(text: String, now: Long) {
        val output = stabilizer.onStale(text)
        val bannerText = deliver(output, now)
        if (bannerText != lastBanner) {
            lastBanner = bannerText
            val hudPacket = lastSentPacket
            runOnUiThread {
                overlayView.setDetectionResult(
                    boxes = emptyList(),
                    leftActive = false,
                    centerActive = false,
                    rightActive = false,
                    motorPatternCode = hudPacket,
                    latencyMs = 0f
                )
                statusBanner.text = bannerText
            }
        }
    }
}
