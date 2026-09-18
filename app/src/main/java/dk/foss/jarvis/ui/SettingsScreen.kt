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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
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
    var model by remember { mutableStateOf(SettingsStore.DEFAULT_MODEL) }
    var provider by remember { mutableStateOf("") }
    var elevenKey by remember { mutableStateOf("") }
    var elevenVoice by remember { mutableStateOf(SettingsStore.DEFAULT_ELEVEN_VOICE) }
    fun isBatteryExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var wakeEnabled by remember { mutableStateOf(false) }
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
        store.updateVoice(elevenKey, elevenVoice)
    }

    fun enableWake() {
        scope.launch { store.updateWake(true) }
        WakeWordService.start(context)
        wakeEnabled = true
        if (!overlayGranted) requestOverlay() else if (!batteryExempt) requestBattery()
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
        model = s.model
        provider = s.provider
        elevenKey = s.elevenKey
        elevenVoice = s.elevenVoiceId
        wakeEnabled = s.wakeEnabled
        localStt = s.useLocalStt
        sttModelId = s.localSttModel
        localTts = s.useLocalTts
        ttsModelId = s.localTtsModel
        loaded = true
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
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("Provider (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                Text(
                    "Hermes ignores the model above unless a provider is sent with it (e.g. minimax) " +
                        "or the server sets gateway.platforms.api_server.direct_model_requests: true. " +
                        "Leave blank to use the model your Hermes is configured with.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )

                PillButton(
                    text = "Save & test connection",
                    onClick = {
                        scope.launch {
                            persist()
                            testing = true
                            status = "Testing\u2026"
                            val s = store.settings.first()
                            val result = HermesClient(s.baseUrl, s.apiKey).testConnection()
                            testing = false
                            status = result.fold(
                                onSuccess = { ids ->
                                    "\u2713 Connected. ${ids.size} model(s)" +
                                        if (ids.isNotEmpty()) ": ${ids.take(5).joinToString()}" else ""
                                },
                                onFailure = { "\u2717 ${it.message}" },
                            )
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
                SectionHeader("On-device speech recognition")
                Text(
                    "Transcribes your voice on the phone — no network and no Google speech service, " +
                        "so it works on GrapheneOS. Download one or more models, pick one, and compare " +
                        "the measured speed on your phone.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Use on-device recognition",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Overrides ElevenLabs Scribe and never falls back to a cloud service.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = localStt,
                        onCheckedChange = {
                            localStt = it
                            scope.launch { store.updateLocalStt(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }
                sttStore.models.forEach { m ->
                    ModelRow(
                        label = m.label,
                        blurb = m.blurb,
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
                if (localStt && sttStates[sttModelId] !is ModelState.Ready) {
                    Text(
                        "The selected model isn’t downloaded yet — voice input will report an error until it is.",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.ErrorOrange,
                    )
                }
                Text(
                    "Speed is measured on your phone from real conversations: “x real time” below 1.00 " +
                        "means it transcribes faster than you speak. A model change applies from the next conversation.",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.Muted,
                )

                SettingsDivider()
                SectionHeader("On-device voice")
                Text(
                    "Speaks replies with a voice model on the phone — no network and no system " +
                        "text-to-speech engine, so it works on GrapheneOS. Download one or more voices, " +
                        "pick one, and use Play sample to hear and time each.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Use on-device voice",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Overrides ElevenLabs and the system voice for replies.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = localTts,
                        onCheckedChange = {
                            localTts = it
                            scope.launch { store.updateLocalTts(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }
                ttsStore.models.forEach { m ->
                    ModelRow(
                        label = m.label,
                        blurb = m.blurb,
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
                            player.speak(
                                SAMPLE_TEXT,
                                onDone = { player.shutdown() },
                                onError = { sampleError = it; player.shutdown() },
                            )
                        },
                    )
                }
                sampleError?.let {
                    Text(it, fontFamily = DmSans, fontSize = 13.sp, color = JarvisColors.ErrorOrange)
                }
                if (localTts && ttsStates[ttsModelId] !is ModelState.Ready) {
                    Text(
                        "The selected voice isn’t downloaded yet — replies fall back to the system voice, " +
                            "which GrapheneOS may not have.",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.ErrorOrange,
                    )
                }

                SettingsDivider()
                SectionHeader("System integration")

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "\u201CHey Jarvis\u201D wake word",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Always-on listening (foreground service). Uses battery + a persistent notification.",
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

                if (wakeEnabled && !overlayGranted) {
                    NeutralButton("Allow \u201Cdisplay over other apps\u201D (needed to open on wake)") { requestOverlay() }
                }
                if (wakeEnabled && !batteryExempt) {
                    NeutralButton("Allow background activity (keep listening always-on)") { requestBattery() }
                }

                Text(
                    "Set Jarvis as your device's digital assistant to launch it with the assist gesture (long-press the power/home button).",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                NeutralButton("Set as default assistant") { openAssistantSettings(context) }
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

@Composable
private fun ModelRow(
    label: String,
    blurb: String,
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
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (selected) JarvisColors.Cyan.copy(alpha = 0.6f) else JarvisColors.CyanBorder, shape)
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = JarvisColors.Cyan,
                    unselectedColor = JarvisColors.Muted,
                ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = JarvisColors.TextPrimary,
                )
                Text(blurb, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.Muted)
            }
        }
        when (state) {
            is ModelState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ready · $sizeMb MB",
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.CyanText,
                        modifier = Modifier.weight(1f),
                    )
                    if (onSample != null) {
                        TextButton(onClick = onSample) { Text("Play sample", fontFamily = DmSans, color = JarvisColors.Cyan) }
                    }
                    TextButton(onClick = onDelete) { Text("Delete", fontFamily = DmSans, color = JarvisColors.Muted) }
                }
                detail?.let { Text(it, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.TextPrimary) }
            }
            is ModelState.Downloading -> {
                Text(
                    "Downloading… ${state.doneBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.TextPrimary,
                )
                LinearProgressIndicator(
                    progress = { state.doneBytes.toFloat() / state.totalBytes },
                    modifier = Modifier.fillMaxWidth(),
                    color = JarvisColors.Cyan,
                    trackColor = JarvisColors.Cyan.copy(alpha = 0.15f),
                )
                TextButton(onClick = onCancel) { Text("Cancel", fontFamily = DmSans, color = JarvisColors.Muted) }
            }
            is ModelState.Installing -> {
                val pct = if (state.totalBytes > 0) (state.doneBytes * 100 / state.totalBytes).toInt() else 0
                Text(
                    "Unpacking… $pct% \u00B7 one-time step, bigger voices take a few minutes",
                    fontFamily = DmSans,
                    fontSize = 12.sp,
                    color = JarvisColors.TextPrimary,
                )
                LinearProgressIndicator(
                    progress = { state.doneBytes.toFloat() / state.totalBytes.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                    color = JarvisColors.Cyan,
                    trackColor = JarvisColors.Cyan.copy(alpha = 0.15f),
                )
                TextButton(onClick = onCancel) { Text("Cancel", fontFamily = DmSans, color = JarvisColors.Muted) }
            }
            is ModelState.Failed -> {
                Text(state.message, fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.ErrorOrange)
                TextButton(onClick = onDownload) { Text("Retry download", fontFamily = DmSans, color = JarvisColors.Cyan) }
            }
            is ModelState.Missing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$sizeMb MB · not downloaded",
                        fontFamily = DmSans,
                        fontSize = 12.sp,
                        color = JarvisColors.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDownload) { Text("Download", fontFamily = DmSans, color = JarvisColors.Cyan) }
                }
            }
        }
    }
}

private const val SAMPLE_TEXT = "Hello, I'm Jarvis. This is how I sound with this voice."

/** "Last: 4.2 s of speech in 0.9 s (0.21× real time) · load 2.1 s" — below 1.00× is faster than real time. */
private fun sttDetail(t: LocalSttEngine.Timing): String? = listOfNotNull(
    if (t.audioMs > 0) {
        "Last: %.1f s of speech in %.1f s (%.2f× real time)"
            .format(t.audioMs / 1000.0, t.decodeMs / 1000.0, t.decodeMs.toDouble() / t.audioMs)
    } else null,
    if (t.loadMs > 0) "load %.1f s".format(t.loadMs / 1000.0) else null,
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Same idea for a voice, plus how long until the first sound. */
private fun ttsDetail(t: LocalTtsEngine.Timing): String? = listOfNotNull(
    if (t.audioMs > 0) {
        "Last: %.1f s of speech in %.1f s (%.2f× real time)"
            .format(t.audioMs / 1000.0, t.genMs / 1000.0, t.genMs.toDouble() / t.audioMs)
    } else null,
    if (t.firstAudioMs > 0) "first sound after %.1f s".format(t.firstAudioMs / 1000.0) else null,
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
