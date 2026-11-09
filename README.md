# HaptiSight

**HaptiSight** is an Android assistive navigation application designed for visually impaired and blind users. It leverages Google ARCore Depth APIs to continuously scan the surrounding environment, detect obstacles across horizontal zones within $< 1.00\text{m}$, and deliver directional haptic feedback directly through a wearable 3-motor glove (via Bluetooth Low Energy) or the smartphone's internal vibrator.

---

## Architecture & System Overview

- **Preview & Rendering:** Full-screen OpenGL ES 2.0 camera feed at 30 FPS (`TARGET_FPS_30` camera config) using external OES textures (`CameraBackgroundRenderer.kt`) matching normalized portrait device coordinates without thermal throttling.
- **Continuous Hybrid Depth Engine:** Zero-allocation hybrid depth engine (`DepthAnalyzer.kt`) acquiring 16-bit raw depth with confidence validation, dynamically boosting gain in low light (`avgConf < 30`), and using dense depth clustering so smooth painted walls, doors, and furniture are reliably detected.
- **Top 75% Floor-Exclusion Scanning ROI:** Restricts vertical depth scanning to `normY: 0.08f..0.75f` (clamped to `0.75f`), completely excluding the floor tiles in front of the user's feet (handled by a white cane) to prevent false alarms.
- **3-Zone Independent Motor Mapping:** Divides the camera field of view into three independent directional columns:
  - **Left Zone (`0.00..0.33`):** Mapped to the **Thumb Motor (L)**.
  - **Centre Zone (`0.33..0.66`):** Mapped to the **Index Finger Motor (C)**.
  - **Right Zone (`0.66..1.00`):** Mapped to the **Right-Side Palm Motor (R)**.
- **Zero-Allocation `ZoneCollector`:** Pre-allocated arrays and object pooling ensure `< 2ms` execution latency and prevent Android Garbage Collection (GC) pauses over multi-hour runs.
- **Virtual Glove Simulator HUD:** On-screen HUD above the Mute button with 3 circular motor indicators (`Thumb`, `Index`, `Right`), live packet telemetry (`TX: [code]`), and sub-millisecond latency readouts so software and logic can be verified before connecting physical hardware.
- **Hardware-Agnostic BLE Bridge:** Transmits 5-byte ASCII packets (`"100S\n"`, `"010S\n"`, `"000N\n"`) over Nordic UART BLE (`6E400001-...`) to ESP32 or Arduino glove hardware with `< 15ms` latency, automatically falling back to phone vibration when disconnected.
- **Version-Safe Haptic Controller:** Strictly outputs uniform **Short Buzz pulses** (`200 ms ON`, `300 ms OFF` at amplitude 255) for obstacles $\le 1.00\text{m}$. Long continuous buzzes are completely eliminated.

---

## 8-State Directional Haptic Matrix (< 1.00m Short Buzz Only)

HaptiSight compares each zone's nearest surface against the strict **`1.00 m` (`1000 mm`)** obstacle cutoff:
- **`<= 1000 mm`:** Triggers crisp **Red bounding boxes** and **Short Buzz haptics** on the corresponding motor(s).
- **`1001..4000 mm`:** Renders **Green safe boxes** (`SAFE: X.XX m`) on distant surfaces with **Zero vibration** (Silent).
- **`> 4000 mm` / Clear path:** Renders `🟢 SAFE PATH` with **Zero vibration**.

| Zone Detection (`<= 1.00m`) | Thumb Motor (Left) | Index Motor (Centre) | Right Motor (Right) | Outgoing BLE Packet | Live Status Banner | Phone / Glove Haptic Feedback |
| :--- | :---: | :---: | :---: | :---: | :--- | :--- |
| **Clear Path (> 1.00m)** | OFF | OFF | OFF | `"000N"` | `🟢 SAFE PATH` | Silent (No vibration) |
| **Left Only** | **ON** | OFF | OFF | `"100S"` | `🔴 OBSTACLE <1m [LEFT] - SHORT BUZZ` | Thumb Motor Short Buzz (`200ms ON / 300ms OFF`) |
| **Centre Only** | OFF | **ON** | OFF | `"010S"` | `🔴 OBSTACLE <1m [CENTRE] - SHORT BUZZ` | Index Motor Short Buzz (`200ms ON / 300ms OFF`) |
| **Right Only** | OFF | OFF | **ON** | `"001S"` | `🔴 OBSTACLE <1m [RIGHT] - SHORT BUZZ` | Right-Side Motor Short Buzz (`200ms ON / 300ms OFF`) |
| **Left + Centre** | **ON** | **ON** | OFF | `"110S"` | `🔴 OBSTACLE <1m [LEFT + CENTRE] - SHORT BUZZ` | Thumb + Index Motors Buzz Together |
| **Centre + Right** | OFF | **ON** | **ON** | `"011S"` | `🔴 OBSTACLE <1m [CENTRE + RIGHT] - SHORT BUZZ` | Index + Right Motors Buzz Together |
| **Left + Right** | **ON** | OFF | **ON** | `"101S"` | `🔴 OBSTACLE <1m [LEFT + RIGHT] - SHORT BUZZ` | Thumb + Right Motors Buzz Together |
| **Fully Blocked (All 3)** | **ON** | **ON** | **ON** | `"111S"` | `🔴 OBSTACLE <1m [LEFT + CENTRE + RIGHT] - SHORT BUZZ` | All 3 Motors Buzz Simultaneously |

---

## Safety & System States

To ensure user safety, HaptiSight strictly manages hardware and tracking states:

| State | Status Banner | BLE Packet | Vibration Behavior | Visual Overlay |
| :--- | :--- | :---: | :--- | :--- |
| **GLOVE CONNECTED** | `🧤 GLOVE: CONNECTED` badge (Green) | Active TX | Physical glove vibrates; phone vibration muted | Virtual glove HUD active with live telemetry |
| **PHONE HAPTICS** | `🧤 PHONE HAPTICS` badge (Grey) | `"000N"` | Phone internal vibrator pulses (fallback mode) | Virtual glove HUD active with live telemetry |
| **LOW CONFIDENCE** | `⚠️ LOW DEPTH CONFIDENCE` | `"000N"` | Silent (all vibrations immediately cancelled) | Overlays cleared; never reports SAFE PATH |
| **DEPTH UNAVAILABLE** | `⚠️ DEPTH UNAVAILABLE` | `"000N"` | Silent (all vibrations immediately cancelled) | Overlays cleared; never reports SAFE PATH |
| **TRACKING PAUSED** | `⚠️ TRACKING PAUSED - ...` | `"000N"` | Optical depth continues; banner alerts user if lighting/movement is poor | Virtual HUD stays responsive |

> **Critical Safety Guarantee:** A lack of depth data is never interpreted as a clear path. When depth becomes stale (> 800 ms), HaptiSight immediately cancels active vibrations, sends `"000N\n"` to the glove, and displays descriptive alerts.

---

## Prerequisites for Physical Device Testing

1. **ARCore-Compatible Device:** A physical Android phone supporting ARCore Depth API (e.g., Google Pixel 4+, Samsung Galaxy S20+, Xiaomi with ARCore support). The Depth API requires a physical device and cannot be verified on emulators.
2. **Google Play Services for AR:** Installed and updated to the latest version from the Google Play Store.
3. **Android OS:** Android 8.0 (API level 26) through Android 14 (API level 34).
4. **Android Studio:** Android Studio Iguana, Jellyfish, Koala, Ladybug, or newer.
5. **Connection:** USB cable (USB debugging) or local Wi-Fi (wireless debugging).
6. **Bluetooth:** Bluetooth enabled on phone for wireless glove pairing.

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
| :--- | :--- | :--- |
| **App Startup** | Launch the application | Camera preview initializes at 30 FPS. **No automatic startup vibration**. Status banner displays `🟢 SCANNING...`. Top-right badge shows `🧤 GLOVE: SCANNING` or `🧤 PHONE HAPTICS`. |
| **Clear Corridor** | Aim camera down an open hallway (> 1.0 m) | Status banner: `🟢 SAFE PATH`. Green bounding boxes on distant walls/doors. HUD: All 3 motor circles OFF (`000N`). **Zero vibration**. |
| **Single Obstacle (< 1.0m)** | Place a chair or object in Center zone (< 1.0 m) | Status banner: `🔴 OBSTACLE <1m [CENTRE] - SHORT BUZZ`. Red bounding box tightly around object (`CENTRE: X.XX m`). HUD: Center (C) circle red, `TX: 010S`. Center motor pulses (200ms Short Buzz). |
| **Multi-Zone Obstacle** | Stand within 1.0m of a wall or cabinet spanning Left + Center | Status banner: `🔴 OBSTACLE <1m [LEFT + CENTRE] - SHORT BUZZ`. Two red boxes (`LEFT: X.XX m`, `CENTRE: X.XX m`). HUD: Thumb (L) and Index (C) red, `TX: 110S`. Left and center motors buzz together. |
| **Floor Exclusion** | Tilt phone slightly downwards toward the floor | Dashed cyan line marks `75% DETECTION ROI`. Floor tiles in front of feet are ignored; no false red obstacle triggers. |
| **Glove Hardware Connect** | Power on the ESP32 glove | Top-right badge turns green: `🧤 GLOVE: CONNECTED`. Phone vibration cancels; physical glove motors vibrate on `< 1.00m` obstacles. |
| **Mute Button** | Tap `MUTE HAPTICS` at bottom | Button turns red (`UNMUTE HAPTICS`). All vibrations cease. Status banner displays `... (MUTED)`. Tap again to unmute (confirmatory test buzz fires). |
| **Camera Occlusion / Dark Corner** | Cover the camera lens with your hand | Low-light boost attempts capture; if zero depth, status banner displays `⚠️ DEPTH UNAVAILABLE`. All vibrations cancel immediately. **Never falsely reports SAFE PATH**. |

---

## Troubleshooting Guide

- **Camera Not Available / Crashes on Launch:** Ensure camera permissions are granted. Verify Google Play Services for AR is installed and updated.
- **No Vibration on Xiaomi / POCO / Redmi Devices:** MIUI sometimes restricts background vibration or applies strict haptic energy saving. Go to phone **Settings > Sound & Vibration > Haptic Feedback** and ensure system haptics are enabled.
- **Depth Not Scanning:** Raw Depth and Dense Depth require sufficient ambient lighting. Avoid completely pitch-black rooms or highly reflective mirrors.

---

## Hardware Wearable Glove Setup (ESP32 & Seeed Studio XIAO)

This section provides complete, step-by-step instructions for flashing a fresh microcontroller board (Standard ESP32 or Seeed Studio XIAO ESP32C3/S3), wiring the 3 directional vibration motors, and wirelessly connecting to the HaptiSight Android app.

Both **Windows** and **Linux** workflows are documented below.

---

### 1. Required Components & Hardware Bill of Materials (BOM)

| Component | Quantity | Purpose | Recommendation / Notes |
| :--- | :---: | :--- | :--- |
| **Microcontroller Board** | 1 | BLE Receiver & Motor Controller | **Seeed Studio XIAO ESP32C3** (ultra-compact, thumb-sized) or standard **ESP32 DevKit V1** (30/38 pin). |
| **Coin Vibration Motors** | 3 | Directional Haptic Feedback | 3V DC Coin/Pancake ERM vibration motors (10mm x 2.7mm or 10mm x 3mm). |
| **NPN Transistors / MOSFETs** | 3 | Motor Current Switching | 2N2222, 2N3904, or AO3400 / 2N7000 N-channel MOSFETs. (*ESP32 GPIO pins only output ~12mA; motors draw 60-80mA!*). |
| **Resistors (1 kΩ)** | 3 | Base Current Limiter | Connects between ESP32 GPIO pin and Transistor Base. |
| **Flyback Diodes** | 3 | Back-EMF Voltage Protection | 1N4148 or 1N4001 across motor terminals (prevents motor inductive kickback from resetting the ESP32). |
| **Power Source** | 1 | Powering ESP32 & Motors | Small 3.7V LiPo battery (connected to BAT pad on XIAO) or a small 5V USB power bank via USB-C. |
| **USB-C Cable** | 1 | Flashing & Debugging | Must be a **Data Cable** (not charge-only). |

---

### 2. Software & IDE Installation (Windows & Linux)

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

### 3. Arduino IDE Board Package Configuration

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

### 4. Circuit Wiring Diagram

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

### 5. Complete ESP32 Glove Firmware Sketch

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

### 6. Flashing & Connecting Steps

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

