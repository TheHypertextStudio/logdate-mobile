package app.logdate.server.identity

import app.logdate.server.config.RuntimeProfile
import java.net.URI

/**
 * Server-side configuration for AT Protocol identity surfaces.
 */
enum class HostedAccountDidMethod {
    PLC,
    WEB,
}

/**
 * Server-side configuration for AT Protocol identity surfaces.
 */
data class AtprotoIdentityConfig(
    val handleDomain: String = "logdate.app",
    val pdsServiceEndpoint: String = "https://logdate.app",
    val hostedAccountDidMethod: HostedAccountDidMethod = HostedAccountDidMethod.PLC,
    val publishHostedPlcOperations: Boolean = false,
    val plcDirectoryUrl: String = PUBLIC_PLC_DIRECTORY_URL,
    val recoveryKeysEnabled: Boolean = false,
) {
    init {
        require(handleDomain.isNotBlank()) { "handleDomain must not be blank" }
        require(isAcceptableServiceEndpoint(pdsServiceEndpoint)) {
            "pdsServiceEndpoint must use https; plain http is accepted outside production only for " +
                "loopback hosts ($LOOPBACK_HOSTS), so an emulator can reach a server running on the " +
                "developer's machine"
        }
        require(isAcceptableServiceEndpoint(plcDirectoryUrl.trim())) {
            "plcDirectoryUrl must use https; plain http is accepted outside production only for loopback " +
                "hosts ($LOOPBACK_HOSTS), so a rehearsal can run against a PLC directory on the same machine"
        }
    }

    val normalizedHandleDomain: String = handleDomain.trim().trim('.').lowercase()
    val serverDid: String = "did:web:$normalizedHandleDomain"
    val normalizedPlcDirectoryUrl: String = plcDirectoryUrl.trim().removeSuffix("/")

    /** Whether operations go to the public PLC directory, where nothing published can be taken back. */
    val targetsPublicPlcDirectory: Boolean = normalizedPlcDirectoryUrl.equals(PUBLIC_PLC_DIRECTORY_URL, ignoreCase = true)

    /** Whether hosted accounts get `did:plc` identities whose operations reach the PLC directory. */
    val publishesPlcOperations: Boolean =
        hostedAccountDidMethod == HostedAccountDidMethod.PLC && publishHostedPlcOperations

    /**
     * Whether people can register their own recovery key on a hosted `did:plc` identity and use it to
     * replace the server's key. It works from the server's own operation log, so it does not need
     * PLC publishing, and turning publishing on does not turn it on: it needs [recoveryKeysEnabled].
     */
    val supportsRecoveryKeys: Boolean =
        hostedAccountDidMethod == HostedAccountDidMethod.PLC && recoveryKeysEnabled

    companion object {
        fun fromEnvironment(
            handleDomain: String? = System.getenv("ATPROTO_HANDLE_DOMAIN"),
            pdsServiceEndpoint: String? = System.getenv("ATPROTO_PDS_SERVICE_URL") ?: System.getenv("ATPROTO_PDS_SERVICE_ENDPOINT"),
            hostedAccountDidMethod: String? = System.getenv("ATPROTO_HOSTED_DID_METHOD"),
            publishHostedPlcOperations: String? = System.getenv("ATPROTO_PLC_PUBLISH_ENABLED"),
            plcDirectoryUrl: String? = System.getenv("ATPROTO_PLC_DIRECTORY_URL"),
            recoveryKeysEnabled: String? = System.getenv("ATPROTO_RECOVERY_KEYS_ENABLED"),
        ): AtprotoIdentityConfig {
            require(publishHostedPlcOperations.isNullOrBlank() || publishHostedPlcOperations.trim().equals("false", ignoreCase = true)) {
                "ATPROTO_PLC_PUBLISH_ENABLED is retired; identity publication requires individual consent"
            }
            val normalizedHandleDomain =
                handleDomain
                    ?.trim()
                    ?.trim('.')
                    ?.lowercase()
                    .orEmpty()
                    .ifBlank { "logdate.app" }
            val resolvedEndpoint = pdsServiceEndpoint?.trim().orEmpty().ifBlank { "https://$normalizedHandleDomain" }
            val resolvedHostedDidMethod =
                when (hostedAccountDidMethod?.trim()?.lowercase()) {
                    null, "" -> HostedAccountDidMethod.PLC
                    "plc" -> HostedAccountDidMethod.PLC
                    "web" -> HostedAccountDidMethod.WEB
                    else -> throw IllegalArgumentException("Unsupported hosted DID method: $hostedAccountDidMethod")
                }
            return AtprotoIdentityConfig(
                handleDomain = normalizedHandleDomain,
                pdsServiceEndpoint = resolvedEndpoint.removeSuffix("/"),
                hostedAccountDidMethod = resolvedHostedDidMethod,
                publishHostedPlcOperations = false,
                plcDirectoryUrl =
                    plcDirectoryUrl
                        ?.trim()
                        .orEmpty()
                        .ifBlank { PUBLIC_PLC_DIRECTORY_URL }
                        .removeSuffix("/"),
                recoveryKeysEnabled = recoveryKeysEnabled?.trim()?.equals("true", ignoreCase = true) == true,
            )
        }
    }
}

/** The public PLC directory that the wider AT Protocol network resolves `did:plc` identities from. */
const val PUBLIC_PLC_DIRECTORY_URL: String = "https://plc.directory"

/** Loopback hosts a device or emulator uses to reach the machine running the server. */
internal val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

/**
 * The endpoint the server advertises to clients. https always; plain http only outside production
 * and only for a loopback host, which is the single case where the server and the client are the
 * same machine and TLS buys nothing. Production is additionally gated by
 * [app.logdate.server.config.ProductionConfigValidator].
 */
internal fun isAcceptableServiceEndpoint(
    endpoint: String,
    profile: RuntimeProfile = RuntimeProfile.fromEnvironment(),
): Boolean {
    if (endpoint.startsWith("https://")) return true
    if (profile.isProduction || !endpoint.startsWith("http://")) return false
    val host = runCatching { URI(endpoint).host }.getOrNull().orEmpty()
    return host in LOOPBACK_HOSTS
}
