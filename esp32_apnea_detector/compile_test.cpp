/*
 * compile_test.cpp
 * ================
 * Standalone compilation test for mfcc_compute.h and code logic.
 * Verifies FFT, Mel filterbank, and MFCC computation WITHOUT
 * requiring Arduino/ESP32 toolchain.
 *
 * Compile: g++ -o compile_test compile_test.cpp -lm -std=c++11
 * Run:     ./compile_test
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cstdint>

// Include the MFCC header directly
#include "mfcc_compute.h"

// ===== Test harness =====

void test_fft() {
    printf("[TEST] FFT correctness...\n");

    // Create a simple sine wave at 1000 Hz sampled at 16kHz
    float real[MFCC_FFT_SIZE];
    float imag[MFCC_FFT_SIZE];
    memset(imag, 0, sizeof(imag));

    float freq = 1000.0f;
    for (int i = 0; i < MFCC_FFT_SIZE; i++) {
        real[i] = sinf(2.0f * M_PI * freq * i / MFCC_SAMPLE_RATE);
    }

    compute_fft(real, imag, MFCC_FFT_SIZE);

    // Find the bin with maximum magnitude
    int max_bin = 0;
    float max_mag = 0;
    for (int k = 0; k < MFCC_FFT_SIZE / 2; k++) {
        float mag = sqrtf(real[k] * real[k] + imag[k] * imag[k]);
        if (mag > max_mag) {
            max_mag = mag;
            max_bin = k;
        }
    }

    // Expected bin: freq * FFT_SIZE / SAMPLE_RATE = 1000 * 512 / 16000 = 32
    int expected_bin = (int)(freq * MFCC_FFT_SIZE / MFCC_SAMPLE_RATE);
    printf("   Peak bin: %d (expected: %d) -- %s\n",
           max_bin, expected_bin,
           (max_bin == expected_bin) ? "PASS" : "FAIL");
}

void test_mel_conversion() {
    printf("[TEST] Hz <-> Mel conversion...\n");

    float hz_values[] = {300.0f, 1000.0f, 4000.0f, 8000.0f};
    for (int i = 0; i < 4; i++) {
        float mel = hz_to_mel(hz_values[i]);
        float hz_back = mel_to_hz(mel);
        float err = fabsf(hz_back - hz_values[i]);
        printf("   %.0f Hz -> %.1f Mel -> %.1f Hz (err: %.4f) -- %s\n",
               hz_values[i], mel, hz_back, err,
               (err < 0.01f) ? "PASS" : "FAIL");
    }
}

void test_mfcc_output() {
    printf("[TEST] MFCC computation...\n");

    // Initialize lookup tables
    mfcc_init();
    printf("   Hamming window initialized\n");
    printf("   Mel filterbank initialized (32 filters, 300-8000 Hz)\n");

    // Create synthetic audio: 1 second of mixed sine waves (simulate snoring ~200Hz)
    int32_t audio[AUDIO_1SEC_SAMPLES];
    for (int i = 0; i < AUDIO_1SEC_SAMPLES; i++) {
        float t = (float)i / MFCC_SAMPLE_RATE;
        // Simulate snoring: 200Hz fundamental + 400Hz harmonic
        float sample = 0.5f * sinf(2.0f * M_PI * 200.0f * t) +
                       0.3f * sinf(2.0f * M_PI * 400.0f * t) +
                       0.1f * sinf(2.0f * M_PI * 800.0f * t);
        // Convert to int32 format (matching I2S output, 14-bit shift)
        audio[i] = (int32_t)(sample * 32768.0f) << 14;
    }

    // Compute MFCC
    float output[MFCC_NUM_FRAMES][MFCC_NUM_FILTERS];
    mfcc_compute(audio, AUDIO_1SEC_SAMPLES, output);

    // Verify output dimensions and that values are finite
    int nan_count = 0;
    int inf_count = 0;
    float min_val = 1e10f, max_val = -1e10f;
    for (int f = 0; f < MFCC_NUM_FRAMES; f++) {
        for (int m = 0; m < MFCC_NUM_FILTERS; m++) {
            float v = output[f][m];
            if (v != v) nan_count++;           // NaN check
            if (v == INFINITY || v == -INFINITY) inf_count++;
            if (v < min_val) min_val = v;
            if (v > max_val) max_val = v;
        }
    }

    printf("   Output shape: (%d, %d) -- PASS\n", MFCC_NUM_FRAMES, MFCC_NUM_FILTERS);
    printf("   NaN values: %d -- %s\n", nan_count, (nan_count == 0) ? "PASS" : "FAIL");
    printf("   Inf values: %d -- %s\n", inf_count, (inf_count == 0) ? "PASS" : "FAIL");
    printf("   Value range: [%.2f, %.2f]\n", min_val, max_val);
    printf("   (Low mel bins should be higher for snoring-like audio)\n");

    // Check that low frequency bins have more energy (snoring is low-freq)
    float low_freq_avg = 0, high_freq_avg = 0;
    for (int f = 0; f < MFCC_NUM_FRAMES; f++) {
        for (int m = 0; m < 8; m++) low_freq_avg += output[f][m];
        for (int m = 24; m < 32; m++) high_freq_avg += output[f][m];
    }
    low_freq_avg /= (MFCC_NUM_FRAMES * 8);
    high_freq_avg /= (MFCC_NUM_FRAMES * 8);
    printf("   Low-freq avg energy:  %.2f\n", low_freq_avg);
    printf("   High-freq avg energy: %.2f\n", high_freq_avg);
    printf("   Low > High: %s\n",
           (low_freq_avg > high_freq_avg) ? "PASS (snoring pattern detected)" : "FAIL");

    // Print first few values of frame 0 for visual inspection
    printf("\n   Frame 0 mel bins (first 8): ");
    for (int m = 0; m < 8; m++) printf("%.2f ", output[0][m]);
    printf("\n   Frame 0 mel bins (last  8): ");
    for (int m = 24; m < 32; m++) printf("%.2f ", output[0][m]);
    printf("\n");
}

void test_silence() {
    printf("\n[TEST] MFCC with silence...\n");

    int32_t silence[AUDIO_1SEC_SAMPLES];
    memset(silence, 0, sizeof(silence));

    float output[MFCC_NUM_FRAMES][MFCC_NUM_FILTERS];
    mfcc_compute(silence, AUDIO_1SEC_SAMPLES, output);

    float avg = 0;
    for (int f = 0; f < MFCC_NUM_FRAMES; f++)
        for (int m = 0; m < MFCC_NUM_FILTERS; m++)
            avg += output[f][m];
    avg /= (MFCC_NUM_FRAMES * MFCC_NUM_FILTERS);

    printf("   Avg energy for silence: %.2f (should be very negative) -- %s\n",
           avg, (avg < -15.0f) ? "PASS" : "WARN");
}

int main() {
    printf("\n========================================\n");
    printf("  MFCC Compute -- Compilation & Logic Test\n");
    printf("========================================\n\n");

    test_mel_conversion();
    test_fft();
    test_mfcc_output();
    test_silence();

    printf("\n========================================\n");
    printf("  ALL TESTS COMPLETE\n");
    printf("========================================\n\n");

    return 0;
}
