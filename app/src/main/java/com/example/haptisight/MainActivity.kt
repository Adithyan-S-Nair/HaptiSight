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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var overlayView: OverlayView
    private lateinit var statusBanner: TextView
    private lateinit var muteButton: MaterialButton
    private lateinit var hapticController: HapticController

    private var session: Session? = null
    private var installRequested = false
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val depthAnalyzer = DepthAnalyzer()

    private var lastAnalysisTime: Long = 0L
    private var lastFrameTimestamp: Long = 0L
    private var lastValidDepthTimestamp: Long = 0L
    private var skipFrames: Int = 0

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            runOnUiThread {
                statusBanner.text = "🟢 CAMERA READY - INITIALIZING..."
            }
            resumeSession()
        } else {
            runOnUiThread {
                statusBanner.text = "⚠️ CAMERA PERMISSION REQUIRED"
            }
            Toast.makeText(this, "Camera permission is required to detect obstacles", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        overlayView = findViewById(R.id.overlayView)
        statusBanner = findViewById(R.id.statusBanner)
        muteButton = findViewById(R.id.muteButton)
        hapticController = HapticController(this)

        muteButton.setOnClickListener {
            hapticController.isMuted = !hapticController.isMuted
            if (hapticController.isMuted) {
                muteButton.text = "UNMUTE HAPTICS"
                muteButton.setBackgroundColor("#B71C1C".toColorInt())
            } else {
                muteButton.text = "MUTE HAPTICS"
                muteButton.setBackgroundColor("#212121".toColorInt())
                hapticController.triggerTestPulse()
            }
        }

        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        lastValidDepthTimestamp = 0L
        lastAnalysisTime = 0L
        lastFrameTimestamp = 0L
        skipFrames = 0
        hapticController.startSession()
        depthAnalyzer.reset()
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

                // Select the widest rear camera config supported by ARCore
                try {
                    val filter = CameraConfigFilter(newSession).setFacingDirection(CameraConfig.FacingDirection.BACK)
                    val supportedConfigs = newSession.getSupportedCameraConfigs(filter)
                    val widestConfig = supportedConfigs.maxByOrNull { it.imageSize.width * it.imageSize.height }
                    if (widestConfig != null) {
                        newSession.cameraConfig = widestConfig
                    }
                } catch (e: Exception) {
                    Log.e("HaptiSight", "Error configuring camera config", e)
                }

                val config = Config(newSession)

                // Configure AUTOMATIC depth mode with fallback to RAW_DEPTH_ONLY
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
                statusBanner.text = "🟢 SCANNING..."
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
        hapticController.stopAll()
        glSurfaceView.onPause()
        session?.pause()
        overlayView.clear()
        lastValidDepthTimestamp = 0L
    }

    override fun onDestroy() {
        super.onDestroy()
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

        // 2. Camera Tracking State Check (State 5: Camera/Tracking Error)
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            hapticController.stopAll()
            val failureReason = camera.trackingFailureReason
            val trackingMessage = when (failureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "⚠️ TRACKING PAUSED - TOO DARK"
                TrackingFailureReason.EXCESSIVE_MOTION -> "⚠️ TRACKING PAUSED - MOVING TOO FAST"
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "⚠️ TRACKING PAUSED - POOR TEXTURE"
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "⚠️ CAMERA UNAVAILABLE"
                TrackingFailureReason.BAD_STATE -> "⚠️ TRACKING ERROR"
                else -> "⚠️ TRACKING PAUSED - MOVE PHONE SLOWLY"
            }
            runOnUiThread {
                overlayView.clear()
                statusBanner.text = trackingMessage
            }
            return
        }

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

        // 5. Throttle depth analysis to once every 60ms (~16 FPS) for instantaneous zero-lag response
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTime < 60L) {
            // Check stale depth timeout (800ms)
            if (lastValidDepthTimestamp > 0L && (now - lastValidDepthTimestamp > 800L)) {
                hapticController.stopAll()
                runOnUiThread {
                    overlayView.clear()
                    statusBanner.text = "⚠️ DEPTH UNAVAILABLE"
                }
            } else {
                hapticController.checkWatchdog()
            }
            return
        }
        lastAnalysisTime = now

        // 6. Synchronous Hybrid Depth Analysis on GLThread (< 1.5ms)
        try {
            val result = depthAnalyzer.analyze(frame)
            if (!result.isDepthUnavailable && !result.lowConfidence) {
                lastValidDepthTimestamp = now
            }
            val bannerText = hapticController.onDetectionUpdated(result)
            val boxesCopy = result.boxes.toList()

            runOnUiThread {
                if (result.isDepthUnavailable || result.lowConfidence) {
                    overlayView.clear()
                } else {
                    overlayView.setDetectionResult(boxesCopy)
                }
                statusBanner.text = bannerText
            }
        } catch (e: ResourceExhaustedException) {
            Log.e("HaptiSight", "ResourceExhaustedException in depth analysis", e)
            System.gc()
            skipFrames = 2
        } catch (e: Exception) {
            Log.e("HaptiSight", "Depth frame processing exception", e)
            if (now - lastValidDepthTimestamp > 800L || lastValidDepthTimestamp == 0L) {
                hapticController.stopAll()
                runOnUiThread {
                    overlayView.clear()
                    statusBanner.text = "⚠️ DEPTH UNAVAILABLE"
                }
            }
        }
    }
}
