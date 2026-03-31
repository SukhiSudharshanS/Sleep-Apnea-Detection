/*  ============================================================================
 *  apnea_monitor_ble.ino
 *  ---------------------------------------------------------------------------
 *  Complete ESP32 Firmware — ApneaMonitor BLE Wearable
 *
 *  MCU   : ESP32 (Standard Dev Module)
 *  Sensors: MAX30102 (Pulse Oximeter)  – I2C
 *           MPU6050  (Accelerometer)   – I2C  (shared bus)
 *
 *  BLE Contract  (must match Android  AppBluetoothManager.kt exactly)
 *  ---------------------------------------------------------------
 *  Device Name : MAX30102_Ring
 *  Service UUID: 19b10000-e8f2-537e-4f6c-d104768a1214
 *  Char 1 (NOTIFY)       : 19b10001-...   Live [SpO2, BPM, Movement] 3 bytes
 *  Char 2 (NOTIFY)       : 19b10002-...   Apnea Alert  [0]=OK [1]=Apnea
 *  Char 3 (READ|NOTIFY)  : 19b10003-...   Bulk Historical Dump
 *
 *  Libraries required (install via Arduino Library Manager):
 *    - NimBLE-Arduino
 *    - SparkFun MAX3010x Pulse and Proximity Sensor Library
 *    - Adafruit MPU6050
 *    - Adafruit Unified Sensor
 *  ============================================================================
 */

#include <Wire.h>
#include <math.h>

// ── Sensor Libraries ────────────────────────────────────────────────────────
#include "MAX30105.h"
#include "spo2_algorithm.h"
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

// ── BLE (NimBLE — much lower RAM footprint than default bluedroid) ───────
#include <NimBLEDevice.h>

// ═══════════════════════════════════════════════════════════════════════════
//  PIN DEFINITIONS
// ═══════════════════════════════════════════════════════════════════════════
#define I2C_SDA     21
#define I2C_SCL     22

// ═══════════════════════════════════════════════════════════════════════════
//  BLE UUIDs  (must match Android AppBluetoothManager companion object)
// ═══════════════════════════════════════════════════════════════════════════
#define SERVICE_UUID          "19b10000-e8f2-537e-4f6c-d104768a1214"
#define CHAR_LIVE_UUID        "19b10001-e8f2-537e-4f6c-d104768a1214"
#define CHAR_ALERT_UUID       "19b10002-e8f2-537e-4f6c-d104768a1214"
#define CHAR_BULK_UUID        "19b10003-e8f2-537e-4f6c-d104768a1214"

// ═══════════════════════════════════════════════════════════════════════════
//  TUNABLES
// ═══════════════════════════════════════════════════════════════════════════
#define LIVE_INTERVAL_MS      2000    // Notify live data every 2 s
#define ACCUMULATE_WINDOW_S   10      // Average over 10 s for history buffer
#define APNEA_SPO2_THRESHOLD  88      // SpO2 below this triggers apnea alert
#define SPO2_SAMPLE_LEN       100     // Samples per SpO2 read burst
#define MAX_HISTORY_LEN       3000    // ~8.3 h @ 10-s intervals (safe headroom)
#define BLE_CHUNK_SIZE        19      // 1 header + 19 payload bytes fits default MTU of 20

// ═══════════════════════════════════════════════════════════════════════════
//  SENSOR OBJECTS
// ═══════════════════════════════════════════════════════════════════════════
MAX30105         maxSensor;
Adafruit_MPU6050 mpu;

// ═══════════════════════════════════════════════════════════════════════════
//  BLE OBJECTS
// ═══════════════════════════════════════════════════════════════════════════
NimBLEServer*         pServer        = nullptr;
NimBLECharacteristic* pCharLive      = nullptr;
NimBLECharacteristic* pCharAlert     = nullptr;
NimBLECharacteristic* pCharBulk      = nullptr;

bool deviceConnected    = false;
bool oldDeviceConnected = false;

// ═══════════════════════════════════════════════════════════════════════════
//  LIVE SENSOR STATE
// ═══════════════════════════════════════════════════════════════════════════
uint32_t irBuf[SPO2_SAMPLE_LEN];
uint32_t redBuf[SPO2_SAMPLE_LEN];

float  latestSpO2   = 98.0f;
float  latestBPM    = 70.0f;
int8_t spo2Valid    = 0;
int8_t hrValid      = 0;
uint8_t lastAlertVal = 0;   // Track last alert sent to avoid spamming

// ═══════════════════════════════════════════════════════════════════════════
//  HISTORY BUFFER  (10-second averaged values)
//  ~3000 entries × 1 byte each × 3 arrays = ~9 KB — well within SRAM
// ═══════════════════════════════════════════════════════════════════════════
uint8_t histSpO2[MAX_HISTORY_LEN];
uint8_t histBPM[MAX_HISTORY_LEN];
uint8_t histMovement[MAX_HISTORY_LEN];
uint16_t histCount = 0;           // Number of 10-s slots filled

// Accumulation registers for the current 10-s window
float   accSpO2      = 0.0f;
float   accBPM       = 0.0f;
float   accMovement  = 0.0f;
uint8_t accSamples   = 0;

// ═══════════════════════════════════════════════════════════════════════════
//  TIMING
// ═══════════════════════════════════════════════════════════════════════════
unsigned long lastLiveNotify  = 0;
unsigned long lastSampleTime  = 0;

// ═══════════════════════════════════════════════════════════════════════════
//  BLE SERVER CALLBACKS
// ═══════════════════════════════════════════════════════════════════════════
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pSvr, NimBLEConnInfo& connInfo) override {
        deviceConnected = true;
        Serial.println("[BLE] Client connected!");

        // Trigger bulk history sync automatically on connection
        sendBulkHistory();
    }

    void onDisconnect(NimBLEServer* pSvr, NimBLEConnInfo& connInfo, int reason) override {
        deviceConnected = false;
        Serial.printf("[BLE] Client disconnected (reason 0x%02X). Restarting advertising...\n", reason);
        NimBLEDevice::startAdvertising();
    }
};

// ═══════════════════════════════════════════════════════════════════════════
//  FORWARD DECLARATIONS
// ═══════════════════════════════════════════════════════════════════════════
void setupSensors();
void setupBLE();
void readSpO2AndBPM();
uint8_t readMovementScore();
void notifyLiveData();
void checkApneaAlert(uint8_t spo2);
void accumulateHistory(uint8_t spo2, uint8_t bpm, uint8_t movement);
void sendBulkHistory();
void sendChunkedArray(uint8_t header, const uint8_t* data, uint16_t len);

// ═══════════════════════════════════════════════════════════════════════════
//  SETUP
// ═══════════════════════════════════════════════════════════════════════════
void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n========================================");
    Serial.println("  ApneaMonitor BLE Firmware v1.0");
    Serial.println("========================================\n");

    Wire.begin(I2C_SDA, I2C_SCL);

    setupSensors();
    setupBLE();

    memset(histSpO2,     0, sizeof(histSpO2));
    memset(histBPM,      0, sizeof(histBPM));
    memset(histMovement, 0, sizeof(histMovement));

    Serial.println("\n[READY] Broadcasting as MAX30102_Ring ...\n");
}

// ═══════════════════════════════════════════════════════════════════════════
//  MAIN LOOP
// ═══════════════════════════════════════════════════════════════════════════
void loop() {
    unsigned long now = millis();

    // ── Sample sensors every 1 second ────────────────────────────────────
    if (now - lastSampleTime >= 1000) {
        lastSampleTime = now;

        readSpO2AndBPM();
        uint8_t spo2     = (uint8_t)constrain(latestSpO2, 0, 100);
        uint8_t bpm      = (uint8_t)constrain(latestBPM, 0, 250);
        uint8_t movement = readMovementScore();

        // Accumulate into the 10-second averaging window
        accumulateHistory(spo2, bpm, movement);

        // Edge AI — instant apnea detection
        checkApneaAlert(spo2);

        // Debug print
        Serial.printf("[LIVE] SpO2:%3d%%  BPM:%3d  Move:%2d  |  Hist:%d/%d\n",
                      spo2, bpm, movement, histCount, MAX_HISTORY_LEN);
    }

    // ── Notify live data to connected client every 2 seconds ─────────────
    if (deviceConnected && (now - lastLiveNotify >= LIVE_INTERVAL_MS)) {
        lastLiveNotify = now;
        notifyLiveData();
    }

    // ── Handle reconnection advertising ──────────────────────────────────
    if (!deviceConnected && oldDeviceConnected) {
        delay(300);                       // Give the stack time to clean up
        NimBLEDevice::startAdvertising();
        Serial.println("[BLE] Advertising restarted.");
        oldDeviceConnected = false;
    }
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
    }

    // Keep the MAX30102 internal FIFO happy
    maxSensor.check();
}

// ═══════════════════════════════════════════════════════════════════════════
//  SENSOR SETUP
// ═══════════════════════════════════════════════════════════════════════════
void setupSensors() {
    // ── MAX30102 ─────────────────────────────────────────────────────────
    Serial.print("[MAX30102] Initializing ... ");
    if (!maxSensor.begin(Wire, I2C_SPEED_STANDARD)) {
        Serial.println("FAILED!  Check wiring.");
        while (1) { delay(1000); }
    }
    // ledBrightness, sampleAvg, ledMode, sampleRate, pulseWidth, adcRange
    maxSensor.setup(60, 4, 2, 400, 411, 4096);
    Serial.println("OK");

    // ── MPU6050 ──────────────────────────────────────────────────────────
    Serial.print("[MPU6050]  Initializing ... ");
    if (!mpu.begin()) {
        Serial.println("FAILED!  Check wiring.");
        while (1) { delay(1000); }
    }
    mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
    mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
    Serial.println("OK");
}

// ═══════════════════════════════════════════════════════════════════════════
//  BLE SETUP
// ═══════════════════════════════════════════════════════════════════════════
void setupBLE() {
    Serial.print("[BLE]      Initializing NimBLE ... ");

    NimBLEDevice::init("MAX30102_Ring");
    NimBLEDevice::setPower(ESP_PWR_LVL_P6);     // +6 dBm — good range

    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    // ── Primary Service ──────────────────────────────────────────────────
    NimBLEService* pService = pServer->createService(SERVICE_UUID);

    // ── Characteristic 1: Live Metrics (NOTIFY) ─────────────────────────
    pCharLive = pService->createCharacteristic(
        CHAR_LIVE_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // ── Characteristic 2: Apnea Alert (NOTIFY) ─────────────────────────
    pCharAlert = pService->createCharacteristic(
        CHAR_ALERT_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // ── Characteristic 3: Bulk Historical Sync (READ | NOTIFY) ──────────
    pCharBulk = pService->createCharacteristic(
        CHAR_BULK_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
    );

    // Start the service
    pService->start();

    // ── Advertising ─────────────────────────────────────────────────────
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->start();

    Serial.println("OK");
    Serial.printf("         Device Name : MAX30102_Ring\n");
    Serial.printf("         Service UUID: %s\n", SERVICE_UUID);
}

// ═══════════════════════════════════════════════════════════════════════════
//  READ SPO2 & BPM  (burst-read from MAX30102)
// ═══════════════════════════════════════════════════════════════════════════
void readSpO2AndBPM() {
    // If no finger/skin is on the sensor, skip
    if (maxSensor.getIR() < 50000) {
        latestSpO2 = 0.0f;
        latestBPM  = 0.0f;
        return;
    }

    for (int i = 0; i < SPO2_SAMPLE_LEN; i++) {
        while (!maxSensor.available()) maxSensor.check();
        redBuf[i] = maxSensor.getRed();
        irBuf[i]  = maxSensor.getIR();
        maxSensor.nextSample();
    }

    int32_t spo2Val = 0, hrVal = 0;
    maxim_heart_rate_and_oxygen_saturation(
        irBuf, SPO2_SAMPLE_LEN, redBuf,
        &spo2Val, &spo2Valid,
        &hrVal,   &hrValid
    );

    if (spo2Valid && spo2Val > 0 && spo2Val <= 100) latestSpO2 = (float)spo2Val;
    if (hrValid   && hrVal  > 20 && hrVal   <  250) latestBPM  = (float)hrVal;
}

// ═══════════════════════════════════════════════════════════════════════════
//  READ MOVEMENT SCORE  (MPU6050 → vector magnitude → 0-10 scale)
// ═══════════════════════════════════════════════════════════════════════════
uint8_t readMovementScore() {
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);

    float magnitude = sqrtf(
        a.acceleration.x * a.acceleration.x +
        a.acceleration.y * a.acceleration.y +
        a.acceleration.z * a.acceleration.z
    );

    // At rest (gravity only) magnitude ≈ 9.81 m/s².
    // Subtract gravity, take the residual as "movement".
    float residual = fabsf(magnitude - 9.81f);

    // Map residual 0–5 m/s² → 0–10 integer scale
    int score = (int)((residual / 5.0f) * 10.0f);
    return (uint8_t)constrain(score, 0, 10);
}

// ═══════════════════════════════════════════════════════════════════════════
//  NOTIFY LIVE DATA  (Char 1 — 3 bytes [SpO2, BPM, Movement])
// ═══════════════════════════════════════════════════════════════════════════
void notifyLiveData() {
    uint8_t spo2     = (uint8_t)constrain(latestSpO2, 0, 100);
    uint8_t bpm      = (uint8_t)constrain(latestBPM, 0, 250);
    uint8_t movement = readMovementScore();

    uint8_t payload[3] = { spo2, bpm, movement };
    pCharLive->setValue(payload, 3);
    pCharLive->notify();

    Serial.printf("[BLE TX] Live → SpO2:%d  BPM:%d  Move:%d\n", spo2, bpm, movement);
}

// ═══════════════════════════════════════════════════════════════════════════
//  APNEA ALERT  (Char 2 — 1 byte:  0 = Normal,  1 = Apnea)
// ═══════════════════════════════════════════════════════════════════════════
void checkApneaAlert(uint8_t spo2) {
    uint8_t alertVal = (spo2 > 0 && spo2 < APNEA_SPO2_THRESHOLD) ? 1 : 0;

    // Only notify on state *change* to avoid flooding the BLE link
    if (alertVal != lastAlertVal) {
        lastAlertVal = alertVal;
        pCharAlert->setValue(&alertVal, 1);

        if (deviceConnected) {
            pCharAlert->notify();
            Serial.printf("[BLE TX] Alert → %s\n",
                          alertVal ? "⚠ APNEA DETECTED" : "✓ Normal");
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  ACCUMULATE HISTORY  (push a 10-second average into the nightly buffer)
// ═══════════════════════════════════════════════════════════════════════════
void accumulateHistory(uint8_t spo2, uint8_t bpm, uint8_t movement) {
    accSpO2     += spo2;
    accBPM      += bpm;
    accMovement += movement;
    accSamples++;

    if (accSamples >= ACCUMULATE_WINDOW_S) {
        if (histCount < MAX_HISTORY_LEN) {
            histSpO2[histCount]     = (uint8_t)(accSpO2     / accSamples);
            histBPM[histCount]      = (uint8_t)(accBPM      / accSamples);
            histMovement[histCount] = (uint8_t)(accMovement  / accSamples);
            histCount++;
        }
        // Reset accumulation
        accSpO2     = 0.0f;
        accBPM      = 0.0f;
        accMovement = 0.0f;
        accSamples  = 0;
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  BULK HISTORY SYNC  (Char 3 — chunked notifications)
//  ─────────────────────────────────────────────────────────────────────────
//  Chunk protocol:
//    Byte 0 = Header tag
//       0xAA = SpO2 data chunk
//       0xBB = BPM  data chunk
//       0xCC = Movement data chunk
//       0xFF = End-of-transfer marker
//    Bytes 1..N = payload data (up to BLE_CHUNK_SIZE bytes)
// ═══════════════════════════════════════════════════════════════════════════
void sendBulkHistory() {
    if (histCount == 0) {
        Serial.println("[SYNC] No history data to send.");
        // Still send end marker so the client knows sync is complete
        uint8_t endMarker = 0xFF;
        pCharBulk->setValue(&endMarker, 1);
        pCharBulk->notify();
        return;
    }

    Serial.printf("[SYNC] Sending %d data points per metric ...\n", histCount);

    // Send SpO2 array
    sendChunkedArray(0xAA, histSpO2, histCount);
    delay(50);

    // Send BPM array
    sendChunkedArray(0xBB, histBPM, histCount);
    delay(50);

    // Send Movement array
    sendChunkedArray(0xCC, histMovement, histCount);
    delay(50);

    // Send end-of-transfer marker
    uint8_t endMarker = 0xFF;
    pCharBulk->setValue(&endMarker, 1);
    pCharBulk->notify();

    Serial.printf("[SYNC] Transfer complete.  Clearing buffers.\n");

    // Clear the history buffers for the next sleep session
    histCount = 0;
    memset(histSpO2,     0, sizeof(histSpO2));
    memset(histBPM,      0, sizeof(histBPM));
    memset(histMovement, 0, sizeof(histMovement));
}

// ── Helper: send one array as chunked BLE notifications ──────────────────
void sendChunkedArray(uint8_t header, const uint8_t* data, uint16_t len) {
    uint8_t chunk[BLE_CHUNK_SIZE + 1];   // +1 for header byte
    uint16_t offset = 0;

    while (offset < len) {
        uint16_t remaining = len - offset;
        uint16_t payloadLen = (remaining > BLE_CHUNK_SIZE) ? BLE_CHUNK_SIZE : remaining;

        chunk[0] = header;
        memcpy(&chunk[1], &data[offset], payloadLen);

        pCharBulk->setValue(chunk, payloadLen + 1);
        pCharBulk->notify();

        offset += payloadLen;

        // Small delay between chunks to let the BLE stack breathe
        delay(20);
    }

    Serial.printf("  [CHUNK] Header 0x%02X — sent %d bytes in %d chunk(s)\n",
                  header, len, (len + BLE_CHUNK_SIZE - 1) / BLE_CHUNK_SIZE);
}
