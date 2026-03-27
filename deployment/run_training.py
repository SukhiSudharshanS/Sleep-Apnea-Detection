"""
Standalone script to train the snoring detection model and output the confusion matrix.
This is a patched version of the original train.py to work with TensorFlow 2.20+.
It does NOT overwrite any existing files - all outputs go to train_run/ and logs_run/ directories.
"""
import os
import sys
import argparse
import numpy as np

# Add the speech_commands directory to sys.path so we can import input_data and models
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, 'tensorflow1', 'tensorflow', 'examples', 'speech_commands'))

import input_data
import models

import tensorflow as tf
from six.moves import xrange

# Monkey-patch tf.io.write_graph to fix TF 2.20 compatibility issue
_original_write_graph = tf.io.write_graph
def _patched_write_graph(graph_or_graph_def, logdir, name, as_text=True):
    """Patched write_graph that handles the float_format issue in TF 2.20."""
    try:
        return _original_write_graph(graph_or_graph_def, logdir, name, as_text)
    except TypeError:
        # Fallback: just skip writing the graph proto text file
        print(f"[INFO] Skipping graph write to {logdir}/{name} (TF 2.20 compat)")
        pass
tf.io.write_graph = _patched_write_graph


def main():
    # ========================
    # CONFIGURATION (matches deployment/train_snoring_model.ipynb)
    # ========================
    WANTED_WORDS = "snoring,no_snoring"
    TRAINING_STEPS = "25,25"
    LEARNING_RATE = "0.005,0.005"
    PREPROCESS = "micro"
    WINDOW_STRIDE = 20
    MODEL_ARCHITECTURE = "tiny_conv"
    VERBOSITY = "INFO"
    EVAL_STEP_INTERVAL = 5
    SAVE_STEP_INTERVAL = 25
    OPTIMIZER = "momentum"
    BATCH_SIZE = 100
    SAMPLE_RATE = 16000
    CLIP_DURATION_MS = 1000
    WINDOW_SIZE_MS = 30.0
    FEATURE_BIN_COUNT = 40
    TIME_SHIFT_MS = 100.0
    BACKGROUND_FREQUENCY = 0.0
    BACKGROUND_VOLUME = 0.0
    SILENCE_PERCENTAGE = 0.0
    UNKNOWN_PERCENTAGE = 0.0
    VALIDATION_PERCENTAGE = 10
    TESTING_PERCENTAGE = 10

    # Paths (using local dataset, new output dirs)
    DATASET_DIR = os.path.join(os.path.dirname(SCRIPT_DIR), "Snoring_Dataset_@16000")
    TRAIN_DIR = os.path.join(SCRIPT_DIR, "train_run")
    LOGS_DIR = os.path.join(SCRIPT_DIR, "logs_run")

    # Create output directories
    os.makedirs(TRAIN_DIR, exist_ok=True)
    os.makedirs(LOGS_DIR, exist_ok=True)

    # Parse training steps and learning rates
    training_steps_list = list(map(int, TRAINING_STEPS.split(',')))
    learning_rates_list = list(map(float, LEARNING_RATE.split(',')))
    wanted_words = WANTED_WORDS.split(',')

    print("=" * 60)
    print("SNORING MODEL TRAINING")
    print("=" * 60)
    print(f"Words: {WANTED_WORDS}")
    print(f"Training steps: {TRAINING_STEPS}")
    print(f"Learning rate: {LEARNING_RATE}")
    print(f"Model: {MODEL_ARCHITECTURE}")
    print(f"Dataset: {DATASET_DIR}")
    print(f"Output: {TRAIN_DIR}")
    print("=" * 60)

    # Set verbosity
    tf.compat.v1.logging.set_verbosity(tf.compat.v1.logging.INFO)

    # Disable eager execution for TF1-style code
    tf.compat.v1.disable_eager_execution()

    # Start a new TensorFlow session
    sess = tf.compat.v1.InteractiveSession()

    # Prepare model settings
    number_of_labels = len(wanted_words)
    model_settings = models.prepare_model_settings(
        len(input_data.prepare_words_list(wanted_words)),
        SAMPLE_RATE, CLIP_DURATION_MS, WINDOW_SIZE_MS,
        WINDOW_STRIDE, FEATURE_BIN_COUNT, PREPROCESS)

    # Create audio processor
    audio_processor = input_data.AudioProcessor(
        '', DATASET_DIR,
        SILENCE_PERCENTAGE, UNKNOWN_PERCENTAGE,
        wanted_words, VALIDATION_PERCENTAGE,
        TESTING_PERCENTAGE, model_settings, LOGS_DIR)

    fingerprint_size = model_settings['fingerprint_size']
    label_count = model_settings['label_count']
    time_shift_samples = int((TIME_SHIFT_MS * SAMPLE_RATE) / 1000)

    # Build model graph
    input_placeholder = tf.compat.v1.placeholder(
        tf.float32, [None, fingerprint_size], name='fingerprint_input')
    fingerprint_input = input_placeholder

    logits, dropout_rate = models.create_model(
        fingerprint_input, model_settings, MODEL_ARCHITECTURE, is_training=True)

    ground_truth_input = tf.compat.v1.placeholder(
        tf.int64, [None], name='groundtruth_input')

    with tf.compat.v1.name_scope('cross_entropy'):
        cross_entropy_mean = tf.compat.v1.losses.sparse_softmax_cross_entropy(
            labels=ground_truth_input, logits=logits)

    with tf.compat.v1.name_scope('train'):
        learning_rate_input = tf.compat.v1.placeholder(
            tf.float32, [], name='learning_rate_input')
        if OPTIMIZER == 'momentum':
            train_step = tf.compat.v1.train.MomentumOptimizer(
                learning_rate_input, .9, use_nesterov=True).minimize(cross_entropy_mean)
        else:
            train_step = tf.compat.v1.train.GradientDescentOptimizer(
                learning_rate_input).minimize(cross_entropy_mean)

    predicted_indices = tf.argmax(input=logits, axis=1)
    correct_prediction = tf.equal(predicted_indices, ground_truth_input)
    confusion_matrix = tf.math.confusion_matrix(
        labels=ground_truth_input, predictions=predicted_indices, num_classes=label_count)
    evaluation_step = tf.reduce_mean(input_tensor=tf.cast(correct_prediction, tf.float32))

    with tf.compat.v1.get_default_graph().name_scope('eval'):
        tf.compat.v1.summary.scalar('cross_entropy', cross_entropy_mean)
        tf.compat.v1.summary.scalar('accuracy', evaluation_step)

    global_step = tf.compat.v1.train.get_or_create_global_step()
    increment_global_step = tf.compat.v1.assign(global_step, global_step + 1)

    saver = tf.compat.v1.train.Saver(tf.compat.v1.global_variables())

    merged_summaries = tf.compat.v1.summary.merge_all(scope='eval')
    train_writer = tf.compat.v1.summary.FileWriter(LOGS_DIR + '/train', sess.graph)
    validation_writer = tf.compat.v1.summary.FileWriter(LOGS_DIR + '/validation')

    tf.compat.v1.global_variables_initializer().run()

    # Try to save graph (may fail on TF 2.20, that's ok)
    tf.io.write_graph(sess.graph_def, TRAIN_DIR, MODEL_ARCHITECTURE + '.pbtxt')

    # Save label list
    from tensorflow.python.platform import gfile
    with gfile.GFile(os.path.join(TRAIN_DIR, MODEL_ARCHITECTURE + '_labels.txt'), 'w') as f:
        f.write('\n'.join(audio_processor.words_list))

    # ========================
    # TRAINING LOOP
    # ========================
    training_steps_max = np.sum(training_steps_list)
    print(f"\nStarting training for {training_steps_max} steps...\n")

    for training_step in xrange(1, training_steps_max + 1):
        # Determine current learning rate
        training_steps_sum = 0
        for i in range(len(training_steps_list)):
            training_steps_sum += training_steps_list[i]
            if training_step <= training_steps_sum:
                learning_rate_value = learning_rates_list[i]
                break

        # Get training data
        train_fingerprints, train_ground_truth = audio_processor.get_data(
            BATCH_SIZE, 0, model_settings, BACKGROUND_FREQUENCY,
            BACKGROUND_VOLUME, time_shift_samples, 'training', sess)

        # Training step
        train_summary, train_accuracy, cross_entropy_value, _, _ = sess.run(
            [merged_summaries, evaluation_step, cross_entropy_mean,
             train_step, increment_global_step],
            feed_dict={
                fingerprint_input: train_fingerprints,
                ground_truth_input: train_ground_truth,
                learning_rate_input: learning_rate_value,
                dropout_rate: 0.5
            })
        train_writer.add_summary(train_summary, training_step)

        print('Step #%d: rate %f, accuracy %.1f%%, cross entropy %f' %
              (training_step, learning_rate_value, train_accuracy * 100, cross_entropy_value))

        is_last_step = (training_step == training_steps_max)
        if (training_step % EVAL_STEP_INTERVAL) == 0 or is_last_step:
            # Validation
            set_size = audio_processor.set_size('validation')
            total_accuracy = 0
            total_conf_matrix = None
            for i in xrange(0, set_size, BATCH_SIZE):
                validation_fingerprints, validation_ground_truth = (
                    audio_processor.get_data(BATCH_SIZE, i, model_settings, 0.0, 0.0, 0, 'validation', sess))
                validation_summary, validation_accuracy, conf_matrix = sess.run(
                    [merged_summaries, evaluation_step, confusion_matrix],
                    feed_dict={
                        fingerprint_input: validation_fingerprints,
                        ground_truth_input: validation_ground_truth,
                        dropout_rate: 0.0
                    })
                validation_writer.add_summary(validation_summary, training_step)
                batch_size = min(BATCH_SIZE, set_size - i)
                total_accuracy += (validation_accuracy * batch_size) / set_size
                if total_conf_matrix is None:
                    total_conf_matrix = conf_matrix
                else:
                    total_conf_matrix += conf_matrix

            print('\n' + '=' * 60)
            print('VALIDATION CONFUSION MATRIX (Step %d):' % training_step)
            print(total_conf_matrix)
            print('Validation accuracy = %.1f%% (N=%d)' % (total_accuracy * 100, set_size))
            print('=' * 60 + '\n')

        # Save checkpoint
        if (training_step % SAVE_STEP_INTERVAL == 0 or training_step == training_steps_max):
            checkpoint_path = os.path.join(TRAIN_DIR, MODEL_ARCHITECTURE + '.ckpt')
            print('Saving to "%s-%d"' % (checkpoint_path, training_step))
            saver.save(sess, checkpoint_path, global_step=training_step)

    # ========================
    # FINAL TEST EVALUATION
    # ========================
    set_size = audio_processor.set_size('testing')
    print('\n' + '#' * 60)
    print('FINAL TEST EVALUATION (N=%d)' % set_size)
    print('#' * 60)

    total_accuracy = 0
    total_conf_matrix = None
    for i in xrange(0, set_size, BATCH_SIZE):
        test_fingerprints, test_ground_truth = audio_processor.get_data(
            BATCH_SIZE, i, model_settings, 0.0, 0.0, 0, 'testing', sess)
        test_accuracy, conf_matrix = sess.run(
            [evaluation_step, confusion_matrix],
            feed_dict={
                fingerprint_input: test_fingerprints,
                ground_truth_input: test_ground_truth,
                dropout_rate: 0.0
            })
        batch_size = min(BATCH_SIZE, set_size - i)
        total_accuracy += (test_accuracy * batch_size) / set_size
        if total_conf_matrix is None:
            total_conf_matrix = conf_matrix
        else:
            total_conf_matrix += conf_matrix

    # Get label names for display
    labels = audio_processor.words_list
    print('\nLabels: %s' % labels)
    print('\n*** FINAL TEST CONFUSION MATRIX ***')
    print(total_conf_matrix)
    print('\n*** FINAL TEST ACCURACY = %.1f%% (N=%d) ***' % (total_accuracy * 100, set_size))
    print('#' * 60)

    sess.close()


if __name__ == '__main__':
    main()
