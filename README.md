# Jarvis — an Android assistant for Hermes

Jarvis is an open-source Android app that turns your phone into a voice + chat
client for a self-hosted [Hermes](https://hermes-agent.nousresearch.com) agent.
It can **replace Gemini as your device's digital assistant**, listens for
**"Hey Jarvis"**, and gives you a **Gemini-style voice conversation** plus a
normal **chat** — all powered by *your* Hermes instance (its memory,
personalities, tools, and model).

The app talks to **one thing only: your Hermes `api_server`** (the
OpenAI-compatible `/v1/chat/completions` endpoint). No companion server, no
sidecar — point it at your Hermes URL + API key and go.

## Features

- 💬 **Streaming chat** with your Hermes agent (continuous sessions via `X-Hermes-Session-Id`).
- 🎙️ **Voice conversation mode** — speak, Jarvis thinks and replies aloud, then listens again.
- 🗣️ **"Hey Jarvis" wake word** — fully on-device ([openWakeWord](https://github.com/dscripka/openWakeWord)), no cloud, no account.
- 🤖 **Default digital assistant** — launch with the long-press / assist gesture, replacing Gemini.
- 🛠️ **See what the agent is doing** — the tools Hermes runs (its `hermes.tool.progress` events) are listed on screen in chat and voice mode, never read aloud.
- 🤏 **Speakable answers** — voice turns carry a short system instruction so Hermes says the result ("lights are off in the kitchen and hallway"), not the process. On by default; editable under *Spoken replies*.
- 🔊 **Pluggable voice** — your phone's built-in TTS by default; optional **ElevenLabs** for premium speech.
- 🔒 App-only: your Hermes key stays on your device; works over LAN, Tailscale, or a reverse proxy.

The **brain is always Hermes** — Jarvis only handles the ears, mouth, face, and OS integration.

## Install

A prebuilt debug APK is in [`dist/`](dist/). With the phone connected and USB
debugging on:

```bash
adb install -r dist/Jarvis-0.1.0-debug.apk
```

Or copy the APK to the phone and tap it (allow "install from unknown sources").

## First-run setup

1. **Open Jarvis → ⚙ Settings.**
2. **Base URL** — your Hermes `api_server`, e.g. `http://100.x.x.x:8642` (Tailscale) or `http://<lan-ip>:8642`.
3. **API key** — your Hermes `API_SERVER_KEY` (in Hermes' `.env`). Tap **Save & test connection** — you should see your models.
   The **Model** field is only honoured by Hermes if you also set **Provider** (e.g. `minimax`), or enable `gateway.platforms.api_server.direct_model_requests: true` on the Hermes server; otherwise Hermes uses the model it is configured with and ignores this field.
4. **Set as default assistant** (optional) — opens system settings; pick Jarvis so the assist gesture launches it.
5. **"Hey Jarvis" wake word** (optional) — toggle on and grant microphone + notification permissions. A persistent notification shows while it listens.
6. **ElevenLabs** (optional) — paste an ElevenLabs API key + voice ID for premium speech; otherwise the phone's built-in voice is used.
7. **On-device speech recognition** (optional, needed on GrapheneOS) — under *On-device speech → Listening*, open the model list and **Download** one or more models (Moonshine, Whisper or Parakeet; from ~125 MB to ~660 MB, resumable), pick one, and switch on **Use on-device recognition**. Speech is then transcribed on the phone — no network, no Google speech service — and it never falls back to a cloud STT. After a few conversations each model row shows its measured speed on your phone, so you can compare.
8. **On-device voice** (optional, needed on GrapheneOS) — under *On-device speech → Speaking*, tap the model row to open the list, **Download** a voice (Supertonic, Piper, Kitten or Kokoro; ~21–105 MB), tap ▶ to hear and time it, pick one, and switch the row on. Supertonic is the fastest in desktop testing; Kokoro sounds best but may not keep up in real time on a phone. Replies are then spoken by the phone itself, with no network and no system text-to-speech engine.

9. **Spoken replies** — on by default. Voice turns send an instruction asking Hermes for short, plain answers. Edit the wording (or switch it off) under *Spoken replies*. It only applies to voice; text chat gets full answers.

Tap the 🎤 in the chat top bar (or use the assist gesture / wake word) to enter
voice conversation.

## Build from source

### 1. Install JDK 17

The project (AGP 8.2.2, Java 17 source/target) needs **JDK 17**. You don't
install Gradle yourself — `./gradlew` downloads the right version on first run.

```bash
# Ubuntu / Debian
sudo apt install openjdk-17-jdk
java -version   # should report 17.x
```

If several JDKs are installed, point Gradle at 17:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### 2. Install the Android SDK

Without an SDK the build stops with `SDK location not found`. You need
**platform 34** and **build-tools 34** (plus `platform-tools` for `adb`). Pick
one:

**Option A — Android Studio (easiest).** Install it and let the setup wizard
download the SDK (default location `~/Android/Sdk`). In *SDK Manager*, add
**Android 14 (API 34)**. Gradle fetches build-tools 34.0.0 on its own on the
first build once the licenses are accepted.

**Option B — command-line tools only.**

1. Download "Command line tools only" for Linux from
   <https://developer.android.com/studio#command-tools> (the filename's version
   number changes, so copy the link from that page).
2. Unpack it so `sdkmanager` ends up at
   `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`:

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools
   unzip commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
   mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
   ```

3. Install the required packages and accept the licenses:

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
   ```

### 3. Point the project at the SDK

Create `local.properties` in the repo root (it's gitignored). The path must be
absolute — `~` is not expanded:

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

Alternatively, set the `ANDROID_HOME` environment variable instead.

### 4. Build

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk (~98 MB)
```

The first build downloads Gradle and all dependencies and takes a few minutes.
Confirm it printed `BUILD SUCCESSFUL` before installing.

### 5. Install on the phone

Enable USB debugging, connect the phone, then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` lives in `~/Android/Sdk/platform-tools`. To use it as plain `adb`, add
this to `~/.bashrc`:

```bash
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
```

Notes:

- Debug builds install as `dk.foss.jarvis.debug`, so they sit side-by-side with a
  release install (`dk.foss.jarvis`).
- If the build **failed**, `adb install -r` will happily re-install the previous
  APK and report "Success". Check `ls -la app/build/outputs/apk/debug/app-debug.apk`
  to make sure the timestamp is fresh.
- A default Hermes connection can be baked in at build time with an optional,
  gitignored `keys.properties` (`JARVIS_BASE_URL`, `JARVIS_API_KEY`,
  `JARVIS_ELEVEN_KEY`, `JARVIS_ELEVEN_VOICE`). Without it you enter these in
  Settings (see [First-run setup](#first-run-setup)).

### Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=...` (step 3) or set `ANDROID_HOME`. |
| Wrong Java version errors | Install JDK 17 and set `JAVA_HOME` (step 1). |
| Licenses / missing build-tools | Run `sdkmanager --licenses` and install `build-tools;34.0.0` (step 2). |
| Wake word doesn't work on an emulator | The native libs are ARM-only (`arm64-v8a`, `armeabi-v7a`); use a real phone. |

The openWakeWord model files (`melspectrogram.onnx`, `embedding_model.onnx`,
`hey_jarvis_v0.1.onnx`) live in `app/src/main/assets/` and are included.

### Signed release

The shipped APK is debug-signed (fine for sideloading). For a release build, add
a `signingConfig` with your keystore and run `./gradlew :app:assembleRelease`.

## Architecture

| Layer | What |
|---|---|
| `hermes/HermesClient` | OkHttp SSE streaming to `/v1/chat/completions`; the only Hermes coupling. |
| `voice/SpeechInput` | On-device STT via Android `SpeechRecognizer`. |
| `voice/TtsEngine` | `AndroidTts` (free) / `ElevenLabsTts` (premium). |
| `ui/ConversationViewModel` | The listen → think → speak → listen loop. |
| `wake/WakeWordService` | openWakeWord foreground service for "Hey Jarvis". |
| `assist/*` | `VoiceInteractionService` so Jarvis can be the default assistant. |

Stack: Kotlin + Jetpack Compose, minSdk 29 / target 34. Design notes in
[`docs/superpowers/specs/`](docs/superpowers/specs/).

## Caveats

- **Wake word costs battery** and requires an always-on mic foreground service (Android restricts the privileged hotword API to preinstalled apps, so this is the only option for third-party apps).
- **Background launch on wake** uses a full-screen-intent notification; reliability varies by OEM/Android version (Android 14 restricts full-screen intents). The assist gesture is the most reliable trigger.
- **Android's built-in STT** quality/language support depends on the phone (Danish needs the language pack installed), and it needs a speech service that GrapheneOS doesn't ship — use the on-device Parakeet option there.
- **On-device speech models** are English (except Parakeet v3), can be large, and hold memory while loaded (freed after 5 idle minutes). Each model has its own licence — e.g. NVIDIA Parakeet is CC-BY-4.0 — so check the model card before redistributing. Runtime: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0).
- **Licensing of the on-device voices:** sherpa-onnx's native library statically includes **espeak-ng (GPL-3.0)**, which the Piper/Kokoro/Kitten voices need (Supertonic does not, but the library is linked either way). Building and running the app yourself is fine, but distributing a built APK carries GPL obligations; check this before publishing one.
- **ElevenLabs** is per-user (your key, your usage cost).

## Credits & license

Licensed under [Apache-2.0](LICENSE). Wake word by
[openWakeWord](https://github.com/dscripka/openWakeWord) (models) and
[openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt)
(`xyz.rementia:openwakeword`).
