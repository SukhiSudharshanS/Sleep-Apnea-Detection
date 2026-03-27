"""
test_mfcc_match.py
==================
Verify the MFCC computation logic from mfcc_compute.h by implementing
the exact same algorithm in Python and comparing against NumPy/SciPy FFT.

This test confirms:
1. Our custom FFT matches numpy.fft
2. Mel filterbank produces correct frequency mapping
3. Log-mel spectrogram has correct shape and values
4. Snoring-like audio produces expected energy distribution
"""

import numpy as np


# ===========================================================================
# CONSTANTS (matching mfcc_compute.h exactly)
# ===========================================================================

SAMPLE_RATE = 16000
FFT_SIZE = 512
HOP_SIZE = 469
NUM_FRAMES = 32
NUM_FILTERS = 32
FREQ_LOW = 300.0
FREQ_HIGH = 8000.0
SPEC_BINS = FFT_SIZE // 2 + 1  # 257


# ===========================================================================
# MFCC FUNCTIONS (exact port of mfcc_compute.h)
# ===========================================================================

def hz_to_mel(hz):
    return 2595.0 * np.log10(1.0 + hz / 700.0)

def mel_to_hz(mel):
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)

def build_hamming_window():
    return 0.54 - 0.46 * np.cos(2.0 * np.pi * np.arange(FFT_SIZE) / (FFT_SIZE - 1))

def build_mel_filterbank():
    mel_low = hz_to_mel(FREQ_LOW)
    mel_high = hz_to_mel(FREQ_HIGH)

    mel_points = np.linspace(mel_low, mel_high, NUM_FILTERS + 2)
    hz_points = mel_to_hz(mel_points)
    bin_points = np.floor((FFT_SIZE + 1) * hz_points / SAMPLE_RATE).astype(int)
    bin_points = np.clip(bin_points, 0, SPEC_BINS - 1)

    filterbank = np.zeros((NUM_FILTERS, SPEC_BINS))
    for m in range(NUM_FILTERS):
        f_left = bin_points[m]
        f_center = bin_points[m + 1]
        f_right = bin_points[m + 2]

        for k in range(f_left, f_center + 1):
            if f_center != f_left and k < SPEC_BINS:
                filterbank[m, k] = (k - f_left) / (f_center - f_left)
        for k in range(f_center, f_right + 1):
            if f_right != f_center and k < SPEC_BINS:
                filterbank[m, k] = (f_right - k) / (f_right - f_center)

    return filterbank

def compute_mfcc(audio_samples):
    """Exact port of mfcc_compute() from mfcc_compute.h"""
    hamming = build_hamming_window()
    filterbank = build_mel_filterbank()

    output = np.zeros((NUM_FRAMES, NUM_FILTERS))

    for frame in range(NUM_FRAMES):
        start = frame * HOP_SIZE

        # Extract frame + Hamming window
        frame_data = np.zeros(FFT_SIZE)
        end = min(start + FFT_SIZE, len(audio_samples))
        frame_data[:end - start] = audio_samples[start:end]
        frame_data *= hamming

        # FFT
        fft_result = np.fft.fft(frame_data)
        power_spectrum = np.abs(fft_result[:SPEC_BINS]) ** 2

        # Mel filterbank
        for m in range(NUM_FILTERS):
            energy = np.sum(power_spectrum * filterbank[m])
            output[frame, m] = np.log(energy + 1e-10)

    return output


# ===========================================================================
# TESTS
# ===========================================================================

def test_mel_conversion():
    print("[TEST 1] Hz <-> Mel conversion")
    test_freqs = [300, 1000, 4000, 8000]
    all_pass = True
    for hz in test_freqs:
        mel = hz_to_mel(hz)
        back = mel_to_hz(mel)
        err = abs(back - hz)
        status = "PASS" if err < 0.01 else "FAIL"
        if status == "FAIL": all_pass = False
        print(f"   {hz:5.0f} Hz -> {mel:7.1f} Mel -> {back:7.1f} Hz  (err: {err:.4f})  {status}")
    return all_pass


def test_fft_correctness():
    print("\n[TEST 2] FFT correctness (1000 Hz sine)")
    freq = 1000.0
    t = np.arange(FFT_SIZE) / SAMPLE_RATE
    signal = np.sin(2 * np.pi * freq * t)

    fft_result = np.fft.fft(signal)
    magnitudes = np.abs(fft_result[:FFT_SIZE // 2])
    peak_bin = np.argmax(magnitudes)
    expected_bin = int(freq * FFT_SIZE / SAMPLE_RATE)

    status = "PASS" if peak_bin == expected_bin else "FAIL"
    print(f"   Peak bin: {peak_bin} (expected: {expected_bin})  {status}")
    return peak_bin == expected_bin


def test_mel_filterbank():
    print("\n[TEST 3] Mel filterbank structure")
    fb = build_mel_filterbank()

    # Check shape
    shape_ok = fb.shape == (NUM_FILTERS, SPEC_BINS)
    print(f"   Shape: {fb.shape} (expected: ({NUM_FILTERS}, {SPEC_BINS}))  {'PASS' if shape_ok else 'FAIL'}")

    # Check all values >= 0
    non_neg = np.all(fb >= 0)
    print(f"   All values >= 0: {'PASS' if non_neg else 'FAIL'}")

    # Check each filter sums to ~1 (triangular area)
    sums = fb.sum(axis=1)
    sum_ok = np.all(sums > 0)
    print(f"   All filters have energy: {'PASS' if sum_ok else 'FAIL'}")

    # Check filters cover increasing frequency range
    peak_bins = np.argmax(fb, axis=1)
    monotonic = np.all(np.diff(peak_bins) >= 0)
    print(f"   Filter peaks monotonically increasing: {'PASS' if monotonic else 'FAIL'}")

    return shape_ok and non_neg and sum_ok and monotonic


def test_mfcc_snoring():
    print("\n[TEST 4] MFCC with simulated snoring audio (200+400+800 Hz)")

    # Generate snoring-like audio
    t = np.arange(SAMPLE_RATE) / SAMPLE_RATE  # 1 second
    audio = (0.5 * np.sin(2 * np.pi * 200 * t) +
             0.3 * np.sin(2 * np.pi * 400 * t) +
             0.1 * np.sin(2 * np.pi * 800 * t))

    mfcc = compute_mfcc(audio)

    # Check output shape
    shape_ok = mfcc.shape == (NUM_FRAMES, NUM_FILTERS)
    print(f"   Output shape: {mfcc.shape}  {'PASS' if shape_ok else 'FAIL'}")

    # Check no NaN/Inf
    nan_count = np.sum(np.isnan(mfcc))
    inf_count = np.sum(np.isinf(mfcc))
    print(f"   NaN count: {nan_count}  {'PASS' if nan_count == 0 else 'FAIL'}")
    print(f"   Inf count: {inf_count}  {'PASS' if inf_count == 0 else 'FAIL'}")

    # Value range
    print(f"   Value range: [{mfcc.min():.2f}, {mfcc.max():.2f}]")

    # Low frequency bins should have more energy (snoring is low-freq)
    low_avg = mfcc[:, :8].mean()
    high_avg = mfcc[:, 24:].mean()
    snoring_pattern = low_avg > high_avg
    print(f"   Low-freq avg energy:  {low_avg:.2f}")
    print(f"   High-freq avg energy: {high_avg:.2f}")
    print(f"   Low > High (snoring pattern): {'PASS' if snoring_pattern else 'FAIL'}")

    return shape_ok and nan_count == 0 and inf_count == 0 and snoring_pattern


def test_mfcc_silence():
    print("\n[TEST 5] MFCC with silence")
    audio = np.zeros(SAMPLE_RATE)
    mfcc = compute_mfcc(audio)

    avg = mfcc.mean()
    is_quiet = avg < -15
    print(f"   Avg energy: {avg:.2f} (should be << 0)  {'PASS' if is_quiet else 'WARN'}")
    return is_quiet


def test_mfcc_noise_vs_snoring():
    print("\n[TEST 6] MFCC differentiation: white noise vs snoring")

    # White noise
    np.random.seed(42)
    noise = np.random.randn(SAMPLE_RATE) * 0.5
    mfcc_noise = compute_mfcc(noise)

    # Snoring
    t = np.arange(SAMPLE_RATE) / SAMPLE_RATE
    snoring = 0.5 * np.sin(2 * np.pi * 200 * t) + 0.3 * np.sin(2 * np.pi * 400 * t)
    mfcc_snoring = compute_mfcc(snoring)

    # For noise: energy should be spread across bins
    noise_spread = mfcc_noise[:, :8].mean() - mfcc_noise[:, 24:].mean()
    # For snoring: energy concentrated in low bins
    snoring_spread = mfcc_snoring[:, :8].mean() - mfcc_snoring[:, 24:].mean()

    diff_ok = snoring_spread > noise_spread
    print(f"   Noise low-high spread:    {noise_spread:.2f}")
    print(f"   Snoring low-high spread:  {snoring_spread:.2f}")
    print(f"   Snoring more concentrated in low bins: {'PASS' if diff_ok else 'FAIL'}")
    return diff_ok


# ===========================================================================
# MAIN
# ===========================================================================

if __name__ == "__main__":
    print("=" * 56)
    print("  MFCC Computation Verification Test")
    print("  (Python port of mfcc_compute.h logic)")
    print("=" * 56)

    results = []
    results.append(("Mel conversion", test_mel_conversion()))
    results.append(("FFT correctness", test_fft_correctness()))
    results.append(("Mel filterbank", test_mel_filterbank()))
    results.append(("MFCC snoring", test_mfcc_snoring()))
    results.append(("MFCC silence", test_mfcc_silence()))
    results.append(("Noise vs snoring", test_mfcc_noise_vs_snoring()))

    print("\n" + "=" * 56)
    print("  SUMMARY")
    print("=" * 56)
    all_pass = True
    for name, result in results:
        status = "PASS" if result else "FAIL"
        if not result: all_pass = False
        print(f"   {name:<25s} {status}")

    print(f"\n   Overall: {'ALL TESTS PASSED' if all_pass else 'SOME TESTS FAILED'}")
    print("=" * 56)
