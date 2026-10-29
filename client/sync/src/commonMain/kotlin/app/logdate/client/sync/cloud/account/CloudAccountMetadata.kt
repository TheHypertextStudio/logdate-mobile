package app.logdate.client.sync.cloud.account

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.shared.model.CloudAccount
import kotlin.uuid.Uuid

internal object CloudAccountStorageKeys {
    const val ACCESS_TOKEN = "cloud_access_token"
    const val REFRESH_TOKEN = "cloud_refresh_token"
    const val ACCOUNT_ID = "cloud_account_id"
    const val ACCOUNT_USERNAME = "cloud_username"
    const val ACCOUNT_DISPLAY_NAME = "cloud_display_name"
    const val ACCOUNT_BIO = "cloud_account_bio"
    const val ACCOUNT_ORIGINAL_BIO = "cloud_account_original_bio"
    const val ACCOUNT_DID = "cloud_account_did"
    const val ACCOUNT_HANDLE = "cloud_account_handle"
    const val USER_ID = "cloud_user_id"
    const val CREATED_AT = "cloud_created_at"
    const val UPDATED_AT = "cloud_updated_at"
    const val PASSKEY_IDS = "cloud_passkey_ids"
    const val ACCOUNT_STORAGE_MODE = "cloud_account_storage_mode"
    const val ACCOUNT_IDENTITY_KIND = "cloud_account_identity_kind"
}

internal suspend fun KeyValueStorage.storeCloudAccount(
    account: CloudAccount,
    backendUrl: String,
) {
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ID, backendUrl), account.id.toString())
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_USERNAME, backendUrl), account.username)
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DISPLAY_NAME, backendUrl), account.displayName)
    account.bio?.let { putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_BIO, backendUrl), it) }
        ?: remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_BIO, backendUrl))
    account.originalBio?.let { putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ORIGINAL_BIO, backendUrl), it) }
        ?: remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ORIGINAL_BIO, backendUrl))
    account.did?.let { putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DID, backendUrl), it) }
        ?: remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DID, backendUrl))
    account.handle?.let { putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_HANDLE, backendUrl), it) }
        ?: remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_HANDLE, backendUrl))
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.USER_ID, backendUrl), account.userId.toString())
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.CREATED_AT, backendUrl), account.createdAt.toString())
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.UPDATED_AT, backendUrl), account.updatedAt.toString())
    putString(
        cloudAccountScopedKey(CloudAccountStorageKeys.PASSKEY_IDS, backendUrl),
        account.passkeyCredentialIds.joinToString(",") { it.toString() },
    )
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_STORAGE_MODE, backendUrl), account.storageMode.wireValue)
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_IDENTITY_KIND, backendUrl), account.identityKind.wireValue)
}

private val legacyAccountMetadataKeys =
    listOf(
        CloudAccountStorageKeys.ACCOUNT_USERNAME,
        CloudAccountStorageKeys.ACCOUNT_DISPLAY_NAME,
        CloudAccountStorageKeys.ACCOUNT_BIO,
        CloudAccountStorageKeys.ACCOUNT_ORIGINAL_BIO,
        CloudAccountStorageKeys.ACCOUNT_DID,
        CloudAccountStorageKeys.ACCOUNT_HANDLE,
        CloudAccountStorageKeys.USER_ID,
        CloudAccountStorageKeys.CREATED_AT,
        CloudAccountStorageKeys.UPDATED_AT,
        CloudAccountStorageKeys.PASSKEY_IDS,
        CloudAccountStorageKeys.ACCOUNT_STORAGE_MODE,
        CloudAccountStorageKeys.ACCOUNT_IDENTITY_KIND,
    )

internal suspend fun KeyValueStorage.migrateLegacyAccountMetadata(backendUrl: String) {
    if (getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ID, backendUrl)) != null) return
    val accountId = getString(CloudAccountStorageKeys.ACCOUNT_ID) ?: return
    legacyAccountMetadataKeys.forEach { key -> copyLegacyValue(key, cloudAccountScopedKey(key, backendUrl)) }
    putString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ID, backendUrl), accountId)
    (
        legacyAccountMetadataKeys +
            listOf(CloudAccountStorageKeys.ACCOUNT_ID, CloudAccountStorageKeys.ACCESS_TOKEN, CloudAccountStorageKeys.REFRESH_TOKEN)
    ).forEach { remove(it) }
}

internal fun parseCloudPasskeyIds(value: String?): List<Uuid> =
    value?.split(",")?.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.orEmpty()

private suspend fun KeyValueStorage.copyLegacyValue(
    sourceKey: String,
    destinationKey: String,
) {
    getString(sourceKey)?.let { value ->
        putString(destinationKey, value)
    }
}

internal fun cloudAccountScopedKey(
    baseKey: String,
    backendUrl: String,
): String = "${baseKey}_${backendUrl.trim().removePrefix("https://").removePrefix("http://").replace(Regex("[^A-Za-z0-9]"), "_")}"
