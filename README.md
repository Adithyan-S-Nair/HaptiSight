# HaptiSight

**HaptiSight** is an Android assistive navigation application designed for visually impaired users. It leverages Google ARCore Depth APIs to continuously scan the surrounding environment, detect obstacles across horizontal zones, and provide real-time directional haptic feedback.

---

## Architecture & System Overview

- **Preview & Rendering:** Full-screen OpenGL ES 2.0 camera feed using external OES textures (`CameraBackgroundRenderer.kt`) matching normalized device coordinates.
- **Depth Analysis Engine:** Hybrid depth processing (`DepthAnalyzer.kt`) utilizing 16-bit raw depth with confidence validation, dynamically falling back to smoothed dense depth for low-texture surfaces.
- **Active Scanning ROI:** Scans the upper 85% of the portrait screen (`normY: 0.08f..0.85f`), intentionally excluding the bottom 15% floor area (handled by a physical cane).
- **3-Zone Adjacency & Clustering:** Divides the field of view into Left (`0.00..0.33`), Center (`0.33..0.66`), and Right (`0.66..1.00`) zones. Merges adjacent blocked zones within 250 mm into a single Wide Barrier.
- **Haptic Engine:** Version-safe, low-latency haptic vibration feedback (`HapticController.kt`) supporting Android API 26 through 34.

---

## 5 Application Safety States

HaptiSight strictly distinguishes between the following states to prevent false reassurance:

| State | Status Banner | Vibration Pattern | Overlay Visualization |
|---|---|---|---|
| **1. SAFE PATH** | `🟢 SAFE PATH` | Silent (all vibrations cancelled) | Green bounding boxes (`SAFE: X.XX m`) on surfaces > 1.2 m |
| **2. OBSTACLE (WIDE BARRIER)** | `🔴 WIDE BARRIER - LONG BUZZ` | Long continuous pulse (650 ms, 1100 ms cooldown) | Red merged bounding box (`WIDE BARRIER: X.XX m`) |
| **2. OBSTACLE (SINGLE OBJECT)** | `🟡 SINGLE OBJECT - SHORT BUZZ` | Single short pulse (250 ms) or double pulse if < 0.8 m | Yellow bounding box (`SINGLE: X.XX m`) |
| **3. LOW CONFIDENCE** | `⚠️ LOW DEPTH CONFIDENCE` | Silent (vibrations stopped) | Outdated overlays cleared |
| **4. DEPTH UNAVAILABLE** | `⚠️ DEPTH UNAVAILABLE` | Silent (vibrations stopped) | Outdated overlays cleared |
| **5. TRACKING ERROR** | `⚠️ TRACKING PAUSED - ...` | Silent (vibrations stopped) | Outdated overlays cleared |

> **Critical Safety Guarantee:** A lack of depth data is never interpreted as a clear path. When depth becomes stale (> 800 ms) or tracking is lost, HaptiSight immediately cancels active vibrations, clears overlays, and alerts the user.

---

## Prerequisites for Physical Device Testing

1. **ARCore-Compatible Device:** A physical Android phone supporting ARCore Depth API (e.g., Google Pixel 4+, Samsung Galaxy S20+, Xiaomi with ARCore support). The Depth API requires a physical device and cannot be verified on standard emulators.
2. **Google Play Services for AR:** Installed and updated to the latest version from the Google Play Store.
3. **Android OS:** Android 8.0 (API level 26) or higher.
4. **Android Studio:** Android Studio Iguana, Jellyfish, Koala, Ladybug, or newer.
5. **Connection:** USB cable (USB debugging) or local Wi-Fi (wireless debugging).

---

## Step-by-Step Testing Guide

### Method 1: USB Debugging via Android Studio

1. **Enable Developer Options on the Phone:**
   - Go to **Settings > About Phone**.
   - Tap **Build Number** 7 times until you see the message *"You are now a developer!"*.
2. **Enable USB Debugging:**
   - Go to **Settings > System > Developer Options** (or **Settings > Additional Settings > Developer Options** on Xiaomi/MIUI).
   - Toggle **USB Debugging** to **ON**.
   - *(Xiaomi/MIUI users)*: Also enable **Install via USB** and disable MIUI optimization if app installation is blocked.
3. **Connect to Computer:**
   - Connect the phone to your development computer via a USB cable.
   - When prompted on the phone with *"Allow USB debugging?"*, check *"Always allow from this computer"* and tap **Allow**.
4. **Open and Run in Android Studio:**
   - Open the `HaptiSight` project directory in Android Studio.
   - Wait for Gradle sync to complete.
   - In the top toolbar device selector, choose your connected physical device.
   - Click **Run** (`Shift + F10`) or execute in terminal:
     ```bash
     ./gradlew installDebug
     ```
5. **Monitor Logs:**
   - Open the **Logcat** window in Android Studio (`Alt + 6` / `Cmd + 6`).
   - Set the filter to:
     ```text
     package:mine tag:HaptiSight
     ```

---

### Method 2: Wireless Debugging (Android 11+)

1. **Connect to Same Wi-Fi:**
   - Ensure your computer and your Android test device are connected to the exact same Wi-Fi network.
2. **Enable Wireless Debugging on Phone:**
   - Go to **Settings > Developer Options > Wireless Debugging**.
   - Toggle **Wireless Debugging** to **ON** and confirm.
3. **Pair Device in Android Studio:**
   - In Android Studio, open the device dropdown menu and click **Pair Devices Using Wi-Fi**.
   - A dialog will show a QR code.
   - On your phone, tap **Pair device with QR code** under Wireless Debugging and scan the code.
4. **Alternative: Manual CLI Pairing via ADB:**
   - On the phone under Wireless Debugging, tap **Pair device with pairing code**. Note the IP address, port, and 6-digit code.
   - Run in terminal:
     ```bash
     adb pair <phone_ip>:<pairing_port>
     # Enter 6-digit pairing code when prompted
     ```
   - Connect using the main IP and port shown on the Wireless Debugging main screen:
     ```bash
     adb connect <phone_ip>:<connect_port>
     ```
   - Verify connection:
     ```bash
     adb devices
     ```
5. **Deploy & Test:**
   - Select the wirelessly connected device in Android Studio and click **Run**, or run:
     ```bash
     ./gradlew installDebug
     ```

---

## Collaborator Verification Matrix

| Test Scenario | Action | Expected Application Behavior |
|---|---|---|
| **App Startup** | Launch the application | Camera preview initializes. **No automatic startup vibration**. Status banner displays `🟢 SCANNING...`. |
| **Clear Corridor** | Aim camera down an open hallway (> 1.2 m) | Status banner: `🟢 SAFE PATH`. Green bounding boxes on distant walls/surfaces. **Zero vibration**. |
| **Single Obstacle** | Place a chair or stand in front of one zone (< 1.2 m) | Status banner: `🟡 SINGLE OBJECT - SHORT BUZZ`. Yellow bounding box on obstacle. Short distinct vibration pulse. |
| **Wide Barrier** | Stand within 1.0 m of a wide wall spanning center + sides | Status banner: `🔴 WIDE BARRIER - LONG BUZZ`. Merged red rectangle across zones. Long continuous vibration buzz. |
| **Camera Occlusion / Cover Lens** | Completely cover the camera lens with your hand | Tracking interrupted. Status banner: `⚠️ TRACKING PAUSED - TOO DARK` or `⚠️ DEPTH UNAVAILABLE`. Vibration immediately stops. Overlays clear. **Never reports SAFE PATH**. |
| **Rapid Motion** | Shake or pan the phone quickly | Status banner: `⚠️ TRACKING PAUSED - MOVING TOO FAST`. Overlays cleared. |
| **Low Light / Stale Depth** | Point into a dark corner with no depth return | Status banner: `⚠️ LOW DEPTH CONFIDENCE` or `⚠️ DEPTH UNAVAILABLE`. **Never reports SAFE PATH**. |
| **Mute Button** | Tap `MUTE HAPTICS` at bottom | Button turns red (`UNMUTE HAPTICS`). All vibrations cease. Status banner displays `... (MUTED)`. Tap again to unmute (confirmatory test buzz fires). |

---

## Troubleshooting Guide

- **Camera Not Available / Crashes on Launch:** Ensure camera permissions are granted. Verify Google Play Services for AR is installed and updated.
- **No Vibration on Xiaomi / POCO / Redmi Devices:** MIUI sometimes restricts background vibration or applies strict haptic energy saving. Go to phone **Settings > Sound & Vibration > Haptic Feedback** and ensure system haptics are enabled.
- **Depth Not Scanning:** Raw Depth and Dense Depth require sufficient ambient lighting. Avoid completely pitch-black rooms or highly reflective mirrors.
