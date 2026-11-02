# HAPTISIGHT AGENT SKILL & EXECUTION PROTOCOL (SKILL.md)

## 1. MANDATORY PRE-FLIGHT CHECK (READ FIRST ON EVERY PROMPT)
Before writing any code or running terminal commands, you MUST:
1. Read `SKILL.md` and `PROGRESS.md` in the project root.
2. Identify the single next unchecked `[ ]` phase in `PROGRESS.md`.
3. Execute ONLY that single phase. Never attempt two phases in one prompt.
4. Run verification (`./gradlew assembleDebug`), update `PROGRESS.md`, and stop.

## 2. TOKEN CONSERVATION & LOW-OVERHEAD RULES
- **Zero Fluff:** Never write introductions, project summaries, or basic Android tutorials.
- **Targeted Edits Only:** Modify only the specific files/lines required. Never echo unchanged code blocks in chat.
- **Ultra-Short Replies:** Keep chat output under 6 bullet points: Files Changed, Verification Result (`PASS`/`FAIL`), and Next Unchecked Phase.

## 3. ANTI-HALLUCINATION & 3-STRIKE CIRCUIT BREAKER
- **Zero Guessing:** Never invent ARCore APIs, hallucinate third-party libraries, or alter package names (`com.example.haptisight`). If an environment variable, SDK path, or hardware requirement is ambiguous, STOP and ask the user.
- **Mandatory Verification:** Every code phase MUST be verified via `./gradlew assembleDebug` before marking `[x]` in `PROGRESS.md`.
- **The 3-Strike Stop Rule:**
  - If any build, Gradle sync, or bug fix fails **3 consecutive times**, STOP IMMEDIATELY.
  - Do NOT attempt a 4th guess.
  - Log the exact compiler/runtime error in `PROGRESS.md` under `## Active Issue / Strike Count` and ask the user how to proceed.

## 4. HAPTISIGHT IMMUTABLE ENGINEERING SPECIFICATIONS

### 4.1 Project & Environment
- **App:** `HaptiSight` (`com.example.haptisight`), Kotlin, XML Views Activity (**NEVER use Jetpack Compose**).
- **SDK & Dependencies:** `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`. Add `implementation("com.google.ar:core:1.44.0")`, `androidx.appcompat:appcompat`, `com.google.android.material:material`, and `androidx.constraintlayout:constraintlayout`.
- **Manifest & Window Flags:**
  - Permissions: `android.permission.CAMERA`, `android.permission.VIBRATE`.
  - Features: `<uses-feature android:name="android.hardware.camera.ar" android:required="true" />`
  - Metadata inside `<application>`: `<meta-data android:name="com.google.ar.core" android:value="required" />`
  - Activity orientation: `android:screenOrientation="portrait"` and `android:configChanges="orientation|screenSize"`.
  - In `MainActivity.onCreate`: `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`.

### 4.2 OpenGL Camera Feed & ARCore Session Lifecycle
- Configure ARCore `Session` with `Config.DepthMode.RAW_DEPTH_ONLY` (fallback to `Config.DepthMode.AUTOMATIC` if raw depth is unsupported on device).
- Render the live camera feed on a full-screen `GLSurfaceView` (`EGLContextClientVersion = 2`) using an `OES` external texture (`GL_TEXTURE_EXTERNAL_OES` with `#extension GL_OES_EGL_image_external : require`).
- Call `session.setDisplayGeometry(displayRotation, width, height)` in `onSurfaceChanged` and transform texture coordinates via `frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD_COORDS, Coordinates2d.TEXTURE_NORMALIZED, texCoords)` so the portrait camera preview is never stretched or rotated.
- Catch `NotYetAvailableException` every frame when calling `frame.acquireRawDepthImage16Bits()` during camera warmup without crashing.
- Always close both `depthImage` and `confidenceImage` inside a `finally` block on every frame to prevent memory exhaustion.

### 4.3 Portrait Coordinate Mapping, Top 85% ROI & Hybrid Depth Engine
- **Byte Buffer & Stride Handling:**
  - Raw Depth plane (`planes[0]`): Read `ByteBuffer` with `.order(ByteOrder.LITTLE_ENDIAN)`. Calculate byte index using `row * depthRowStride + col * depthPixelStride`. Convert 16-bit short to unsigned millimeters: `val depthMm = buffer.getShort(index).toInt() and 0xFFFF`.
  - Confidence plane (`planes[0]`): Calculate byte index using `row * confRowStride + col * confPixelStride`. Convert byte to unsigned int (`0..255`): `val conf = confBuffer.get(index).toInt() and 0xFF`.
- **Sensor-to-Portrait Rotation Mapping (90° CW Rear Camera):**
  - Raw depth images have dimensions `(rawW, rawH)` in sensor landscape orientation. Map every sensor pixel `(col, row)` where `0 <= col < rawW` and `0 <= row < rawH` to normalized **Portrait Screen Coordinates `(normX, normY)`** in `0.0f..1.0f`:
    - `normX = 1.0f - (row.toFloat() / rawH.toFloat())` (0.0 = Left edge of portrait screen, 1.0 = Right edge)
    - `normY = col.toFloat() / rawW.toFloat()` (0.0 = Top edge of portrait screen, 1.0 = Bottom edge)
- **Top 85% Active Scanning ROI:**
  - Active detection scans pixels where `normY` is between `0.08f` and `0.85f` (Top 85% of portrait screen, ignoring bottom 15% floor/cane space).
  - Divide `normX` into 3 horizontal zones:
    - **Left Zone:** `0.00f <= normX < 0.333f`
    - **Center Zone:** `0.333f <= normX < 0.666f`
    - **Right Zone:** `0.666f <= normX <= 1.00f`
- **Hybrid Depth Acquisition & 15th Percentile Filter:**
  - Step A: Raw Depth + Confidence (`conf >= 45`, `depthMm in 250..4500`).
  - Step B: If raw depth yields `< 30` valid pixels or throws, fallback to smoothed depth (`0.15f..0.80f`) in the same tick.
  - For each zone with valid pixel count `>= 8` (at stride 3), sort valid `depthMm` values ascending and pick the element at index `(size * 0.15).toInt()` as that zone's closest surface distance `D_min` (in mm).

### 4.4 Style B Tight Dynamic Bounding Boxes & Merging Logic (`OverlayView.kt`)
- **Tight Pixel Clustering:**
  - Once a zone has a valid `D_min`, find matching pixels within `D_min` to `D_min + 220 mm` in that zone and take the 10th to 90th percentile of their `normX` and `normY` coordinates around the median, ensuring minimum box width and height `>= 0.12f`.
  - **Height & Floor Clamping:** Clamped to `maxNormY <= 0.85f` so bounding boxes never bleed into the bottom 15% floor space.
- **Color & Adjacency Merging Rules:**
  - Define a zone as **Blocked** if its `D_min <= 1200` mm.
  - **RED Merged Box (Wide Barrier Adjacency Rule):**
    - Triggered when two or three adjacent zones BOTH have `D_min <= 1200 mm` and `|D_zone1 - D_zone2| <= 200 mm`.
    - When true, merge participating adjacent blocked zones into **ONE single RED rectangle (`#FF1744`)** using `(minOf(minNormX), minOf(minNormY), maxOf(maxNormX), maxOf(maxNormY))`, with `D_merged = minOf(participating D_min)` and label `"WIDE BARRIER: X.XX m"`.
  - **YELLOW Box (Single / Narrow Obstacle):**
    - Any remaining Blocked zone (`D_min <= 1200 mm` that did not merge into a Red box) draws its own tight **YELLOW rectangle (`#FFD600`)** with label `"SINGLE: X.XX m"`.
  - **GREEN Box (Safe Surface):**
    - Any zone with `1200 mm < D_min <= 4500 mm` draws a tight **GREEN rectangle (`#00E676`)** with label `"SAFE: X.XX m"`.
- **Overlay Drawing Rules:**
  - Draw a horizontal white/cyan dashed cutoff line across the screen at `Y = 0.85f * viewHeight` labeled `"85% DETECTION ROI"`.
  - Draw a dark semi-transparent background pill behind every distance label. If `boxTop < 60f`, draw the label pill *inside* the top-left corner of the rectangle so it never clips off the top edge.
  - Always invoke UI updates (`OverlayView.setDetectionResult(...)` and `statusBanner.text = ...`) on the Main UI Thread (`runOnUiThread`).

### 4.5 Version-Safe Haptic Engine (`HapticController.kt`)
- Use `VibratorManager` on `API >= 31` and `@Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE)` on `API 26..30`.
- Apply alarm usage priority safely:
  - On `API >= 33`: Pass `VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()`.
  - On `API 26..32`: Pass `@Suppress("DEPRECATION") AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()`.
- **Vibration Patterns & Cooldowns (Max Amplitude `255`):**
  - **Muted State:** If user toggled Mute ON, cancel vibrations and do not buzz.
  - **Sensor Blindness Fallback:** If total valid pixels across the Top 75% ROI is `< 15%` of total ROI pixels continuously for `> 1500 ms` (after initial 3-second camera warmup), show banner `⚠️ LOW DEPTH CONFIDENCE` and trigger 3 slow pulses (`timings = [0, 200, 200, 200, 200, 200]`, `amplitudes = [0, 255, 0, 255, 0, 255]`, `2500 ms` cooldown).
  - **Doorway Rule (`Left` and `Right` blocked `<= 2000 mm`, but `Center` is Safe `> 2000 mm`):** Keep vibration **SILENT** so user can walk cleanly through open doorways; show Yellow boxes on sides and banner `🟢 CENTER PATH CLEAR (DOORWAY)`.
  - **Wide Barrier (Any RED Box present):**
    - Banner: `🔴 WIDE BARRIER - LONG BUZZ`
    - If `D_merged < 1000 mm`: `800 ms` continuous pulse at `255` amplitude (`1200 ms` cooldown).
    - If `1000 mm <= D_merged <= 2000 mm`: `600 ms` continuous pulse at `255` amplitude (`1200 ms` cooldown).
  - **Single Obstacle (Any YELLOW Box present, not Doorway):**
    - Banner: `🟡 SINGLE OBJECT - SHORT BUZZ` (use the minimum `D_min` among Yellow boxes).
    - If `D_min < 1000 mm`: Fast double pulse `timings = [0, 150, 100, 150]`, `amplitudes = [0, 255, 0, 255]` (`1000 ms` cooldown).
    - If `1000 mm <= D_min <= 2000 mm`: Single short pulse `300 ms` at `255` amplitude (`1000 ms` cooldown).
  - **Safe Path (All zones `> 2000 mm`):**
    - Banner: `🟢 SAFE PATH` — Silent.

## 5. MANDATORY POST-TASK DOCUMENTATION
At the end of every turn, update `PROGRESS.md` with:
1. `[x]` on the completed phase.
2. Modified file paths and `./gradlew assembleDebug` verification status (`PASS`/`FAIL`).
3. Exact pointer to the next unchecked phase.

## 6. MASTER CHECKLIST TEMPLATE (FOR `PROGRESS.md`)
# HaptiSight Development Progress (PROGRESS.md)
**Current Status:** Initialized - Ready for Phase 1
**Active Issue / Strike Count:** None (0/3)

## Phase Checklist
- [ ] **Phase 1: Gradle & Manifest Configuration** (Configure `app/build.gradle.kts` with ARCore `1.44.0`, `minSdk 26`, `compileSdk 34`; configure `AndroidManifest.xml` with Camera, Vibrate, Portrait lock, and ARCore required metadata; verify with `./gradlew assembleDebug`).
- [ ] **Phase 2: UI Layout & Style B OverlayView** (Build `activity_main.xml` with top Status Banner, full-screen `GLSurfaceView`, `OverlayView`, and bottom Pause/Mute toggle button; implement `OverlayView.kt` with 75% dashed ROI line, Green/Yellow/Red dynamic boxes, and clamped text pills; verify build).
- [ ] **Phase 3: OpenGL Background Renderer & ARCore Session** (Create `CameraBackgroundRenderer.kt` with external OES shader and coordinate transformation; wire up ARCore `Session` lifecycle, runtime Camera permission request, and `RAW_DEPTH_ONLY` config in `MainActivity.kt`; verify build).
- [ ] **Phase 4: 16-Bit Depth Scanner & 3-Zone Adjacency Engine** (Create `DepthAnalyzer.kt` with `ByteOrder.LITTLE_ENDIAN` stride-safe parsing, 90° portrait coordinate mapping, Top 75% ROI filter, 10th percentile `D_min`, tight pixel bounds with full-height extension, and Red/Yellow/Green merging; verify build).
- [ ] **Phase 5: Version-Safe Haptic Controller & MainActivity Integration** (Create `HapticController.kt` with API 26–34 compatibility, `USAGE_ALARM`, `255` amplitude patterns, doorway silence rule, low-confidence triple-pulse fallback, and Mute button wiring in `MainActivity.kt`; verify build).
- [ ] **Phase 6: Production Audit & Final APK Build** (Verify `depthImage.close()` and `confidenceImage.close()` in `finally` blocks, verify `runOnUiThread` safety, run clean `./gradlew assembleDebug`, and confirm APK output at `app/build/outputs/apk/debug/app-debug.apk`).

## Execution Log
- Session initialized.

