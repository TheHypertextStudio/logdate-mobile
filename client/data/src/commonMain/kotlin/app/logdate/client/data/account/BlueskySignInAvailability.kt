package app.logdate.client.data.account

import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerProtocolFeature

internal fun blueskySignInAvailable(
    localEnabled: Boolean,
    backend: String,
    descriptor: ServerDescriptor?,
): Boolean {
    if (!localEnabled || descriptor == null || !Regex("https://[^/?#@\\s]+").matches(backend)) return false
    return descriptor.serverOrigin == backend &&
        descriptor.apiBaseUrl == "$backend/api/v1" &&
        !descriptor.passkey?.rpId.isNullOrBlank() &&
        descriptor.hasCapability(ServerCapability.AUTH_PASSKEY) &&
        descriptor.hasProtocolFeature(ServerProtocolFeature.BLUESKY_SIGN_IN_V1) &&
        descriptor.hasProtocolFeature(ServerProtocolFeature.PERSONAL_SPACE_V1) &&
        descriptor.hasProtocolFeature(ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1)
}
