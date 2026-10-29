package app.logdate.server

import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.ServerProtocolFeature

data class ServerDescriptorConfig(
    val deploymentKind: DeploymentKind = DeploymentKind.SELF_HOSTED,
    val encryptedAccountKeysEnabled: Boolean = false,
    val personalSpaceEnabled: Boolean = false,
    /**
     * Whether existing accounts can move their journals into their personal spaces through
     * `/api/v1/space-migration`. It takes effect only together with [personalSpaceEnabled].
     */
    val personalSpaceMigrationEnabled: Boolean = false,
    val displayName: String = defaultDisplayName(DeploymentKind.SELF_HOSTED),
    val privacyPolicyUrl: String? = null,
    val termsOfServiceUrl: String? = null,
) {
    /**
     * The storage mode an account created now starts in. With personal spaces on, new accounts keep
     * their data there from the start, since they have nothing to migrate; accounts that already
     * exist keep the mode they have.
     */
    val newAccountStorageMode: AccountStorageMode
        get() = if (personalSpaceEnabled) AccountStorageMode.PERSONAL_SPACE_V1 else AccountStorageMode.LEGACY

    /** Whether this server moves existing journals into personal spaces: both flags are on. */
    val movesJournalsToPersonalSpaces: Boolean
        get() = personalSpaceEnabled && personalSpaceMigrationEnabled

    fun toDescriptor(
        identityConfig: AtprotoIdentityConfig,
        webAuthnRpId: String,
        webAuthnRpName: String,
        diagnosticReportsEnabled: Boolean = false,
        blueskySignInEnabled: Boolean = false,
        atprotoConnectionEnabled: Boolean = false,
    ): ServerDescriptor {
        val serverOrigin = identityConfig.pdsServiceEndpoint.trimEnd('/')
        val capabilities =
            buildList {
                add(ServerCapability.AUTH_PASSKEY)
                add(ServerCapability.SYNC_CONTENT)
                add(ServerCapability.SYNC_MEDIA)
                add(ServerCapability.ATPROTO_IDENTITY)
                add(ServerCapability.ATPROTO_OAUTH)
                if (deploymentKind == DeploymentKind.FIRST_PARTY) {
                    add(ServerCapability.BILLING_SUBSCRIPTIONS)
                    add(ServerCapability.MANAGED_QUOTA)
                    add(ServerCapability.CLOUD_TRANSCRIPTION)
                }
            }

        return ServerDescriptor(
            serverOrigin = serverOrigin,
            apiBaseUrl = "$serverOrigin/api/v1",
            apiVersion = "v1",
            deploymentKind = deploymentKind,
            displayName = displayName,
            handleDomain = identityConfig.normalizedHandleDomain,
            passkey = ServerPasskeyConfig(rpId = webAuthnRpId, rpName = webAuthnRpName),
            capabilities = capabilities,
            protocolFeatures =
                buildList {
                    add(ServerProtocolFeature.CANONICAL_OWNER_BINDING_V1)
                    if (blueskySignInEnabled && encryptedAccountKeysEnabled && personalSpaceEnabled) {
                        add(ServerProtocolFeature.BLUESKY_SIGN_IN_V1)
                        if (atprotoConnectionEnabled) add(ServerProtocolFeature.ATPROTO_CONNECTION_V1)
                    }
                    add(ServerProtocolFeature.RICH_DRAFTS_V1)
                    add(ServerProtocolFeature.JOURNAL_MERGE_V1)
                    add(ServerProtocolFeature.DEVICE_LINKING_V1)
                    add(ServerProtocolFeature.CONDITIONAL_CREATE_V1)
                    add(ServerProtocolFeature.MOMENTS_V1)
                    if (diagnosticReportsEnabled) {
                        add(ServerProtocolFeature.DIAGNOSTIC_REPORTS_V1)
                    }
                    if (encryptedAccountKeysEnabled) {
                        add(ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1)
                    }
                    if (personalSpaceEnabled) {
                        add(ServerProtocolFeature.PERSONAL_SPACE_V1)
                    }
                    if (movesJournalsToPersonalSpaces) {
                        add(ServerProtocolFeature.PERSONAL_SPACE_MIGRATION_V1)
                    }
                    if (identityConfig.publishesPlcOperations) {
                        add(ServerProtocolFeature.ATPROTO_PLC_PUBLISHING_V1)
                    }
                    if (identityConfig.supportsRecoveryKeys) {
                        add(ServerProtocolFeature.ATPROTO_RECOVERY_KEYS_V1)
                    }
                },
            privacyPolicyUrl = privacyPolicyUrl,
            termsOfServiceUrl = termsOfServiceUrl,
        )
    }

    companion object {
        fun fromEnvironment(
            deploymentKind: String? = System.getenv("LOGDATE_DEPLOYMENT_KIND"),
            displayName: String? = System.getenv("LOGDATE_SERVER_DISPLAY_NAME"),
            privacyPolicyUrl: String? = System.getenv("LOGDATE_PRIVACY_POLICY_URL"),
            termsOfServiceUrl: String? = System.getenv("LOGDATE_TERMS_OF_SERVICE_URL"),
            encryptedAccountKeysEnabled: Boolean = System.getenv("LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED") == "true",
            personalSpaceEnabled: Boolean = System.getenv("LOGDATE_PERSONAL_SPACE_ENABLED") == "true",
            personalSpaceMigrationEnabled: Boolean = System.getenv("LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED") == "true",
        ): ServerDescriptorConfig {
            val resolvedDeploymentKind =
                when (deploymentKind?.trim()?.lowercase()) {
                    null, "", "self_hosted", "self-hosted" -> DeploymentKind.SELF_HOSTED
                    "first_party", "first-party", "firstparty" -> DeploymentKind.FIRST_PARTY
                    else -> throw IllegalArgumentException("Unsupported deployment kind: $deploymentKind")
                }
            return ServerDescriptorConfig(
                deploymentKind = resolvedDeploymentKind,
                encryptedAccountKeysEnabled = encryptedAccountKeysEnabled,
                personalSpaceEnabled = personalSpaceEnabled,
                personalSpaceMigrationEnabled = personalSpaceMigrationEnabled,
                displayName =
                    displayName
                        ?.trim()
                        .orEmpty()
                        .ifBlank { defaultDisplayName(resolvedDeploymentKind) },
                privacyPolicyUrl =
                    privacyPolicyUrl
                        ?.trim()
                        .orEmpty()
                        .ifBlank { defaultPrivacyPolicyUrl(resolvedDeploymentKind) },
                termsOfServiceUrl =
                    termsOfServiceUrl
                        ?.trim()
                        .orEmpty()
                        .ifBlank { defaultTermsOfServiceUrl(resolvedDeploymentKind) },
            )
        }

        private fun defaultDisplayName(deploymentKind: DeploymentKind): String =
            when (deploymentKind) {
                DeploymentKind.FIRST_PARTY -> "LogDate Cloud"
                DeploymentKind.SELF_HOSTED -> "LogDate Server"
            }

        private fun defaultPrivacyPolicyUrl(deploymentKind: DeploymentKind): String? =
            when (deploymentKind) {
                DeploymentKind.FIRST_PARTY -> "https://logdate.app/privacy"
                DeploymentKind.SELF_HOSTED -> null
            }

        private fun defaultTermsOfServiceUrl(deploymentKind: DeploymentKind): String? =
            when (deploymentKind) {
                DeploymentKind.FIRST_PARTY -> "https://logdate.app/terms"
                DeploymentKind.SELF_HOSTED -> null
            }
    }
}
