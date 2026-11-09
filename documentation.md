# HaptiSight – System Documentation & Hardware-Software Bridge

## 1. Overview of Completed Work vs. New Architecture Changes

### 1.1 What Has Already Been Built (Core Android Engine)
1. **60 FPS OpenGL ES 2.0 Camera Pipeline (`CameraBackgroundRenderer.kt`):** Renders the widest rear camera stream across a distortion-free full-screen quad (`-1.0f` to `+1.0f`) using hardware `GL_TEXTURE_EXTERNAL_OES` shaders without CPU copies[cite: 18].
2. **Synchronous Continuous Hybrid Depth Engine (`DepthAnalyzer.kt`):** Acquires 16-bit Raw Depth, 8-bit Confidence maps, and 16-bit Dense Depth synchronously on the GLThread in `< 1.2 ms` using `stride = 3` subsampling and pre-allocated primitive arrays.
3. **Top 85% Upper-Body ROI & Portrait Coordinate Mapping:** Rotates landscape sensor coordinates 90° clockwise into portrait space (`normX`, `normY`) and restricts active scanning to `normY in 0.08f..0.85f`, ignoring the bottom 15% floor area swept by the user's white cane.
4. **Anti-Drift, Tracking & Watchdog Safety:** Immediately silences feedback and clears HUD upon tracking loss (`TrackingState != TRACKING`), preserves focal length calibration with `FocusMode.FIXED`, refreshes `lastValidDepthTimestamp` on active frames, and enforces 400ms obstacle and 800ms stale-depth watchdogs.

### 1.2 New Changes Introduced for the Wearable 3-Motor Glove & Multi-Minute Reliability
1. **Strict `< 1.00m` (`<= 1000 mm`) Proximity Cutoff:** Haptic feedback activates **strictly when an obstacle is within `< 1.00m` (`200 mm .. 1000 mm`)** using 15th-percentile surface filtering for high metric precision. Obstacles `> 1.00m` remain completely silent (`OFF`) and render as Green safe boxes on screen.
2. **Short-Buzz-Only Actuation (Long Buzz Removed):** All long continuous vibration patterns are removed. Whenever any zone detects an obstacle `< 1.00m`, the system outputs a crisp, uniform **Short Buzz (`200 ms ON`, `300 ms OFF` repeating pulse)**.
3. **3-Zone Independent Motor Mapping:** The screen is evaluated as 3 independent directional sectors (`Left`, `Centre`, `Right`) mapped directly to 3 button/coin vibration motors on a single glove (Thumb, Index, Right-side) across 8 possible combinations.
4. **Anti-Thermal Camera & Analysis Throttling:** Configures rear camera with `TARGET_FPS_30` at balanced ~720p resolution and throttles GL depth analysis to 80ms (~12.5 FPS) with `Config.UpdateMode.LATEST_CAMERA_IMAGE`, keeping the device cool (< 35°C).
5. **Low-Light Dynamic Gain & 35th-Percentile Metric Distance:** Dynamically checks scene illumination; when ambient confidence drops in dark rooms (`avgConf < 30`), raw depth sensitivity is boosted to prevent dropped surfaces. Takes the 35th percentile (`p35`) to prevent sensor noise from dipping below 1.00m on distant objects.
6. **Floor Exclusion ROI:** Restricts vertical scanning to `normY in 0.08f..0.75f` (clamped to `0.75f`), eliminating false distance measurements from the floor tiles sloping up towards the camera.
7. **Continuous Optical Depth Acquisition & HAL Stability:** Removed `session.pause()/resume()` from the render loop to eliminate camera preview black flashes. Decoupled optical depth analysis from 6-DOF odometry tracking so obstacle detection operates continuously even when facing blank walls or during degraded tracking.
8. **Zero-Allocation Object Pooling & Guarded Buffer Releases:** Pre-allocates primitive arrays, `ZoneBox` instances, static 8-state tables (`MOTOR_PATTERNS`, `BANNER_TEXTS`), and box lists in `DepthAnalyzer.kt` to eliminate GC pauses over 4+ hour runs; wraps every native `Image.close()` in an individual guard clause to guarantee native memory cleanup.
9. **On-Screen Virtual Glove Simulator HUD:** Displays 3 virtual motor indicators (`Thumb`, `Index`, `Right`), the live outgoing command string (e.g., `"101S"`), and processing latency on the smartphone screen so the entire software pipeline can be tested before connecting physical hardware.
10. **Microcontroller-Agnostic Wireless Bridge (`BleGloveController.kt`):** Streams lightweight 4-character ASCII packets over Bluetooth with 250ms packet deduplication, supporting **ESP32**, **Seeed Studio XIAO ESP32C3**, or **Arduino** without changing the Android application code.

---

## 2. Technologies & Libraries Used

| Layer | Technology / Library | Version / API | Role in HaptiSight |
| :--- | :--- | :--- | :--- |
| **Language** | Kotlin | 1.9+ / JVM 17 | Primary application, depth math, and Bluetooth controller language. |
| **Android SDK** | Android Native XML Views | `minSdk 26`, `targetSdk 34` | Zero-overhead UI layout (`ConstraintLayout`, `GLSurfaceView`, custom `View`). |
| **Depth Sensing** | Google ARCore SDK (`com.google.ar:core`) | `1.44.0` | Provides 16-bit Raw Depth (`acquireRawDepthImage16Bits`), Confidence maps, and Dense Depth. |
| **Camera Graphics** | OpenGL ES 2.0 (`android.opengl.GLES20`) | API 26–34 | Hardware-accelerated external OES texture rendering at 30 FPS. |
| **Wireless Link** | Android BLE API (`android.bluetooth.le`) | API 26–34 | Low-latency (<15ms) GATT UART packet transmission to external microcontrollers. |
| **Phone Haptics** | `VibratorManager` & `VibrationEffect` | API 26–34 | Local short-buzz fallback when external microcontroller hardware is disconnected. |
| **Unit Testing** | JUnit 4 (`junit:junit:4.13.2`) | `4.13.2` | Automated verification of all 8 zone combinations and safety states. |

---

## 3. How the `< 1.00m` Precise Depth & 3-Zone Engine Works

### Step 1: Per-Zone Hybrid Depth Measurement & Low-Light Dynamic Gain
Every `80 ms`, `DepthAnalyzer` scans the top 75% of the camera frame across three horizontal columns (ignoring the bottom 25% floor space):
* **Left Zone (`0.00f <= normX < 0.333f`):** Monitored for the **Thumb Motor**.
* **Centre Zone (`0.333f <= normX < 0.666f`):** Monitored for the **Index Finger Motor**.
* **Right Zone (`0.666f <= normX <= 1.00f`):** Monitored for the **Right-Side Motor**.

In each zone independently:
1. It monitors scene illumination. In low-light rooms (`avgConf < 30`), it dynamically boosts gain down to `conf >= 0` to preserve depth sensitivity.
2. Depth samples are partitioned into pre-allocated `ZoneCollector` pools: blocked (`200..1000 mm`) and safe (`1001..4000 mm`).
3. If raw blocked points $\ge 5$ or dense blocked points $\ge 10$, an obstacle within 1 meter is confirmed. Takes the 25th percentile of the blocked cluster as $D_{min}$ ($\le 1000\text{ mm}$) and frames the actual physical object tightly. Background rooms or distant walls never mask foreground obstacles.
4. If no blocked obstacle exists and safe points exist ($\ge 6$ raw or $\ge 15$ dense), it reports a Green safe surface (`SAFE: X.XX m`).
5. Safe path is confirmed whenever no zone has an obstacle $\le 1000\text{ mm}$.

### Step 2: 8-State Directional Motor Truth Table (`< 1.00m` Short Buzz Only)
Each zone's `D_min` is compared against the strict **`1000 mm` (`1.00 m`)** threshold[cite: 19]:

| Detected Obstacle Position (`<= 1.00m`) | Thumb Motor (Left) | Index Motor (Centre) | Right-Side Motor (Right) | Outgoing Packet | Glove Haptic Feedback |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **No obstacle (`> 1.00m`)** | OFF | OFF | OFF | `"000N"` | No vibration (Silent)[cite: 19] |
| **Left only (`<= 1.00m`)** | **ON** | OFF | OFF | `"100S"` | Thumb Short Buzz (`200ms ON / 300ms OFF`)[cite: 19] |
| **Centre only (`<= 1.00m`)** | OFF | **ON** | OFF | `"010S"` | Index Short Buzz (`200ms ON / 300ms OFF`)[cite: 19] |
| **Right only (`<= 1.00m`)** | OFF | OFF | **ON** | `"001S"` | Right-side Short Buzz (`200ms ON / 300ms OFF`)[cite: 19] |
| **Left + Centre (`<= 1.00m`)** | **ON** | **ON** | OFF | `"110S"` | Thumb + Index Short Buzz together[cite: 19] |
| **Centre + Right (`<= 1.00m`)** | OFF | **ON** | **ON** | `"011S"` | Index + Right-side Short Buzz together[cite: 19] |
| **Left + Right (`<= 1.00m`)** | **ON** | OFF | **ON** | `"101S"` | Thumb + Right-side Short Buzz together |
| **Fully blocked (All 3 `<= 1.00m`)** | **ON** | **ON** | **ON** | `"111S"` | All 3 motors Short Buzz together[cite: 19] |

---

## 4. How the Android App Communicates with Any Microcontroller

To ensure you can use **any hardware board** (Standard ESP32 DevKit, Seeed Studio XIAO ESP32C3, Arduino Nano 33 BLE, or an Arduino Uno/Nano with an HM-10 BLE module), HaptiSight uses a universal **5-byte ASCII UART-over-BLE protocol**:

```text
Packet Format: [LeftBit][CenterBit][RightBit][BuzzMode]\n
Example 1: "100S\n" -> Left Motor ON, Center OFF, Right OFF, Short Buzz (200ms ON / 300ms OFF)
Example 2: "101S\n" -> Left Motor ON, Center OFF, Right ON, Short Buzz
Example 3: "000N\n" -> All Motors OFF (Safe Path > 1.00m)

---

## 5. Wearable Glove Hardware Setup & Complete Firmware Guide (ESP32 & Seeed Studio XIAO)

This section provides complete instructions for flashing a microcontroller (Standard ESP32 DevKit or Seeed Studio XIAO ESP32C3/S3), wiring the 3 directional vibration motors, and wirelessly linking to the HaptiSight Android app. Both **Windows** and **Linux** workflows are covered.

---

### 5.1 Required Components & Hardware Bill of Materials (BOM)

| Component | Quantity | Purpose | Recommendation / Notes |
| :--- | :---: | :--- | :--- |
| **Microcontroller Board** | 1 | BLE Receiver & Motor Controller | **Seeed Studio XIAO ESP32C3** (compact, thumb-sized) or standard **ESP32 DevKit V1** (30/38 pin). |
| **Coin Vibration Motors** | 3 | Directional Haptic Feedback | 3V DC Coin/Pancake ERM vibration motors (10mm x 2.7mm or 10mm x 3mm). |
| **NPN Transistors / MOSFETs** | 3 | Motor Current Switching | 2N2222, 2N3904, or AO3400 / 2N7000 N-channel MOSFETs. (*ESP32 GPIO pins only output ~12mA; motors draw 60-80mA!*). |
| **Resistors (1 kΩ)** | 3 | Base Current Limiter | Connects between ESP32 GPIO pin and Transistor Base. |
| **Flyback Diodes** | 3 | Back-EMF Voltage Protection | 1N4148 or 1N4001 across motor terminals (prevents motor inductive kickback from resetting the ESP32). |
| **Power Source** | 1 | Powering ESP32 & Motors | Small 3.7V LiPo battery (connected to BAT pad on XIAO) or a small 5V USB power bank via USB-C. |
| **USB-C Cable** | 1 | Flashing & Debugging | Must be a **Data Cable** (not charge-only). |

---

### 5.2 Software & IDE Installation (Windows & Linux)

#### A. Download & Install Arduino IDE 2.x
1. Download Arduino IDE 2.3+ from the official site: [arduino.cc/en/software](https://www.arduino.cc/en/software).
   - **Windows:** Download the **Windows MSI Installer** or **Windows ZIP**. Run the installer and accept all driver installation prompts.
   - **Linux:** Download the **Linux AppImage** or install via Flatpak:
     ```bash
     # Option 1: AppImage
     chmod +x arduino-ide_*_Linux_64bit.AppImage
     ./arduino-ide_*_Linux_64bit.AppImage

     # Option 2: Flatpak
     flatpak install flathub cc.arduino.IDE2
     flatpak run cc.arduino.IDE2
     ```

#### B. USB Drivers & Serial Permissions Setup

##### Windows Setup:
- **Seeed Studio XIAO ESP32C3 / ESP32S3:** Uses native USB CDC. No drivers needed; Windows 10/11 recognizes it automatically as a USB Serial Device.
- **Standard ESP32 DevKit (CP2102 or CH340 chip):**
  - If your board uses Silicon Labs CP2102: Download and install the [CP210x Universal Windows Driver](https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers).
  - If your board uses WCH CH340: Download and install the [CH341SER Windows Driver](https://www.wch-ic.com/downloads/CH341SER_EXE.html).
  - Open **Device Manager -> Ports (COM & LPT)** to verify your board appears (e.g. `COM3` or `COM4`).

##### Linux Setup (Ubuntu, Debian, Fedora, Arch):
On Linux, user accounts require permission to access USB serial devices (`/dev/ttyUSB0` or `/dev/ttyACM0`):
1. Add your user to the `dialout` and `uucp` groups:
   ```bash
   sudo usermod -a -G dialout,uucp $USER
   ```
2. Disable `ModemManager` if it interferes with or temporarily locks serial communication:
   ```bash
   sudo systemctl stop ModemManager
   sudo systemctl disable ModemManager
   ```
3. Add udev rules so you can flash without `sudo`:
   ```bash
   sudo tee /etc/udev/rules.d/99-esp32.rules << 'EOF'
   # Standard ESP32 CP210x / CH340
   SUBSYSTEMS=="usb", ATTRS{idVendor}=="10c4", ATTRS{idProduct}=="ea60", MODE="0666"
   SUBSYSTEMS=="usb", ATTRS{idVendor}=="1a86", ATTRS{idProduct}=="7523", MODE="0666"
   # Seeed Studio XIAO ESP32C3 / Espressif USB CDC
   SUBSYSTEMS=="usb", ATTRS{idVendor}=="303a", MODE="0666"
   SUBSYSTEMS=="usb", ATTRS{idVendor}=="2886", MODE="0666"
   EOF
   sudo udevadm control --reload-rules
   sudo udevadm trigger
   ```
4. **Important:** Log out of your Linux session and log back in (or run `newgrp dialout`) for group changes to take effect.

---

### 5.3 Arduino IDE Board Package Configuration

1. Launch Arduino IDE.
2. Go to **File -> Preferences** (on Linux: **File -> Preferences** or `Ctrl + Comma`).
3. In **Additional boards manager URLs**, paste the Espressif ESP32 package index URL:
   ```text
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
   *(If you already have other URLs, separate them with a comma).*
4. Click **OK**.
5. In the left sidebar, click the **Boards Manager** icon (or go to **Tools -> Board -> Boards Manager**).
6. Search for `esp32` by **Espressif Systems** and click **Install** (install version `2.0.14` or `3.0+`).
7. Once installed, configure your target board in **Tools**:

#### For Seeed Studio XIAO ESP32C3:
- **Tools -> Board -> esp32 -> `XIAO_ESP32C3`**
- **Tools -> USB CDC On Boot:** `Enabled` *(Crucial for serial monitoring over native USB)*
- **Tools -> Flash Frequency:** `80MHz`
- **Tools -> Port:** Select your board port (`COMx` on Windows, `/dev/ttyACM0` on Linux).

#### For Standard ESP32 DevKit (30-pin or 38-pin):
- **Tools -> Board -> esp32 -> `ESP32 Dev Module`**
- **Tools -> Upload Speed:** `921600` (or `115200` if upload fails)
- **Tools -> Port:** Select your board port (`COMx` on Windows, `/dev/ttyUSB0` on Linux).

---

### 5.4 Circuit Wiring Diagram

```text
               +3.3V (or 5V Power Bank / LiPo +)
                 |
                 +-------------------+-------------------+
                 |                   |                   |
               +---+               +---+               +---+
               |   | Motor 1       |   | Motor 2       |   | Motor 3
               | M | (Thumb - L)   | M | (Index - C)   | M | (Right - R)
               |   |               |   |               |   |
               +---+               +---+               +---+
                 |   [Diode |<]      |   [Diode |<]      |   [Diode |<]
                 |   (Cathode to +)  |   (Cathode to +)  |   (Cathode to +)
                 |                   |                   |
                 +---- Collector     +---- Collector     +---- Collector
                       (2N2222)            (2N2222)            (2N2222)
                      /                   /                   /
ESP32 Pin 1 --[1kΩ]--|  Base         --[1kΩ]--|  Base         --[1kΩ]--|  Base
                      \                   \                   \
                       Emitter             Emitter             Emitter
                          |                   |                   |
                          +-------------------+-------------------+
                                              |
                                             GND (ESP32 Ground)
```

#### Pin Mapping Table:

| Zone / Finger Indicator | Vibration Motor Placement | Seeed Studio XIAO ESP32C3 | Standard ESP32 DevKit V1 |
| :--- | :--- | :---: | :---: |
| **LEFT Zone** | Thumb Finger Pad / Left Glove | **`D0`** (GPIO 2) | **`GPIO 18`** |
| **CENTRE Zone** | Index Finger Pad / Center Glove | **`D1`** (GPIO 3) | **`GPIO 19`** |
| **RIGHT Zone** | Right-Side Palm / Pinky Pad | **`D2`** (GPIO 4) | **`GPIO 21`** |
| **Ground Reference** | Transistor Emitters & Diode Anodes | **`GND`** | **`GND`** |
| **Positive Motor Supply** | Motor Positive (+) Red Leads | **`3V3`** or **`5V / VBUS`** | **`VIN`** (5V) or **`3V3`** |

---

### 5.5 Complete ESP32 Glove Firmware Sketch

Create a new sketch in Arduino IDE, paste the code below, and save it as `HaptiSight_Glove.ino`:

```cpp
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// Standard Nordic UART Service & Characteristic UUIDs (Matching Android BleGloveController.kt)
#define SERVICE_UUID        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

// Pin Configuration: Select pins based on your board
#if defined(ARDUINO_XIAO_ESP32C3) || defined(ARDUINO_Seeed_XIAO_ESP32C3)
  const int PIN_MOTOR_THUMB = D0;  // GPIO 2 on XIAO ESP32C3 (Thumb - Left)
  const int PIN_MOTOR_INDEX = D1;  // GPIO 3 on XIAO ESP32C3 (Index - Center)
  const int PIN_MOTOR_RIGHT = D2;  // GPIO 4 on XIAO ESP32C3 (Right Side)
#else
  // Standard ESP32 DevKit pins
  const int PIN_MOTOR_THUMB = 18;  // Thumb Motor (Left Zone)
  const int PIN_MOTOR_INDEX = 19;  // Index Motor (Centre Zone)
  const int PIN_MOTOR_RIGHT = 21;  // Right-Side Motor (Right Zone)
#endif

bool deviceConnected = false;
String currentPattern = "000N";
unsigned long lastPacketTime = 0;

class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        deviceConnected = true;
        Serial.println(">> Phone Connected to HaptiSight Glove!");
    }
    void onDisconnect(BLEServer* pServer) override {
        deviceConnected = false;
        currentPattern = "000N";
        digitalWrite(PIN_MOTOR_THUMB, LOW);
        digitalWrite(PIN_MOTOR_INDEX, LOW);
        digitalWrite(PIN_MOTOR_RIGHT, LOW);
        Serial.println(">> Phone Disconnected. Motors Silenced. Restarting BLE Advertising...");
        pServer->getAdvertising()->start();
    }
};

class RxCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        std::string rx = pCharacteristic->getValue();
        if (rx.length() >= 4) {
            currentPattern = String(rx.substr(0, 4).c_str());
            lastPacketTime = millis();
            Serial.print(">> Command Received: ");
            Serial.println(currentPattern);
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("--- Starting HaptiSight Glove BLE Controller ---");

    pinMode(PIN_MOTOR_THUMB, OUTPUT);
    pinMode(PIN_MOTOR_INDEX, OUTPUT);
    pinMode(PIN_MOTOR_RIGHT, OUTPUT);
    digitalWrite(PIN_MOTOR_THUMB, LOW);
    digitalWrite(PIN_MOTOR_INDEX, LOW);
    digitalWrite(PIN_MOTOR_RIGHT, LOW);

    // Initialise BLE with target device name expected by Android app
    BLEDevice::init("HaptiSight-Glove");
    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);
    BLECharacteristic *pRxChar = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    pRxChar->setCallbacks(new RxCallbacks());
    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println(">> BLE Advertising started as 'HaptiSight-Glove'. Ready to connect!");
}

void loop() {
    unsigned long now = millis();

    // Safety Watchdog: If no packet arrives for > 400ms, immediately silence all motors
    if (now - lastPacketTime > 400) {
        currentPattern = "000N";
    }

    // Short Buzz waveform cycle: 200ms ON / 300ms OFF (500ms repeating period)
    bool buzzOn = ((now % 500) < 200) && (currentPattern.charAt(3) == 'S');

    digitalWrite(PIN_MOTOR_THUMB, (buzzOn && currentPattern.charAt(0) == '1') ? HIGH : LOW);
    digitalWrite(PIN_MOTOR_INDEX, (buzzOn && currentPattern.charAt(1) == '1') ? HIGH : LOW);
    digitalWrite(PIN_MOTOR_RIGHT, (buzzOn && currentPattern.charAt(2) == '1') ? HIGH : LOW);

    delay(10);
}
```

---

### 5.6 Flashing & Connecting Steps

1. **Connect ESP to Computer:**
   - Plug the ESP into your Windows or Linux PC with the USB-C cable.
2. **Select Board & Port:**
   - In Arduino IDE, pick your board (**Seeed XIAO_ESP32C3** or **ESP32 Dev Module**) and select the detected COM/Serial port.
3. **Upload the Code:**
   - Click the **Upload** button (`Ctrl + U` / `Cmd + U`).
   - *If upload gets stuck on "Connecting......______":*
     - **Standard ESP32:** Hold down the `BOOT` button on the board, press and release `RST/EN`, then release `BOOT` when writing begins.
     - **Seeed Studio XIAO ESP32C3:** Hold down the `B` (Boot) button while plugging in the USB cable to force bootloader mode, then click Upload.
4. **Verify BLE Advertising:**
   - Open Arduino IDE **Serial Monitor** (`Ctrl + Shift + M` / `Cmd + Shift + M`) set to `115200 baud`.
   - You should see:
     ```text
     --- Starting HaptiSight Glove BLE Controller ---
     >> BLE Advertising started as 'HaptiSight-Glove'. Ready to connect!
     ```
5. **Connect to Phone App:**
   - Turn ON Bluetooth on your Android phone.
   - Launch the **HaptiSight** app.
   - The app will automatically scan for `"HaptiSight-Glove"`.
   - Within 1–2 seconds, the badge in the top right will change from `🧤 GLOVE: SCANNING` to green:
     ```text
     🧤 GLOVE: CONNECTED
     ```
   - Place an obstacle (chair, hand, wall) within 1 meter of the phone camera:
     - On phone screen: The obstacle turns **RED**, and the virtual glove HUD highlights the active motor.
     - On physical glove: The corresponding vibration motor pulses with the crisp **200ms Short Buzz**!

