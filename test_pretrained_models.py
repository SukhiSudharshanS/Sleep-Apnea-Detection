"""
test_pretrained_models.py
=========================
Verify that both pretrained models load correctly and produce valid outputs.
"""

import os
import sys
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

import tensorflow as tf
from tensorflow.keras import layers, Model


SNORING_MODEL_PATH = os.path.join(os.path.dirname(__file__), "models", "cnn.h5")
ECG_MODEL_PATH = os.path.join(
    os.path.dirname(__file__),
    "..",
    "Sleep-Apnea-Detector-main",
    "Sleep-Apnea-Detector-main",
    "saved_models",
    "apnea_cnn_small.h5",
)


def build_feature_extractor(model, name="feature_extractor"):
    """
    Build a feature extractor from a Sequential or Functional model.
    Removes the last layer (classification head) and returns features.
    Works with both Keras Sequential and Functional API models.
    """
    input_shape = model.input_shape  # (None, ...)
    dummy = np.zeros([1] + list(input_shape[1:]), dtype=np.float32)
    _ = model(dummy, training=False)

    inp = layers.Input(shape=input_shape[1:], name=f"{name}_input")
    x = inp
    for layer in model.layers:
        # Skip InputLayer (used by Functional API models)
        if isinstance(layer, tf.keras.layers.InputLayer):
            continue
        # Stop before the last layer (the classification head)
        if layer == model.layers[-1]:
            break
        x = layer(x)

    feature_model = Model(inputs=inp, outputs=x, name=name)
    return feature_model


def test_snoring_model():
    print("=" * 60)
    print("TEST 1: Snoring Detection CNN (cnn.h5)")
    print("=" * 60)

    if not os.path.isfile(SNORING_MODEL_PATH):
        print(f"  [FAIL] Model file not found: {SNORING_MODEL_PATH}")
        return False

    model = tf.keras.models.load_model(SNORING_MODEL_PATH, compile=False)
    print(f"  [OK] Loaded from {SNORING_MODEL_PATH}")
    model.summary()

    input_shape = model.input_shape
    print(f"\n  Input shape: {input_shape}")

    dummy = np.random.randn(1, *input_shape[1:]).astype(np.float32)
    pred = model.predict(dummy, verbose=0)
    print(f"  Output shape: {pred.shape}")
    print(f"  Dummy prediction: {pred.flatten()}")

    feat_model = build_feature_extractor(model, "snoring_feat")
    feat_out = feat_model.predict(dummy, verbose=0)
    print(f"\n  Feature extractor output shape: {feat_out.shape}")
    print(f"  Penultimate layer: '{model.layers[-2].name}' -> {feat_out.shape[-1]}-dim")

    print("  [OK] Snoring model test PASSED\n")
    return True


def test_ecg_model():
    print("=" * 60)
    print("TEST 2: ECG Apnea 1D-CNN (apnea_cnn_small.h5)")
    print("=" * 60)

    if not os.path.isfile(ECG_MODEL_PATH):
        print(f"  [FAIL] Model file not found: {ECG_MODEL_PATH}")
        return False

    model = tf.keras.models.load_model(ECG_MODEL_PATH, compile=False)
    print(f"  [OK] Loaded from {ECG_MODEL_PATH}")
    model.summary()

    input_shape = model.input_shape
    print(f"\n  Input shape: {input_shape}")

    dummy = np.random.randn(1, *input_shape[1:]).astype(np.float32)
    pred = model.predict(dummy, verbose=0)
    print(f"  Output shape: {pred.shape}")
    print(f"  Dummy prediction: {pred.flatten()}")

    feat_model = build_feature_extractor(model, "ecg_feat")
    feat_out = feat_model.predict(dummy, verbose=0)
    print(f"\n  Feature extractor output shape: {feat_out.shape}")
    print(f"  Penultimate layer: '{model.layers[-2].name}' -> {feat_out.shape[-1]}-dim")

    print("  [OK] ECG model test PASSED\n")
    return True


if __name__ == "__main__":
    print("\n--- Pretrained Model Verification ---\n")

    results = []
    results.append(("Snoring CNN", test_snoring_model()))
    results.append(("ECG 1D-CNN", test_ecg_model()))

    print("=" * 60)
    print("SUMMARY")
    print("=" * 60)
    for name, passed in results:
        status = "[PASSED]" if passed else "[FAILED]"
        print(f"  {name}: {status}")

    if all(r[1] for r in results):
        print("\nAll models verified. Ready for fusion training!\n")
        sys.exit(0)
    else:
        print("\nSome models failed. Fix issues above before proceeding.\n")
        sys.exit(1)
