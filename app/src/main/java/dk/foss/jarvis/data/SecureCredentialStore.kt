package dk.foss.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keystore-backed encrypted storage for the Cloudflare Access Service Token (Settings → Cloudflare Access).
 * Nothing else in Jarvis needed secure-at-rest storage before this — the Hermes API key and the ElevenLabs key
 * both live in plain [androidx.datastore.preferences.core.Preferences] alongside the rest of [JarvisSettings] —
 * so there was no existing mechanism to reuse. A Cloudflare Access client secret is explicitly more sensitive
 * (it's effectively a second bearer credential, accepted by Cloudflare's edge before a request ever reaches
 * Hermes), so it gets its own AES256-GCM encrypted file instead of joining them. The client ID isn't as
 * sensitive but is stored alongside it here for simplicity, per the same file.
 *
 * [SettingsStore] is the only caller: it merges this into the [JarvisSettings] flow, so nothing outside `data/`
 * needs to know these two fields aren't in DataStore like everything else.
 */
class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext

    // Wrapped in runCatching: a Keystore/EncryptedSharedPreferences failure on some device must not break
    // JarvisSettings (and so every Hermes connection) for every user just because this combines into it — only
    // Cloudflare Access degrades, to "no credentials stored", same as a fresh install that never set any.
    private val prefs: Result<SharedPreferences> by lazy {
        runCatching {
            // The stable 1.0.0 release of security-crypto (no alpha dependency for a security-sensitive feature)
            // only has the MasterKeys utility, not the later MasterKey.Builder API.
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "jarvis_secure_credentials",
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    /** Current values, read synchronously (this is plain [SharedPreferences] underneath, not suspending). */
    fun current(): Pair<String, String> {
        val p = prefs.getOrNull() ?: return "" to ""
        return (p.getString(KEY_CLIENT_ID, "") ?: "") to (p.getString(KEY_CLIENT_SECRET, "") ?: "")
    }

    fun update(clientId: String, clientSecret: String) {
        prefs.getOrNull()
            ?.edit()
            ?.putString(KEY_CLIENT_ID, clientId)
            ?.putString(KEY_CLIENT_SECRET, clientSecret)
            ?.apply()
    }

    /** Emits the current pair immediately, then again on every change, for [SettingsStore.settings] to combine into [JarvisSettings]. */
    val credentials: Flow<Pair<String, String>> = callbackFlow {
        val p = prefs.getOrNull()
        if (p == null) {
            trySend("" to "")
            awaitClose {}
        } else {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_CLIENT_ID || key == KEY_CLIENT_SECRET) trySend(current())
            }
            trySend(current())
            p.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }.distinctUntilChanged()

    private companion object {
        const val KEY_CLIENT_ID = "cf_client_id"
        const val KEY_CLIENT_SECRET = "cf_client_secret"
    }
}
