package dk.foss.jarvis.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dk.foss.jarvis.BuildConfig

/**
 * What the screens call the assistant. [name] is the user's chosen name (default "Jarvis"); [wakePhrase]
 * is the phrase the always-on listener waits for, or null when the wake word is off.
 */
data class Branding(val name: String, val wakePhrase: String?)

val LocalBranding = staticCompositionLocalOf { Branding(BuildConfig.DEFAULT_ASSISTANT_NAME, null) }
