package app.logdate.shared.model

import kotlin.uuid.Uuid

/** Keeps the server's account identity when adapting to the older cloud-account model. */
fun LogDateAccount.toCloudAccount(): CloudAccount =
    CloudAccount(
        id = id,
        userId = id,
        username = username,
        displayName = displayName,
        did = did,
        handle = handle,
        bio = bio,
        originalBio = originalBio,
        createdAt = createdAt,
        updatedAt = updatedAt,
        // This legacy summary accepts UUIDs; the passkey API retains the opaque WebAuthn IDs.
        passkeyCredentialIds = passkeyCredentialIds.mapNotNull(Uuid::parseOrNull),
        storageMode = storageMode,
        identityKind = identityKind,
    )
