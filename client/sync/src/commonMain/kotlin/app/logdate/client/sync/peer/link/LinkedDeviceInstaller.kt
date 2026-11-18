package app.logdate.client.sync.peer.link

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.OriginSessionVault
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.sameOwnerId
import app.logdate.client.networking.DeviceLinkApiException
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.datalayer.v2.LinkBundle
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import app.logdate.shared.model.devices.DeviceLinkRedeemResponse
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** Why a phone's account bundle was not installed on this device. The watch reports it in its state document. */
enum class LinkProblem {
    INCOMPLETE_BUNDLE,
    LINK_CODE_EXPIRED,
    LINK_CODE_INVALID,
    DEVICE_LIMIT_REACHED,
    BOUND_TO_ANOTHER_ACCOUNT,
    KEY_CONFLICT,
    SERVER_REFUSED,
    NETWORK,
    INTERRUPTED,
    SESSION_REVOKED,

    /** The device's own sign-in ran out unused. Nothing is wrong: its phone links it again without asking anyone. */
    SESSION_EXPIRED,
}

/** An owner and the server its rows belong to. */
data class OwnerScope(
    val ownerId: String,
    val origin: String,
)

/** Moves what a device recorded under one owner and server to another; returns how many queued changes stayed behind. */
fun interface OwnerRowsMover {
    suspend fun move(
        from: OwnerScope,
        to: OwnerScope,
        deviceId: String,
    ): Int
}

/** Redeems a link code at the account's server, whichever server this device is connected to. */
fun interface LinkCodeRedeemer {
    suspend fun redeem(
        origin: String,
        descriptor: ServerDescriptor?,
        code: String,
        deviceName: String,
    ): Result<DeviceLinkRedeemResponse>
}

sealed interface InstallOutcome {
    data class Installed(
        val accountId: String,
        val origin: String,
        val leftBehind: Int,
        val sessionId: String? = null,
    ) : InstallOutcome

    data class Failed(
        val problem: LinkProblem,
    ) : InstallOutcome
}

/** Installs a phone's account bundle on a linked device. */
interface LinkInstaller {
    /** Why this device cannot take [bundle] at all; checked before the exchange is confirmed. */
    suspend fun bindingProblem(bundle: LinkBundle): LinkProblem?

    suspend fun install(bundle: LinkBundle): InstallOutcome

    /** Finishes an install a stopped process left part way, or null when none was. */
    suspend fun resume(): InstallOutcome?
}

/**
 * Turns a phone's account bundle into this device's keys, session, owner and server, one journaled
 * step at a time. The link code is never written down: a process stopped before redeeming it ends the
 * install, and the phone links again with a new code.
 */
class LinkedDeviceInstaller(
    private val journal: KeyValueStorage,
    private val identityKeys: IdentityKeyManager,
    private val mediaKeys: MediaPayloadKeyProvider,
    private val owner: CanonicalOwnerProvider,
    private val config: LogDateConfigRepository,
    private val sessions: OriginSessionVault,
    private val redeemer: LinkCodeRedeemer,
    private val mover: OwnerRowsMover,
    private val deviceId: suspend () -> String,
    private val deviceName: () -> String,
    private val clock: Clock = Clock.System,
    private val persistConfiguration: suspend () -> Unit,
) : LinkInstaller {
    private val mutex = Mutex()

    override suspend fun bindingProblem(bundle: LinkBundle): LinkProblem? {
        val fields = CloudLinkFields.of(bundle) ?: return LinkProblem.INCOMPLETE_BUNDLE
        if (clock.now().toEpochMilliseconds() >= fields.codeExpiresAt) return LinkProblem.LINK_CODE_EXPIRED
        if (owner.hasBoundOwner() &&
            !sameOwnerId(owner.getCanonicalOwnerId(), fields.accountId)
        ) {
            return LinkProblem.BOUND_TO_ANOTHER_ACCOUNT
        }
        if (!identityKeys.canUseForAccount(fields.accountId)) return LinkProblem.BOUND_TO_ANOTHER_ACCOUNT
        if (!identityKeys.canInstallLinkedIdentityKey(fields.identityKey, fields.fingerprint)) return LinkProblem.KEY_CONFLICT
        if (!mediaKeys.canInstallAccountKey(fields.mediaKey)) return LinkProblem.KEY_CONFLICT
        return null
    }

    override suspend fun install(bundle: LinkBundle): InstallOutcome =
        mutex.withLock {
            read()?.let { pending ->
                resumePending(pending)?.let { outcome ->
                    if (outcome !is InstallOutcome.Installed ||
                        (sameOwnerId(outcome.accountId, bundle.accountId) && outcome.origin == bundle.serverOrigin?.trimEnd('/'))
                    ) {
                        return@withLock outcome
                    }
                }
            }
            bindingProblem(bundle)?.let { problem -> return@withLock refused(problem) }
            val fields = checkNotNull(CloudLinkFields.of(bundle))
            val entry =
                InstallJournal(
                    step = InstallStep.STARTED,
                    accountId = fields.accountId,
                    origin = fields.origin,
                    descriptor = fields.descriptorJson,
                    oldOwner = owner.getCanonicalOwnerId(),
                    oldOrigin = config.getCurrentBackendUrl().trimEnd('/'),
                    targetOwner = if (owner.hasBoundOwner()) owner.getCanonicalOwnerId() else fields.accountId,
                )
            save(entry)
            installKeys(fields)?.let { problem -> return@withLock abandon(problem) }
            save(entry.copy(step = InstallStep.KEYS_INSTALLED))
            val session =
                when (val redeemed = redeem(fields)) {
                    is Redeemed.Session -> redeemed
                    is Redeemed.Refused -> return@withLock abandon(redeemed.problem)
                }
            sessions.write(fields.origin, session.session)
            val saved = entry.copy(step = InstallStep.SESSION_SAVED, sessionId = session.sessionId)
            save(saved)
            finish(saved)
        }

    override suspend fun resume(): InstallOutcome? =
        mutex.withLock {
            val pending = read() ?: return@withLock null
            resumePending(pending) ?: abandon(LinkProblem.INTERRUPTED)
        }

    private suspend fun resumePending(pending: InstallJournal): InstallOutcome? {
        if (pending.step == InstallStep.STARTED) {
            abandon(LinkProblem.INTERRUPTED)
            return null
        }
        if (pending.step == InstallStep.KEYS_INSTALLED) {
            val session = sessions.read(pending.origin)
            if (!sameOwnerId(session?.accountId, pending.accountId)) {
                abandon(LinkProblem.INTERRUPTED)
                return null
            }
            val saved = pending.copy(step = InstallStep.SESSION_SAVED)
            save(saved)
            return finish(saved)
        }
        return finish(pending)
    }

    private suspend fun finish(entry: InstallJournal): InstallOutcome {
        if (!sameOwnerId(entry.targetOwner, entry.accountId) ||
            (owner.hasBoundOwner() && owner.getCanonicalOwnerId() != entry.targetOwner)
        ) {
            return abandon(LinkProblem.BOUND_TO_ANOTHER_ACCOUNT)
        }
        if (!identityKeys.canUseForAccount(entry.accountId)) return abandon(LinkProblem.BOUND_TO_ANOTHER_ACCOUNT)
        val session = sessions.read(entry.origin)
        if (session == null ||
            !sameOwnerId(session.accountId, entry.accountId) ||
            session.accessToken.isBlank() ||
            session.refreshToken.isBlank()
        ) {
            return abandon(LinkProblem.SESSION_REVOKED)
        }
        val moved =
            if (entry.step == InstallStep.SESSION_SAVED) {
                moveRows(entry) ?: return InstallOutcome.Failed(LinkProblem.INTERRUPTED)
            } else {
                entry
            }
        if (!owner.adoptRemoteOwnerIfUninitialized(moved.targetOwner)) {
            return abandon(LinkProblem.BOUND_TO_ANOTHER_ACCOUNT)
        }
        config.updateBackendUrl(moved.origin)
        config.updateServerDescriptor(moved.descriptor?.let(::descriptorFromText))
        persistConfiguration()
        journal.remove(KEY)
        Napier.i("This device is linked to its phone's account; ${moved.leftBehind} queued change(s) stayed with its earlier owner")
        return InstallOutcome.Installed(moved.accountId, moved.origin, moved.leftBehind, moved.sessionId)
    }

    private suspend fun moveRows(entry: InstallJournal): InstallJournal? {
        val leftBehind =
            try {
                mover.move(OwnerScope(entry.oldOwner, entry.oldOrigin), OwnerScope(entry.targetOwner, entry.origin), deviceId())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Moving this device's earlier captures to its account failed; it is finished when LogDate next starts", e)
                return null
            }
        val moved = entry.copy(step = InstallStep.ROWS_MOVED, leftBehind = leftBehind)
        save(moved)
        return moved
    }

    private suspend fun installKeys(fields: CloudLinkFields): LinkProblem? =
        try {
            identityKeys.installLinkedIdentityKey(fields.identityKey, fields.fingerprint)
            if (identityKeys.bindToAccount(fields.accountId)) {
                mediaKeys.installAccountKey(fields.mediaKey)
                null
            } else {
                LinkProblem.BOUND_TO_ANOTHER_ACCOUNT
            }
        } catch (e: IllegalArgumentException) {
            Napier.e("The keys from the phone do not match their fingerprint or this device's keys", e)
            LinkProblem.KEY_CONFLICT
        } catch (e: IllegalStateException) {
            Napier.e("This device already has a different identity key", e)
            LinkProblem.KEY_CONFLICT
        }

    private suspend fun redeem(fields: CloudLinkFields): Redeemed {
        val response =
            redeemer.redeem(fields.origin, fields.descriptor, fields.code, deviceName()).getOrElse { error ->
                if (error is CancellationException) throw error
                Napier.w("The phone's link code was not redeemed", error)
                return Redeemed.Refused(problemFor(error))
            }
        if (!sameOwnerId(response.accountId, fields.accountId) ||
            response.tokens.accessToken.isBlank() ||
            response.tokens.refreshToken.isBlank()
        ) {
            return Redeemed.Refused(LinkProblem.SERVER_REFUSED)
        }
        return Redeemed.Session(
            UserSession(response.tokens.accessToken, response.tokens.refreshToken, response.accountId),
            response.sessionId,
        )
    }

    private fun problemFor(error: Throwable): LinkProblem {
        if (error !is DeviceLinkApiException) return LinkProblem.NETWORK
        return when (error.code) {
            DeviceLinkErrorCodes.LINK_CODE_INVALID -> LinkProblem.LINK_CODE_INVALID
            DeviceLinkErrorCodes.DEVICE_LIMIT_REACHED -> LinkProblem.DEVICE_LIMIT_REACHED
            else -> LinkProblem.SERVER_REFUSED
        }
    }

    private fun refused(problem: LinkProblem): InstallOutcome {
        Napier.w("The phone's account bundle was not installed: $problem")
        return InstallOutcome.Failed(problem)
    }

    private suspend fun abandon(problem: LinkProblem): InstallOutcome {
        journal.remove(KEY)
        Napier.w("Linking this device to its phone's account stopped with $problem; the phone links it again")
        return InstallOutcome.Failed(problem)
    }

    private suspend fun save(entry: InstallJournal) {
        val text = WearJson.json.encodeToString(InstallJournal.serializer(), entry)
        journal.putString(KEY, text)
        check(journal.getString(KEY) == text) { "Could not write the link install journal" }
    }

    private suspend fun read(): InstallJournal? {
        val text = journal.getString(KEY) ?: return null
        return try {
            WearJson.json.decodeFromString(InstallJournal.serializer(), text)
        } catch (e: IllegalArgumentException) {
            Napier.e("The link install journal could not be read; the phone links this device again", e)
            journal.remove(KEY)
            null
        }
    }

    private sealed interface Redeemed {
        data class Session(
            val session: UserSession,
            val sessionId: String,
        ) : Redeemed

        data class Refused(
            val problem: LinkProblem,
        ) : Redeemed
    }

    @Serializable
    private enum class InstallStep {
        STARTED,
        KEYS_INSTALLED,
        SESSION_SAVED,
        ROWS_MOVED,
    }

    @Serializable
    private data class InstallJournal(
        val step: InstallStep,
        val accountId: String,
        val origin: String,
        val descriptor: String? = null,
        val oldOwner: String,
        val oldOrigin: String,
        val leftBehind: Int = 0,
        val sessionId: String? = null,
        val targetOwner: String = accountId,
    )

    private class CloudLinkFields(
        val accountId: String,
        val origin: String,
        val descriptor: ServerDescriptor?,
        val descriptorJson: String?,
        val code: String,
        val codeExpiresAt: Long,
        val identityKey: ByteArray,
        val fingerprint: String,
        val mediaKey: ByteArray,
    ) {
        companion object {
            fun of(bundle: LinkBundle): CloudLinkFields? {
                if (bundle.mode != LinkMode.CLOUD) return null
                if (bundle.accountId.isNullOrBlank() ||
                    bundle.serverOrigin.isNullOrBlank() ||
                    bundle.linkCode.isNullOrBlank() ||
                    bundle.identityKeyFingerprint.isNullOrBlank()
                ) {
                    return null
                }
                return CloudLinkFields(
                    accountId = bundle.accountId,
                    origin = bundle.serverOrigin.trimEnd('/'),
                    descriptor = bundle.serverDescriptor?.let(::descriptorFromJson),
                    descriptorJson = bundle.serverDescriptor?.toString(),
                    code = bundle.linkCode,
                    codeExpiresAt = bundle.linkCodeExpiresAt ?: return null,
                    identityKey = bundle.identityKey?.let(LinkWire::decodeKey) ?: return null,
                    fingerprint = bundle.identityKeyFingerprint,
                    mediaKey = bundle.mediaKey?.let(LinkWire::decodeKey) ?: return null,
                )
            }
        }
    }

    private companion object {
        const val KEY = "linked_device.install_journal.v1"
    }
}

private fun descriptorFromJson(element: JsonElement): ServerDescriptor? =
    try {
        WearJson.json.decodeFromJsonElement(ServerDescriptor.serializer(), element)
    } catch (e: IllegalArgumentException) {
        Napier.w("The phone's server descriptor could not be read; the server's features are read again when it is next contacted", e)
        null
    }

private fun descriptorFromText(text: String): ServerDescriptor? =
    try {
        WearJson.json.decodeFromString(ServerDescriptor.serializer(), text)
    } catch (e: IllegalArgumentException) {
        Napier.w("The saved server descriptor could not be read", e)
        null
    }
