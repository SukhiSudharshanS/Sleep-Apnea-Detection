# ESP32 Sleep Apnea Detector - Deployment Guide

## Overview
This guide walks you through deploying the trained 3-sensor fusion model onto your ESP32 as a standalone wearable sleep apnea detector.

---

## 1. Hardware Wiring

### Components
| Component | Purpose |
|---|---|
| ESP32 DevKit v1 | Main controller |
| MAX30102 | SpO2 + Heart Rate (I2C) |
| MPU6050 | Movement detection (I2C) |
| INMP441 | Audio / snoring (I2S) |
| Buzzer | Alert (GPIO 27) |

### Wiring Diagram
```
ESP32          MAX30102       MPU6050        INMP441
─────          ────────       ───────        ───────
GPIO 21 (SDA) ─── SDA ─────── SDA
GPIO 22 (SCL) ─── SCL ─────── SCL
GPIO 25 ──────────────────────────────────── WS (LRCLK)
GPIO 26 ──────────────────────────────────── SCK (BCLK)
GPIO 33 ──────────────────────────────────── SD (DOUT)
GPIO 27 ─── Buzzer (+)
GND     ─── GND ──────── GND ──────────── GND / L
3.3V    ─── VCC ──────── VCC ──────────── VDD
```
> [!IMPORTANT]
> MAX30102 and MPU6050 share the same I2C bus (GPIO 21/22). They have different I2C addresses so no conflict.

---

## 2. Arduino IDE Setup

### Step 1: Install ESP32 Board Support
1. Open Arduino IDE → **File > Preferences**
2. In "Additional Board Manager URLs", add:
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
3. Go to **Tools > Board > Board Manager** → search "esp32" → Install **"esp32 by Espressif Systems"**

### Step 2: Select Your Board
- **Tools > Board** → `ESP32 Dev Module`
- **Tools > Flash Size** → `4MB (32Mb)` 
- **Tools > Partition Scheme** → `Huge APP (3MB No OTA / 1MB SPIFFS)`
- **Tools > Upload Speed** → `921600`

### Step 3: Install Required Libraries
Go to **Sketch > Include Library > Manage Libraries** and install:

| Library | Author | Version |
|---|---|---|
| SparkFun MAX3010x Pulse and Proximity Sensor Library | SparkFun | Latest |
| Adafruit MPU6050 | Adafruit | Latest |
| TensorFlowLite_ESP32 | TensorFlow | Latest |

> [!TIP]
> If "TensorFlowLite_ESP32" is not found, you can use **EloquentTinyML** as an alternative. The API is similar.

### Step 4: Open and Flash
1. Open `esp32_apnea_detector/sleep_apnea_detector.ino` in Arduino IDE
2. Make sure `sleep_apnea_model.h` is in the same folder
3. Connect ESP32 via USB
4. Click **Upload** (→)
5. Open **Serial Monitor** at 115200 baud

---

## 3. How It Works (Runtime)

```
Every 1 second:
  1. Read MAX30102 → SpO2 (%) and BPM
  2. Read MPU6050  → Movement magnitude (g)
  3. Store values in a rolling 60-second buffer

Every 60 seconds (once buffer is full):
  4. Read INMP441  → Compute audio MFCC features (32x32)
  5. Load all 3 inputs into TFLite Micro interpreter
  6. Run inference → Get apnea risk score (0.0 to 1.0)
  7. If risk > 0.6 → Trigger buzzer alarm
```

---

## 4. Testing Without Sensors

If you want to test the ESP32 code without all sensors connected, you can modify the code to use test data:

```cpp
// In loop(), replace sensor reads with:
float spo2 = 95.0 + random(-50, 50) / 100.0;  // Simulate SpO2
float bpm = 72.0 + random(-5, 5);              // Simulate BPM
float movement = 1.0 + random(-10, 10) / 100.0; // Simulate still
```

---

## 5. Retraining the Model

If you collect new data or want to improve accuracy:

### Step 1: Prepare Data
Place your SpO2/BPM CSV files in the project folder. Format:
```csv
timestamp,spo2,bpm,label
0,98.2,65,0
1,97.8,66,0
...
```
Where `label` = 0 (normal) or 1 (apneic).

### Step 2: Retrain
```bash
# Option A: Retrain with UCD clinical data (already done)
python prepare_ucd_data.py --data_dir <path_to_ucddb> --train

# Option B: Retrain with synthetic data
python fusion_model.py --demo
```

### Step 3: Re-convert for ESP32
```bash
python convert_to_tflite.py --test
```
This regenerates:
- `models/sleep_apnea_fusion_v3.tflite`
- `esp32_apnea_detector/sleep_apnea_model.h`

### Step 4: Re-flash ESP32
Open Arduino IDE → Upload again. The new model will be compiled into the firmware.

---

## 6. File Map (Nothing Was Modified)

All new files created, existing files untouched:

```
Snoring-Detection-master/
├── fusion_model.py                  # Model architecture (3-sensor fusion)
├── prepare_ucd_data.py              # UCD database extraction + training
├── convert_to_tflite.py             # .h5 → .tflite → .h conversion
├── processed_data/
│   └── ucd_max30102_dataset.npz     # Extracted clinical SpO2+BPM windows
├── models/
│   ├── cnn.h5                       # Original snoring CNN (untouched)
│   ├── sleep_apnea_fusion_v3.h5     # Trained fusion model
│   └── sleep_apnea_fusion_v3.tflite # TFLite model for ESP32
└── esp32_apnea_detector/
    ├── sleep_apnea_detector.ino     # Arduino sketch (flash this)
    └── sleep_apnea_model.h          # Model as C byte array
```

---

## 7. Troubleshooting

| Problem | Solution |
|---|---|
| `AllocateTensors() FAILED` | Increase `kTensorArenaSize` in the .ino file |
| MAX30102 not detected | Check I2C wiring, try `Wire.begin()` without params |
| SpO2 always 0 | Place finger firmly on sensor with no light leakage |
| Model too large for flash | Run `python convert_to_tflite.py --quantize` for INT8 |
| BPM unstable | Keep MAX30102 still on fingertip for 10+ seconds |

> [!WARNING]
> The model is ~2.5MB. Use **"Huge APP (3MB No OTA)"** partition scheme or the model won't fit.
