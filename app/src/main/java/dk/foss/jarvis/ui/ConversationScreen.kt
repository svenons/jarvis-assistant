package dk.foss.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
    /** Bumped only by a genuine wake-word detection; drives re-listening while this screen is already open. */
    wakeTrigger: Int,
    /** Whether this particular entry to the screen should start listening on its own (wake word or an explicit
     * mic tap) rather than landing on Idle and waiting for a tap — avoids "butt dials" from a bare app open or
     * assist gesture. */
    autoListen: Boolean,
    /** Show the system unlock prompt (fingerprint / PIN); calls back with whether the phone got unlocked. */
    onRequestUnlock: ((Boolean) -> Unit) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state
    val transcript by vm.transcript
    val reply by vm.reply
    val error by vm.error
    val hint by vm.hint
    val working by vm.working
    val stalled by vm.stalled
    val tools = vm.tools
    val ttsNotice by vm.ttsNotice
    val locked by vm.locked
    val unlockRequested by vm.unlockRequested

    val segments = vm.segments
    val speakingIndex by vm.speakingIndex
    val pendingText by vm.pendingText
    val listState = rememberLazyListState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) vm.startListening()
    }

    LaunchedEffect(Unit) {
        vm.resetView()
        if (autoListen) {
            if (!hasPermission) {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                vm.startListening()
            }
        }
    }
    var lastTrigger by remember { mutableStateOf(wakeTrigger) }
    LaunchedEffect(wakeTrigger) {
        if (wakeTrigger != lastTrigger) {
            lastTrigger = wakeTrigger
            if (hasPermission && state == ConvState.Idle) {
                vm.startListening(fromWake = true)
            }
        }
    }
    LaunchedEffect(unlockRequested) {
        if (unlockRequested) {
            vm.unlockRequested.value = false
            onRequestUnlock { unlocked -> vm.onUnlockResult(unlocked) }
        }
    }
    LaunchedEffect(speakingIndex) {
        if (speakingIndex in 0 until segments.size) {
            listState.animateScrollToItem(speakingIndex)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // The unlock prompt can stop the activity briefly; that isn't leaving the conversation.
            if (event == Lifecycle.Event.ON_STOP && !vm.unlockInFlight) vm.stopAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopAll()
        }
    }

    val isActive = state != ConvState.Idle && error == null

    DeepSpaceBackground(active = isActive) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            if (locked) {
                Box(Modifier.align(Alignment.TopStart).padding(top = 12.dp)) {
                    StatusTag("LOCKED", JarvisColors.ErrorOrange)
                }
            }

            // Top-right close button
            IconButton(
                onClick = onExit,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = JarvisColors.TextPrimary.copy(alpha = 0.7f),
                )
            }

            // Show error layout when error != null, regardless of state
            if (error != null) {
                ErrorLayout(
                    errorMessage = error!!,
                    onRetry = {
                        if (hasPermission) vm.onMicTap()
                        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onDismiss = onExit,
                )
            } else {
                when (state) {
                    ConvState.Idle -> IdleContent(
                        hasPermission = hasPermission,
                        hint = hint,
                        onMicTap = {
                            if (hasPermission) vm.onMicTap()
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    )
                    ConvState.Listening -> ListeningContent(
                        transcript = transcript,
                        onMicTap = {
                            if (hasPermission) vm.onMicTap()
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    )
                    ConvState.Thinking -> ThinkingContent(
                        transcript = transcript,
                        stalled = stalled,
                        tools = tools,
                        onMicTap = {
                            if (hasPermission) vm.onMicTap()
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onStop = { vm.onStopTap() },
                    )
                    ConvState.Speaking -> SpeakingContent(
                        segments = segments,
                        speakingIndex = speakingIndex,
                        pendingText = pendingText,
                        listState = listState,
                        tools = tools,
                        notice = ttsNotice,
                        onMicTap = {
                            if (hasPermission) vm.onMicTap()
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onStop = { vm.onStopTap() },
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(hasPermission: Boolean, hint: String?, onMicTap: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Only when the wake word is on in Settings (Branding.wakePhrase is null otherwise).
            if (LocalBranding.current.wakePhrase != null) StatusTag("WAKE WORD ACTIVE", JarvisColors.Cyan)

            // Glowing mic button with pulse ring
            Box(contentAlignment = Alignment.Center) {
                // Single expanding pulse ring
                val transition = rememberInfiniteTransition(label = "idlePulse")
                val pulseScale by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.6f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2600, easing = LinearEasing),
                    ),
                    label = "idlePulseScale",
                )
                val pulseAlpha by transition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2600, easing = LinearEasing),
                    ),
                    label = "idlePulseAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .border(2.dp, JarvisColors.Blue.copy(alpha = 0.5f), CircleShape),
                )
                // Mic button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        JarvisColors.Blue,
                                        JarvisColors.Blue.copy(alpha = 0.6f),
                                    ),
                                ),
                            )
                            // Inset highlight
                            drawCircle(
                                color = Color.White.copy(alpha = 0.08f),
                                radius = size.minDimension * 0.38f,
                            )
                        }
                        .clip(CircleShape)
                        .clickable { onMicTap() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            JarvisColors.Blue,
                                            JarvisColors.Blue.copy(alpha = 0.5f),
                                            Color.Transparent,
                                        ),
                                        radius = size.minDimension * 0.6f,
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Microphone",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                }
            }

            // Caption or hint
            if (hint != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = hint,
                        fontFamily = DmSans,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = JarvisColors.CyanText,
                    )
                    Text(
                        text = "Tap the mic to talk",
                        fontFamily = DmSans,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = JarvisColors.Muted,
                    )
                }
            } else {
                val wakePhrase = LocalBranding.current.wakePhrase
                Text(
                    text = if (wakePhrase != null) "Tap, or say \u201C$wakePhrase\u201D" else "Tap to talk",
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = JarvisColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ListeningContent(transcript: String, onMicTap: () -> Unit) {
    // Blinking caret
    val transition = rememberInfiniteTransition(label = "caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretBlink",
    )

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StatusTag("LISTENING", JarvisColors.Cyan)

            Spacer(Modifier.height(32.dp))

            PulseRings {
                Waveform(barCount = 18, barWidth = 4.dp, minH = 14.dp, maxH = 56.dp)
            }

            if (transcript.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = transcript,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Normal,
                    fontSize = 19.sp,
                    color = JarvisColors.TextPrimaryAlpha,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Blinking caret
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(2.dp)
                        .height(20.sp.value.dp)
                        .alpha(caretAlpha)
                        .background(JarvisColors.Cyan),
                )
            }
        }

        MicFab(
            onMicTap = onMicTap,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun ThinkingContent(transcript: String, stalled: Boolean, tools: List<ToolStep>, onMicTap: () -> Unit, onStop: () -> Unit) {
    // Blinking dots
    val transition = rememberInfiniteTransition(label = "blink")
    val dot1 by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1",
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2",
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3",
    )

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StatusTag(
                text = if (stalled) "WORKING" else "THINKING",
                color = JarvisColors.ThinkBlue,
            )

            Spacer(Modifier.height(32.dp))

            ThinkingOrbs()

            if (transcript.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "\u201C$transcript\u201D",
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = JarvisColors.TextPrimary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Three blinking dots
            Box(
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).alpha(dot1).background(JarvisColors.ThinkBlue, CircleShape))
                    Box(Modifier.size(6.dp).alpha(dot2).background(JarvisColors.ThinkBlue, CircleShape))
                    Box(Modifier.size(6.dp).alpha(dot3).background(JarvisColors.ThinkBlue, CircleShape))
                }
            }

            if (tools.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                ToolActivity(tools, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            }
        }

        VoiceControls(
            onStop = onStop,
            onMicTap = onMicTap,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun SpeakingContent(
    segments: List<String>,
    speakingIndex: Int,
    pendingText: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    tools: List<ToolStep>,
    notice: String?,
    onMicTap: () -> Unit,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // Small waveform + status
            PulseRings(
                modifier = Modifier.size(100.dp),
            ) {
                Waveform(barCount = 9, barWidth = 3.dp, minH = 6.dp, maxH = 22.dp)
            }

            Spacer(Modifier.height(8.dp))

            StatusTag("SPEAKING", JarvisColors.Cyan)

            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.ErrorOrange,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            if (tools.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ToolActivity(tools, Modifier.fillMaxWidth().padding(horizontal = 8.dp), maxRows = 3)
            }

            Spacer(Modifier.height(16.dp))

            // Reply inside glass card
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 104.dp),
                ) {
                    items(segments.size) { i ->
                        val isSpeaking = i == speakingIndex
                        Text(
                            text = segments[i],
                            fontFamily = SpaceGrotesk,
                            fontWeight = if (isSpeaking) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (isSpeaking) 20.sp else 16.sp,
                            textAlign = TextAlign.Center,
                            color = if (isSpeaking) JarvisColors.Cyan else JarvisColors.TextPrimary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (pendingText.isNotEmpty()) {
                        item {
                            Text(
                                text = pendingText,
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                color = JarvisColors.TextSecondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        VoiceControls(
            onStop = onStop,
            onMicTap = onMicTap,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

/**
 * Bottom controls while Hermes is thinking or speaking. Stop ends the turn and goes idle (no listening), which is what
 * cancelling should do; the mic is for talking over it: it ends the turn and listens.
 */
@Composable
private fun VoiceControls(onStop: () -> Unit, onMicTap: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(JarvisColors.GlassBg)
                    .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.4f), CircleShape)
                    .clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(28.dp), tint = JarvisColors.TextPrimary)
            }
            Spacer(Modifier.height(4.dp))
            Text("Stop", fontFamily = DmSans, fontSize = 12.sp, color = JarvisColors.TextSecondary)
        }
        MicFab(onMicTap = onMicTap)
    }
}

@Composable
private fun MicFab(onMicTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                JarvisColors.Blue.copy(alpha = 0.7f),
                                JarvisColors.Blue.copy(alpha = 0.3f),
                                Color.Transparent,
                            ),
                        ),
                    )
                }
                .clip(CircleShape)
                .background(JarvisColors.Blue.copy(alpha = 0.8f))
                .clickable { onMicTap() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Microphone",
                modifier = Modifier.size(32.dp),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ErrorLayout(
    errorMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Server-off icon with orange ring
            Box(
                modifier = Modifier.size(100.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Error scrim glow
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        JarvisColors.ErrorOrange.copy(alpha = 0.14f),
                                        Color.Transparent,
                                    ),
                                ),
                            )
                        },
                )
                // Orange ring
                val transition = rememberInfiniteTransition(label = "errorPulse")
                val ringScale by transition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2600, easing = LinearEasing),
                    ),
                    label = "errorRingScale",
                )
                val ringAlpha by transition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2600, easing = LinearEasing),
                    ),
                    label = "errorRingAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(ringScale)
                        .alpha(ringAlpha)
                        .border(2.dp, JarvisColors.ErrorOrange.copy(alpha = 0.5f), CircleShape),
                )
                // Server-off glyph (simplified)
                Text(
                    text = "\u2300",
                    fontSize = 36.sp,
                    color = JarvisColors.ErrorOrange,
                )
            }

            Text(
                text = "Can't reach ${LocalBranding.current.name}",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                color = JarvisColors.ErrorOrange,
            )

            Text(
                text = errorMessage,
                fontFamily = DmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = JarvisColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(8.dp))

            PillButton(
                text = "Retry",
                onClick = onRetry,
                accent = true,
            )

            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(
                    text = "Dismiss",
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = JarvisColors.Muted,
                )
            }
        }
    }
}
