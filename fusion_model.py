"""
fusion_model.py
===============
Multi-Sensor Fusion Model for Sleep Apnea Detection & Risk Prediction.

Three independent sensor branches (one per physical device):
  Branch A -- INMP441 Microphone  -> 32x32 MFCC image        (frozen pretrained CNN)
  Branch B -- MAX30102 Sensor     -> (60, 2) [SpO2, BPM]     (trainable 1D-CNN)
  Branch C -- MPU6050 Sensor      -> (60, 1) [Movement Mag]  (trainable 1D-CNN)

Architecture:
  Audio MFCC (32,32,1) -> Frozen Audio CNN      -> feat_A
  SpO2+BPM   (60, 2)   -> Trainable MAX CNN     -> feat_B
  Movement   (60, 1)   -> Trainable MPU CNN     -> feat_C
                                    |
                              Concatenate
                                    |
                           Dense Fusion Head
                                    |
                          Apnea Risk Score [0-1]

Usage:
  python fusion_model.py --demo       # Validate pipeline with synthetic data
  python fusion_model.py --predict    # Run single inference
"""

import os
import argparse
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

import tensorflow as tf
from tensorflow.keras import layers, Model


# ===========================================================================
# CONFIGURATION
# ===========================================================================

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

SNORING_MODEL_PATH     = os.path.join(BASE_DIR, "models", "cnn.h5")
FUSION_MODEL_SAVE_PATH = os.path.join(BASE_DIR, "models", "sleep_apnea_fusion_v3.h5")

# --- Input shapes (one per physical sensor) ---
AUDIO_INPUT_SHAPE    = (32, 32, 1)   # INMP441 mic  -> MFCC image
MAX30102_INPUT_SHAPE = (60, 2)       # MAX30102     -> [SpO2 (%), BPM] per second, 60s window
MPU6050_INPUT_SHAPE  = (60, 1)       # MPU6050      -> [Movement Magnitude (g)] per second

# Training hyperparams
EPOCHS       = 20
BATCH_SIZE   = 16
LEARNING_RATE = 1e-3
DEMO_SAMPLES = 500


# ===========================================================================
# 1. BRANCH A -- AUDIO: Frozen Pretrained CNN (INMP441)
# ===========================================================================

def _build_feature_extractor(model, name):
    """Strip the classification head and return a frozen feature extractor."""
    input_shape = model.input_shape
    dummy = np.zeros([1] + list(input_shape[1:]), dtype=np.float32)
    _ = model(dummy, training=False)

    inp = layers.Input(shape=input_shape[1:], name=f"{name}_input")
    x = inp
    for layer in model.layers:
        if isinstance(layer, tf.keras.layers.InputLayer):
            continue
        if layer == model.layers[-1]:
            break
        x = layer(x)

    return Model(inputs=inp, outputs=x, name=name)


def load_audio_feature_extractor():
    """
    Load pretrained Snoring CNN (INMP441 branch).
    Removes classification head, freezes all weights.
    Input:  (32, 32, 1)  -- MFCC spectrogram
    Output: N-dim feature vector
    """
    print("[BRANCH A] Loading Audio CNN (INMP441)...")
    full_model = tf.keras.models.load_model(SNORING_MODEL_PATH, compile=False)
    extractor = _build_feature_extractor(full_model, "audio_extractor")

    for layer in extractor.layers:
        layer.trainable = False

    dummy = np.zeros([1] + list(AUDIO_INPUT_SHAPE), dtype=np.float32)
    feat_dim = extractor.predict(dummy, verbose=0).shape[-1]
    print(f"   -> {feat_dim}-dim features (FROZEN)\n")
    return extractor


# ===========================================================================
# 2. BRANCH B -- MAX30102: Trainable 1D-CNN (SpO2 + BPM)
# ===========================================================================

def build_max30102_extractor():
    """
    Fresh trainable 1D-CNN for the MAX30102 branch.
    Input:  (60, 2) -- [SpO2 (%), BPM] sampled every second for 60s
    Output: 32-dim feature vector

    Physiological pattern learned:
      - SpO2 desaturation dip followed by BPM spike = apnea event

    NOTE: Uses GlobalAveragePooling1D instead of LSTM for TFLite Micro
          compatibility. LSTMs use TensorListReserve which is unsupported
          on ESP32 / TFLite Micro.
    """
    print("[BRANCH B] Building MAX30102 1D-CNN (SpO2 + BPM)...")

    inp = layers.Input(shape=MAX30102_INPUT_SHAPE, name="max30102_input")

    # Conv block 1 -- detect short-term SpO2/HR fluctuations
    x = layers.Conv1D(16, kernel_size=3, activation="relu", padding="same",
                      name="max_conv1")(inp)
    x = layers.BatchNormalization(name="max_bn1")(x)
    x = layers.MaxPooling1D(pool_size=2, name="max_pool1")(x)

    # Conv block 2 -- detect longer desaturation windows
    x = layers.Conv1D(32, kernel_size=5, activation="relu", padding="same",
                      name="max_conv2")(x)
    x = layers.BatchNormalization(name="max_bn2")(x)
    x = layers.MaxPooling1D(pool_size=2, name="max_pool2")(x)

    # Temporal aggregation (TFLite-compatible alternative to LSTM)
    x = layers.GlobalAveragePooling1D(name="max_gap")(x)
    x = layers.Dense(32, activation="relu", name="max_dense")(x)

    extractor = Model(inputs=inp, outputs=x, name="max30102_extractor")
    print("   -> 32-dim features (TRAINABLE)\n")
    return extractor


# ===========================================================================
# 3. BRANCH C -- MPU6050: Trainable 1D-CNN (Movement Magnitude)
# ===========================================================================

def build_mpu6050_extractor():
    """
    Fresh trainable 1D-CNN for the MPU6050 branch.
    Input:  (60, 1) -- Movement Magnitude (sqrt(x^2+y^2+z^2) in g) per second
    Output: 16-dim feature vector

    Physiological pattern learned:
      - Sudden jerk spike after a quiet period = arousal after apnea event
    """
    print("[BRANCH C] Building MPU6050 1D-CNN (Movement)...")

    inp = layers.Input(shape=MPU6050_INPUT_SHAPE, name="mpu6050_input")

    # Conv block -- detect sudden spike patterns
    x = layers.Conv1D(8, kernel_size=3, activation="relu", padding="same",
                      name="mpu_conv1")(inp)
    x = layers.MaxPooling1D(pool_size=2, name="mpu_pool1")(x)

    x = layers.Conv1D(16, kernel_size=3, activation="relu", padding="same",
                      name="mpu_conv2")(x)
    x = layers.MaxPooling1D(pool_size=2, name="mpu_pool2")(x)

    x = layers.Flatten(name="mpu_flatten")(x)
    x = layers.Dense(16, activation="relu", name="mpu_dense")(x)

    extractor = Model(inputs=inp, outputs=x, name="mpu6050_extractor")
    print("   -> 16-dim features (TRAINABLE)\n")
    return extractor


# ===========================================================================
# 4. FUSION MODEL
# ===========================================================================

def build_fusion_model(audio_extractor, max30102_extractor, mpu6050_extractor):
    """
    Fuse all three sensor branches.

    Branch A (Audio)    -> feat_A  (N-dim,  frozen)
    Branch B (MAX30102) -> feat_B  (32-dim, trainable)
    Branch C (MPU6050)  -> feat_C  (16-dim, trainable)
                            |
                       Concatenate
                            |
                    Dense(64) -> Dropout(0.3)
                    Dense(32) -> Dropout(0.2)
                    Dense(1,  sigmoid)
                            |
                    Apnea Risk Score [0..1]
    """
    print("[FUSION] Building Fusion Model...")

    # Inputs -- one per physical sensor
    audio_input    = layers.Input(shape=AUDIO_INPUT_SHAPE,    name="audio_input")
    max30102_input = layers.Input(shape=MAX30102_INPUT_SHAPE, name="max30102_input")
    mpu6050_input  = layers.Input(shape=MPU6050_INPUT_SHAPE,  name="mpu6050_input")

    # Feature extraction
    feat_A = audio_extractor(audio_input)        # frozen
    feat_B = max30102_extractor(max30102_input)  # trainable
    feat_C = mpu6050_extractor(mpu6050_input)    # trainable

    # Fuse
    combined = layers.Concatenate(name="sensor_fusion")([feat_A, feat_B, feat_C])

    # Classification head
    x = layers.Dense(64, activation="relu",  name="fusion_dense_1")(combined)
    x = layers.Dropout(0.3,                  name="fusion_dropout_1")(x)
    x = layers.Dense(32, activation="relu",  name="fusion_dense_2")(x)
    x = layers.Dropout(0.2,                  name="fusion_dropout_2")(x)
    output = layers.Dense(1, activation="sigmoid", name="apnea_risk")(x)

    fusion = Model(
        inputs=[audio_input, max30102_input, mpu6050_input],
        outputs=output,
        name="sleep_apnea_fusion_v3",
    )
    fusion.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=LEARNING_RATE),
        loss="mse",
        metrics=["mae"],
    )

    print("   [OK] Fusion model built!\n")
    fusion.summary()
    return fusion


# ===========================================================================
# 5. SYNTHETIC DATA GENERATOR (Demo Mode)
# ===========================================================================

def generate_synthetic_data(n_samples=DEMO_SAMPLES):
    """
    Generate realistic synthetic data for all three sensor branches.

    Simulated physiology:
      Healthy  (severity ~0.0): Normal SpO2~98%, BPM~65, Movement~1.0g
      Apnea    (severity ~1.0): SpO2 dip -> BPM spike -> Movement jerk

    Returns:
      audio_data    : (N, 32, 32, 1)
      max30102_data : (N, 60, 2)   -- [SpO2, BPM] per second
      mpu6050_data  : (N, 60, 1)   -- [Movement Magnitude] per second
      labels        : (N,)         -- continuous severity 0.0 to 1.0
    """
    print(f"[DATA] Generating {n_samples} synthetic samples...\n")

    labels        = np.random.uniform(0.0, 1.0, size=n_samples).astype(np.float32)
    audio_data    = np.random.randn(n_samples, 32, 32, 1).astype(np.float32) * 0.5
    max30102_data = np.zeros((n_samples, 60, 2), dtype=np.float32)
    mpu6050_data  = np.zeros((n_samples, 60, 1), dtype=np.float32)

    for i in range(n_samples):
        sev = labels[i]

        # --- Branch A: Audio amplitude increases with snoring severity ---
        audio_data[i] += sev * 1.5

        # --- Branch B: MAX30102 (SpO2, BPM) ---
        spo2 = np.random.normal(98.0, 0.5, size=(60,)).astype(np.float32)
        bpm  = np.random.normal(65.0, 2.0, size=(60,)).astype(np.float32)

        if sev > 0.25:
            event_start = np.random.randint(10, 35)
            drop_len    = max(5, int(20 * sev))          # 5-20 seconds of desaturation

            # SpO2 dips during apnea
            spo2[event_start : event_start + drop_len] -= sev * 10.0

            # BPM spikes AFTER the dip (sympathetic arousal response)
            recovery = event_start + drop_len
            bpm[recovery : recovery + 8] += sev * 30.0

        max30102_data[i, :, 0] = spo2
        max30102_data[i, :, 1] = bpm

        # --- Branch C: MPU6050 (Movement) ---
        move = np.random.normal(1.0, 0.05, size=(60,)).astype(np.float32)

        if sev > 0.25:
            # Jerk happens at the same recovery moment as BPM spike
            recovery = event_start + drop_len
            jerk_idx = np.clip(recovery - 1, 0, 58)
            move[jerk_idx : jerk_idx + 3] += sev * 2.5

        mpu6050_data[i, :, 0] = move

    print(f"   Audio    : {audio_data.shape}")
    print(f"   MAX30102 : {max30102_data.shape}  [SpO2, BPM]")
    print(f"   MPU6050  : {mpu6050_data.shape}  [Movement]")
    print(f"   Severity : {labels.min():.2f} to {labels.max():.2f}  (mean {labels.mean():.2f})\n")

    return audio_data, max30102_data, mpu6050_data, labels


# ===========================================================================
# 6. TRAINING
# ===========================================================================

def train_fusion_model(fusion_model, audio_data, max30102_data, mpu6050_data, labels):
    """
    Train the fusion head + MAX30102 branch + MPU6050 branch jointly.
    The Audio branch remains frozen throughout.
    """
    print("[TRAIN] Starting training...\n")

    n       = len(labels)
    n_train = int(0.8 * n)

    idx = np.random.permutation(n)
    audio_data    = audio_data[idx]
    max30102_data = max30102_data[idx]
    mpu6050_data  = mpu6050_data[idx]
    labels        = labels[idx]

    Xa_tr, Xa_val = audio_data[:n_train],    audio_data[n_train:]
    Xb_tr, Xb_val = max30102_data[:n_train], max30102_data[n_train:]
    Xc_tr, Xc_val = mpu6050_data[:n_train],  mpu6050_data[n_train:]
    y_tr,  y_val  = labels[:n_train],         labels[n_train:]

    print(f"   Train: {n_train} samples | Val: {n - n_train} samples\n")

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss", patience=5, restore_best_weights=True
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor="val_loss", factor=0.5, patience=3, verbose=1
        ),
    ]

    fusion_model.fit(
        [Xa_tr, Xb_tr, Xc_tr],
        y_tr,
        validation_data=([Xa_val, Xb_val, Xc_val], y_val),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        callbacks=callbacks,
        verbose=1,
    )

    val_loss, val_mae = fusion_model.evaluate(
        [Xa_val, Xb_val, Xc_val], y_val, verbose=0
    )
    print(f"\n[RESULT] Val MSE: {val_loss:.4f}  |  Val MAE: {val_mae:.4f}")


def save_model(fusion_model):
    os.makedirs(os.path.dirname(FUSION_MODEL_SAVE_PATH), exist_ok=True)
    fusion_model.save(FUSION_MODEL_SAVE_PATH)
    print(f"\n[SAVED] Model -> {FUSION_MODEL_SAVE_PATH}")


# ===========================================================================
# 7. INFERENCE
# ===========================================================================

def predict_apnea_risk(audio_mfcc, max30102_window, mpu6050_window, model_path=None):
    """
    Run inference with all three sensor inputs.

    Args:
        audio_mfcc      : np.array (32, 32) or (32, 32, 1)  -- MFCC from INMP441
        max30102_window : np.array (60, 2)                   -- [SpO2, BPM] from MAX30102
        mpu6050_window  : np.array (60,) or (60, 1)          -- Movement from MPU6050

    Returns:
        float: apnea risk score (0.0 = healthy, 1.0 = severe apnea)
    """
    model_path = model_path or FUSION_MODEL_SAVE_PATH
    if not os.path.isfile(model_path):
        raise FileNotFoundError(f"Model not found: {model_path}. Run --demo first.")

    model = tf.keras.models.load_model(model_path, compile=False)

    # Ensure correct shapes and add batch dimension
    if audio_mfcc.ndim == 2:
        audio_mfcc = audio_mfcc[..., np.newaxis]           # (32,32) -> (32,32,1)
    audio_batch = np.expand_dims(audio_mfcc, axis=0)        # (1, 32, 32, 1)

    max_batch = np.expand_dims(max30102_window, axis=0)     # (1, 60, 2)

    if mpu6050_window.ndim == 1:
        mpu6050_window = mpu6050_window[..., np.newaxis]    # (60,) -> (60,1)
    mpu_batch = np.expand_dims(mpu6050_window, axis=0)      # (1, 60, 1)

    prediction = model.predict([audio_batch, max_batch, mpu_batch], verbose=0)
    return float(prediction[0][0])


# ===========================================================================
# 8. MAIN
# ===========================================================================

def main():
    parser = argparse.ArgumentParser(description="Sleep Apnea Fusion Model v3")
    parser.add_argument("--demo",    action="store_true", help="Train on synthetic data")
    parser.add_argument("--predict", action="store_true", help="Run single inference")
    args = parser.parse_args()

    if args.demo:
        print("\n" + "=" * 65)
        print("  SLEEP APNEA FUSION MODEL v3  --  3-Sensor Demo")
        print("=" * 65 + "\n")

        # Load / build the three sensor branches
        audio_ext   = load_audio_feature_extractor()
        max_ext     = build_max30102_extractor()
        mpu_ext     = build_mpu6050_extractor()

        # Fuse
        fusion = build_fusion_model(audio_ext, max_ext, mpu_ext)

        # Synthetic data
        audio_data, max30102_data, mpu6050_data, labels = generate_synthetic_data()

        # Train
        train_fusion_model(fusion, audio_data, max30102_data, mpu6050_data, labels)

        # Save
        save_model(fusion)

        # Patient simulation
        print("\n" + "=" * 65)
        print("  Patient Simulation -- Live Inference")
        print("=" * 65)

        profiles = [
            {"name": "Healthy Adult",                   "audio_shift": 0.0, "severity": 0.0},
            {"name": "Mild Snorer",                     "audio_shift": 0.4, "severity": 0.2},
            {"name": "Moderate Risk (SpO2 dips)",       "audio_shift": 0.7, "severity": 0.5},
            {"name": "High Risk (desaturation + jerk)", "audio_shift": 1.2, "severity": 0.8},
            {"name": "Severe Apnea",                    "audio_shift": 1.5, "severity": 1.0},
        ]

        profile = profiles[np.random.randint(len(profiles))]
        sev = profile["severity"]
        print(f"\n   Patient: {profile['name']}  (severity={sev})")

        # Build test inputs
        test_audio = (np.random.randn(32, 32, 1).astype(np.float32) * 0.5) + profile["audio_shift"]

        test_max30102 = np.zeros((60, 2), dtype=np.float32)
        test_max30102[:, 0] = np.random.normal(98.0, 0.5, (60,))   # SpO2
        test_max30102[:, 1] = np.random.normal(65.0, 2.0, (60,))   # BPM
        if sev > 0.25:
            test_max30102[20:30, 0] -= sev * 10.0   # SpO2 drop
            test_max30102[30:38, 1] += sev * 30.0   # BPM spike

        test_mpu6050 = np.random.normal(1.0, 0.05, (60, 1)).astype(np.float32)
        if sev > 0.25:
            test_mpu6050[29:32, 0] += sev * 2.5     # Arousal jerk

        risk = predict_apnea_risk(test_audio, test_max30102, test_mpu6050)
        print(f"   Apnea Risk Score: {risk:.4f}")

        if risk < 0.3:
            level = "LOW    -- No significant apnea indicators"
        elif risk < 0.6:
            level = "MODERATE -- Risk factors present, monitoring advised"
        else:
            level = "HIGH   -- Strong apnea indicators, clinical review recommended"
        print(f"   Risk Level: {level}")

        print("\n[DONE] Demo complete!")
        print(f"   Model saved: {FUSION_MODEL_SAVE_PATH}")
        print("   Swap generate_synthetic_data() with a real PhysioNet data loader")
        print("   to train on actual SpO2/BPM/Movement overnight recordings.\n")

    elif args.predict:
        print("\n[PREDICT] Running inference with simulated normal vitals...")
        test_audio    = np.random.randn(32, 32, 1).astype(np.float32)
        test_max30102 = np.column_stack([
            np.full(60, 98.0),   # SpO2 stable
            np.full(60, 65.0),   # BPM stable
        ]).astype(np.float32)
        test_mpu6050  = np.ones((60, 1), dtype=np.float32)  # No movement

        risk = predict_apnea_risk(test_audio, test_max30102, test_mpu6050)
        print(f"   Apnea Risk Score: {risk:.4f}")
        print(f"   Risk Level: {'HIGH' if risk > 0.5 else 'LOW'}\n")

    else:
        parser.print_help()


if __name__ == "__main__":
    main()
