/*
 * mfcc_compute.h
 * ==============
 * Real MFCC computation for ESP32 -- FFT + Mel Filterbank + Log Energy
 *
 * Produces a (32, 32) log-mel spectrogram from 1 second of 16kHz audio,
 * matching the features the pretrained snoring CNN expects.
 *
 * Pipeline per second of audio (16000 samples):
 *   1. Split into 32 overlapping frames (512 samples each, hop=469)
 *   2. Apply Hamming window to each frame
 *   3. Compute 512-point FFT (radix-2)
 *   4. Compute power spectrum |FFT|^2
 *   5. Apply 32 triangular Mel-scale filterbank
 *   6. Take log of each filter output
 *   -> Result: 32 time frames x 32 mel bins
 *
 * Parameters (standard for snoring/speech at 16kHz):
 *   Sample rate : 16000 Hz
 *   FFT size    : 512 (32ms window)
 *   Hop size    : 469 samples (~29ms) -> 32 frames per second
 *   Mel filters : 32 (300 Hz to 8000 Hz)
 */

#ifndef MFCC_COMPUTE_H
#define MFCC_COMPUTE_H

#include <math.h>
#include <string.h>

// ===========================================================================
// CONSTANTS
// ===========================================================================

#define MFCC_SAMPLE_RATE   16000
#define MFCC_FFT_SIZE      512
#define MFCC_HOP_SIZE      469    // (16000 - 512) / (32 - 1) ≈ 469
#define MFCC_NUM_FRAMES    32
#define MFCC_NUM_FILTERS   32
#define MFCC_FREQ_LOW      300.0f   // Mel filterbank lower edge (Hz)
#define MFCC_FREQ_HIGH     8000.0f  // Mel filterbank upper edge (Hz)

// FFT half-spectrum bins (only need positive frequencies)
#define MFCC_SPEC_BINS     (MFCC_FFT_SIZE / 2 + 1)  // 257

// ===========================================================================
// PRECOMPUTED TABLES (initialized once in mfcc_init)
// ===========================================================================

static float hamming_window[MFCC_FFT_SIZE];
static int mel_bin_points[MFCC_NUM_FILTERS + 2];
static bool mfcc_initialized = false;

// FFT working buffers
static float fft_real[MFCC_FFT_SIZE];
static float fft_imag[MFCC_FFT_SIZE];

// ===========================================================================
// HELPER: Hz <-> Mel conversion
// ===========================================================================

static inline float hz_to_mel(float hz) {
  return 2595.0f * log10f(1.0f + hz / 700.0f);
}

static inline float mel_to_hz(float mel) {
  return 700.0f * (powf(10.0f, mel / 2595.0f) - 1.0f);
}

// ===========================================================================
// IN-PLACE RADIX-2 FFT (Cooley-Tukey)
// ===========================================================================

static void fft_bit_reverse(float* real, float* imag, int n) {
  int j = 0;
  for (int i = 0; i < n; i++) {
    if (j > i) {
      float tmp_r = real[j]; real[j] = real[i]; real[i] = tmp_r;
      float tmp_i = imag[j]; imag[j] = imag[i]; imag[i] = tmp_i;
    }
    int m = n >> 1;
    while (m >= 1 && j >= m) {
      j -= m;
      m >>= 1;
    }
    j += m;
  }
}

static void compute_fft(float* real, float* imag, int n) {
  /*
   * In-place radix-2 Cooley-Tukey FFT.
   * n must be a power of 2 (512 in our case).
   */
  fft_bit_reverse(real, imag, n);

  for (int step = 2; step <= n; step <<= 1) {
    int half = step >> 1;
    float angle = -2.0f * M_PI / (float)step;

    for (int group = 0; group < n; group += step) {
      for (int pair = 0; pair < half; pair++) {
        float w_real = cosf(angle * pair);
        float w_imag = sinf(angle * pair);

        int even = group + pair;
        int odd  = group + pair + half;

        float odd_r = real[odd] * w_real - imag[odd] * w_imag;
        float odd_i = real[odd] * w_imag + imag[odd] * w_real;

        real[odd]  = real[even] - odd_r;
        imag[odd]  = imag[even] - odd_i;
        real[even] += odd_r;
        imag[even] += odd_i;
      }
    }
  }
}

// ===========================================================================
// INITIALIZE (call once in setup)
// ===========================================================================

void mfcc_init() {
  if (mfcc_initialized) return;

  // --- Hamming window ---
  for (int i = 0; i < MFCC_FFT_SIZE; i++) {
    hamming_window[i] = 0.54f - 0.46f * cosf(2.0f * M_PI * i / (MFCC_FFT_SIZE - 1));
  }

  // --- Mel filterbank (32 triangular filters) ---
  float mel_low  = hz_to_mel(MFCC_FREQ_LOW);
  float mel_high = hz_to_mel(MFCC_FREQ_HIGH);

  // (NUM_FILTERS + 2) equally spaced points in Mel scale
  float mel_points[MFCC_NUM_FILTERS + 2];
  for (int i = 0; i < MFCC_NUM_FILTERS + 2; i++) {
    mel_points[i] = mel_low + (mel_high - mel_low) * i / (MFCC_NUM_FILTERS + 1);
  }

  // Convert Mel points to FFT bin indices globally
  for (int i = 0; i < MFCC_NUM_FILTERS + 2; i++) {
    float hz = mel_to_hz(mel_points[i]);
    mel_bin_points[i] = (int)floorf((MFCC_FFT_SIZE + 1) * hz / MFCC_SAMPLE_RATE);
    if (mel_bin_points[i] >= MFCC_SPEC_BINS) mel_bin_points[i] = MFCC_SPEC_BINS - 1;
  }

  mfcc_initialized = true;
}

// ===========================================================================
// COMPUTE LOG-MEL SPECTROGRAM FOR ONE SECOND OF AUDIO
// ===========================================================================

void mfcc_compute(const int16_t* audio_samples, int num_samples,
                  float output[MFCC_NUM_FRAMES][MFCC_NUM_FILTERS]) {
  /*
   * Compute a (32, 32) log-mel spectrogram from raw I2S audio.
   *
   * Args:
   *   audio_samples : Raw 16-bit I2S samples (16000 for 1 second)
   *   num_samples   : Number of samples (should be 16000)
   *   output        : [32][32] array to fill with log-mel energy
   */

  for (int frame = 0; frame < MFCC_NUM_FRAMES; frame++) {
    int start = frame * MFCC_HOP_SIZE;

    // --- Step 1: Extract frame and apply Hamming window ---
    for (int i = 0; i < MFCC_FFT_SIZE; i++) {
      int idx = start + i;
      if (idx < num_samples) {
        // Convert 16-bit I2S sample to normalized float [-1, 1]
        float sample = (float)audio_samples[idx] / 32768.0f;
        fft_real[i] = sample * hamming_window[i];
      } else {
        fft_real[i] = 0.0f;  // Zero-pad if beyond audio length
      }
      fft_imag[i] = 0.0f;
    }

    // --- Step 2: Compute 512-point FFT ---
    compute_fft(fft_real, fft_imag, MFCC_FFT_SIZE);

    // --- Step 3: Compute power spectrum |FFT|^2 ---
    float power_spectrum[MFCC_SPEC_BINS];
    for (int k = 0; k < MFCC_SPEC_BINS; k++) {
      power_spectrum[k] = fft_real[k] * fft_real[k] + fft_imag[k] * fft_imag[k];
    }

    // --- Step 4: Apply Mel filterbank dynamically ---
    for (int m = 0; m < MFCC_NUM_FILTERS; m++) {
      int f_left   = mel_bin_points[m];
      int f_center = mel_bin_points[m + 1];
      int f_right  = mel_bin_points[m + 2];

      float energy = 0.0f;
      
      // Rising slope: f_left to f_center
      for (int k = f_left; k <= f_center && k < MFCC_SPEC_BINS; k++) {
        if (f_center != f_left) {
          float weight = (float)(k - f_left) / (float)(f_center - f_left);
          energy += power_spectrum[k] * weight;
        }
      }
      
      // Falling slope: f_center to f_right (avoid double counting f_center)
      for (int k = f_center + 1; k <= f_right && k < MFCC_SPEC_BINS; k++) {
        if (f_right != f_center) {
          float weight = (float)(f_right - k) / (float)(f_right - f_center);
          energy += power_spectrum[k] * weight;
        }
      }

      // --- Step 5: Log energy (with floor to avoid log(0)) ---
      output[frame][m] = logf(energy + 1e-10f);
    }
  }
}

#endif  // MFCC_COMPUTE_H
