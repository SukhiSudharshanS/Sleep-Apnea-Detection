#include <Wire.h>
#include <math.h>
#include <climits>

#include "MAX30105.h"
#include "spo2_algorithm.h"
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <driver/i2s.h>

#include "mfcc_compute.h"

#include <TensorFlowLite_ESP32.h>
#include <tensorflow/lite/micro/micro_mutable_op_resolver.h>
#include <tensorflow/lite/micro/micro_interpreter.h>
#include <tensorflow/lite/schema/schema_generated.h>
#include <tensorflow/lite/micro/micro_error_reporter.h>

#include "sleep_apnea_model.h"

#define I2C_SDA    21
#define I2C_SCL    22
#define I2S_WS     25
#define I2S_SCK    26
#define I2S_SD     33
#define BUZZER_PIN 27
#define LED_PIN    2

#define WINDOW_SECONDS     60
#define MFCC_ROWS          32
#define MFCC_COLS          32
#define RISK_THRESHOLD     0.6f
#define AUDIO_SAMPLE_RATE  16000
#define AUDIO_1SEC_SAMPLES 16000
#define FFT_SIZE           512
#define FFT_HOP            256
#define AUDIO_BINS         32
#define SPO2_BUFFER_LEN    100
#define ALERT_BEEPS        3
#define ALERT_ON_MS        300
#define ALERT_OFF_MS       200

constexpr int kTensorArenaSize = 75 * 1024;
uint8_t* tensor_arena = nullptr;

void  setup_max30102();
void  setup_mpu6050();
void  setup_inmp441();
void  setup_fft();
void  setup_tflite();
void  read_spo2_and_bpm();
float read_movement_magnitude();
void  capture_audio_mfcc();
void  run_inference();
void  start_alert();
void  tick_alert();

MAX30105         maxSensor;
Adafruit_MPU6050 mpu;

const tflite::Model*      tfl_model   = nullptr;
tflite::MicroInterpreter* interpreter = nullptr;
TfLiteTensor* input_audio    = nullptr;
TfLiteTensor* input_max30102 = nullptr;
TfLiteTensor* input_mpu6050  = nullptr;
TfLiteTensor* output_tensor  = nullptr;

float spo2_buffer[WINDOW_SECONDS];
float bpm_buffer[WINDOW_SECONDS];
float movement_buffer[WINDOW_SECONDS];
float current_mfcc[MFCC_NUM_FRAMES][MFCC_NUM_FILTERS];
float loudest_mfcc[MFCC_NUM_FRAMES][MFCC_NUM_FILTERS];
float max_window_energy = -9999.0f;

int16_t* audio_raw = nullptr;

static bool  fft_ready = false;

uint32_t ir_buf_spo2[SPO2_BUFFER_LEN];
uint32_t red_buf_spo2[SPO2_BUFFER_LEN];

float  latest_spo2 = 98.0f;
float  latest_bpm  = 70.0f;
int8_t spo2_valid  = 0;
int8_t hr_valid    = 0;

float current_audio_db = 0.0f;
int   buffer_index = 0;
bool  buffer_full  = false;

enum AlertState { ALERT_IDLE, ALERT_BEEP_ON, ALERT_BEEP_OFF };
AlertState    alertState     = ALERT_IDLE;
int           alertBeepCount = 0;
unsigned long alertTimer     = 0;

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n=== Sleep Apnea Detector v3 ===\n");

  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_PIN,    OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  digitalWrite(LED_PIN,    LOW);

  Wire.begin(I2C_SDA, I2C_SCL);

  setup_max30102();
  setup_mpu6050();
  setup_inmp441();
  setup_fft();
  setup_tflite();

  memset(spo2_buffer,     0, sizeof(spo2_buffer));
  memset(bpm_buffer,      0, sizeof(bpm_buffer));
  memset(movement_buffer, 0, sizeof(movement_buffer));
  memset(current_mfcc,    0, sizeof(current_mfcc));
  memset(loudest_mfcc,    0, sizeof(loudest_mfcc));

  Serial.println("[READY] First inference after 60 seconds.\n");
}

void setup_max30102() {
  Serial.print("[MAX30102] ");
  if (!maxSensor.begin(Wire, I2C_SPEED_STANDARD)) {
    Serial.println("FAILED"); while (1);
  }
  maxSensor.setup(60, 4, 2, 400, 411, 4096);
  Serial.println("OK");
}

void setup_mpu6050() {
  Serial.print("[MPU6050]  ");
  if (!mpu.begin()) {
    Serial.println("FAILED"); while (1);
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  Serial.println("OK");
}

void setup_inmp441() {
  Serial.print("[INMP441]  ");

  i2s_config_t cfg;
  memset(&cfg, 0, sizeof(cfg));
  cfg.mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate          = AUDIO_SAMPLE_RATE;
  cfg.bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count        = 8;
  cfg.dma_buf_len          = 1024;
  cfg.use_apll             = false;
  cfg.tx_desc_auto_clear   = false;
  cfg.fixed_mclk           = 0;

  i2s_pin_config_t pins;
  memset(&pins, 0, sizeof(pins));
  pins.bck_io_num   = I2S_SCK;
  pins.ws_io_num    = I2S_WS;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num  = I2S_SD;

  i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  i2s_set_pin(I2S_NUM_0, &pins);
  i2s_zero_dma_buffer(I2S_NUM_0);
  Serial.println("OK");
}

void setup_fft() {
  Serial.print("[MFCC]     ");
  mfcc_init();
  fft_ready = true;
  Serial.println("OK");
}

void setup_tflite() {
  Serial.print("[TFLITE]   ");

  // Allocate tensor arena from heap
  tensor_arena = (uint8_t*)malloc(kTensorArenaSize);
  if (!tensor_arena) {
    Serial.println("Arena malloc FAILED!"); while (1);
  }

  static tflite::ErrorReporter* error_reporter = tflite::GetMicroErrorReporter();

  tfl_model = tflite::GetModel(sleep_apnea_model_data);
  if (tfl_model->version() != TFLITE_SCHEMA_VERSION) {
    Serial.println("Schema mismatch!"); while (1);
  }

  static tflite::MicroMutableOpResolver<15> resolver;
  resolver.AddExpandDims();
  resolver.AddShape();
  resolver.AddLogistic();
  resolver.AddReshape();
  resolver.AddStridedSlice();
  resolver.AddPack();
  resolver.AddDequantize();
  resolver.AddQuantize();
  resolver.AddMul();
  resolver.AddFullyConnected();
  resolver.AddAdd();
  resolver.AddMean();
  resolver.AddConv2D();
  resolver.AddConcatenation();
  resolver.AddMaxPool2D();
  static tflite::MicroInterpreter static_interp(
      tfl_model, resolver,
      tensor_arena, kTensorArenaSize, error_reporter);
  interpreter = &static_interp;

  if (interpreter->AllocateTensors() != kTfLiteOk) {
    Serial.println("AllocateTensors FAILED!"); while (1);
  }

  for (int i = 0; i < interpreter->inputs_size(); i++) {
    TfLiteTensor* t = interpreter->input_tensor(i);
    if (t->dims->size == 4 && t->dims->data[1] == MFCC_ROWS && t->dims->data[2] == MFCC_COLS)
      input_audio = t;
    else if (t->dims->size == 3 && t->dims->data[2] == 2)
      input_max30102 = t;
    else if (t->dims->size == 3 && t->dims->data[2] == 1)
      input_mpu6050 = t;
  }

  if (!input_audio || !input_max30102 || !input_mpu6050) {
    Serial.println("Tensor mapping FAILED!"); while (1);
  }

  output_tensor = interpreter->output_tensor(0);
  Serial.println("OK");
  Serial.printf("   Arena: %d / %d bytes\n", interpreter->arena_used_bytes(), kTensorArenaSize);
}

void read_spo2_and_bpm() {
  if (maxSensor.getIR() < 50000) {
    latest_spo2 = 0.0f;
    latest_bpm  = 0.0f;
    return;
  }
  for (int i = 0; i < SPO2_BUFFER_LEN; i++) {
    while (!maxSensor.available()) maxSensor.check();
    red_buf_spo2[i] = maxSensor.getRed();
    ir_buf_spo2[i]  = maxSensor.getIR();
    maxSensor.nextSample();
  }
  int32_t spo2_val = 0, hr_val = 0;
  maxim_heart_rate_and_oxygen_saturation(
      ir_buf_spo2, SPO2_BUFFER_LEN, red_buf_spo2,
      &spo2_val, &spo2_valid,
      &hr_val,   &hr_valid);

  if (spo2_valid && spo2_val > 0 && spo2_val <= 100) latest_spo2 = (float)spo2_val;
  if (hr_valid   && hr_val  > 20 && hr_val   <  250) latest_bpm  = (float)hr_val;
}

float read_movement_magnitude() {
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);
  return sqrtf(
    a.acceleration.x * a.acceleration.x +
    a.acceleration.y * a.acceleration.y +
    a.acceleration.z * a.acceleration.z
  ) / 9.81f;
}

void capture_audio_mfcc() {
  if (!fft_ready) return;

  // Temporarily allocate audio buffer from heap
  audio_raw = (int16_t*)malloc(AUDIO_1SEC_SAMPLES * sizeof(int16_t));
  if (!audio_raw) {
    current_audio_db = -999.0f;
    return;
  }

  int total = 0;
  size_t got = 0;
  while (total < AUDIO_1SEC_SAMPLES) {
    int want = min(1024, AUDIO_1SEC_SAMPLES - total);
    i2s_read(I2S_NUM_0, &audio_raw[total], want * sizeof(int16_t), &got, 100 / portTICK_PERIOD_MS);
    total += (int)(got / sizeof(int16_t));
    if (got == 0) break;
  }

  if (total >= MFCC_FFT_SIZE) {
    mfcc_compute(audio_raw, total, current_mfcc);
    
    float peak = -999.0f;
    float total_energy = 0.0f;
    for (int r = 0; r < MFCC_NUM_FRAMES; r++) {
      for (int c = 0; c < MFCC_NUM_FILTERS; c++) {
        total_energy += current_mfcc[r][c];
        if (current_mfcc[r][c] > peak) peak = current_mfcc[r][c];
      }
    }
    current_audio_db = peak;

    // Cache this second if it's the loudest event witnessed this minute
    if (total_energy > max_window_energy) {
      max_window_energy = total_energy;
      memcpy(loudest_mfcc, current_mfcc, sizeof(current_mfcc));
    }
  } else {
    current_audio_db = -999.0f;
  }

  free(audio_raw);
  audio_raw = nullptr;
}

void start_alert() {
  alertBeepCount = 0;
  alertState     = ALERT_BEEP_ON;
  alertTimer     = millis();
  digitalWrite(BUZZER_PIN, HIGH);
  digitalWrite(LED_PIN,    HIGH);
}

void tick_alert() {
  if (alertState == ALERT_IDLE) return;
  unsigned long now = millis();
  if (alertState == ALERT_BEEP_ON && now - alertTimer >= ALERT_ON_MS) {
    digitalWrite(BUZZER_PIN, LOW);
    digitalWrite(LED_PIN,    LOW);
    alertTimer = now;
    alertState = ALERT_BEEP_OFF;
  } else if (alertState == ALERT_BEEP_OFF && now - alertTimer >= ALERT_OFF_MS) {
    if (++alertBeepCount >= ALERT_BEEPS) {
      alertState = ALERT_IDLE;
    } else {
      digitalWrite(BUZZER_PIN, HIGH);
      digitalWrite(LED_PIN,    HIGH);
      alertTimer = now;
      alertState = ALERT_BEEP_ON;
    }
  }
}

void loop() {
  static unsigned long lastSampleTime = 0;
  tick_alert();

  if (millis() - lastSampleTime >= 1000) {
    lastSampleTime = millis();

    read_spo2_and_bpm();
    float spo2     = latest_spo2;
    float bpm      = latest_bpm;
    float movement = read_movement_magnitude();
    capture_audio_mfcc(); // Listen every single second!

    spo2_buffer[buffer_index]     = spo2;
    bpm_buffer[buffer_index]      = bpm;
    movement_buffer[buffer_index] = movement;

    buffer_index++;
    if (buffer_index >= WINDOW_SECONDS) { buffer_index = 0; buffer_full = true; }

    Serial.printf("[LIVE] SpO2:%.1f%% BPM:%.0f Move:%.2fg Audio:%.1f [%d/%d]\n",
                  spo2, bpm, movement, current_audio_db,
                  buffer_full ? WINDOW_SECONDS : buffer_index, WINDOW_SECONDS);

    if (buffer_full && buffer_index == 0) {
      run_inference();
      max_window_energy = -9999.0f; // Reset tracking for next minute
    }
  }

  maxSensor.getIR();
}

void run_inference() {
  Serial.println("\n=== Inference ===");

  for (int row = 0; row < MFCC_ROWS; row++) {
    for (int col = 0; col < MFCC_COLS; col++)
      input_audio->data.f[row * MFCC_COLS + col] = loudest_mfcc[row][col];
  }

  for (int t = 0; t < WINDOW_SECONDS; t++) {
    int idx = (buffer_index + t) % WINDOW_SECONDS;
    input_max30102->data.f[t * 2 + 0] = spo2_buffer[idx];
    input_max30102->data.f[t * 2 + 1] = bpm_buffer[idx];
  }

  for (int t = 0; t < WINDOW_SECONDS; t++)
    input_mpu6050->data.f[t] = movement_buffer[(buffer_index + t) % WINDOW_SECONDS];

  unsigned long t0 = micros();
  if (interpreter->Invoke() != kTfLiteOk) {
    Serial.println("[ERROR] Invoke failed!"); return;
  }
  unsigned long ms = (micros() - t0) / 1000UL;

  float risk = output_tensor->data.f[0];
  Serial.printf("[RESULT] Risk: %.4f  (%lu ms)\n", risk, ms);

  if (risk >= RISK_THRESHOLD) {
    Serial.println("[ALERT] HIGH RISK!");
    start_alert();
  } else if (risk >= 0.3f) {
    Serial.println("[WARN]  Moderate risk.");
    digitalWrite(LED_PIN, HIGH); delay(200); digitalWrite(LED_PIN, LOW);
  } else {
    Serial.println("[OK]    Normal.");
  }

  float avg_spo2 = 0, avg_bpm = 0, max_move = 0, max_audio = current_audio_db;
  for (int i = 0; i < WINDOW_SECONDS; i++) {
    avg_spo2 += spo2_buffer[i];
    avg_bpm  += bpm_buffer[i];
    if (movement_buffer[i] > max_move) max_move = movement_buffer[i];
  }
  Serial.printf("   SpO2:%.1f%% BPM:%.0f Move:%.2fg Audio:%.1f\n",
                avg_spo2 / WINDOW_SECONDS, avg_bpm / WINDOW_SECONDS, max_move, max_audio);
  Serial.println("=================\n");
}