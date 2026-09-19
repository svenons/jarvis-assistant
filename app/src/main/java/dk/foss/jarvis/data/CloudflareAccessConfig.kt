package dk.foss.jarvis.data

/**
 * Cloudflare Access Service Token credentials (Settings → Cloudflare Access), an optional OUTER authentication
 * layer in front of Hermes's own — for users who expose Hermes through a Cloudflare Tunnel protected by
 * Cloudflare Access and want Jarvis to authenticate to it directly, without running WARP on the device.
 *
 * [clientId]/[clientSecret] are persisted in [SecureCredentialStore] (Keystore-backed encrypted storage), never
 * in the plain DataStore the rest of [JarvisSettings] lives in. [toString] is overridden so the secret can never
 * leak into a log line or crash report through a default data-class dump.
 */
data class CloudflareAccessConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val clientSecret: String = "",
) {
    /** True only once both credentials are actually present — [enabled] alone is not enough to authenticate with. */
    val isValid: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun toString(): String =
        "CloudflareAccessConfig(enabled=$enabled, clientId=${if (clientId.isBlank()) "" else "••••"}, clientSecret=${if (clientSecret.isBlank()) "" else "••••"})"
}
