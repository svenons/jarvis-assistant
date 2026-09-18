# How to train a wake phrase (e.g. "Hey Hades")

Jarvis wakes on an [openWakeWord](https://github.com/dscripka/openWakeWord) model. Each phrase is its own
small model (a few hundred KB); you can't type a phrase into the app. You train a model once on synthetic
speech, then import the `.onnx` file under **Settings → Wake word → Wake phrase → Your own phrase…**.

This is a summary of openWakeWord's own
[`notebooks/automatic_model_training.ipynb`](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb)
and [`examples/custom_model.yml`](https://github.com/dscripka/openWakeWord/blob/main/examples/custom_model.yml).
Those are the source of truth; check them if anything here has drifted.

## What you need

- **Linux or Google Colab.** The notebook says automatic training is Linux-only because its text-to-speech
  library (Piper) is. Colab is Linux, so it works from any computer.
- **A GPU is strongly advised** (Colab's free GPU is enough for a first model). Without one it will be very slow.
- **Disk and bandwidth for the datasets**: the notebook downloads a ~2,000-hour precomputed "negative" feature
  file (`openwakeword_features_ACAV100M_2000_hrs_16bit.npy`), a validation set, room impulse responses and
  background noise. Expect several GB.

## Steps

1. **Open the training notebook.** The project README links a simplified Colab version (says "<1 hour"); the
   fuller one is `automatic_model_training.ipynb` in the repo. Run the setup cell (clones
   `piper-sample-generator`, installs dependencies) and the data-download cells.
2. **Set the phrase and sizes** in the training config (a YAML file; fields as in `custom_model.yml`):

   ```python
   config["target_phrase"] = ["hey hades"]
   config["model_name"] = "hey_hades"
   config["n_samples"] = 20000        # the file recommends at least 20,000; 100,000+ is often best
   config["n_samples_val"] = 2000
   config["steps"] = 50000            # the file's default; the notebook's quick demo uses 10,000
   ```

   The notebook's demo uses far smaller numbers (1,000 samples, 10,000 steps) to be quick — fine to see the
   pipeline work, not for a model you'll rely on.
3. **Run the three stages** (each is one command in the notebook):

   ```
   python openwakeword/openwakeword/train.py --training_config my_model.yaml --generate_clips
   python openwakeword/openwakeword/train.py --training_config my_model.yaml --augment_clips
   python openwakeword/openwakeword/train.py --training_config my_model.yaml --train_model
   ```
4. **Collect the result**: `my_custom_model/hey_hades.onnx` (a `.tflite` is written too; you only need the
   `.onnx`). Copy it to your phone.
5. **Import it**: Settings → Wake word → Wake phrase → *Your own phrase…* → pick the file, then type the name
   ("Hey Hades") so notifications show it. The app refuses files that aren't openWakeWord classifiers
   (it expects a `[1, 16, 96]` input).

## Choosing the phrase

- Prefer something distinctive and not too short. "Hey Hades" (a greeting plus a two-syllable name) is a
  reasonable shape; a single short word will false-trigger far more.
- The trainer automatically generates near-miss phrases from phoneme overlap to teach the model what *not* to
  fire on. If you notice specific everyday words setting it off, list them under `custom_negative_phrases`
  in the config and retrain.
- Listen to a few of the generated clips (they're written under the config's `output_dir`). If the synthetic
  voices mispronounce the name, respell it phonetically in `target_phrase` — this is practical advice, not
  something the docs promise.

## Tuning after import

- Start at **Normal** sensitivity. Custom models start at openWakeWord's usual operating point (score bars
  0.5 / 0.35); a model can score very differently from the bundled ones, so expect to adjust.
- **Too many false triggers** → Stricter. **Misses you** → More sensitive.
- To see what your voice actually scores, run `adb logcat -s JarvisWake` and say the phrase: the service logs
  one `voice burst peak=…` line per utterance. If your real peaks sit below the bars, raise the sensitivity;
  if background speech peaks near them, lower it.

## Caveats

- Training notebooks pin old library versions (TensorFlow 2.8, SpeechBrain 0.5, …). If the setup cell fails on
  a current Colab runtime, that's version drift in the notebook, not something in Jarvis; the repo's issues
  are the place to look.
- A model trained on synthetic speech may behave differently for your voice, accent and microphone than for
  the demo voices. More samples and steps, and testing in your own environment, are the fix.
- Custom wake models have not been exercised on a device by this project's build: the import path and the
  feature pipeline were checked against the bundled models only.
