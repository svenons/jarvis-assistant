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
| Wire protocol | `hermes/HermesClient.kt`, `hermes/Models.kt` | **Only** Hermes coupling. A turn is `sendTurn()`: `POST /v1/runs` + SSE from `/v1/runs/{id}/events` (falls back to `/v1/chat/completions` on servers without runs); `fetchRun` / `stopRun`; `/v1/models` for the connection test. |
| Background runs | `run/RunWatcher.kt`, `run/RunService.kt`, `data/ConversationRepository.completeRun` | Collects the result of a turn nobody is listening to: polls the run, writes the answer into the conversation, saves, notifies. Foreground service only while runs exist. |
| Shared HTTP | `net/Http.kt` | `Http.base` (bounded timeouts) + `Http.streaming` (`readTimeout(0)` for SSE, derived from `base`). Reuse these — never build a new `OkHttpClient`. |
| Persistence | `data/SettingsStore.kt` (DataStore prefs), `data/ConversationStore.kt` (one JSON file per conversation), `data/ConversationRepository.kt` (singleton source of truth) | Settings = DataStore; conversations = `filesDir/conversations/<id>.json`. Don't mix. |
| STT | `voice/VoiceRecognizer.kt` (interface), `voice/SpeechInput.kt` (on-device), `voice/ScribeRecognizer.kt` + `voice/AudioCapture.kt` + `voice/ElevenLabsStt.kt` (ElevenLabs), `voice/LocalRecognizer.kt` + `voice/LocalSttModel.kt` (fully on-device, sherpa-onnx; catalog of tiny → large models, see below) | Three backends behind one interface. |
| TTS | `voice/TtsEngine.kt` (`AndroidTts` free / `ElevenLabsTts` premium), `voice/LocalTts.kt` (on-device sherpa-onnx voices: Supertonic / Inflect / Piper / Kitten / Kokoro) | Three backends behind one interface. |
| Model downloads | `voice/ModelStore.kt` | Shared base for `LocalSttStore` / `LocalTtsStore`: resumable, SHA-256-verified downloads + per-model `ModelState`. |
| Voice loop | `ui/ConversationViewModel.kt` | `ConvState` Idle→Listening→Thinking→Speaking; recognition, streaming, sentence extraction, single-flight TTS pump, turn invalidation, wake re-arm. |
| Wake word | `wake/WakeWordService.kt`, `wake/BootReceiver.kt` | openWakeWord foreground mic service; launches the app on "Hey Jarvis". Two Settings toggles: `wakeEnabled` (listen while the app is open; `MainActivity` starts it in `onStart`) and `wakeBackground` (keep listening once the app is closed; without it `onStop` stops the service, and `BootReceiver` only restarts it when both are on). |
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
- **TTS is single-flight, except voices that queue.** For `AndroidTts` / `ElevenLabsTts`, `pump()` guards
  with `if (speaking) return` and only advances from `speak`'s `onDone`/`onError`; a premium-TTS failure on
  one sentence falls back to `AndroidTts` for that sentence. Never call `speak` in a loop. A voice that
  implements `QueuedTts` (`LocalTts`) instead gets every sentence the moment it exists via `enqueue()`
  (`ConversationViewModel.speakQueued`): it synthesizes the next sentence while the current one plays and
  writes all of them into one continuous `AudioTrack`, so there is no gap between sentences. The old
  one-sentence-at-a-time flow paid thread start + synthesis + a 0.6 s prebuffer + a new track between every
  pair of sentences. In queued mode the turn ends via `finishIfSpoken()` (`streamDone && pendingSpeech == 0`),
  never via `pump()`. A sentence that fails is retried once with the model reloaded, then reported through
  `onError` → `reportSpeechFailure` → `ttsNotice` (shown on the Speaking screen) and skipped; a "successful"
  synthesis that yields zero audio counts as a failure. Failures must never be silent.
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
- **The wake listener is a foreground service, not tied to the activity.** Only `wakeBackground` decides whether
  it outlives the screen. On Android 14+ a microphone foreground service can't be started from a boot receiver, so
  `BootReceiver` can fail (it logs `could not start the wake listener at boot`) and the listener then only comes
  back the next time the app is opened. The "WAKE WORD ACTIVE" tag on the voice screen shows only when
  `LocalBranding.wakePhrase != null`, i.e. `wakeEnabled`. Not verified on a device.
- **Wake word is owned by whoever holds the mic.** `pauseEngine()` before
  recognition (frees the mic), resume only when leaving the conversation.
  `goIdle()` deliberately does NOT re-arm wake inside a conversation — doing so
  caused an idle→wake-fires→relaunch→no-speech→idle feedback loop. There's also an
  `emptyWakeTurns` loop-breaker (`MAX_EMPTY_WAKE=2`) that stops re-arming after 2
  consecutive empty wake turns and requires a tap.
- **Recreation must not replay the wake/assist intent.** A rotation recreates `MainActivity` with the original
  intent, so `onCreate` only bumps `assistEpoch` when `savedInstanceState == null`; otherwise rotating would jump to the
  voice screen and start listening. (`screen` is a plain `remember`, so a rotation still returns to Chat.)
- **Opening the voice screen doesn't start listening unless something explicit asked for it — a bare app/assist
  launch never records audio.** `assistEpoch` (bumped by both a genuine wake-word launch, `EXTRA_FROM_ASSIST`, and
  a bare system `ACTION_ASSIST` gesture) only *navigates* to `Screen.Conversation`. A separate `wakeEpoch` bumps
  ONLY for `EXTRA_FROM_ASSIST`, and `ConversationScreen`'s `autoListen` param (`wakeEpoch != consumedWakeEpoch`,
  OR the Chat screen's mic icon was just tapped, tracked via `manualVoiceTap`) is what actually starts listening
  on entry; a bare `ACTION_ASSIST` gesture or opening the app while locked with no fresh trigger lands on Idle and
  waits for a tap instead. `consumedWakeEpoch`/`manualVoiceTap` live in `MainActivity`'s composable, outside the
  `when (screen)` block, so they survive Chat↔Conversation switches without replaying a stale trigger. This was
  added to stop pocket/assist-gesture "butt dials" from recording audio automatically — only a real "Hey Jarvis"
  detection or an explicit mic tap does.
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
  STT models (`LocalSttStore`, `filesDir/models/<id>/`) are either individual files from a *pinned*
  Hugging Face revision, each with a SHA-256, or one `.tar.bz2` from the sherpa-onnx `asr-models` release
  (`LocalSttModel.archive`, pinned to GitHub's asset digest, unpacked with the shared `ModelStore.unpack`;
  the unpacked files are size-checked). TTS voices (`LocalTtsStore`, `filesDir/tts/<id>/content/`) are one `.tar.bz2` from
  the sherpa-onnx `tts-models` release (pinned to GitHub's own asset `digest`), unpacked with
  commons-compress behind a path-traversal guard; a `.complete` marker is written last. `ModelStore.unpack` drops the
  archive's top-level folder after ignoring `.` path segments: some tarballs (Parakeet 110M) prefix every entry with `./`.
  Add a model = one catalog entry (verify the file layout and hashes first; several
  `csukuangfj/...` HF repos are empty — those models only exist as release tarballs).
  Model ids double as folder names and are persisted in settings: don't rename them. `LocalTtsStore` deletes
  `filesDir/tts/<id>` folders whose id left the catalog (STT doesn't), so dropping a voice also frees its space.
  The user asked that existing models are **not removed** when better ones arrive: add, don't replace.
- **Catalog entries carry `year`, `score`, `desktopRtf`; Settings shows them as `meta`** ("2026 · WER 5.9% · 0.07×
  real time on a desktop", built by `modelMeta`). `year` is when that *build* was published (release asset date),
  `desktopRtf` is measured (below), and `score` is only a published number I could source: Open ASR Leaderboard
  average WER (Moonshine tiny v1 12.7, Parakeet v3 6.3, Parakeet Unified 5.9, Whisper turbo 7.8) and MOS
  (Kokoro 4.44, Supertonic 3 4.32). Blank = no source found. Don't invent one. Both lists are kept sorted by
  `desktopRtf`, fastest first.
- **STT catalog, ordered by measured speed.** Desktop sherpa-onnx 1.13.8, 4 threads, RTF on a 15 s clip (8 s for
  Moonshine 2026): Zipformer small 0.020, Moonshine tiny 2026 0.029, Parakeet 110M 0.034 (default), Moonshine tiny
  v1 0.037, Moonshine base 2026 0.044, Moonshine base v1 0.051, Parakeet 0.6B v2/v3/Unified 0.064/0.066/0.069,
  Whisper tiny.en 0.090, base.en 0.178, small.en 0.496, turbo 0.642. Zipformer small prints ALL CAPS, so
  `uppercaseOutput` → `SttAudio.sentenceCase`. Parakeet 0.6B needs ~1.2 GB RAM and a 650 MB download.
  **Moonshine v2 (`.ort`, merged decoder, `Family.MoonshineV2`) fails silently on audio of 10 s or more**: ONNX
  Runtime errors inside the quantized decoder and sherpa returns an empty string. `maxSegmentSeconds = 7` makes
  `SttAudio.split` cut long speech at the quietest 20 ms in the last 40% of each piece (unit-checked: cuts land in
  pauses, no samples lost). Never assume an empty transcript means silence for that family.
- **How Hermes actually picks a model** (source + docs, "Per-request model selection"). Precedence: a session
  `/model` override, then a model persisted on the session, then a `model_routes` alias, then the request's
  `model`/`provider`, then Hermes's own default. So a model already set on a Hermes-side conversation beats the
  app's setting. On `/v1/chat/completions` a bare `model` is honoured only if it is a route alias, or the request
  also carries a `provider`, or the server has `gateway.platforms.api_server.direct_model_requests: true`;
  `hermes-agent` (the virtual name) means "Hermes's default". The Settings "Provider (optional)" field is sent
  with `model` (`ChatRequest.provider`, omitted when blank — `explicitNulls = false`). `DEFAULT_MODEL` is now
  `hermes-agent` (it used to be one person's `mimo-v2.5-pro-ultraspeed`); stored models are left alone.
  **The Model field is a picker**: `GET /v1/models` lists only `hermes-agent` plus route aliases (with `root` = the
  model each resolves to), while `GET /api/model/options` (same bearer key, served by the api_server) returns the
  real catalog `{providers:[{slug,name,authenticated,models,featured_models,total_models}], model, provider}`.
  Picking a provider model sets Model **and** Provider together, which is what makes Hermes honour it; providers
  with `authenticated=false` are not offered. Both fetches fail quietly (older Hermes) and the field stays free text.
- **Thinking effort is a per-request field.** Settings → "Thinking effort" (`JarvisSettings.thinking`, one of
  `SettingsStore.THINKING_LEVELS`, blank = Hermes default) is sent as `model_options.reasoning_effort` on every
  `/v1/chat/completions` request (`ChatRequest.modelOptions`, omitted when blank). Hermes's `parse_reasoning_effort` maps
  `none` to "reasoning disabled" and ignores an unrecognised value (falls back to its own default), so an older or odd
  server degrades quietly. Levels: `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` (`ultra` is Hermes-internal
  and clamped to `max`, so it is not offered). Not verified against a live server.
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
  cleared per turn) for its on-screen display.
- **Reasoning is not streamed by Hermes, but it is stored; it is fetched after the turn.** The chat stream has
  no reasoning. `/api/sessions/{id}/chat/stream` and `/v1/runs` events carry a `reasoning.available` event, but
  that is only the visible text of each model step (`_relay_thinking`: cut to 500 chars, and it fires for the
  final answer too), i.e. mostly a copy of the reply, so it is deliberately not used. The model's real
  reasoning is stored per message: `GET /api/sessions/{id}/messages` returns `reasoning`, `reasoning_content`
  and `tool_calls`. `TurnEnricher.addDetails` reads that after each turn and inserts a
  `UiMessage.ROLE_REASONING` entry (and stored tool calls if none arrived live) at the START of the turn (index
  `turnStart`, right after the user message), guarded so it does nothing if anything else touched the
  conversation meanwhile. Settings has a switch (`showReasoning`). Reasoning appears when the turn ends, not
  live; if the model returns none, or this Hermes lacks the route, nothing is added. `historyForRequest()`
  excludes `tool` and `reasoning` entries (`UiMessage.isAnnotation`). `hermes/TurnDetails.kt` is the tolerant
  parser (the API is undocumented: every field is a `JsonElement`).
- **Replies never start with a blank line.** Hermes opens replies with newlines; `ConversationRepository.streamReply`
  creates the reply on its first visible text and both view models use it. Saved conversations are trimmed on load.
- **Turns are runs, so leaving the app does not cancel them.** `/v1/chat/completions` is cancelled by Hermes the
  moment the client disconnects (`_abandon_agent_task` hard-interrupts the agent); a run (`POST /v1/runs`) is its own
  server task that outlives its event stream. `HermesClient.sendTurn` returns a `TurnHandle`: `detach()` stops
  listening (the run goes on), `stop()` is the only real cancel (`POST /v1/runs/{id}/stop`). Rules the view models
  follow: leaving (`stopAll`, `resetView`, `onCleared`, the voice screen's X, app backgrounded or swiped away) = detach;
  Stop / Cancel = stop. On the voice screen, Thinking and Speaking show a labelled **Stop** (`onStopTap`: `beginTurn()` then
  `goIdle()`, so it ends the turn and does *not* listen) next to the mic (`onMicTap`: talk over it, which also stops the
  turn but then listens); X never listens, it only leaves. `Settings → Keep working when I leave`
  (`useRuns`, default on) turns this off; a server answering 404/405/501 to `/v1/runs` falls back to the chat stream
  automatically, where a turn can't outlive the app and there is no `PendingRun`.
- **A run in flight is saved with the conversation** (`Conversation.pendingRun`, default null for old files), set in
  `onRunStarted` and cleared when the run ends or is cancelled. `RunWatcher` owns polling for detached runs (2 s, then
  5 s, then 15 s); the UI owns the result while it is listening (`begin` → `finish`, or `detach`). `resumeStored()` (called
  from `MainActivity.onStart` and when `RunService` is restarted after a process kill) re-adopts saved pending runs.
  `completeRun` writes into the active conversation or a saved one, replacing the partial reply that was streaming when
  the app left. Chat blocks a new message while a run is pending (a second turn would only queue on the session).
- **Runs API facts (verified in Hermes source/docs, not on a live server):** create returns `202 {run_id}`; with a
  `session_id` and no `conversation_history` Hermes loads that session's transcript, so the app sends only `input`
  (and `conversation_history` only for a session Hermes hasn't seen); the client makes up the session id for a new
  conversation. Events: `message.delta {delta}`, `tool.started {tool,preview}` / `tool.completed` (no tool id: paired
  first-in-first-out per tool name), `approval.request`, `run.completed {output}`, `run.failed {error}`,
  `run.cancelled` / `run.interrupted`. There is **no event replay** after a reconnect and finished runs are forgotten
  after a short TTL, so a run that outlives its stream is collected by `GET /v1/runs/{id}`; if Hermes already forgot
  it, `RunWatcher` recovers the reply from the session transcript (`latestReply`). A stream that ends without a
  terminal event is `onStreamLost`, never a finished turn.
- **Approvals are always denied.** A run pauses on `approval.request` until answered. Jarvis never approves for the
  user: the client immediately posts `{"choice":"deny","resolve_all":true}` to `/v1/runs/{id}/approval` and shows a
  "Denied, needs your approval" tool line, so a run can't hang. This is new with runs: the chat stream never paused.
- **Delivery to a channel goes through Hermes's Jobs API, never a run.** A run can't message a channel (the API-server
  toolset has no `send_message`), but a cron job can: `HermesClient.createBackgroundJob` does
  `POST /api/jobs {name, prompt, schedule:"in 1m", deliver, repeat:1}` then `POST /api/jobs/{id}/run` (documented "run now":
  it fires at the next scheduler tick, every 60 s). `deliver` is `Settings → Send finished tasks to` (`deliverTarget`, a
  dropdown of `SettingsStore.DELIVER_TARGETS`, default `telegram`, editable for `telegram:<id>` / `discord:#chan`;
  `DELIVER_OFF` disables both uses below): a platform's **home channel** (`/sethome` on the server) or `all`; without
  `deliver` Hermes only saves the output to a file. Two uses: (1) the chat's "→ Telegram" button
  (`ChatViewModel.sendInBackground`) sends the typed task as a job; (2) `RunWatcher.relayToChannel` sends the *answer* of
  a run the user **left running** (detached, so only when the watcher concludes it) when the app is not on screen, if
  `relayLeft` is on: a job whose whole prompt is "reply with exactly this text", since the run itself can't deliver.
  Facts from the Hermes source: the create body needs `name`, `schedule`, `prompt` (≤ 5000 chars, injection-scanned: a 400
  carries the reason; the relay just logs it and the answer still reaches History and the notification); `reasoning_effort`
  is **not** accepted on create; a job runs in a **fresh session with no chat context**, so `backgroundPrompt` puts the last
  few messages in a button task's prompt; the relayed reply may be reworded slightly and costs one extra request. Known
  gaps: Hermes validates `deliver` only when the job fires, so a platform with no home channel is accepted and then fails on
  the server as `last_status = delivery_failed`, which the app does not see; and finished one-shot jobs stay in the job list
  as `completed` (clean up with `hermes cron remove`).
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
- **`LocalTts` streams into one `AudioTrack` shared by all queued sentences** and must `play()` before
  writing — a blocking write to a stopped `MODE_STREAM` track never returns. `stop()` bumps `generation`,
  swaps in a fresh `Out` (so a stale worker can't touch the next run's state) and does
  `pause()+flush()+release()` so a writer blocked on a full buffer is released. It pre-buffers ~0.6 s only when
  the track starts. Per-sentence `onStart`/`onDone` come from a main-thread poll of `playbackHeadPosition`
  against each sentence's frame range (with a deadline so a stalled track can't hang the turn).
- **espeak-ng is GPL-3.0 and is statically inside `libsherpa-onnx-jni.so`** (the Piper/Kokoro/Kitten
  voices need it; the voice archives also ship its `espeak-ng-data`). Distributing this APK
  (e.g. in `dist/`) therefore has GPL implications for an otherwise Apache-2.0 project — sort
  that out before publishing a build. Supertonic's archive ships no `espeak-ng-data` and its model is
  OpenRAIL-M (code MIT); the library is statically linked regardless, so the GPL note stands.
- **TTS catalog, ordered by measured speed.** Real-time factor with desktop sherpa-onnx 1.13.8, 4 threads, one
  sentence: Supertonic 0.05, Inflect nano v2 0.06 (22 MB, VITS + espeak), Piper Lessac low 0.15, Piper Ryan low
  0.16, Supertonic 3 0.18, Piper Lessac medium 0.20 (default), Piper LibriTTS-R medium 0.21, Kitten nano 0.29,
  Kitten micro 0.33, Kokoro int8 1.3. Left out as too slow: Piper high (1.4), Kitten mini (0.58); Pocket TTS needs
  a reference voice. Inflect nano v1 was reviewed as robotic (MOS 3.48); v2 has no review I found. The four
  original tiny voices spoke 21 awkward inputs (numbers, URLs, emoji, Danish, punctuation only) without an exception or empty audio on
  desktop, so a phone-side crash is likelier memory or threading than input text. Supertonic calls the stream callback once with the whole utterance, so
  `LocalTtsEngine.generate` also plays the returned audio if no chunk ever arrived.
- **`ConversationViewModel.settings` is cached**, but `ensureReady` re-reads it at the start of
  each conversation (when `recognizer` is null, since `stopAll` drops it), and rebuilds the TTS
  engine if the voice choice changed.
- **`extractSentences` only splits on `.`/`!`/`?` when followed by whitespace**
  (so `3.5` and trailing mid-stream `.` aren't split) plus a soft cap at 180 chars.
  `flushPendingSentence` (idle flush) only speaks a buffered sentence if it already
  ends with `.!?`.
- **`SettingsStore` silently migrates the model**: empty OR the legacy value
  `"kimi-for-coding"` is rewritten to `DEFAULT_MODEL = "hermes-agent"` on read; a deliberately-set custom
  model is preserved.
- **Opened over the lock screen = voice only, and Hermes is told.** `MainActivity.locked`
  (`KeyguardManager.isKeyguardLocked`, refreshed on resume, new intent, unlock and screen-off) forces the
  Conversation screen: no chat history, History list or Settings (API key), and closing it `finish()`es instead of
  opening the chat. `ConversationViewModel` starts a fresh conversation on the first turn of a locked visit
  (`freshConversation`; the earlier one is persisted first) so nothing from earlier chats is shown or sent, and
  appends the locked-phone instruction to the system message every turn while locked (Settings → "Locked phone":
  `lockedGuard` toggle + `lockedPrompt`, blank = `SettingsStore.DEFAULT_LOCKED_PROMPT`, which also forbids revealing
  personal data, tokens and passwords). That is an instruction, not a lock: Hermes runs tools server-side and nothing
  in the app can stop an action it decides to take. The app always adds `UNLOCK_PROTOCOL` after it: Hermes ends a
  reply with `[[UNLOCK]]` when a request needs the phone unlocked. `MarkerFilter` strips that from the streamed text
  (it can be split across chunks), and once the reply has been spoken `finishTurn` asks `MainActivity.requestUnlock`
  (`KeyguardManager.requestDismissKeyguard`: the system fingerprint/PIN prompt); on success `continueAfterUnlock`
  sends "I've unlocked the phone…" as a normal user turn so Hermes carries on, and on cancel the screen goes idle
  (never auto-starts the mic). `ConversationScreen` skips `stopAll` on `ON_STOP` while `unlockInFlight`. A `LOCKED` tag shows.
- **`ConversationScreen` calls `vm.stopAll()` in both `ON_STOP` and `onDispose`** (which now *detaches* a running
  turn instead of cancelling it, and skips `ON_STOP` while an unlock prompt is up),
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
