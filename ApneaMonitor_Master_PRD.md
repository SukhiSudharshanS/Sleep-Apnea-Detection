# 🧠 ANTIGRAVITY MASTER DIRECTIVE: APNEA MONITOR PROJECT

> **AGENT INSTRUCTION:** You are 'Antigravity', acting as a Senior Mobile and Embedded Systems Architect. You must read and strictly adhere to this document for all future code generation, debugging, and architectural decisions for the `ApneaMonitor` project. Do not deviate from the tech stack, BLE contract, or UI guidelines defined below.

## 1. Product Requirements Document (PRD)

**Product Name:** ApneaMonitor  
**Mission:** To provide clinical-grade, continuous, non-invasive sleep apnea monitoring using a custom wearable ESP32 ring and an Android companion app.  
**Target Audience:** Patients suspected of sleep apnea, utilizing the device overnight for diagnostic screening.  

**Core Features:**
* **Edge AI Detection:** Local ML inference (TensorFlow Lite) on the ESP32 to detect apnea events in real-time.
* **Multimodal Sensor Fusion:** Tracking SpO2, Heart Rate (BPM), Actigraphy (Movement), and Audio (Snoring intensity).
* **Live Dashboard:** Real-time visualization of vitals via BLE.
* **Offline Data Syncing:** Overnight data buffering on the hardware, syncing to the Android Room database upon morning reconnection.
* **Clinical Export:** Automated generation of PDF reports and CSV datasets exported directly to the user's public `Downloads` folder via MediaStore.

---

## 2. Infrastructure & Tech Stack

### 2.1 Hardware (ESP32 Wearable)
* **MCU:** ESP32 Dev Module
* **Sensors:** 
    * MAX30102 (SpO2 & Heart Rate) via I2C.
    * MPU6050 (Accelerometer/Movement) via I2C.
    * INMP441 (Microphone/Snoring) via I2S.
* **Bluetooth Stack:** `NimBLEDevice` (Crucial for memory savings).
* **Edge ML:** `TensorFlowLite_ESP32` (MicroInterpreter).

### 2.2 Software (Android App)
* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material 3)
* **Architecture:** MVVM (Model-View-ViewModel) with Clean Architecture principles.
* **Asynchronous Processing:** Kotlin Coroutines & `StateFlow`. *Rule: All heavy processing (ML, DB, File I/O) MUST occur on `Dispatchers.Default` or `Dispatchers.IO`.*
* **Local Database:** Room Database (`SleepSessionDao`).
* **Target API:** Android 14 (API 34). Strict adherence to precise location and BLE permissions.

---

## 3. The BLE Contract (Strict Enforcement)

Both the ESP32 Server and Android Client MUST adhere to this exact GATT configuration.

**Device Identity:**
* **Advertised Name:** `MAX30102_Ring`
* **Service UUID:** `19b10000-e8f2-537e-4f6c-d104768a1214`

**Characteristic 1: Live Stream (NOTIFY)**
* **UUID:** `19b10001-e8f2-537e-4f6c-d104768a1214`
* **Payload:** 4-Byte Array `[SpO2, BPM, Movement, AudioLevel]`
* **Frequency:** Every 2 seconds.

**Characteristic 2: Apnea Alert (NOTIFY)**
* **UUID:** `19b10002-e8f2-537e-4f6c-d104768a1214`
* **Payload:** 1-Byte Array. `0-100` Risk Percentage score.
* **Constraint:** ESP32 must implement a 60-second inference window (Phase 4 Fusion logic).

**Characteristic 3: Bulk Historical Sync (READ/NOTIFY)**
* **UUID:** `19b10003-e8f2-537e-4f6c-d104768a1214`
* **Protocol:** Header-based chunking. Android must reassemble arrays based on the first byte.
    * `0xAA` = SpO2 Payload
    * `0xBB` = BPM Payload
    * `0xCC` = Movement Payload
    * `0xFF` = End of Transmission (Trigger DB Save)

---

## 4. Application Flow & State Machine

1.  **Launch & Permissions:** App requests `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `ACCESS_FINE_LOCATION`.
2.  **Discovery:** App checks `SharedPreferences` for a saved MAC address. 
    * *If saved:* Trigger `connectGatt(autoConnect = true)`.
    * *If empty:* Initiate BLE Scanner filtered ONLY by `SERVICE_UUID`.
3.  **Connection Handshake:** 
    * Upon `STATE_CONNECTED`, stop scanning.
    * **CRITICAL WAIT:** Delay `discoverServices()` by 2000ms to prevent Android 14 NimBLE crash.
4.  **Data Ingestion:** Route incoming BLE bytes to `MutableStateFlow` variables in `AppBluetoothManager`.
5.  **ML Inference (Android Side):** If running `ApneaFusionModel`, launch on `Dispatchers.Default` to prevent Main Thread ANRs (Application Not Responding).
6.  **Export:** User triggers export. App uses `MediaStore` to save PDF/CSV to the public `/Downloads` directory.

---

## 5. UI / UX Document

**Aesthetic:** Clinical, minimalist, modern, dark-mode native.

**Color Palette:**
* **Backgrounds:** Deep Navy (`#1A2235`) or Midnight Blue (`#0B0F19`).
* **Text:** Pure White (`#FFFFFF`) or Light Gray (`#E2E8F0`).
* **Accents (SpO2/BPM):** Medical Cyan (`#06B6D4`) and Alert Red (`#EF4444`).

**Typography:**
* Modern Sans-Serif (e.g., Roboto or Inter). 
* Heavy emphasis on large, readable typography for vital signs (e.g., `fontSize = 48.sp`, `FontWeight.Bold`).

**Core Components:**
* **Branding:** The custom app logo (`ic_apnea_logo.png`) must be utilized in the top App Bar alongside the "ApneaMonitor" text, scaled to `ContentScale.Fit` with `40.dp` height.
* **Dashboard (`DashboardScreen.kt`):** A 2x2 grid layout utilizing `StatCard` composables displaying SpO2, BPM, Movement, and Audio Level. 
* **Loading States:** Use circular progress indicators during BLE scanning and data chunk reassembly.

---

## 6. Antigravity Agent Directives (Self-Correction Rules)

Whenever generating or modifying code for this project, the agent must silently check these rules:
1.  **Never block the UI thread:** Do not put `Thread.sleep()`, heavy loops, or ML inference on the main Compose thread.
2.  **No ghost variables:** Ensure all `StateFlow` variables are properly imported and assigned using `.value = X` (e.g., `_audioLevel.value = ...`).
3.  **Strict File Paths:** Never use `getExternalFilesDir()` for PDF/CSV exports. Always use the `MediaStore` API.
4.  **Graceful Degradation:** If a BLE connection fails, log the exact GATT error code and gracefully reset the UI to `Disconnected`. Do not crash the app.

----

## 7. Security, Power, and Lifecycle Constraints (Mission Critical)

* **Data Privacy:** All local storage (Room DB) must eventually migrate to encrypted storage (SQLCipher). The app must include a HIPAA-compliant consent gate before the first BLE scan.
* **Hardware UX:** ESP32 must implement standard BLE Battery Service (`180F`). Android UI must display hardware battery percentage. ESP32 must define LED states to communicate status without the app.
* **Firmware OTA:** Architecture must account for a future OTA update pipeline via BLE. Do not hardcode firmware versions; implement a version-check handshake on connection.
* **User Calibration:** The Room Database must include a `UserContext` entity (Age, Weight, Baseline Vitals) to eventually act as dynamic weights for the `ApneaFusionModel`.
