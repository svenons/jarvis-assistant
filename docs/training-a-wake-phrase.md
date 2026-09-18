# How to train a wake phrase (e.g. "Hey Hades")

Jarvis wakes on an [openWakeWord](https://github.com/dscripka/openWakeWord) model, and each phrase is its own small model. You can't type one into the app. Train a model once on synthetic speech, then import the `.onnx` file under Settings > Wake word > Wake phrase > Your own phrase.

This summarizes openWakeWord's [`notebooks/automatic_model_training.ipynb`](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb) and [`examples/custom_model.yml`](https://github.com/dscripka/openWakeWord/blob/main/examples/custom_model.yml). Check them if anything here has drifted.

## What you need

- Linux or Google Colab. The notebook's speech library (Piper) is Linux-only, and Colab is Linux.
- A GPU. Colab's free one is enough for a first model.
- Several GB of downloads: a 2,000-hour "negative" feature file (`openwakeword_features_ACAV100M_2000_hrs_16bit.npy`), a validation set, room impulse responses and background noise.

## Steps

1. Open the notebook. The project README links a simplified Colab version (it says under an hour). The fuller one is `automatic_model_training.ipynb` in the repo. Run the setup and data-download cells.
2. Set the phrase and sizes in the training config, a YAML file with the fields in `custom_model.yml`:

   ```python
   config["target_phrase"] = ["hey hades"]
   config["model_name"] = "hey_hades"
   config["n_samples"] = 20000        # the file recommends at least 20,000; 100,000+ is often best
   config["n_samples_val"] = 2000
   config["steps"] = 50000            # the file's default; the notebook's quick demo uses 10,000
   ```

   The demo's 1,000 samples and 10,000 steps show the pipeline works but aren't enough for a model you'll rely on.
3. Run the three stages, one command each in the notebook:

   ```
   python openwakeword/openwakeword/train.py --training_config my_model.yaml --generate_clips
   python openwakeword/openwakeword/train.py --training_config my_model.yaml --augment_clips
   python openwakeword/openwakeword/train.py --training_config my_model.yaml --train_model
   ```
4. Copy `my_custom_model/hey_hades.onnx` to your phone. You don't need the `.tflite`.
5. Import it in the app and type the name ("Hey Hades") so notifications show it. The app rejects files that aren't openWakeWord classifiers (it expects a `[1, 16, 96]` input).

## Choosing the phrase

- Pick something distinctive. A greeting plus a two-syllable name, like "Hey Hades", works. A single short word false-triggers far more.
- The trainer generates near-miss phrases as negatives. If specific everyday words set it off, add them to `custom_negative_phrases` and retrain.
- Listen to a few generated clips (in `output_dir`). If the synthetic voices mispronounce the name, try respelling it phonetically in `target_phrase`. That's practical advice, not something the docs promise.

## Tuning

Start at Normal sensitivity. Custom models start at openWakeWord's usual score bars (0.5 and 0.35), but a model can score very differently from the bundled ones. Stricter cuts false triggers, and More sensitive catches you more often.

To see what your voice scores, run `adb logcat -s JarvisWake` and say the phrase. The service logs one `voice burst peak=…` line per utterance. If your peaks sit below the bars, raise the sensitivity. If background speech peaks near them, lower it.

## Caveats

- The notebooks pin old library versions (TensorFlow 2.8, SpeechBrain 0.5 and others). If setup fails on a current Colab runtime, that's notebook drift, not Jarvis.
- A model trained on synthetic speech can behave differently with your voice and microphone. More samples and steps, and testing in your own environment, help.
- This project hasn't run a custom wake model on a device. The import path and feature pipeline were checked against the bundled models only.
