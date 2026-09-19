package dk.foss.jarvis.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import dk.foss.jarvis.BuildConfig
import dk.foss.jarvis.wake.WakeModels
import androidx.core.content.ContextCompat
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.hermes.ModelEntry
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import dk.foss.jarvis.hermes.ModelOptionsResponse
import dk.foss.jarvis.voice.ElevenLabsVoices
import dk.foss.jarvis.voice.ElevenVoice
import dk.foss.jarvis.voice.LocalSttEngine
import dk.foss.jarvis.voice.LocalSttModel
import dk.foss.jarvis.voice.LocalSttStore
import dk.foss.jarvis.voice.LocalTts
import dk.foss.jarvis.voice.LocalTtsEngine
import dk.foss.jarvis.voice.LocalTtsModel
import dk.foss.jarvis.voice.LocalTtsStore
import dk.foss.jarvis.voice.ModelState
import dk.foss.jarvis.voice.VoicePreviewPlayer
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    // Cloudflare Access Service Token — an optional outer auth layer for a Hermes exposed through a Cloudflare
    // Tunnel behind Cloudflare Access. Continues using baseUrl above; no separate Cloudflare URL.
    var cfAccessEnabled by remember { mutableStateOf(false) }
    var cfClientId by remember { mutableStateOf("") }
    var cfClientSecret by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(SettingsStore.DEFAULT_MODEL) }
    // The model names Hermes itself offers (GET /v1/models), for the picker under Model.
    var hermesModels by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    // The real provider catalog (GET /api/model/options); null when this Hermes doesn't offer it.
    var modelOptions by remember { mutableStateOf<ModelOptionsResponse?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf("") }
    var useRuns by remember { mutableStateOf(true) }
    var deliverTarget by remember { mutableStateOf(SettingsStore.DEFAULT_DELIVER_TARGET) }
    var deliverMenu by remember { mutableStateOf(false) }
    var relayLeft by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var assistantName by remember { mutableStateOf("") }
    var savedName by remember { mutableStateOf("") } // last name the wake service was told about
    var voiceBrief by remember { mutableStateOf(true) }
    var showReasoning by remember { mutableStateOf(true) }
    var voicePrompt by remember { mutableStateOf("") }
    var lockedGuard by remember { mutableStateOf(true) }
    var lockedPrompt by remember { mutableStateOf("") }
    var elevenKey by remember { mutableStateOf("") }
    var elevenVoice by remember { mutableStateOf(SettingsStore.DEFAULT_ELEVEN_VOICE) }
    fun isBatteryExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var wakeEnabled by remember { mutableStateOf(false) }
    var wakeBackground by remember { mutableStateOf(true) }
    var autoListenOnAssist by remember { mutableStateOf(false) }
    var localStt by remember { mutableStateOf(false) }
    val sttStore = remember { LocalSttStore.get(context) }
    val sttStates by sttStore.states.collectAsState()
    val sttTimings by LocalSttEngine.timings.collectAsState()
    var sttModelId by remember { mutableStateOf(LocalSttModel.DEFAULT_ID) }
    var localTts by remember { mutableStateOf(false) }
    val ttsStore = remember { LocalTtsStore.get(context) }
    val ttsStates by ttsStore.states.collectAsState()
    val ttsTimings by LocalTtsEngine.timings.collectAsState()
    var ttsModelId by remember { mutableStateOf(LocalTtsModel.DEFAULT_ID) }
    var samplePlayer by remember { mutableStateOf<LocalTts?>(null) }
    var sttExpanded by remember { mutableStateOf(false) }
    var wakeModelId by remember { mutableStateOf(WakeModels.DEFAULT_ID) }
    var wakeCustomName by remember { mutableStateOf("") }
    var wakeSensitivity by remember { mutableStateOf(1) }
    var wakeExpanded by remember { mutableStateOf(false) }
    var hasCustomWake by remember { mutableStateOf(WakeModels.hasCustom(context)) }
    var wakeError by remember { mutableStateOf<String?>(null) }
    var wakeNameDirty by remember { mutableStateOf(false) }
    var sampleActive by remember { mutableStateOf(false) }
    var ttsExpanded by remember { mutableStateOf(false) }
    var sampleError by remember { mutableStateOf<String?>(null) }
    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    var voices by remember { mutableStateOf<List<ElevenVoice>>(emptyList()) }
    var voicesLoading by remember { mutableStateOf(false) }
    var voicesError by remember { mutableStateOf<String?>(null) }
    var voicesExpanded by remember { mutableStateOf(false) }
    val previewPlayer = remember { VoicePreviewPlayer(context) }
    var previewRefresh by remember { mutableStateOf(0) }
    var voicesAutoLoaded by remember { mutableStateOf(false) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = AndroidSettings.canDrawOverlays(context) }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { batteryExempt = isBatteryExempt() }

    fun requestOverlay() {
        overlayLauncher.launch(
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }

    fun requestBattery() {
        batteryLauncher.launch(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    suspend fun persist() {
        store.updateConnection(baseUrl, apiKey, model, provider)
        store.updateCloudflareAccess(cfAccessEnabled, cfClientId, cfClientSecret)
        store.updateVoice(elevenKey, elevenVoice)
        store.updateVoicePrompt(voicePrompt)
        store.updateDeliverTarget(deliverTarget)
        store.updateLockedPrompt(lockedPrompt)
        store.updateWakeCustomName(wakeCustomName)
        store.updateAssistantName(assistantName)
        val renamed = assistantName.trim().isNotEmpty() && assistantName.trim() != savedName
        if (wakeNameDirty || renamed) { // the notification shows the name and phrase, so refresh it
            wakeNameDirty = false
            savedName = assistantName.trim()
            if (wakeEnabled) WakeWordService.reload()
        }
    }

    val wakeImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            wakeError = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { WakeModels.importCustom(context, uri) }
                result.fold(
                    onSuccess = {
                        hasCustomWake = true
                        wakeModelId = WakeModels.CUSTOM_ID
                        store.updateWakeModel(WakeModels.CUSTOM_ID)
                        if (wakeEnabled) WakeWordService.reload()
                    },
                    onFailure = { wakeError = it.message ?: "Couldn't import that model." },
                )
            }
        }
    }

    /** Save a wake choice, then restart the listener so it takes effect now. */
    fun chooseWake(save: suspend () -> Unit) {
        scope.launch {
            save()
            if (wakeEnabled) WakeWordService.reload()
        }
    }

    fun enableWake() {
        scope.launch { store.updateWake(true) }
        WakeWordService.start(context)
        wakeEnabled = true
        if (!overlayGranted) requestOverlay() else if (wakeBackground && !batteryExempt) requestBattery()
    }

    val wakePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) enableWake()
    }

    fun toggleWake(on: Boolean) {
        if (on) {
            val needed = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) enableWake() else wakePermLauncher.launch(needed.toTypedArray())
        } else {
            scope.launch { store.updateWake(false) }
            WakeWordService.stop(context)
            wakeEnabled = false
        }
    }

    LaunchedEffect(Unit) {
        val s = store.settings.first()
        baseUrl = s.baseUrl
        apiKey = s.apiKey
        cfAccessEnabled = s.cloudflareAccess.enabled
        cfClientId = s.cloudflareAccess.clientId
        cfClientSecret = s.cloudflareAccess.clientSecret
        model = s.model
        provider = s.provider
        assistantName = s.assistantName
        savedName = s.assistantName
        voiceBrief = s.voiceBrief
        showReasoning = s.showReasoning
        voicePrompt = s.voicePrompt
        thinking = s.thinking
        useRuns = s.useRuns
        deliverTarget = s.deliverTarget
        relayLeft = s.relayLeft
        lockedGuard = s.lockedGuard
        lockedPrompt = s.lockedPrompt
        elevenKey = s.elevenKey
        elevenVoice = s.elevenVoiceId
        wakeEnabled = s.wakeEnabled
        wakeBackground = s.wakeBackground
        autoListenOnAssist = s.autoListenOnAssist
        localStt = s.useLocalStt
        sttModelId = s.localSttModel
        localTts = s.useLocalTts
        ttsModelId = s.localTtsModel
        wakeModelId = s.wakeModel
        wakeCustomName = s.wakeCustomName
        wakeSensitivity = s.wakeSensitivity
        // Unblock the screen before touching the network: "Save & test" is gated on `loaded`, and an unreachable
        // Hermes (exactly when you need that button) would otherwise keep it disabled until these calls time out.
        loaded = true
        // With a connection already saved, offer Hermes's model names without waiting for "Save & test". Only a
        // success is applied, so a slow failure can't wipe the lists a "Save & test" filled in meanwhile.
        if (s.isConfigured) {
            val hermes = HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess)
            hermes.fetchModels().onSuccess { hermesModels = it }
            hermes.fetchModelOptions().getOrNull()?.let { modelOptions = it }
        }
    }

    LaunchedEffect(Unit) {
        previewPlayer.onStateChanged = { previewRefresh++ }
    }

    LaunchedEffect(loaded, elevenKey) {
        if (loaded && elevenKey.isNotBlank() && !voicesAutoLoaded && voices.isEmpty()) {
            voicesAutoLoaded = true
            voicesLoading = true
            voicesError = null
            val result = ElevenLabsVoices(elevenKey).list()
            voicesLoading = false
            result.fold(
                onSuccess = { voices = it },
                onFailure = { voicesError = it.message ?: "Failed to load voices" },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { samplePlayer?.shutdown() }
    }

    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    // The wake listener hears the phone's own speaker: a sample or preview that says (or sounds like)
    // the wake phrase would set it off. So pause it while anything plays, and re-arm after a short
    // settle so the tail of the sound doesn't count.
    val previewing = previewRefresh.let { previewPlayer.isLoading || previewPlayer.isPlaying }
    val speakerActive = sampleActive || previewing
    val wakeHeld = remember { booleanArrayOf(false) }
    LaunchedEffect(speakerActive) {
        if (speakerActive) {
            WakeWordService.pauseListening()
            wakeHeld[0] = true
        } else if (wakeHeld[0]) {
            delay(WAKE_REARM_MS)
            WakeWordService.resumeListening()
            wakeHeld[0] = false
        }
    }
    LaunchedEffect(sampleActive) { // safety net: never leave the listener paused by a stuck sample
        if (sampleActive) { delay(SAMPLE_MAX_MS); sampleActive = false }
    }
    DisposableEffect(Unit) {
        onDispose { if (wakeHeld[0]) WakeWordService.resumeListening() }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisColors.Cyan.copy(alpha = 0.5f),
        unfocusedBorderColor = JarvisColors.CyanBorder,
        focusedLabelColor = JarvisColors.Cyan,
        unfocusedLabelColor = JarvisColors.Muted,
        cursorColor = JarvisColors.Cyan,
        focusedTextColor = JarvisColors.TextPrimary,
        unfocusedTextColor = JarvisColors.TextPrimary,
        focusedPlaceholderColor = JarvisColors.Muted,
        unfocusedPlaceholderColor = JarvisColors.Muted,
    )

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Settings",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            previewPlayer.stop()
                            scope.launch { persist() }
                            onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisColors.Cyan,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = JarvisColors.TextPrimary,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Assistant")
                OutlinedTextField(
                    value = assistantName,
                    onValueChange = { assistantName = it },
                    label = { Text("Name") },
                    placeholder = { Text(BuildConfig.DEFAULT_ASSISTANT_NAME) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                Text(
                    "What the app and its notifications call your assistant — try “Hades”. To change what " +
                        "you say to wake it, pick a phrase under Wake word. The launcher name is fixed " +
                        "when the app is built (JARVIS_APP_NAME in keys.properties).",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )

                SettingsDivider()
                SectionHeader("Hermes connection")

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; status = null },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://100.x.x.x:8642") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; status = null },
                    label = { Text("API key (Bearer)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                Text(
                    "Cloudflare Access",
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = JarvisColors.TextPrimary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Use Cloudflare Access",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Authenticate Hermes requests through Cloudflare Zero Trust. This allows connecting to a " +
                                "Hermes server protected by Cloudflare Access without using WARP.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = cfAccessEnabled,
                        onCheckedChange = { cfAccessEnabled = it; status = null },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }
                if (cfAccessEnabled) {
                    OutlinedTextField(
                        value = cfClientId,
                        onValueChange = { cfClientId = it; status = null },
                        label = { Text("Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors,
                    )
                    Text(
                        "Cloudflare Access Service Token Client ID",
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.Muted,
                    )
                    OutlinedTextField(
                        value = cfClientSecret,
                        onValueChange = { cfClientSecret = it; status = null },
                        label = { Text("Client Secret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors,
                    )
                    Text(
                        "Cloudflare Access Service Token Client Secret",
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.Muted,
                    )
                }

                val pickerRows = pickerRows(modelOptions, hermesModels)
                ExposedDropdownMenuBox(
                    expanded = modelMenu && pickerRows.isNotEmpty(),
                    onExpandedChange = { modelMenu = it },
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        singleLine = true,
                        trailingIcon = {
                            if (pickerRows.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = textFieldColors,
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenu && pickerRows.isNotEmpty(),
                        onDismissRequest = { modelMenu = false },
                    ) {
                        pickerRows.forEach { row ->
                            when (row) {
                                is PickerRow.Header -> Text(
                                    row.text,
                                    fontFamily = DmSans,
                                    fontSize = 12.sp,
                                    color = JarvisColors.Muted,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                                is PickerRow.Choice -> DropdownMenuItem(
                                    text = { Text(row.label) },
                                    onClick = {
                                        model = row.model
                                        provider = row.provider // sending the provider is what makes Hermes honour the model
                                        modelMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("Provider (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                Text(
                    modelHelp(model.trim(), provider.trim(), hermesModels, modelOptions),
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )

                ExposedDropdownMenuBox(expanded = thinkingMenu, onExpandedChange = { thinkingMenu = it }) {
                    OutlinedTextField(
                        value = SettingsStore.THINKING_LEVELS.firstOrNull { it.first == thinking }?.second ?: "Hermes default",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Thinking effort") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = thinkingMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = textFieldColors,
                    )
                    ExposedDropdownMenu(expanded = thinkingMenu, onDismissRequest = { thinkingMenu = false }) {
                        SettingsStore.THINKING_LEVELS.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    thinking = value
                                    thinkingMenu = false
                                    scope.launch { store.updateThinking(value) }
                                },
                            )
                        }
                    }
                }
                Text(
                    "How long the model reasons before answering, sent with every message. Lower is faster and cheaper " +
                        "(good for voice); higher is better for hard problems. Hermes default follows your server\u2019s setting.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )

                ExposedDropdownMenuBox(expanded = deliverMenu, onExpandedChange = { deliverMenu = it }) {
                    OutlinedTextField(
                        value = deliverTarget,
                        onValueChange = { deliverTarget = it }, // also accepts e.g. telegram:<chat id> or discord:#channel
                        label = { Text("Send finished tasks to") },
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deliverMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = textFieldColors,
                    )
                    ExposedDropdownMenu(expanded = deliverMenu, onDismissRequest = { deliverMenu = false }) {
                        SettingsStore.DELIVER_TARGETS.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    deliverTarget = value
                                    deliverMenu = false
                                    scope.launch { store.updateDeliverTarget(value) }
                                },
                            )
                        }
                    }
                }
                Text(
                    "Where Hermes sends the answer of a task done in the background: that platform\u2019s home channel, which " +
                        "must be set on the server first (send /sethome in that chat). Used by the chat\u2019s \u201C\u2192\u201D " +
                        "button, and by the switch below. Off turns both off.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Also send tasks I leave running",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "If you close the screen or the app while Hermes is still working, it carries on by itself, and when " +
                                "it finishes and you\u2019re not looking at the app, the answer is also sent to that channel. " +
                                "Uses one short extra request to Hermes, which may reword the message slightly.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = relayLeft,
                        onCheckedChange = {
                            relayLeft = it
                            scope.launch { store.updateRelayLeft(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Keep working when I leave",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Send each message as a Hermes run, so it carries on if you close the app, and the answer is " +
                                "added to the conversation (with a notification). Only Stop or Cancel ends it. Off: Hermes " +
                                "cancels a reply the moment the app disconnects. Older servers without runs fall back to that.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = useRuns,
                        onCheckedChange = {
                            useRuns = it
                            scope.launch { store.updateUseRuns(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Reasoning and tool calls in history",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "After each reply, read what Hermes stored for the turn and add the model's reasoning " +
                                "(when it gives one) to the top of the turn. It appears once the reply is done, not live.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = showReasoning,
                        onCheckedChange = {
                            showReasoning = it
                            scope.launch { store.updateShowReasoning(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                PillButton(
                    text = "Save & test connection",
                    onClick = {
                        // Cloudflare Access on with a blank Client ID/Secret would silently send no CF-Access-* headers
                        // at all (HermesClient.probe/fetchModels only add them once both are non-blank) \u2014 catch
                        // that here instead, so it's a clear error rather than a connection test that quietly skips
                        // the auth the user just turned on.
                        if (cfAccessEnabled && (cfClientId.isBlank() || cfClientSecret.isBlank())) {
                            status = "\u2717 Cloudflare Access is on, but Client ID and Client Secret can\u2019t be empty."
                        } else {
                            scope.launch {
                                persist()
                                testing = true
                                status = "Testing\u2026"
                                val s = store.settings.first()
                                val result = HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess).fetchModels()
                                testing = false
                                result.onSuccess { hermesModels = it }
                                modelOptions = HermesClient(s.baseUrl, s.apiKey, s.cloudflareAccess).fetchModelOptions().getOrNull()
                                status = result.fold(
                                    onSuccess = { models ->
                                        "\u2713 Connected. ${models.size} model(s)" +
                                            if (models.isNotEmpty()) ": ${models.take(5).joinToString { it.id }}" else ""
                                    },
                                    onFailure = { "\u2717 ${it.message}" },
                                )
                            }
                        }
                    },
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded && !testing && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                )
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                }
                status?.let {
                    Text(
                        it,
                        fontFamily = DmSans,
                        fontSize = 14.sp,
                        color = when {
                            it.startsWith("\u2713") -> JarvisColors.Cyan
                            it.startsWith("Testing") -> JarvisColors.TextSecondary
                            else -> JarvisColors.ErrorOrange
                        },
                    )
                }

                SettingsDivider()
                SectionHeader("Spoken replies")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Short, speakable answers",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "In voice conversations, ask Hermes to say the result, not the process — " +
                                "\u201Clights are off in the kitchen and hallway\u201D. Text chat is unaffected.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = voiceBrief,
                        onCheckedChange = {
                            voiceBrief = it
                            scope.launch { store.updateVoiceBrief(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }
                if (voiceBrief) {
                    OutlinedTextField(
                        value = voicePrompt,
                        onValueChange = { voicePrompt = it },
                        label = { Text("Instruction sent with each voice message") },
                        placeholder = { Text(SettingsStore.DEFAULT_VOICE_PROMPT, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors,
                    )
                    Text(
                        "Leave blank for the built-in wording. Sent as a system message on every voice turn, " +
                            "so Hermes follows it without you installing anything on the server.",
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.Muted,
                    )
                }

                SettingsDivider()
                SectionHeader("Locked phone")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Protect a locked phone",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "When you talk to the assistant over the lock screen, tell Hermes the phone is locked so it " +
                                "won\u2019t reveal personal data or change anything. If a request needs unlocking, you are " +
                                "asked for your fingerprint or PIN and the conversation carries on.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = lockedGuard,
                        onCheckedChange = {
                            lockedGuard = it
                            scope.launch { store.updateLockedGuard(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }
                if (lockedGuard) {
                    OutlinedTextField(
                        value = lockedPrompt,
                        onValueChange = { lockedPrompt = it },
                        label = { Text("Instruction sent while the phone is locked") },
                        placeholder = { Text(SettingsStore.DEFAULT_LOCKED_PROMPT, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors,
                    )
                    Text(
                        "Leave blank for the built-in wording, which also keeps passwords, tokens and other personal " +
                            "details out of replies. The request to unlock is added automatically. This is an " +
                            "instruction to Hermes, not a lock: it runs its tools on your server.",
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.Muted,
                    )
                }

                SettingsDivider()
                SectionHeader("Voice (optional)")
                Text(
                    "Leave blank to use your phone's built-in voice. Set an ElevenLabs key for premium speech.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                OutlinedTextField(
                    value = elevenKey,
                    onValueChange = { elevenKey = it },
                    label = { Text("ElevenLabs API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = elevenVoice,
                    onValueChange = { elevenVoice = it },
                    label = { Text("ElevenLabs voice ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PillButton(
                        text = if (voicesLoading) "Loading\u2026" else "Load voices",
                        onClick = {
                            scope.launch {
                                voicesLoading = true
                                voicesError = null
                                val result = ElevenLabsVoices(elevenKey).list()
                                voicesLoading = false
                                result.fold(
                                    onSuccess = { voices = it },
                                    onFailure = { voicesError = it.message ?: "Failed to load voices" },
                                )
                            }
                        },
                        accent = false,
                        enabled = elevenKey.isNotBlank() && !voicesLoading,
                    )
                    if (voicesLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                    }
                }
                voicesError?.let {
                    Text(
                        "\u2717 $it",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.ErrorOrange,
                    )
                }

                if (elevenKey.isBlank()) {
                    Text(
                        "Enter your ElevenLabs API key to load voices.",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.Muted,
                    )
                }

                if (voices.isNotEmpty()) {
                    val previewId = previewPlayer.currentVoiceId
                    val previewing = previewPlayer.isLoading || previewPlayer.isPlaying
                    // Force reads so Compose recomposes on preview state changes
                    @Suppress("UNUSED_VARIABLE") val _r = previewRefresh

                    ExposedDropdownMenuBox(
                        expanded = voicesExpanded,
                        onExpandedChange = { voicesExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = voiceLabel(elevenVoice, voices),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicesExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            colors = textFieldColors,
                        )
                        ExposedDropdownMenu(
                            expanded = voicesExpanded,
                            onDismissRequest = { voicesExpanded = false },
                            modifier = Modifier.heightIn(max = 340.dp),
                        ) {
                            voices.forEach { v ->
                                val label = buildString {
                                    append(v.category.ifEmpty { "voice" })
                                    v.labels["gender"]?.let { append(" \u00b7 "); append(it) }
                                    v.labels["accent"]?.let { append(" \u00b7 "); append(it) }
                                }
                                DropdownMenuItem(
                                    text = {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                v.name,
                                                fontFamily = DmSans,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = JarvisColors.TextPrimary,
                                            )
                                            Text(
                                                label,
                                                fontFamily = DmSans,
                                                fontSize = 11.sp,
                                                color = JarvisColors.Muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (v.preview_url != null) {
                                            IconButton(onClick = {
                                                if (previewId == v.voice_id && previewing) {
                                                    previewPlayer.stop()
                                                } else {
                                                    previewPlayer.play(v.voice_id, v.preview_url)
                                                }
                                            }) {
                                                if (previewId == v.voice_id && previewPlayer.isLoading) {
                                                    CircularProgressIndicator(
                                                        Modifier.size(18.dp),
                                                        strokeWidth = 2.dp,
                                                        color = JarvisColors.Cyan,
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = if (previewId == v.voice_id && previewPlayer.isPlaying)
                                                            Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                                        contentDescription = if (previewId == v.voice_id && previewPlayer.isPlaying)
                                                            "Stop" else "Preview",
                                                        tint = JarvisColors.Cyan,
                                                modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        elevenVoice = v.voice_id
                                        voicesExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }

                SettingsDivider()
                SectionHeader("On-device speech")
                Text(
                    "Runs on the phone — no network and no Google speech service, so it works on " +
                        "GrapheneOS. Speed is measured on this phone from real use: below 1.00× real " +
                        "time is faster than real time. Under each model: the year that build was published, " +
                        "a published accuracy score where one exists (WER: lower is better; MOS: higher is " +
                        "better), and its speed on a desktop; a phone is slower. Lists run quickest first. " +
                        "A change applies from the next conversation.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )
                ModelPicker(
                    title = "Listening",
                    hint = "Replaces ElevenLabs Scribe; never falls back to a cloud service.",
                    enabled = localStt,
                    onEnabled = {
                        localStt = it
                        scope.launch { store.updateLocalStt(it) }
                    },
                    selectedLabel = sttStore.model(sttModelId).label,
                    state = sttStates[sttModelId] ?: ModelState.Missing,
                    sizeMb = sttStore.model(sttModelId).totalBytes / 1_000_000,
                    detail = sttTimings[sttModelId]?.let { sttDetail(it) },
                    missingHint = "voice input will fail until it's downloaded",
                    expanded = sttExpanded,
                    onToggle = { sttExpanded = !sttExpanded },
                ) {
                    sttStore.models.forEach { m ->
                        ModelRow(
                            label = m.label,
                            blurb = m.blurb,
                            meta = m.meta,
                            sizeMb = m.totalBytes / 1_000_000,
                            state = sttStates[m.id] ?: ModelState.Missing,
                            selected = m.id == sttModelId,
                            detail = sttTimings[m.id]?.let { sttDetail(it) },
                            onSelect = {
                                sttModelId = m.id
                                scope.launch { store.updateLocalSttModel(m.id) }
                            },
                            onDownload = { sttStore.download(m) },
                            onCancel = { sttStore.cancel(m) },
                            onDelete = { sttStore.delete(m) },
                        )
                    }
                }
                ModelPicker(
                    title = "Speaking",
                    hint = "Replaces ElevenLabs and the system voice for replies.",
                    enabled = localTts,
                    onEnabled = {
                        localTts = it
                        scope.launch { store.updateLocalTts(it) }
                    },
                    selectedLabel = ttsStore.model(ttsModelId).label,
                    state = ttsStates[ttsModelId] ?: ModelState.Missing,
                    sizeMb = ttsStore.model(ttsModelId).archiveBytes / 1_000_000,
                    detail = ttsTimings[ttsModelId]?.let { ttsDetail(it) },
                    missingHint = "replies fall back to the system voice, which GrapheneOS may not have",
                    expanded = ttsExpanded,
                    onToggle = { ttsExpanded = !ttsExpanded },
                ) {
                    ttsStore.models.forEach { m ->
                        ModelRow(
                            label = m.label,
                            blurb = m.blurb,
                            meta = m.meta,
                            sizeMb = m.archiveBytes / 1_000_000,
                            state = ttsStates[m.id] ?: ModelState.Missing,
                            selected = m.id == ttsModelId,
                            detail = ttsTimings[m.id]?.let { ttsDetail(it) },
                            onSelect = {
                                ttsModelId = m.id
                                scope.launch { store.updateLocalTtsModel(m.id) }
                            },
                            onDownload = { ttsStore.download(m) },
                            onCancel = { ttsStore.cancel(m) },
                            onDelete = { ttsStore.delete(m) },
                            onSample = {
                                sampleError = null
                                samplePlayer?.shutdown()
                                val player = LocalTts(context, m.id)
                                samplePlayer = player
                                sampleActive = true
                                player.speak(
                                    SAMPLE_TEXT,
                                    onDone = { sampleActive = false; player.shutdown() },
                                    onError = { sampleError = it; sampleActive = false; player.shutdown() },
                                )
                            },
                        )
                    }
                }
                sampleError?.let {
                    Text(it, fontFamily = DmSans, fontSize = 13.sp, color = JarvisColors.ErrorOrange)
                }

                SettingsDivider()
                SectionHeader("System integration")

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Wake word",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Listen for the wake phrase while the app is open.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = wakeEnabled,
                        onCheckedChange = { toggleWake(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Keep listening in the background",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = if (wakeEnabled) JarvisColors.TextPrimary else JarvisColors.Muted,
                        )
                        Text(
                            "Also listen with the app closed and the screen off, and restart when the phone does. " +
                                "Uses battery and shows a persistent notification. Off: it stops when you leave the app.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = wakeBackground,
                        enabled = wakeEnabled,
                        onCheckedChange = {
                            wakeBackground = it
                            scope.launch { store.updateWakeBackground(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                WakePhraseCard(
                    selectedId = wakeModelId,
                    customName = wakeCustomName,
                    hasCustom = hasCustomWake,
                    sensitivity = wakeSensitivity,
                    expanded = wakeExpanded,
                    error = wakeError,
                    textFieldColors = textFieldColors,
                    onToggle = { wakeExpanded = !wakeExpanded },
                    onSelect = { id ->
                        wakeModelId = id
                        chooseWake { store.updateWakeModel(id) }
                    },
                    onName = { wakeCustomName = it; wakeNameDirty = true },
                    onImport = { wakeImportLauncher.launch(arrayOf("*/*")) },
                    onRemove = {
                        WakeModels.removeCustom(context)
                        hasCustomWake = false
                        if (wakeModelId == WakeModels.CUSTOM_ID) {
                            wakeModelId = WakeModels.DEFAULT_ID
                            chooseWake { store.updateWakeModel(WakeModels.DEFAULT_ID) }
                        }
                    },
                    onSensitivity = { level ->
                        wakeSensitivity = level
                        chooseWake { store.updateWakeSensitivity(level) }
                    },
                )

                if (wakeEnabled && !overlayGranted) {
                    NeutralButton("Allow \u201Cdisplay over other apps\u201D (needed to open on wake)") { requestOverlay() }
                }
                if (wakeEnabled && wakeBackground && !batteryExempt) {
                    NeutralButton("Allow background activity (keep listening always-on)") { requestBattery() }
                }

                Text(
                    "Set ${LocalBranding.current.name} as your device's digital assistant to launch it with the assist gesture (long-press the power/home button).",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                NeutralButton("Set as default assistant") { openAssistantSettings(context) }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Start listening on the assist gesture",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Off (default): the assist gesture opens the voice screen and waits for a tap, so an " +
                                "accidental gesture in your pocket doesn't record audio. On: it starts listening " +
                                "immediately, the same as the wake word.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = autoListenOnAssist,
                        onCheckedChange = {
                            autoListenOnAssist = it
                            scope.launch { store.updateAutoListenOnAssist(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = JarvisColors.CyanText,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = JarvisColors.Cyan.copy(alpha = 0.08f),
    )
}

@Composable
private fun NeutralButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.CyanBorder),
    ) {
        Text(
            text = text,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = JarvisColors.TextPrimary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * Which phrase the always-on listener waits for: a bundled model, or one the user trained and imported
 * (openWakeWord models are per-phrase, so "Hey Hades" needs its own trained model), plus sensitivity.
 */
@Composable
private fun WakePhraseCard(
    selectedId: String,
    customName: String,
    hasCustom: Boolean,
    sensitivity: Int,
    expanded: Boolean,
    error: String?,
    textFieldColors: androidx.compose.material3.TextFieldColors,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onName: (String) -> Unit,
    onImport: () -> Unit,
    onRemove: () -> Unit,
    onSensitivity: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val customLabel = customName.ifBlank { "Custom wake word" }
    val current = when {
        selectedId == WakeModels.CUSTOM_ID && hasCustom -> customLabel
        else -> WakeModels.bundled.firstOrNull { it.id == selectedId }?.phrase ?: WakeModels.bundled.first().phrase
    }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, JarvisColors.CyanBorder, shape)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Wake phrase", fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.Muted)
                Text(current, fontFamily = DmSans, fontSize = 14.sp, color = JarvisColors.CyanText)
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide wake phrases" else "Choose wake phrase",
                tint = JarvisColors.Cyan,
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WakeChoiceRow(
                    label = "Hey Jarvis",
                    subtitle = null,
                    selected = selectedId == WakeModels.DEFAULT_ID,
                    onClick = { onSelect(WakeModels.DEFAULT_ID) },
                )
                WakeModels.bundled.filter { it.id != WakeModels.DEFAULT_ID }.forEach { m ->
                    WakeChoiceRow(label = m.phrase, subtitle = null, selected = selectedId == m.id, onClick = { onSelect(m.id) })
                }
                WakeChoiceRow(
                    label = if (hasCustom) customLabel else "Your own phrase…",
                    subtitle = if (hasCustom) "Imported model" else "Import a model you trained, e.g. “Hey Hades”",
                    selected = selectedId == WakeModels.CUSTOM_ID && hasCustom,
                    onClick = { if (hasCustom) onSelect(WakeModels.CUSTOM_ID) else onImport() },
                ) {
                    RowAction(Icons.Default.Add, if (hasCustom) "Replace model" else "Import model", JarvisColors.Cyan, onImport)
                    if (hasCustom) RowAction(Icons.Default.Delete, "Remove custom model", JarvisColors.Muted, onRemove)
                }
                if (hasCustom) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = onName,
                        label = { Text("Name of your phrase") },
                        placeholder = { Text("Hey Hades") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = textFieldColors,
                    )
                }
                error?.let { Text(it, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.ErrorOrange) }
                Text(
                    "A wake phrase can't be typed in: each one is a small model trained for it. Train one " +
                        "with openWakeWord's training notebook (github.com/dscripka/openWakeWord; needs Linux " +
                        "or Google Colab), then import the .onnx file here.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Sensitivity: " + listOf("stricter", "normal", "more sensitive")[sensitivity.coerceIn(0, 2)],
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.TextPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Slider(
                    value = sensitivity.toFloat(),
                    onValueChange = { onSensitivity(it.roundToInt()) },
                    valueRange = 0f..2f,
                    steps = 1,
                    colors = SliderDefaults.colors(
                        thumbColor = JarvisColors.Cyan,
                        activeTrackColor = JarvisColors.Cyan,
                        inactiveTrackColor = JarvisColors.Cyan.copy(alpha = 0.2f),
                        activeTickColor = JarvisColors.Cyan,
                        inactiveTickColor = JarvisColors.Cyan.copy(alpha = 0.4f),
                    ),
                )
                Text(
                    "Stricter = fewer false triggers; more sensitive = catches you from further away.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )
            }
        }
    }
}

/** A selectable line in [WakePhraseCard]: ✓ when chosen, optional trailing actions. */
@Composable
private fun WakeChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) JarvisColors.Cyan.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = JarvisColors.Cyan, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(label, fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = JarvisColors.TextPrimary)
            subtitle?.let { Text(it, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.Muted) }
        }
        actions()
    }
}

/**
 * One on-device feature (listening or speaking): its on/off switch and a one-line summary of the
 * chosen model. The full model list only takes space when [expanded].
 */
@Composable
private fun ModelPicker(
    title: String,
    hint: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    selectedLabel: String,
    state: ModelState,
    sizeMb: Long,
    detail: String?,
    /** Finishes "Not downloaded — …" when the feature is on but its model isn't there. */
    missingHint: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    models: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, JarvisColors.CyanBorder, shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = JarvisColors.TextPrimary,
                )
                Text(hint, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.Muted)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisColors.Cyan,
                    checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                    uncheckedThumbColor = JarvisColors.Muted,
                    uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                ),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(selectedLabel, fontFamily = DmSans, fontSize = 14.sp, color = JarvisColors.CyanText)
                val (status, color) = when (state) {
                    is ModelState.Ready -> "Ready · $sizeMb MB${detail?.let { " · $it" } ?: ""}" to JarvisColors.TextSecondary
                    is ModelState.Downloading -> "Downloading… ${percent(state.doneBytes, state.totalBytes)}%" to JarvisColors.TextPrimary
                    is ModelState.Installing -> "Unpacking… ${percent(state.doneBytes, state.totalBytes)}%" to JarvisColors.TextPrimary
                    is ModelState.Failed -> state.message to JarvisColors.ErrorOrange
                    is ModelState.Missing ->
                        if (enabled) "Not downloaded — $missingHint" to JarvisColors.ErrorOrange
                        else "Not downloaded · $sizeMb MB" to JarvisColors.Muted
                }
                Text(status, fontFamily = DmSans, fontSize = 12.sp, color = color)
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide models" else "Choose model",
                tint = JarvisColors.Cyan,
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = models,
            )
        }
    }
}

private fun percent(done: Long, total: Long): Int = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0

/** A model in the expanded list: tap to select; small icon buttons to download, play a sample, cancel, delete. */
@Composable
private fun ModelRow(
    label: String,
    blurb: String,
    /** Year, published score and desktop speed, e.g. "2026 · WER 5.9% · 0.07× real time on a desktop". */
    meta: String,
    sizeMb: Long,
    state: ModelState,
    selected: Boolean,
    /** Measured speed, shown once the model is ready and has been used. */
    detail: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSample: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) JarvisColors.Cyan.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = JarvisColors.Cyan, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(label, fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = JarvisColors.TextPrimary)
                Text(blurb, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.Muted)
                Text(meta, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.TextSecondary)
                val (status, color) = when (state) {
                    is ModelState.Ready -> "Ready · $sizeMb MB${detail?.let { " · $it" } ?: ""}" to JarvisColors.CyanText
                    is ModelState.Downloading ->
                        "Downloading… ${state.doneBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB" to JarvisColors.TextPrimary
                    is ModelState.Installing ->
                        "Unpacking… ${percent(state.doneBytes, state.totalBytes)}% · one-time step" to JarvisColors.TextPrimary
                    is ModelState.Failed -> state.message to JarvisColors.ErrorOrange
                    is ModelState.Missing -> "$sizeMb MB · not downloaded" to JarvisColors.Muted
                }
                Text(status, fontFamily = DmSans, fontSize = 12.sp, color = color)
            }
            when (state) {
                is ModelState.Ready -> {
                    if (onSample != null) RowAction(Icons.Default.PlayArrow, "Play sample", JarvisColors.Cyan, onSample)
                    RowAction(Icons.Default.Delete, "Delete", JarvisColors.Muted, onDelete)
                }
                is ModelState.Downloading, is ModelState.Installing -> RowAction(Icons.Default.Close, "Cancel", JarvisColors.Muted, onCancel)
                is ModelState.Failed, is ModelState.Missing -> RowAction(Icons.Default.Download, "Download", JarvisColors.Cyan, onDownload)
            }
        }
        when (state) {
            is ModelState.Downloading -> LinearProgressIndicator(
                progress = { state.doneBytes.toFloat() / state.totalBytes.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = JarvisColors.Cyan,
                trackColor = JarvisColors.Cyan.copy(alpha = 0.15f),
            )
            is ModelState.Installing -> LinearProgressIndicator(
                progress = { state.doneBytes.toFloat() / state.totalBytes.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = JarvisColors.Cyan,
                trackColor = JarvisColors.Cyan.copy(alpha = 0.15f),
            )
            else -> {}
        }
    }
}

/** A 40 dp icon button — tighter than IconButton's 48 dp minimum, so a row stays short. */
@Composable
private fun RowAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(10.dp).size(20.dp),
    )
}

// Deliberately free of the wake phrase, which the listener would otherwise hear from the speaker.
private const val SAMPLE_TEXT = "Hello, this is how I sound with this voice. Testing, one, two, three."
private const val WAKE_REARM_MS = 800L   // settle time after playback before the wake listener re-arms
private const val SAMPLE_MAX_MS = 30_000L

/** "0.21× real time · load 2.1 s" — below 1.00× is faster than real time. */
private fun sttDetail(t: LocalSttEngine.Timing): String? = listOfNotNull(
    if (t.audioMs > 0) "%.2f× real time".format(t.decodeMs.toDouble() / t.audioMs) else null,
    if (t.loadMs > 0) "load %.1f s".format(t.loadMs / 1000.0) else null,
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Same idea for a voice, plus how long until the first sound. */
private fun ttsDetail(t: LocalTtsEngine.Timing): String? = listOfNotNull(
    if (t.audioMs > 0) "%.2f× real time".format(t.genMs.toDouble() / t.audioMs) else null,
    if (t.firstAudioMs > 0) "first sound %.1f s".format(t.firstAudioMs / 1000.0) else null,
    if (t.loadMs > 0) "load %.1f s".format(t.loadMs / 1000.0) else null,
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun openAssistantSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun voiceLabel(voiceId: String, voices: List<ElevenVoice>): String {
    if (voiceId.isBlank()) return ""
    return voices.firstOrNull { it.voice_id == voiceId }?.name ?: voiceId
}

private sealed interface PickerRow {
    data class Header(val text: String) : PickerRow
    data class Choice(val label: String, val model: String, val provider: String) : PickerRow
}

private fun JsonElement.asModelId(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * What the Model menu offers: Hermes's own default, its model routes, then real models grouped by provider
 * (the provider's featured ones, or its first few). Choosing a provider model also sets the Provider field.
 */
private fun pickerRows(options: ModelOptionsResponse?, routes: List<ModelEntry>): List<PickerRow> {
    val rows = ArrayList<PickerRow>()
    if (options != null || routes.isNotEmpty()) {
        rows += PickerRow.Choice("${SettingsStore.DEFAULT_MODEL} (Hermes's own default)", SettingsStore.DEFAULT_MODEL, "")
    }
    val aliases = routes.filter { it.id != SettingsStore.DEFAULT_MODEL }
    if (aliases.isNotEmpty()) {
        rows += PickerRow.Header("Your Hermes's model routes")
        aliases.forEach { rows += PickerRow.Choice(if (it.root != null && it.root != it.id) "${it.id} (${it.root})" else it.id, it.id, "") }
    }
    options?.providers?.filter { it.authenticated }?.forEach { p ->
        val featured = p.featured_models.mapNotNull { it.asModelId() }
        val models = featured.ifEmpty { p.models.mapNotNull { it.asModelId() }.take(MODELS_PER_PROVIDER) }
        if (models.isEmpty()) return@forEach
        val more = (p.total_models ?: p.models.size) - models.size
        rows += PickerRow.Header((p.name ?: p.slug) + if (more > 0) " ($more more: type the name)" else "")
        models.forEach { rows += PickerRow.Choice(it, it, p.slug) }
    }
    return rows
}

private const val MODELS_PER_PROVIDER = 12

/** What the chosen model will actually do, given what Hermes offers. */
private fun modelHelp(model: String, provider: String, routes: List<ModelEntry>, options: ModelOptionsResponse?): String {
    val current = options?.model?.takeIf { it.isNotBlank() }?.let { m ->
        " (currently $m" + (options.provider?.takeIf { it.isNotBlank() }?.let { " via $it" } ?: "") + ")"
    } ?: ""
    val pinned = " A model already set on a conversation inside Hermes (a /model command) wins until you start a new one."
    val direct = "Otherwise it is ignored unless a Provider is set with it (for example minimax) or the server sets " +
        "gateway.platforms.api_server.direct_model_requests: true."
    return when {
        model.isEmpty() || model == SettingsStore.DEFAULT_MODEL -> "Hermes uses the model it is configured with$current."
        routes.any { it.id == model } -> {
            val target = routes.first { it.id == model }.root?.takeIf { it != model }
            "A model route on your Hermes" + (target?.let { ", using $it" } ?: "") + "." + pinned
        }
        provider.isNotEmpty() -> "Sent with provider \"$provider\", so Hermes uses this model for your messages.$pinned"
        routes.isEmpty() -> "Hermes only honours this name if it is one of its model routes. $direct"
        else -> "Not one of your Hermes's model routes. $direct"
    }
}
