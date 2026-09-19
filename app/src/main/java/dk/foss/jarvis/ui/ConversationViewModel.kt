package dk.foss.jarvis.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.data.PendingRun
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.run.RunWatcher
import dk.foss.jarvis.voice.AndroidTts
import dk.foss.jarvis.voice.ElevenLabsTts
import dk.foss.jarvis.voice.LocalRecognizer
import dk.foss.jarvis.voice.LocalTts
import dk.foss.jarvis.voice.QueuedTts
import dk.foss.jarvis.voice.ScribeRecognizer
import dk.foss.jarvis.voice.SpeechInput
import dk.foss.jarvis.voice.TtsEngine
import dk.foss.jarvis.voice.VoiceRecognizer
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ConvState { Idle, Connecting, Listening, Thinking, Speaking }

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: VoiceRecognizer? = null

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val state = mutableStateOf(ConvState.Idle)
    val transcript = mutableStateOf("")
    val reply = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    val hint = mutableStateOf<String?>(null)
    /** Set when the voice couldn't speak a sentence, so it is never skipped silently. Cleared each turn. */
    val ttsNotice = mutableStateOf<String?>(null)
    /** Where [sendReplyToChannel] ("Send to <channel>", Speaking screen) delivers to — Settings → Send finished
     *  tasks to, same as the chat's "→" button. Shown on the button so it's clear where a tap will send the reply. */
    val deliverTarget = mutableStateOf(SettingsStore.DEFAULT_DELIVER_TARGET)
    /** Result of the last [sendReplyToChannel] tap ("Sent to your telegram." / a failure). Cleared each turn. */
    val deliveryNotice = mutableStateOf<String?>(null)
    /** The lock screen is up, so anything that changes something needs the phone unlocked first. */
    val locked = mutableStateOf(false)
    // Opened over the lock screen: the first turn starts a fresh conversation, so nothing from earlier chats is sent.
    private var freshConversation = false
    /** Set when the screen should show the system unlock prompt (fingerprint / PIN); the screen clears it. */
    val unlockRequested = mutableStateOf(false)
    /** True from asking for the unlock until its result comes back; the screen doesn't stop the conversation meanwhile. */
    var unlockInFlight = false
        private set
    // Hermes asks for an unlock by ending its reply with UNLOCK_MARKER; the filter strips it from what is shown/spoken.
    private var unlockFilter = MarkerFilter(UNLOCK_MARKER)
    private var unlockNeeded = false
    val working = mutableStateOf(false) // Hermes stream still open (response not complete)
    val stalled = mutableStateOf(false) // content paused mid-stream — likely running a tool
    /** Tools the agent ran this turn (from `hermes.tool.progress`) — display only, never spoken. */
    val tools = androidx.compose.runtime.mutableStateListOf<ToolStep>()

    // --- follow-along reply display state ---
    val segments = androidx.compose.runtime.mutableStateListOf<String>()
    val speakingIndex = mutableStateOf(-1)
    val pendingText = mutableStateOf("")
    private var spokenCount = 0
    // Index of this turn's reply in the shared conversation, or -1 before its first word. The reply is
    // written there as it streams (not when the stream ends), so a turn cut short by a tap, the screen
    // locking or leaving the screen still keeps whatever was said.
    private var assistantIndex = -1

    private var settings: JarvisSettings? = null
    private var tts: TtsEngine? = null
    private var androidFallback: AndroidTts? = null
    private var builtTtsChoice: String? = null // which voice `tts` was built for
    private var turnHandle: HermesClient.TurnHandle? = null
    private val watcher = RunWatcher.get(app)
    private var gotReplyText = false // some reply text was shown this turn (else the run's final output is)
    private var continuous = true

    // --- streaming-TTS pipeline ---
    private val sentenceBuffer = StringBuilder()
    private val ttsQueue = ArrayDeque<String>()
    private var speaking = false
    private var pendingSpeech = 0 // sentences handed to a queueing voice that haven't finished playing
    private var ttsFailures = 0
    private var streamDone = false
    private var turn = 0 // bumped each turn; stale async callbacks check this and bail
    private var retriedThisTurn = false

    // While idle in a conversation we re-arm "Hey Jarvis" so the user can re-activate
    // by voice. Loop-breaker: if wake-triggered turns keep coming back empty, stop
    // re-arming and require a tap (real speech / a tap resets the counter).
    private var currentTurnFromWake = false
    private var emptyWakeTurns = 0
    private val rearmWake = Runnable { WakeWordService.resumeListening() }

    // Speak a complete sentence that's been sitting in the buffer once the stream
    // goes quiet (e.g. the agent paused to run a tool), not only when more text arrives.
    private val idleFlush = Runnable { flushPendingSentence() }

    // If content pauses while the stream is still open, the agent is likely running a tool.
    private val stallIndicator = Runnable { if (working.value) stalled.value = true }

    init {
        viewModelScope.launch { ensureReady() }
        viewModelScope.launch { settingsStore.settings.collect { deliverTarget.value = it.deliverTarget } }
    }

    private suspend fun ensureReady() {
        // stopAll() drops the recognizer, so a null one marks the start of a conversation:
        // re-read settings then, so changes made in Settings apply without an app restart.
        val s = settings.takeIf { recognizer != null }
            ?: settingsStore.settings.first().also { settings = it }

        // Rebuild the voice if the choice changed since it was built (nothing is speaking here).
        val ttsChoice = when {
            s.useLocalTts -> "local:${s.localTtsModel}" // explicit on-device choice: never a cloud voice
            s.useElevenLabs -> "eleven:${s.elevenKey}:${s.elevenVoiceId}"
            else -> "android"
        }
        if (tts == null || ttsChoice != builtTtsChoice) {
            tts?.shutdown()
            tts = when {
                s.useLocalTts -> LocalTts(getApplication(), s.localTtsModel)
                s.useElevenLabs -> ElevenLabsTts(getApplication(), s.elevenKey, s.elevenVoiceId)
                else -> AndroidTts(getApplication(), languageTag = null)
            }
            builtTtsChoice = ttsChoice
        }

        if (recognizer == null) {
            recognizer = when {
                // Explicit choice: never fall back to a cloud STT (a missing model is reported instead).
                s.useLocalStt -> LocalRecognizer(getApplication(), s.localSttModel)
                // With an ElevenLabs key, use Scribe (far better accuracy); else Android's recognizer.
                s.useElevenLabs -> ScribeRecognizer(getApplication(), s.elevenKey, languageCode = null)
                else -> SpeechInput(getApplication())
            }
            recognizer?.prewarm()
        }
    }

    /** Set up a turn that keeps the conversation going and owns the mic (or speaker) until it ends. */
    private fun claimTurn(fromWake: Boolean) {
        // Bumped here (not just in beginTurn) so the pre-flight checks below — which run before beginTurn and can
        // take a few seconds (reachability) — are themselves invalidated by a cancel or a newer start, same as
        // any other in-flight async work.
        turn++
        // A conversation auto-continues: after Jarvis speaks it listens again.
        continuous = true
        retriedThisTurn = false
        currentTurnFromWake = fromWake
        // Free the mic from the always-on wake listener so STT can record, and cancel
        // any pending re-arm so the wake engine doesn't grab the mic mid-capture.
        main.removeCallbacks(rearmWake)
        WakeWordService.pauseListening()
    }

    fun startListening(fromWake: Boolean = false) {
        claimTurn(fromWake)
        val myTurn = turn
        // Immediate feedback: without this the screen sits unchanged for however long ensureReady()/probe()
        // take (up to a few seconds off-network), which looks broken rather than working.
        error.value = null
        hint.value = null
        state.value = ConvState.Connecting
        viewModelScope.launch {
            ensureReady()
            if (turn != myTurn) return@launch // cancelled, or superseded by another start, while loading
            val s = settings
            if (s == null || !s.isConfigured) {
                error.value = "Configure Hermes in Settings first."
                state.value = ConvState.Idle
                return@launch
            }
            // Check reachability before committing to a listen: otherwise being off the right network (e.g. away
            // from home wifi) only surfaces after the mic recorded and STT transcribed, for nothing.
            val probe = HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess).probe()
            if (turn != myTurn) return@launch
            if (probe != HermesClient.Probe.Reachable) {
                error.value = if (probe == HermesClient.Probe.AccessBlocked) {
                    "Cloudflare Access blocked the request. Check Settings > Cloudflare Access (Client ID, Secret) and its Service Auth policy."
                } else {
                    "No connection to Hermes. Check your wifi, or set up a VPN/tunnel to reach it from outside your network."
                }
                state.value = ConvState.Idle
                return@launch
            }
            if (repo.pendingRun != null && turnHandle == null) {
                // An earlier request is still running with nobody listening; a new one would only queue behind it.
                hint.value = "Hermes is still working on your last request in the background."
                state.value = ConvState.Idle
                return@launch
            }
            if (freshConversation) {
                freshConversation = false
                repo.persist() // keep the earlier conversation in History, then start clean
                repo.startNew()
            }
            beginTurn()
            state.value = ConvState.Listening
            startRecognition()
        }
    }

    /** Close out the current reply (finished or cut off) and save the conversation. */
    private fun endReply() {
        assistantIndex = -1
        repo.persistAsync() // a no-op when nothing changed since the last save
    }

    /** A new turn starts while the previous one is still running (you interrupted it): that really cancels it. */
    private fun stopTurn() {
        val h = turnHandle ?: return
        turnHandle = null
        val runId = repo.pendingRun?.runId
        h.stop()
        if (runId != null) {
            watcher.stop(runId)
            repo.updatePendingRun(null)
        }
    }

    /**
     * Stop listening (screen closed, app left). A run keeps going on the server: [RunWatcher] collects its answer
     * into the conversation and notifies. On the old chat-stream fallback this simply ends the stream.
     */
    private fun leaveTurn() {
        val h = turnHandle ?: return
        turnHandle = null
        assistantIndex = -1
        h.detach()
        repo.pendingRun?.runId?.let { watcher.detach(it) }
        repo.persistAsync()
    }

    /** The run ended while we were listening, so its answer is already in the conversation. */
    private fun endRun() {
        turnHandle = null
        repo.pendingRun?.runId?.let { watcher.finish(it) }
        repo.updatePendingRun(null)
    }

    /** Start a fresh turn: invalidate in-flight callbacks and clear pipeline state. */
    private fun beginTurn() {
        endReply() // a reply still streaming from the previous turn is kept, not dropped
        turn++
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        working.value = false
        stalled.value = false
        tools.clear()
        // Stop any in-flight recognition so the next start isn't blocked by
        // AudioCapture's "if (active) return" guard (which silently drops it).
        runCatching { recognizer?.stop() }
        stopTurn() // interrupting a turn that is still running cancels it
        runCatching { tts?.stop(); androidFallback?.stop() }
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        pendingSpeech = 0
        ttsFailures = 0
        ttsNotice.value = null
        deliveryNotice.value = null
        streamDone = false
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        transcript.value = ""
        reply.value = ""
        error.value = null
        hint.value = null
    }

    /**
     * Reset the on-screen display when the voice screen is (re)opened, so it reflects
     * the current conversation rather than a stale previous exchange (the VM is retained
     * across screen visits while the shared conversation may have been replaced).
     */
    fun resetView() {
        endReply()
        turn++ // invalidate any in-flight callbacks from a prior screen visit
        main.removeCallbacks(rearmWake)
        emptyWakeTurns = 0
        currentTurnFromWake = false
        runCatching { recognizer?.stop() }
        leaveTurn()
        transcript.value = ""
        reply.value = ""
        error.value = null
        hint.value = null
        working.value = false
        stalled.value = false
        tools.clear()
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        state.value = ConvState.Idle
        locked.value = isPhoneLocked()
        freshConversation = locked.value
    }

    private fun startRecognition() {
        val myTurn = turn
        recognizer?.start(languageTag = null, listener = object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) = onMain {
                if (turn == myTurn) transcript.value = text
            }

            override fun onEnd() = onMain {
                // Recording finished — show "Thinking" while Scribe transcribes.
                if (turn == myTurn && state.value == ConvState.Listening) state.value = ConvState.Thinking
            }

            override fun onFinal(text: String) = onMain {
                if (turn != myTurn) return@onMain
                transcript.value = text
                if (text.isBlank()) goIdle() else think(text)
            }

            override fun onError(message: String, transient: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                // Cold-start / mic-handoff hiccup right after a wake — retry once.
                if (transient && !retriedThisTurn) {
                    retriedThisTurn = true
                    main.postDelayed({ if (turn == myTurn) startRecognition() }, 450)
                    return@onMain
                }
                hint.value = message
                goIdle()
            }
        })
    }

    private fun think(userText: String) {
        val myTurn = turn
        emptyWakeTurns = 0 // got real speech — reset the wake loop-breaker
        hint.value = null
        // Pipeline state was already cleared by beginTurn(); only the stream-status
        // flags are new for this thinking phase.
        state.value = ConvState.Thinking
        working.value = true
        main.postDelayed(stallIndicator, STALL_MS)
        assistantIndex = -1
        gotReplyText = false
        unlockFilter = MarkerFilter(UNLOCK_MARKER)
        unlockNeeded = false
        repo.addMessage("user", userText)
        repo.persistAsync() // the utterance is saved now, not when the reply finishes
        val turnStart = repo.messages.size // the turn's entries start right after the utterance
        val requestHistory = repo.historyForRequest()

        val s = settings ?: return
        val client = HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess)
        val convId = repo.activeConversationId
        val request = HermesClient.TurnRequest(
            requestHistory, s.model, s.provider, repo.sessionId, systemPromptFor(s), s.thinking, s.useRuns,
        )
        turnHandle = client.sendTurn(request, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = onMain {
                if (turn == myTurn) showReply(textDelta)
            }

            override fun onRunStarted(runId: String) = onMain {
                // Not turn-guarded: even if we left meanwhile, the run exists and its result must be collected.
                if (repo.activeConversationId != convId) return@onMain
                val run = PendingRun(runId, System.currentTimeMillis())
                repo.updatePendingRun(run)
                repo.persistAsync()
                watcher.begin(convId, runId, run.startedAt)
                if (turn != myTurn || turnHandle == null) watcher.detach(runId)
            }

            override fun onFinalOutput(text: String) = onMain {
                // Normally the text streamed already; show the final answer only if nothing did.
                if (turn == myTurn && !gotReplyText && text.isNotBlank()) showReply(text)
            }

            override fun onStreamLost(message: String) = onMain {
                if (turn != myTurn) return@onMain
                // The connection dropped but the run goes on: the watcher collects it instead of failing the turn.
                turnHandle = null
                repo.pendingRun?.runId?.let { watcher.detach(it) }
                beginTurn() // stops speech and clears the pipeline
                hint.value = "Lost the connection. Hermes is still working; the answer will be added to the conversation."
                goIdle()
            }

            override fun onToolProgress(id: String, tool: String, emoji: String, label: String, running: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                tools.applyToolEvent(id, tool, emoji, label, running) // the live on-screen list
                if (running) {
                    // Also keep the step in the saved conversation. Text after a tool becomes a new reply
                    // after it, so history reads in the order things happened.
                    if (repo.addToolMessage(id, toolLine(emoji, tool, label)) >= 0) assistantIndex = -1
                    stalled.value = true // a tool is running: say WORKING now, not after the stall timer
                } else {
                    repo.finishTool(id)
                }
            }

            override fun onSessionId(id: String) { repo.setSessionId(id) }

            override fun onComplete() = onMain {
                if (turn != myTurn) return@onMain
                // Text held back as a possible marker start was ordinary text after all.
                unlockFilter.finish().let { if (it.isNotEmpty()) onTextDelta(it) }
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                // flush whatever's left as the final sentence
                val rest = sentenceBuffer.toString().trim()
                sentenceBuffer.setLength(0)
                if (rest.isNotEmpty()) enqueueSpeech(rest)
                pendingText.value = ""
                // The reply is already in the conversation (see onTextDelta); just close it out and save.
                assistantIndex = -1
                repo.persistAsync()
                if (s.showReasoning) {
                    val turnEnd = repo.messages.size
                    viewModelScope.launch { TurnEnricher.addDetails(client, repo, turnStart, turnEnd) }
                }
                endRun()
                streamDone = true
                if (tts is QueuedTts) finishIfSpoken() else pump()
            }

            override fun onError(message: String) = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                error.value = message
                endRun()
                endReply() // keep any partial reply that arrived before the failure
                goIdle()
            }
        })
    }

    private fun isPhoneLocked(): Boolean =
        (getApplication<Application>().getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    /**
     * The voice-style instruction, plus (when the lock screen is up) the locked-phone instruction and how to ask for
     * an unlock. The wording of the former is a setting; the latter is the app's own protocol, so it is always added.
     * Re-sent every turn.
     */
    private fun systemPromptFor(s: JarvisSettings): String? {
        val lockedNow = isPhoneLocked()
        locked.value = lockedNow
        val lockedText = if (lockedNow) s.lockedInstructions?.let { "$it\n\n$UNLOCK_PROTOCOL" } else null
        return listOfNotNull(s.voiceInstructions, lockedText)
            .joinToString("\n\n")
            .ifBlank { null }
    }

    /** Reply text arrived (streamed, or a run's final answer): hide an unlock marker, then show and speak the rest. */
    private fun showReply(text: String) {
        val visible = unlockFilter.feed(text)
        if (unlockFilter.found) unlockNeeded = true
        if (visible.isNotEmpty()) {
            gotReplyText = true
            onTextDelta(visible)
        }
    }

    /** A token arrived: show it, and speak as soon as a full sentence is available. */
    private fun onTextDelta(delta: String) {
        reply.value += delta
        assistantIndex = repo.streamReply(assistantIndex, delta)
        sentenceBuffer.append(delta)
        extractSentences()
        pendingText.value = sentenceBuffer.toString().trim()
        // Content is flowing → not stalled. Re-arm both timers.
        stalled.value = false
        main.removeCallbacks(idleFlush)
        main.postDelayed(idleFlush, IDLE_FLUSH_MS)
        main.removeCallbacks(stallIndicator)
        main.postDelayed(stallIndicator, STALL_MS)
    }

    private fun flushPendingSentence() {
        val s = sentenceBuffer.toString().trim()
        if (s.isNotEmpty() && (s.endsWith('.') || s.endsWith('!') || s.endsWith('?'))) {
            sentenceBuffer.setLength(0)
            pendingText.value = ""
            enqueueSpeech(s)
        }
    }

    private fun extractSentences() {
        while (true) {
            val s = sentenceBuffer
            var cut = -1
            for (i in s.indices) {
                val c = s[i]
                if (c == '\n') { cut = i; break }
                // sentence end only when we already see the following char is whitespace
                // (so "3.5" or a trailing "." mid-stream isn't split prematurely)
                if ((c == '.' || c == '!' || c == '?') && i + 1 < s.length && s[i + 1].isWhitespace()) {
                    cut = i; break
                }
            }
            // Soft cap so one long clause still starts early. The FIRST piece of a reply is cut shorter: a voice
            // synthesizes a whole sentence before playing it (so a failed one can be retried cleanly), and the wait
            // before the first sound is that first piece's synthesis time.
            val cap = if (segments.isEmpty()) FIRST_PIECE_MAX_CHARS else 180
            if (cut < 0 && s.length > cap) {
                val sp = s.lastIndexOf(' ')
                if (sp > 40) cut = sp
            }
            if (cut < 0) break
            val sentence = s.substring(0, cut + 1).trim()
            s.delete(0, cut + 1)
            if (sentence.isNotEmpty()) enqueueSpeech(sentence)
        }
    }

    private fun enqueueSpeech(text: String) {
        segments.add(text)
        val engine = tts
        if (engine is QueuedTts) {
            speakQueued(engine, text, segments.lastIndex)
            return
        }
        ttsQueue.addLast(text)
        pump()
    }

    /**
     * A voice that queues (the on-device ones) gets every sentence as soon as it exists, so it synthesizes the next
     * while the current one plays. The one-at-a-time [pump] below is for voices that can't.
     */
    private fun speakQueued(engine: QueuedTts, text: String, index: Int) {
        val myTurn = turn
        pendingSpeech++
        engine.enqueue(
            text,
            onStart = {
                if (turn == myTurn) {
                    speakingIndex.value = index
                    if (state.value != ConvState.Speaking) state.value = ConvState.Speaking
                }
            },
            onDone = { if (turn == myTurn) { pendingSpeech--; finishIfSpoken() } },
            onError = { message ->
                if (turn == myTurn) {
                    pendingSpeech--
                    reportSpeechFailure(text, message)
                    finishIfSpoken()
                }
            },
        )
    }

    /** The reply has streamed in and every queued sentence has played (or failed): the turn is over. */
    private fun finishIfSpoken() {
        if (streamDone && pendingSpeech <= 0 && ttsQueue.isEmpty()) finishTurn()
    }

    private fun reportSpeechFailure(text: String, message: String) {
        Log.w("ConversationVM", "voice failed on \"${text.take(40)}\": $message")
        ttsFailures++
        ttsNotice.value = if (ttsFailures >= 2) {
            "The voice keeps failing ($message). Try another one in Settings."
        } else {
            "Couldn\u2019t speak one sentence: $message"
        }
    }

    /** Speak queued sentences one after another (the next synthesizes after the prior plays). */
    private fun pump() {
        if (speaking) return
        val next = ttsQueue.removeFirstOrNull()
        if (next == null) {
            if (streamDone) finishTurn()
            return
        }
        speaking = true
        speakingIndex.value = spokenCount
        spokenCount++
        if (state.value != ConvState.Speaking) state.value = ConvState.Speaking
        val engine = tts ?: ensureFallback()
        if (engine == null) { speaking = false; return }
        val myTurn = turn
        engine.speak(
            text = next,
            onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
            onError = {
                // premium engine failed on this sentence — say it with on-device TTS, then continue
                val fb = ensureFallback()
                if (fb != null && fb !== engine) {
                    fb.speak(
                        text = next,
                        onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
                        onError = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
                    )
                } else {
                    main.post { if (turn == myTurn) { speaking = false; pump() } }
                }
            },
        )
    }

    private fun finishTurn() {
        // Hermes said the request needs the phone unlocked (and has now finished saying so): ask for it, then
        // carry on from onUnlockResult. Only meaningful while actually locked.
        val askUnlock = unlockNeeded && isPhoneLocked()
        unlockNeeded = false
        if (askUnlock) { askToUnlock(); return }
        if (continuous) startListening() else goIdle()
    }

    private fun askToUnlock() {
        state.value = ConvState.Idle // not goIdle(): nothing should re-arm the wake word behind the unlock prompt
        hint.value = "Unlock your phone to continue"
        unlockInFlight = true
        unlockRequested.value = true
    }

    /** The system unlock prompt (fingerprint / PIN) finished. On success the request is sent again, now unlocked. */
    fun onUnlockResult(unlocked: Boolean) {
        if (!unlockInFlight) return
        unlockInFlight = false
        locked.value = isPhoneLocked()
        if (unlocked) {
            continueAfterUnlock()
        } else {
            // Don't start the mic on our own here: the user may have backed out of the app.
            hint.value = "Still locked. Tap the mic to keep talking."
            goIdle()
        }
    }

    /** Tell Hermes the phone is unlocked so it carries out what it held back. The conversation carries on after. */
    private fun continueAfterUnlock() {
        claimTurn(fromWake = false)
        viewModelScope.launch {
            ensureReady()
            beginTurn()
            transcript.value = UNLOCKED_FOLLOW_UP
            think(UNLOCKED_FOLLOW_UP)
        }
    }

    /**
     * Go idle WITHIN the conversation. We deliberately do NOT resume the wake word
     * here: the conversation screen owns the mic the whole time it is open, and
     * re-arming the wake word on idle created a feedback loop (idle -> wake fires ->
     * assist relaunch -> startListening -> no-speech -> idle -> ...). The wake word
     * is resumed only when the conversation is actually left (stopAll/onCleared).
     * To re-engage after a silence, tap the mic.
     */
    private fun goIdle() {
        state.value = ConvState.Idle
        main.removeCallbacks(rearmWake)
        // Count consecutive *wake-triggered* empty turns; a non-wake idle (after a
        // real exchange, a tap, or auto-listen) resets the counter.
        if (currentTurnFromWake) emptyWakeTurns++ else emptyWakeTurns = 0
        if (emptyWakeTurns <= MAX_EMPTY_WAKE) {
            // Re-arm "Hey Jarvis" after a short settle so TTS tail/echo can't self-trigger.
            main.postDelayed(rearmWake, WAKE_REARM_DELAY_MS)
        }
        // else: too many empty wake turns in a row — require a tap (loop-breaker).
    }

    private fun ensureFallback(): TtsEngine? {
        (tts as? AndroidTts)?.let { return it }
        if (androidFallback == null) androidFallback = AndroidTts(getApplication(), languageTag = null)
        return androidFallback
    }

    /**
     * Stop button (Thinking or Speaking): end the turn and go idle, without listening. A run still going on the server is
     * cancelled for good; the partial reply that was already shown stays in the conversation.
     */
    fun onStopTap() {
        currentTurnFromWake = false
        beginTurn() // invalidates callbacks, stops the run (stopTurn), speech and the pipeline
        goIdle()
    }

    /**
     * "Send to <channel>" (Speaking screen): relay the reply that's currently being shown/spoken to the delivery
     * channel (Settings → Send finished tasks to), the same one the chat's "→" button uses. Unlike
     * [sendTaskToChannel] (Thinking screen, sends the request), this sends the answer you already got — for when you
     * want it kept or shared as a message rather than only spoken. Independent of the live turn: it neither stops nor is stopped
     * by it, and failure is reported in [deliveryNotice], never routed through [error]/[hint].
     */
    fun sendReplyToChannel() {
        val text = reply.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val s = settings ?: settingsStore.settings.first().also { settings = it }
            if (!s.isConfigured || !s.deliverEnabled) return@launch
            val target = s.deliverTarget
            deliveryNotice.value = null
            val head = "Deliver the message below to the user. Reply with exactly that text and nothing else: do not add, " +
                "change, summarize or comment on it, and do not use any tools.\n\n"
            val body = if (head.length + text.length <= MAX_JOB_PROMPT) text else text.take(MAX_JOB_PROMPT - head.length - 1) + "…"
            HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess).createBackgroundJob("Jarvis: answer", head + body, target)
                .onSuccess { deliveryNotice.value = "Sent to your $target." }
                .onFailure { deliveryNotice.value = "Couldn’t send to $target: ${it.message}" }
        }
    }

    /**
     * "→ <channel>" (Thinking screen): the voice twin of the chat's "→" button. Hands the request you just spoke to
     * Hermes as a background job whose answer is delivered to the channel (Settings → Send finished tasks to), and
     * ends this turn so the same work isn't done twice. The turn is only ended once Hermes accepted the job; if it
     * refuses, the turn carries on and [deliveryNotice] says why.
     */
    fun sendTaskToChannel() {
        val task = transcript.value.trim()
        if (task.isEmpty()) return
        val myTurn = turn
        // `think` already saved the utterance, so the job's context is what came before it.
        val all = repo.messages.toList()
        val earlier = all.subList(0, all.indexOfLast { it.role == "user" }.coerceAtLeast(0))
        viewModelScope.launch {
            val s = settings ?: settingsStore.settings.first().also { settings = it }
            if (!s.isConfigured || !s.deliverEnabled) return@launch
            val target = s.deliverTarget
            deliveryNotice.value = null
            val prompt = backgroundPrompt(task, earlier)
            if (prompt == null) {
                deliveryNotice.value = "That request is too long to send in the background."
                return@launch
            }
            val name = "Jarvis: " + task.replace(Regex("\\s+"), " ").take(80)
            HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess).createBackgroundJob(name, prompt, target)
                .onSuccess { id ->
                    if (turn == myTurn) onStopTap() // cancels the local run and goes idle; it must not also listen
                    val step = "job-$id"
                    repo.addToolMessage(step, toolLine("📨", "background", "Sent as a background task. The answer goes to your $target home channel."))
                    repo.finishTool(step)
                    repo.persistAsync()
                    hint.value = "Sent to your $target. The answer will arrive there."
                }
                .onFailure { deliveryNotice.value = "Couldn’t send to $target: ${it.message}" }
        }
    }

    fun onMicTap() {
        when (state.value) {
            // Connecting has no recognizer running yet (recognizer?.stop() is then a harmless no-op) — the tap
            // just cancels the pending checks via the turn bump.
            ConvState.Listening, ConvState.Connecting -> { turn++; recognizer?.stop(); goIdle() }
            else -> startListening()
        }
    }

    fun stopAll() {
        continuous = false
        unlockInFlight = false // a late unlock result belongs to a screen that is gone
        unlockRequested.value = false
        unlockNeeded = false
        assistantIndex = -1 // the persistAsync() below saves the partial reply already in the conversation
        turn++
        emptyWakeTurns = 0
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        main.removeCallbacks(rearmWake)
        working.value = false
        stalled.value = false
        tools.clear()
        recognizer?.release()
        recognizer = null
        runCatching { tts?.stop(); androidFallback?.stop() }
        leaveTurn() // leaving the screen doesn't kill a turn that is already running
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        pendingSpeech = 0
        ttsNotice.value = null
        deliveryNotice.value = null
        streamDone = false
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        state.value = ConvState.Idle
        hint.value = null
        // Save the conversation (covers turns that ended in an error/cancel, not just
        // successful replies) before handing the mic back to the wake listener.
        repo.persistAsync()
        WakeWordService.resumeListening()
    }

    override fun onCleared() {
        assistantIndex = -1
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        main.removeCallbacks(rearmWake)
        recognizer?.release()
        leaveTurn()
        tts?.shutdown()
        androidFallback?.shutdown()
        repo.persistAsync()
        WakeWordService.resumeListening()
        super.onCleared()
    }

    private companion object {
        /** Hermes ends a reply with this when the request needs the phone unlocked; the app strips it and prompts. */
        const val UNLOCK_MARKER = "[[UNLOCK]]"
        const val UNLOCK_PROTOCOL =
            "When something needs the phone unlocked, say so in one short sentence and end your reply with the exact " +
                "text $UNLOCK_MARKER. The app then asks the user to unlock with their fingerprint or PIN and tells you " +
                "when they have; do not carry out the request until then. Never write $UNLOCK_MARKER for any other reason."
        const val UNLOCKED_FOLLOW_UP = "I've unlocked the phone. Go ahead with what I asked."
        const val FIRST_PIECE_MAX_CHARS = 90
        const val IDLE_FLUSH_MS = 350L
        const val STALL_MS = 800L
        const val WAKE_REARM_DELAY_MS = 1200L
        const val MAX_EMPTY_WAKE = 2 // consecutive empty wake turns before requiring a tap
    }
}
