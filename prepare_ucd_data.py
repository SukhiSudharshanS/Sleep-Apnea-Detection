"""
prepare_ucd_data.py
====================
Extract SpO2 + Pulse Rate from the UCD Sleep Apnea Database (PhysioNet)
and train the MAX30102 branch of the fusion model on real clinical data.

Steps:
  1. Parse .rec (EDF) files  -> extract SpO2 & Pulse Rate channels
  2. Parse _respevt.txt      -> get apnea event timestamps & durations
  3. Window into 60-second segments with binary labels
  4. Save processed dataset as .npz
  5. (Optional) Train the fusion model on the real data

Usage:
  python prepare_ucd_data.py --data_dir <path_to_ucddb_folder>
  python prepare_ucd_data.py --data_dir <path_to_ucddb_folder> --train

The data_dir should contain files like:
  ucddb002.rec, ucddb002_respevt.txt, ucddb003.rec, ...
"""

import os
import re
import glob
import argparse
import numpy as np
from datetime import datetime, timedelta

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"


# ===========================================================================
# 1. PARSE EDF (.rec) FILES -- Extract SpO2 & Pulse Rate
# ===========================================================================

def extract_spo2_pulse(rec_path):
    """
    Read a .rec (EDF) file and extract the SpO2 and Pulse Rate signals.

    Returns:
        spo2   : np.array -- SpO2 values (%) resampled to 1 Hz (1 sample/sec)
        pulse  : np.array -- Pulse Rate (BPM) resampled to 1 Hz
        start_time : datetime -- recording start time from EDF header
    """
    import pyedflib

    f = pyedflib.EdfReader(rec_path)

    n_signals = f.signals_in_file
    labels = [f.getLabel(i).strip().lower() for i in range(n_signals)]

    # Find SpO2 channel
    spo2_idx = None
    pulse_idx = None
    for i, label in enumerate(labels):
        if "sao2" in label or "spo2" in label or "oxygen" in label or "sat" in label:
            spo2_idx = i
        if "pulse" in label or "heart" in label or "pr" == label:
            pulse_idx = i

    if spo2_idx is None:
        print(f"   [WARN] No SpO2 channel found in {rec_path}")
        print(f"          Available channels: {labels}")
        f._close()
        return None, None, None

    # Read signals
    spo2_raw = f.readSignal(spo2_idx)
    spo2_fs = int(f.getSampleFrequency(spo2_idx))

    if pulse_idx is not None:
        pulse_raw = f.readSignal(pulse_idx)
        pulse_fs = int(f.getSampleFrequency(pulse_idx))
    else:
        # If no separate pulse channel, we still proceed with SpO2 only
        # and fill pulse with a placeholder (we'll handle this downstream)
        pulse_raw = None
        pulse_fs = None

    # Get recording start time
    start_time = f.getStartdatetime()

    f._close()

    # Resample to 1 Hz (1 sample per second) by averaging
    def resample_to_1hz(signal, fs):
        n_seconds = len(signal) // fs
        resampled = np.zeros(n_seconds, dtype=np.float32)
        for s in range(n_seconds):
            chunk = signal[s * fs : (s + 1) * fs]
            resampled[s] = np.mean(chunk)
        return resampled

    spo2 = resample_to_1hz(spo2_raw, spo2_fs)

    if pulse_raw is not None:
        pulse = resample_to_1hz(pulse_raw, pulse_fs)
        # Make sure both are the same length
        min_len = min(len(spo2), len(pulse))
        spo2 = spo2[:min_len]
        pulse = pulse[:min_len]
    else:
        # Estimate pulse rate from SpO2 changes (rough fallback)
        pulse = np.full_like(spo2, 70.0)  # placeholder
        print(f"   [WARN] No Pulse channel found, using placeholder BPM=70")

    return spo2, pulse, start_time


# ===========================================================================
# 2. PARSE RESPIRATORY EVENT ANNOTATIONS
# ===========================================================================

def parse_respevt(respevt_path, recording_start_time, total_seconds):
    """
    Parse a _respevt.txt annotation file from the UCD database.
    Returns a binary array: 1 = apnea/hypopnea event active, 0 = normal.

    Actual UCD format (3-line header then data):
        00:29:13  HYP-C             16       89.9    4.1     -     -      64.7   -5.7
        01:42:22  APNEA-O           17       87.9    7       -     -      64.7   -7.3
    Where:
        Col 0 = Time of event (HH:MM:SS)
        Col 1 = Event type (HYP-C, HYP-O, APNEA-O, APNEA-M, APNEA-C, HYP-M)
        Col 2 = Optional PB/CS flag (usually empty)
        Col 3 = Duration (seconds)
    """
    event_mask = np.zeros(total_seconds, dtype=np.float32)

    if not os.path.exists(respevt_path):
        print(f"   [WARN] No annotation file: {respevt_path}")
        return event_mask

    with open(respevt_path, "r", errors="replace") as f:
        lines = f.readlines()

    # Recognized apnea/hypopnea event keywords
    APNEA_KEYWORDS = ["HYP", "APNEA", "HYPOPNEA"]
    events_found = 0

    for line in lines:
        line = line.strip()
        if not line:
            continue

        parts = line.split()
        if len(parts) < 3:
            continue

        # First column must look like a timestamp HH:MM:SS
        time_str = parts[0]
        if not re.match(r"^\d{2}:\d{2}:\d{2}$", time_str):
            continue  # skip header lines

        event_type = parts[1].upper()

        # Check if this is an apnea-related event
        is_apnea = any(kw in event_type for kw in APNEA_KEYWORDS)
        if not is_apnea:
            continue

        # Parse duration: find the first integer-like number after the event type
        duration = None
        for p in parts[2:]:
            try:
                val = float(p)
                # Duration is typically 5-120 seconds, and is usually an integer
                if 3.0 <= val <= 300.0 and (val == int(val) or val > 5):
                    duration = val
                    break
            except ValueError:
                continue

        if duration is None:
            duration = 15.0  # reasonable default for apnea events

        # Calculate event start offset in seconds from recording start
        try:
            event_time = datetime.strptime(time_str, "%H:%M:%S")
            event_dt = recording_start_time.replace(
                hour=event_time.hour,
                minute=event_time.minute,
                second=event_time.second,
            )

            # Handle overnight crossing (event time < start time = next day)
            if event_dt < recording_start_time:
                event_dt += timedelta(days=1)

            offset_sec = int((event_dt - recording_start_time).total_seconds())

            if 0 <= offset_sec < total_seconds:
                end_sec = min(offset_sec + int(duration), total_seconds)
                event_mask[offset_sec:end_sec] = 1.0
                events_found += 1
        except (ValueError, TypeError):
            continue

    print(f"     Events parsed: {events_found} respiratory events")
    return event_mask


# ===========================================================================
# 3. WINDOW THE DATA INTO 60-SECOND SEGMENTS
# ===========================================================================

def create_windows(spo2, pulse, event_mask, window_size=60, stride=30):
    """
    Slide a 60-second window across the data.

    Returns:
        X : np.array (N, 60, 2)  -- [SpO2, BPM] per second
        y : np.array (N,)        -- label = fraction of window that is apneic
                                     (0.0 = fully normal, 1.0 = fully apneic)
    """
    X_list = []
    y_list = []

    total = len(spo2)
    for start in range(0, total - window_size, stride):
        end = start + window_size

        spo2_win  = spo2[start:end]
        pulse_win = pulse[start:end]
        event_win = event_mask[start:end]

        # Skip windows with invalid SpO2 values (sensor artifacts)
        if np.any(spo2_win < 40) or np.any(spo2_win > 100):
            continue
        if np.any(pulse_win < 20) or np.any(pulse_win > 220):
            continue

        # Stack into (60, 2) array
        x = np.stack([spo2_win, pulse_win], axis=-1)  # (60, 2)

        # Label = fraction of apneic seconds in the window
        label = np.mean(event_win)

        X_list.append(x)
        y_list.append(label)

    if not X_list:
        return np.empty((0, window_size, 2)), np.empty((0,))

    return np.array(X_list, dtype=np.float32), np.array(y_list, dtype=np.float32)


# ===========================================================================
# 4. PROCESS THE ENTIRE DATABASE
# ===========================================================================

def process_ucd_database(data_dir):
    """
    Process all patients in the UCD database.
    Returns combined X (N, 60, 2) and y (N,) arrays.
    """
    # Find all .rec files
    rec_files = sorted(glob.glob(os.path.join(data_dir, "ucddb*.rec")))

    if not rec_files:
        # Maybe files are in a subdirectory
        rec_files = sorted(glob.glob(os.path.join(data_dir, "**", "ucddb*.rec"), recursive=True))

    if not rec_files:
        print(f"[ERROR] No .rec files found in {data_dir}")
        print(f"        Make sure the UCD database files are in this directory.")
        return None, None

    print(f"[INFO] Found {len(rec_files)} patient recordings\n")

    all_X = []
    all_y = []

    for rec_path in rec_files:
        patient_id = os.path.basename(rec_path).replace(".rec", "")
        respevt_path = os.path.join(os.path.dirname(rec_path), f"{patient_id}_respevt.txt")

        print(f"   Processing {patient_id}...")

        # Step 1: Extract SpO2 and Pulse Rate
        spo2, pulse, start_time = extract_spo2_pulse(rec_path)
        if spo2 is None:
            print(f"   [SKIP] {patient_id} -- no SpO2 data found")
            continue

        total_seconds = len(spo2)
        hours = total_seconds / 3600
        print(f"     SpO2  : {total_seconds} samples ({hours:.1f} hours)")
        print(f"     Pulse : {len(pulse)} samples")

        # Step 2: Parse respiratory event annotations
        event_mask = parse_respevt(respevt_path, start_time, total_seconds)
        n_apnea_sec = int(np.sum(event_mask))
        print(f"     Apnea : {n_apnea_sec}s ({n_apnea_sec/60:.0f} min) apneic out of {total_seconds}s")

        # Step 3: Window into 60-second segments
        X, y = create_windows(spo2, pulse, event_mask)
        print(f"     Windows: {len(X)} (apneic ratio: {np.mean(y > 0.3):.1%})")

        if len(X) > 0:
            all_X.append(X)
            all_y.append(y)

        print()

    if not all_X:
        print("[ERROR] No valid windows extracted from any patient!")
        return None, None

    X_combined = np.concatenate(all_X, axis=0)
    y_combined = np.concatenate(all_y, axis=0)

    print("=" * 60)
    print(f"[DONE] Total dataset:")
    print(f"   Samples   : {len(X_combined)}")
    print(f"   Shape     : {X_combined.shape}  (windows, timesteps, features)")
    print(f"   Normal    : {np.sum(y_combined < 0.1)} windows")
    print(f"   Apneic    : {np.sum(y_combined > 0.3)} windows")
    print(f"   Label range: {y_combined.min():.2f} to {y_combined.max():.2f}")
    print("=" * 60)

    return X_combined, y_combined


# ===========================================================================
# 5. SAVE / LOAD PROCESSED DATA
# ===========================================================================

PROCESSED_DATA_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "processed_data", "ucd_max30102_dataset.npz"
)


def save_processed(X, y):
    os.makedirs(os.path.dirname(PROCESSED_DATA_PATH), exist_ok=True)
    np.savez_compressed(PROCESSED_DATA_PATH, X=X, y=y)
    print(f"\n[SAVED] Processed dataset -> {PROCESSED_DATA_PATH}")
    print(f"        {X.shape[0]} windows of (60, 2) [SpO2, BPM]\n")


def load_processed():
    data = np.load(PROCESSED_DATA_PATH)
    return data["X"], data["y"]


# ===========================================================================
# 6. MAIN
# ===========================================================================

def main():
    parser = argparse.ArgumentParser(description="UCD Sleep Apnea -> MAX30102 Dataset")
    parser.add_argument("--data_dir", type=str, required=True,
                        help="Path to downloaded UCD database folder")
    parser.add_argument("--train", action="store_true",
                        help="After processing, train the fusion model")
    args = parser.parse_args()

    print("\n" + "=" * 65)
    print("  UCD Sleep Apnea Database -> MAX30102 Training Data")
    print("=" * 65 + "\n")

    # Process the database
    X, y = process_ucd_database(args.data_dir)

    if X is None:
        return

    # Save the processed dataset
    save_processed(X, y)

    # Optional: train the fusion model
    if args.train:
        print("\n" + "=" * 65)
        print("  Training MAX30102 Branch on Real Clinical Data")
        print("=" * 65 + "\n")

        from fusion_model import (
            load_audio_feature_extractor,
            build_max30102_extractor,
            build_mpu6050_extractor,
            build_fusion_model,
            train_fusion_model,
            save_model,
        )

        # Load extractors
        audio_ext = load_audio_feature_extractor()
        max_ext   = build_max30102_extractor()
        mpu_ext   = build_mpu6050_extractor()

        # Build fusion model
        fusion = build_fusion_model(audio_ext, max_ext, mpu_ext)

        # Prepare data:
        # Audio = random for now (no real audio paired with UCD data)
        # MAX30102 = real SpO2 + BPM from UCD
        # MPU6050 = placeholder (no movement data in UCD)
        n = len(X)
        audio_data   = np.random.randn(n, 32, 32, 1).astype(np.float32) * 0.3
        max30102_data = X  # (N, 60, 2)  -- REAL DATA!
        mpu6050_data = np.ones((n, 60, 1), dtype=np.float32)  # placeholder

        print(f"[INFO] Training with {n} real SpO2+BPM windows from UCD database")
        print(f"       Audio branch: random data (frozen, won't affect learning)")
        print(f"       MPU branch:   placeholder (no movement in UCD)\n")

        train_fusion_model(fusion, audio_data, max30102_data, mpu6050_data, y)
        save_model(fusion)

        print("\n[DONE] Fusion model trained on real clinical SpO2+BPM data!")
        print("       The MAX30102 branch has learned real desaturation patterns.")
        print("       Next: collect paired audio + movement data to train those branches.\n")


if __name__ == "__main__":
    main()
