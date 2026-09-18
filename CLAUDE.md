# CLAUDE.md

Guidance for Claude Code (and other AI agents) working in this repo. Read this
before editing. For the human-facing overview see [README.md](README.md).

## What this is

**Jarvis** — an open-source Android app (Kotlin + Jetpack Compose) that turns a
phone into a voice + chat client for a self-hosted **Hermes** agent. It can
replace Gemini as the device assistant, listens for **"Hey Jarvis"** fully
on-device, and runs a Gemini-style voice conversation.

The brain is always Hermes. Jarvis only does ears (STT), mouth (TTS), face
(Compose UI), and OS integration (assist + wake word). The **only** coupling to
the Hermes wire protocol lives in `hermes/HermesClient.kt` — replacing the
backend means editing that file (+ `hermes/Models.kt` shapes) and nothing else.

Package: `dk.foss.jarvis`. Single Gradle module `:app`. No nav library, no DI
framework, no companion server.

## Build, install, and **verify** (read this — it has bitten us)

```bash
./gradlew :app:assembleDebug              # output: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17 + Android SDK platform 34 / build-tools 34. `local.properties` needs
`sdk.dir=...`.

**Always confirm the build TRULY succeeded before installing.** Hard-won lesson:

- A command like `./gradlew assembleDebug | tail; echo "EXIT=$?"` makes the
  background-task notification report **exit 0 from the `echo`**, not Gradle.
  A FAILED build then looks green. Capture Gradle's own exit:
  `./gradlew :app:assembleDebug; GEXIT=$?` and grep the log for `BUILD SUCCESSFUL`
  / `^e: ` before installing.
- `adb install -r` on a failed build silently **re-installs the previous good
  APK** ("Success") — hiding the failure. After installing, sanity-check the APK
  timestamp (`ls -la app/build/outputs/apk/debug/app-debug.apk`) to confirm it
  was freshly built, not stale.
- One correct check, not many. Don't re-run builds to "verify" repeatedly.

There are no unit/instrumentation tests in this repo; verification = a clean
compile + the running app on a device.

## CI and releases

Two workflows in `.github/workflows/`: **Build** (PRs into `master` → `:app:assembleDebug`) and **Release**
(push to `master`, or manual with a bump type → debug APK + tag + GitHub Release). No build artifacts are
uploaded anywhere. Details and the tested/untested list are in [`docs/releasing.md`](docs/releasing.md). What
matters when editing:

- **Versioning is by tag, computed from commit messages** (`.github/scripts/next-version.sh`): `type!:` /
  `BREAKING CHANGE:` → major, `feat:` → minor, anything else → patch; first release is `0.1.0`. Write PR
  titles as conventional commits (they become the squash-merge message). `versionCode` =
  `MAJOR*1000000 + MINOR*1000 + PATCH`. `app/build.gradle` takes `-PappVersionName` / `-PappVersionCode` and
  defaults to `0.1.0` / `1`; don't hand-edit those defaults to cut a release.
- **Releases are debug builds** (`dk.foss.jarvis.debug`, `X.Y.Z-debug`), deliberately: this is a sideloaded
  app. Every debug build (local and CI) is signed with the committed `app/debug.keystore` — the standard
  auto-generated Android debug key (`android` / `androiddebugkey`), copied from the maintainer's
  `~/.android/debug.keystore` and public by design — so a release installs over the previous one and over
  local builds. Without it each CI runner would invent a new debug key and no release could update the last.
  `.gitignore` un-ignores that one keystore (`!app/debug.keystore`); don't add a *private* key next to it,
  don't reuse it for anything real, and don't add release-signing machinery (secrets, `signingConfigs.release`)
  without being asked.
- The Release workflow verifies package, `versionCode`, `versionName` and that the signing certificate equals
  `app/debug.keystore`'s before publishing.

## Toolchain & versions (keep aligned)

- AGP **8.2.2**, Kotlin **1.9.22** (the `kotlin.android` and
  `kotlin.plugin.serialization` plugins move in lockstep — both pinned in the
  root `build.gradle`).
- Compose is **BOM-managed** (`androidx.compose:compose-bom:2024.02.02`): add
  Compose artifacts **without** versions. The Compose compiler is pinned
  separately at `kotlinCompilerExtensionVersion '1.5.10'` and is coupled to the
  Kotlin version — bump them together.
- `compileSdk`/`targetSdk` **34**, `minSdk` **29**, Java 17 source+target +
  `jvmTarget '17'` (keep all aligned).
- New repositories go in `settings.gradle` only — `FAIL_ON_PROJECT_REPOS`
  forbids per-module `repositories {}` blocks. The wake-word lib resolves from
  the `jitpack.io` repo declared there.
- Debug builds get `applicationIdSuffix '.debug'` so debug + release install
  side-by-side (`dk.foss.jarvis.debug` vs `dk.foss.jarvis`).
- `release` currently has `minifyEnabled false` — R8/shrinking is OFF. Enabling
  it later needs keep rules for ONNX/openWakeWord + kotlinx-serialization.

## Architecture — the listen → think → speak loop

`ConversationViewModel` owns the entire voice loop; everything else is a swappable
mechanism behind an interface.

| Layer | File(s) | Role |
|---|---|---|
| Wire protocol | `hermes/HermesClient.kt`, `hermes/Models.kt` | **Only** Hermes coupling. OkHttp SSE → `/v1/chat/completions`; `/v1/models` for the connection test. |
| Shared HTTP | `net/Http.kt` | `Http.base` (bounded timeouts) + `Http.streaming` (`readTimeout(0)` for SSE, derived from `base`). Reuse these — never build a new `OkHttpClient`. |
| Persistence | `data/SettingsStore.kt` (DataStore prefs), `data/ConversationStore.kt` (one JSON file per conversation), `data/ConversationRepository.kt` (singleton source of truth) | Settings = DataStore; conversations = `filesDir/conversations/<id>.json`. Don't mix. |
| STT | `voice/VoiceRecognizer.kt` (interface), `voice/SpeechInput.kt` (on-device), `voice/ScribeRecognizer.kt` + `voice/AudioCapture.kt` + `voice/ElevenLabsStt.kt` (ElevenLabs), `voice/LocalRecognizer.kt` + `voice/LocalSttModel.kt` (fully on-device, sherpa-onnx; catalog of Moonshine / Whisper / Parakeet models) | Three backends behind one interface. |
| TTS | `voice/TtsEngine.kt` (`AndroidTts` free / `ElevenLabsTts` premium), `voice/LocalTts.kt` (on-device sherpa-onnx voices: Supertonic / Piper / Kitten / Kokoro) | Three backends behind one interface. |
| Model downloads | `voice/ModelStore.kt` | Shared base for `LocalSttStore` / `LocalTtsStore`: resumable, SHA-256-verified downloads + per-model `ModelState`. |
| Voice loop | `ui/ConversationViewModel.kt` | `ConvState` Idle→Listening→Thinking→Speaking; recognition, streaming, sentence extraction, single-flight TTS pump, turn invalidation, wake re-arm. |
| Wake word | `wake/WakeWordService.kt`, `wake/BootReceiver.kt` | openWakeWord foreground mic service; launches the app on "Hey Jarvis". |
| Assistant integration | `assist/JarvisInteractionService.kt`, `JarvisInteractionSessionService.kt`, `JarvisInteractionSession.kt`, `JarvisRecognitionService.kt` | `VoiceInteractionService` so Jarvis can be the default assistant. |
| UI / design | `MainActivity.kt`, `ui/*Screen.kt`, `ui/Theme.kt`, `ui/JarvisDesign.kt` | Compose screens + the "Direction A" design system. |

**Backend selection:** `settings.useElevenLabs` (true when an ElevenLabs key +
voice id are set) picks `ScribeRecognizer`+`ElevenLabsTts`, else
`SpeechInput`+`AndroidTts`. On top of that, `settings.useLocalStt` (Settings →
"On-device speech recognition") forces `LocalRecognizer` for STT and **never falls
back to a cloud STT** — if the model isn't downloaded it reports an error instead —
and `settings.useLocalTts` forces `LocalTts` for replies (a missing voice falls back
to `AndroidTts` per sentence, like any premium-TTS failure). `localSttModel` /
`localTtsModel` pick the entry from the `LocalSttModel.all` / `LocalTtsModel.all`
catalogs. Both are chosen in `ConversationViewModel.ensureReady`. New voice backends
plug into the `VoiceRecognizer`/`TtsEngine` interfaces, never bypass them.

**Navigation:** no nav library. `MainActivity` has `private enum Screen { Chat,
Settings, Conversation, History }` in a remembered `mutableStateOf`. An assist or
wake intent bumps an `assistEpoch` counter that a `LaunchedEffect` observes to
jump to `Conversation` (handles both cold start and `onNewIntent`). Non-Chat
screens add `BackHandler { screen = Chat }`.

## Conventions (match these)

- **Async correctness via a `turn` counter.** Capture `myTurn = turn` at the
  start of an operation; every recognizer/stream/TTS callback bails unless
  `turn == myTurn`. Bump `turn` in `beginTurn`/`resetView`/`stopAll`/`onMicTap`
  to invalidate in-flight work. This is the core concurrency discipline — don't
  add callbacks that skip the guard.
- **`hint` vs `error` are separate channels.** `hint` = soft / "No speech heard"
  / transient mic hiccups, shown on the Idle screen. `error` = hard Hermes stream
  failures, shown via `ErrorLayout` ("Can't reach Jarvis"). Never route a
  no-speech outcome into `error`.
- **`VoiceRecognizer.Listener.onError(message, transient)`**: set `transient=true`
  ONLY for retryable hiccups (mic/client/busy/disconnect, network transcription
  failure). No-match / speech-timeout / blank transcript = `transient=false` so
  the loop goes idle instead of retrying. The VM retries a transient error
  exactly once per turn (450 ms delay).
- **TTS is single-flight.** `pump()` guards with `if (speaking) return` and only
  advances from `speak`'s `onDone`/`onError`. Premium-TTS failure on one sentence
  falls back to `AndroidTts` for that sentence, then continues. Serialize
  sentences via the callback — never call `speak` in a loop.
- **Construct a fresh `HermesClient(baseUrl, apiKey)` per request** from
  `JarvisSettings`; never cache it. It is the only place that builds `/v1` URLs,
  sets `Authorization`/`X-Hermes-Session-Id`, or parses OpenAI chunk JSON.
  `HermesClient.StreamCallbacks` methods are `onDelta` / `onSessionId` /
  `onComplete` / `onError` (note: `onDelta`, not `onTextDelta`).
- **All wire/persisted types are `@Serializable` with defaults** (`Json` uses
  `ignoreUnknownKeys=true; encodeDefaults=true`). New persisted
  `Conversation`/`StoredMessage` fields MUST have defaults for on-disk
  backward-compat (e.g. `sessionId` defaults to null).
- **Strip `isError` messages** before building a Hermes request
  (`historyForRequest`) and before saving (`persist`) — errors are UI-only.
- **`ConversationRepository` is the single mutation point** for the active
  conversation; its `messages` is a `SnapshotStateList` and `sessionId` is
  `@Volatile` (written from the SSE callback thread). Unsaved changes are tracked with a version
  counter (not a dirty flag, which a concurrent edit could clear), saves are serialized by a mutex, and
  `ConversationStore.save` returns false and logs on failure so the change stays pending. It uses an
  app-lifetime `ioScope` (not `viewModelScope`, which is cancelled before `onCleared` and would drop
  the final save). ViewModels call `persistAsync()` in `onCleared`.
- **Voice turns are saved as they happen, not when they finish.** `ConversationViewModel.think` saves the
  user's utterance immediately, and `onTextDelta` streams the reply into the repository as it arrives
  (`assistantIndex`), so a turn cut short by a mic tap, the screen locking or leaving the screen keeps what
  was said. `endReply()` closes the reply and saves at every turn boundary (`beginTurn`, `resetView`,
  `onError`, `onComplete`). Don't move the reply back to a single `addMessage` in `onComplete`: any
  `turn` change before the stream ends would then silently drop it. History shows one row per conversation
  (the whole voice session, until "New conversation"), titled by its first message, with the message count.
- **Edge-to-edge:** `WindowCompat.setDecorFitsSystemWindows(window, false)` +
  transparent bars in `MainActivity`. Each screen then applies its own insets:
  `statusBarsPadding()` (ConversationScreen), `imePadding()` (ChatScreen +
  SettingsScreen so the keyboard doesn't hide the input bar). Scaffold screens
  consume the Scaffold padding.
- **Styling pulls from `JarvisColors` tokens + the 3 font families.** Don't
  hardcode hex in screens. Status pills = `StatusTag`, primary actions =
  `PillButton`, panels = `GlassCard`. Reusable animated composables live in
  `JarvisDesign.kt` (`DeepSpaceBackground`, `Waveform`, `PulseRings`,
  `ThinkingOrbs`, `JarvisMark`). `JetBrainsMono` is only for `StatusTag` labels;
  display/headings/transcript use `SpaceGrotesk`, body/buttons use `DmSans`.

## Gotchas / landmines (non-obvious — verified against the code)

- **Wake-word sensitivity is NOT the library threshold.** `MODEL_THRESHOLD=0.95f`
  is set deliberately high to keep openWakeWord's own detection + logging dormant;
  the app drives detection off the **raw `scores` Flow** (not `detections`) in
  `WakeWordService.onScore`. The effective bar is a **per-model** `strong` (single
  frame) OR `sustained` (`SMOOTHING_FRAMES=5`-frame avg, ~0.4 s) score, kept in `WakeModels`
  (`jarvis_v1` 0.3/0.2, `hey_jarvis_v0.1` 0.5/0.35, custom 0.5/0.35) and scaled by the
  Settings sensitivity (×1.4 / ×1.0 / ×0.7), with `REFRACTORY_MS=2500L` anti-re-fire.
  `COOLDOWN_MS` is dead config (engine `detections` is never consumed). Tune the
  `WakeModels` bars / `SENSITIVITY` / `SMOOTHING_FRAMES`, not `MODEL_THRESHOLD`.
- **A custom wake phrase (e.g. "Hey Hades") is an imported openWakeWord classifier, not typed text.**
  The library (`xyz.rementia:openwakeword`) can only load a classifier from *assets* (its
  `OnnxModelRunner`/`AudioRecorder` are `internal`), so `wake/CustomWakeEngine.kt` is an adapted copy of
  its feature pipeline (Apache-2.0, attribution in the file header) that takes the classifier as bytes.
  `WakeModels.importCustom` copies the file to `filesDir/wake/custom.onnx` after checking it takes a
  `[batch, 16, 96]` input (all bundled classifiers do). Bundled models still use the library engine;
  `WakeWordService` hides both behind a private `Detector`. `WakeWordService.reload()` restarts it after a
  Settings change (write the setting first, then reload — the service re-reads DataStore). Because the
  library only exposes ONNX Runtime at runtime, `app/build.gradle` also declares
  `onnxruntime-android:1.18.0` (the library's own version) so this code can compile against it.
  The custom engine's pipeline was checked against the bundled models with a line-for-line Python port
  (synthetic "Hey Jarvis" peaks at 1.00, unrelated speech at 0.00); it has not run on a device.
- **The wake listener hears the phone's own speaker.** A TTS voice saying the wake phrase scores 1.00, so
  anything that plays audio while the listener is armed must pause it: a conversation does
  (`pauseListening` on start, re-arm after `WAKE_REARM_DELAY_MS`), and Settings does for the local-voice
  sample and the ElevenLabs preview (`WakeWordService.pauseListening/resumeListening`, 800 ms settle).
  The sample text must not contain the wake phrase. There is no acoustic echo cancellation — gating our own
  playback is the fix, since we know when we're playing.
- **`onScore` state is unsynchronized** and only safe because the `scores`
  collector runs on `uiScope` (Main). Don't move the collector off the main thread.
- **Wake word is owned by whoever holds the mic.** `pauseEngine()` before
  recognition (frees the mic), resume only when leaving the conversation.
  `goIdle()` deliberately does NOT re-arm wake inside a conversation — doing so
  caused an idle→wake-fires→relaunch→no-speech→idle feedback loop. There's also an
  `emptyWakeTurns` loop-breaker (`MAX_EMPTY_WAKE=2`) that stops re-arming after 2
  consecutive empty wake turns and requires a tap.
- **`JarvisRecognitionService` is a deliberate no-op** (returns `ERROR_CLIENT`).
  It exists ONLY because a `VoiceInteractionService` must declare a
  `recognitionService` in `interaction_service.xml`. Deleting it breaks assistant
  registration. Real recognition happens in `voice/`.
- **The assist session must use `startAssistantActivity()`**, not
  `context.startActivity` — the latter only flashed the overlay without bringing
  the app forward (background-activity-launch limits). Unlocked background launch
  from `WakeWordService` relies on the `SYSTEM_ALERT_WINDOW` BAL exemption; locked
  launch uses a `CATEGORY_CALL` full-screen-intent notification.
- **`AudioCapture`'s speech threshold is relative, and quiet speech is boosted.** "Speech" = RMS above
  3× the room's ambient level (minimum over the last ~1 s of pre-speech frames, first 150 ms of mic
  warm-up ignored), clamped to 250–700; it was a fixed 1000, which meant shouting next to the phone.
  The ambient estimate freezes once speech starts. Before the WAV is written the utterance is amplified
  toward RMS 3000 (gain 1–12×, clipped), which helps both local STT and Scribe. Each capture logs
  `ambient / speech / gain` under the `AudioCapture` logcat tag — use it before retuning the constants.
- **`AudioCapture` returns `onResult(null)` on near-silence** (needs
  `speechFrames >= MIN_SPEECH_FRAMES`, ~300 ms of real audio) because ElevenLabs
  Scribe hallucinates phantom phrases on near-silent audio. `ScribeRecognizer`
  turns that null into `onError("No speech heard", transient=false)`. Scribe path
  has **no live partials** — transcript updates only on `onFinal` (unlike
  `SpeechInput`). Audio is fixed at 16 kHz / mono / 16-bit PCM WAV.
- **`ElevenLabsTts` is not truly streaming** despite the `/stream` URL — it buffers
  the full mp3 to `cacheDir` before `MediaPlayer` playback.
- **Reuse one `SpeechRecognizer` instance** across turns; create/destroy churn
  triggers `ERROR_SERVER_DISCONNECTED` (code 11).
- **Local STT = sherpa-onnx, fetched at build time.** `app/build.gradle` downloads the
  official *static-link* AAR into `app/libs/` (gitignored) and verifies a pinned
  SHA-256 (`fetchSherpaOnnx`). Use the static-link build: it has ONNX Runtime inside
  `libsherpa-onnx-jni.so`, so it doesn't clash with openwakeword's `libonnxruntime.so`.
  Don't switch to the JitPack coordinate (it re-publishes the non-static AAR plus desktop
  native jars). x86/x86_64 native libs are excluded in `packaging.jniLibs` because the AAR's
  x86 `libonnxruntime.so` collides — the app is ARM-only anyway.
- **Local models are downloaded at runtime, never bundled** (the APK is already ~150 MB).
  STT models (`LocalSttStore`, `filesDir/models/<id>/`) are individual files from a *pinned*
  Hugging Face revision, each with a SHA-256; the biggest (Parakeet, encoder 652 MB) is
  ~660 MB. TTS voices (`LocalTtsStore`, `filesDir/tts/<id>/content/`) are one `.tar.bz2` from
  the sherpa-onnx `tts-models` release (pinned to GitHub's own asset `digest`), unpacked with
  commons-compress behind a path-traversal guard; a `.complete` marker is written last.
  Add a model = one catalog entry (verify the file layout and hashes first; several
  `csukuangfj/...` HF repos are empty — those models only exist as release tarballs).
  Model ids double as folder names and are persisted in settings: don't rename them.
- **Hermes ignores a bare `model`.** On `/v1/chat/completions` the Hermes api_server only honours
  the request's `model` if the request also carries a `provider`, or the server has
  `gateway.platforms.api_server.direct_model_requests: true` (see Hermes docs, "Per-request model
  selection"). So the Settings "Provider (optional)" field is sent alongside `model`
  (`ChatRequest.provider`, omitted when blank — `HermesClient`'s Json has `explicitNulls = false`).
  `/v1/models` only advertises the profile name, so it can't be used to list selectable models.
- **Tool activity is shown, reasoning is not available.** Hermes' `/v1/chat/completions` stream carries
  only content deltas plus a custom SSE event `hermes.tool.progress` (`{tool, emoji, label, toolCallId,
  status: running|completed}`; `_`-prefixed internal tools are filtered server-side; a `completed` without a
  prior `running` is dropped). `HermesClient` maps it to `StreamCallbacks.onToolProgress`. Tool steps are never spoken,
  but each one is also a conversation message with role `tool` (`UiMessage.ROLE_TOOL`, text from `toolLine()`),
  added by `ConversationRepository.addToolMessage` and saved like any other, so history shows what the agent
  did. `historyForRequest()` excludes them (they aren't chat turns) and History's message count skips them.
  Text that arrives after a tool starts becomes a new assistant message after it (`assistantIndex` /
  `replyIndex` reset to -1), which is why chat creates its reply on the first delta instead of an empty
  placeholder up front. The voice screen additionally keeps a live `tools` list (`ui/ToolActivity.kt`,
  cleared per turn) for its on-screen display. There is no reasoning/thinking stream on that endpoint, so
  there is nothing to render for it.
- **Voice turns send a `system` message.** `JarvisSettings.voiceInstructions` (toggle + editable text,
  default `SettingsStore.DEFAULT_VOICE_PROMPT`) is passed as `streamChat(systemPrompt = …)` from
  `ConversationViewModel` only — text chat is unaffected. Hermes layers a request `system` message on top of
  its own prompt (ephemeral, not stored in the session), so it must be re-sent every turn. This is used
  instead of a Hermes *skill* on purpose: skills load on demand (`/skill-name`), so they can't be forced
  onto every voice turn.
- **TTS voice install is slow, not stuck.** Voices are `.tar.bz2` and bzip2 is decoded in pure Java
  (CPU-bound: ~5 s per 67 MB on a fast desktop, several times that on a phone, more in a debug
  build; Supertonic is 85 MB, Kokoro int8 103 MB). `ModelState.Installing` carries progress for that reason — keep the
  progress reporting if you touch `unpack`. Input buffering makes no measurable difference.
- **`LocalSttEngine` / `LocalTtsEngine`** keep one model loaded across conversations and free
  it after 5 idle minutes; switching models frees the old one first. All native access is under
  one lock because releasing during a decode crashes. They also record load/decode timings,
  which Settings shows so models can be compared on the real device.
- **`LocalTts` streams into an `AudioTrack`** and must `play()` before writing — a blocking write
  to a stopped `MODE_STREAM` track never returns. `stop()` does `pause()+flush()+release()` so a
  writer blocked on a full buffer is released. It pre-buffers ~0.6 s so a slow voice doesn't stutter.
- **espeak-ng is GPL-3.0 and is statically inside `libsherpa-onnx-jni.so`** (the Piper/Kokoro/Kitten
  voices need it; the voice archives also ship its `espeak-ng-data`). Distributing this APK
  (e.g. in `dist/`) therefore has GPL implications for an otherwise Apache-2.0 project — sort
  that out before publishing a build. Supertonic's archive ships no `espeak-ng-data` and its model is
  OpenRAIL-M (code MIT); the library is statically linked regardless, so the GPL note stands.
- **TTS catalog is the int8 builds, ordered by measured speed.** Real-time factor with desktop sherpa-onnx
  1.13.8, 4 threads, one sentence: Supertonic 0.05, Piper low 0.14, Kitten v0.8 0.27, Piper medium 0.33,
  Kokoro int8 1.3 (slower than real time, even on a desktop). The bigger fp32/fp16 builds of the same voices
  were dropped; `LocalTtsStore` deletes `filesDir/tts/<id>` folders whose id left the catalog, so removing an
  entry also frees its space. Supertonic calls the stream callback once with the whole utterance, so
  `LocalTtsEngine.generate` also plays the returned audio if no chunk ever arrived.
- **`ConversationViewModel.settings` is cached**, but `ensureReady` re-reads it at the start of
  each conversation (when `recognizer` is null, since `stopAll` drops it), and rebuilds the TTS
  engine if the voice choice changed.
- **`extractSentences` only splits on `.`/`!`/`?` when followed by whitespace**
  (so `3.5` and trailing mid-stream `.` aren't split) plus a soft cap at 180 chars.
  `flushPendingSentence` (idle flush) only speaks a buffered sentence if it already
  ends with `.!?`.
- **`SettingsStore` silently migrates the model**: empty OR the legacy value
  `"kimi-for-coding"` is rewritten to `DEFAULT_MODEL = "mimo-v2.5-pro-ultraspeed"`
  on read; a deliberately-set custom model is preserved.
- **`ConversationScreen` calls `vm.stopAll()` in both `ON_STOP` and `onDispose`**,
  and `vm.resetView()` in `LaunchedEffect(Unit)` (the VM is retained but the shared
  repo conversation may have been replaced — resetView avoids showing a stale
  exchange).

## Config & secrets

- **Default connection baked at build time:** an optional, gitignored
  `keys.properties` at repo root with `JARVIS_BASE_URL`, `JARVIS_API_KEY`,
  `JARVIS_ELEVEN_KEY`, `JARVIS_ELEVEN_VOICE` is injected into
  `BuildConfig.DEFAULT_*` (values escaped via `jvEscape`). Missing file/keys →
  empty strings → a clean unconfigured build. Read these via
  `BuildConfig.DEFAULT_*`, not literals. **Never commit `keys.properties`** (it's
  in `.gitignore` alongside `*.keystore`, `secrets.properties`, `local.properties`,
  and `.aidelegate/`). Keys belong in `keys.properties` or the Hermes server
  `.env`; never echo them back or commit them.
- **The assistant's name is configurable.** In-app text and notification titles read
  `JarvisSettings.assistantName` (Settings → Assistant; default `BuildConfig.DEFAULT_ASSISTANT_NAME`), which
  `MainActivity` exposes to every screen as `LocalBranding` (`ui/Branding.kt`, also carries the active wake
  phrase). Don't hardcode "Jarvis" in UI text — read `LocalBranding.current.name`; `WakeWordService` keeps
  its own copy for notifications and `WakeWordService.reload()` refreshes it on a rename. The launcher /
  assistant-picker label can't change at runtime, so `app/build.gradle` takes an optional `JARVIS_APP_NAME`
  from `keys.properties` and feeds both `resValue app_name` (no `app_name` in `strings.xml` any more) and
  `BuildConfig.DEFAULT_ASSISTANT_NAME`. `resValue` is XML-escaped by AGP itself; only Android string
  escapes (`\'`, `\"`, leading `@`/`?`) are added in `resEscape` — verified with `aapt2 dump badging`.
  Internal identifiers (package `dk.foss.jarvis`, `Jarvis*` class names, `jarvis_*` channel ids, the
  bundled "Hey Jarvis" wake-model labels) deliberately stay, since they name code or a model, not the product.
- `usesCleartextTraffic="true"` is intentional (self-hosted/LAN Hermes over HTTP).
- ONNX models live in `app/src/main/assets/`. The shared frontend
  (`melspectrogram.onnx`) + embedding (`embedding_model.onnx`) are loaded
  implicitly by openWakeWord. The **wake phrase** is a Settings choice (`wakeModel`), catalogued in
  `wake/WakeModels.kt`: `jarvis_v1.onnx` (default; community model, higher recall), `jarvis_v2.onnx`
  (lower false-positive rate, lower recall) and the original `hey_jarvis_v0.1.onnx` (low recall,
  peaked ~0.3–0.45). Add a bundled phrase = an asset + one `WakeModels.bundled` entry. Native libs are ARM-only
  (`abiFilters 'arm64-v8a','armeabi-v7a'`) — x86 emulators won't run wake-word
  inference.
- Editing assistant capabilities means editing `res/xml/interaction_service.xml`
  and `res/xml/recognition_service.xml`, not just the manifest.

## Design notes

Spec: [`docs/superpowers/specs/2026-06-17-jarvis-hermes-assistant-design.md`](docs/superpowers/specs/2026-06-17-jarvis-hermes-assistant-design.md).
A prebuilt debug APK ships in [`dist/`](dist/).
