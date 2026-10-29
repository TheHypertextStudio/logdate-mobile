package app.logdate.client.sync.cloud.account

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.SessionStorage
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.CloudAccount
import app.logdate.shared.model.LogDateAccount
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The signed-in account as its server last described it at `/auth/me`, for the server ([origin])
 * and account ([accountId]) it was read from. Sync reads it again each time it starts.
 */
@Serializable
data class ServerReportedAccount(
    val origin: String,
    val accountId: String,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val storageMode: AccountStorageMode,
    val identityKind: AccountIdentityKind = AccountIdentityKind.UNRECOGNIZED,
) {
    /** True when this describes [accountId] on [origin]. */
    fun describes(
        origin: String,
        accountId: String,
    ): Boolean = this.accountId == accountId && this.origin == origin.trimEnd('/')

    /** This account as a [CloudAccount], or null when its ID isn't a UUID. A linked device has no passkeys of its own. */
    fun toCloudAccount(): CloudAccount? {
        val id = Uuid.parseOrNull(accountId) ?: return null
        return CloudAccount(
            id = id,
            username = username,
            displayName = displayName,
            did = did,
            handle = handle,
            userId = id,
            createdAt = createdAt,
            updatedAt = updatedAt,
            passkeyCredentialIds = emptyList(),
            storageMode = storageMode,
            identityKind = identityKind,
        )
    }

    companion object {
        /** [account] as the server at [origin] reported it. */
        fun of(
            origin: String,
            account: LogDateAccount,
        ): ServerReportedAccount =
            ServerReportedAccount(
                origin = origin.trimEnd('/'),
                accountId = account.id.toString(),
                username = account.username,
                displayName = account.displayName,
                did = account.did,
                handle = account.handle,
                createdAt = account.createdAt,
                updatedAt = account.updatedAt,
                storageMode = account.storageMode,
                identityKind = account.identityKind,
            )
    }
}

/**
 * Remembers the account the server last described, so sync and the account screens can read its DID
 * and storage mode without asking the server each time. It holds one account: the one this
 * installation is signed in to.
 */
class ServerAccountStore(
    private val storage: KeyValueStorage,
) {
    suspend fun read(): ServerReportedAccount? = storage.getString(KEY)?.let(::decode)

    fun observe(): Flow<ServerReportedAccount?> = storage.observeString(KEY).map { text -> text?.let(::decode) }

    suspend fun write(account: ServerReportedAccount) =
        storage.putStringOrThrow(KEY, json.encodeToString(ServerReportedAccount.serializer(), account))

    suspend fun clear() = storage.remove(KEY)

    private fun decode(text: String): ServerReportedAccount? =
        try {
            json.decodeFromString(ServerReportedAccount.serializer(), text)
        } catch (e: IllegalArgumentException) {
            Napier.e("The saved account could not be read; it is read from the server again when sync next starts", e)
            null
        }

    private companion object {
        const val KEY = "cloud.server_reported_account.v1"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The storage mode of the account this device is signed in to, as its server last reported it. It is
 * null when no one is signed in, and [AccountStorageMode.UNRECOGNIZED] when the server hasn't reported
 * a mode for this account on this server yet: a device never guesses legacy. Transports choose how to
 * sync from it.
 */
class ServerAccountStorageMode(
    private val sessions: SessionStorage,
    private val accounts: ServerAccountStore,
) {
    suspend fun currentMode(): AccountStorageMode? {
        val bound = sessions.getOriginBoundSession() ?: return null
        val reported = accounts.read()?.takeIf { it.describes(bound.origin, bound.session.accountId) }
        return reported?.storageMode ?: AccountStorageMode.UNRECOGNIZED
    }
}
