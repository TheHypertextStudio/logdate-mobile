package app.logdate.client.sync.v2.link

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.device.crypto.CryptoManager
import app.logdate.client.device.crypto.IdentityKeyFingerprint
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.networking.DeviceLinkApiException
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.datalayer.v2.LinkBundle
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerProtocolFeature
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.IOException

/** The account keys a phone hands to a watch it links. Never the recovery phrase. */
interface AccountKeySource {
    fun changes(): Flow<Unit> = kotlinx.coroutines.flow.flowOf(Unit)

    suspend fun canUseForAccount(accountId: String): Boolean

    suspend fun identityKey(): ByteArray?

    fun fingerprint(identityKey: ByteArray): String

    suspend fun mediaKey(): ByteArray
}

class DeviceAccountKeySource(
    private val identityKeys: IdentityKeyManager,
    private val mediaKeys: MediaPayloadKeyProvider,
    private val cryptoManager: CryptoManager,
) : AccountKeySource {
    override fun changes(): Flow<Unit> = identityKeys.observeIdentityChanges()

    override suspend fun canUseForAccount(accountId: String): Boolean = identityKeys.canUseForAccount(accountId)

    override suspend fun identityKey(): ByteArray? = if (identityKeys.hasIdentityKey()) identityKeys.getIdentityKey() else null

    override fun fingerprint(identityKey: ByteArray): String = IdentityKeyFingerprint.of(identityKey, cryptoManager)

    override suspend fun mediaKey(): ByteArray = mediaKeys.getOrCreateKey()
}

/**
 * Links a watch to this phone's account when the phone is signed in to a server that links devices,
 * has an identity key, and the watch can take an account link; otherwise pairs it.
 */
class CloudLinkBundleSource(
    private val sessions: SessionStorage,
    private val config: LogDateConfigRepository,
    private val minter: LinkCodeMinter,
    private val keys: AccountKeySource,
    private val v2Enabled: suspend () -> Boolean,
) : LinkBundleSource {
    override fun changes(): Flow<Unit> =
        combine(sessions.getSessionFlow(), config.serverDescriptor, config.backendUrl, keys.changes()) { _, _, _, _ -> }

    override suspend fun plan(watchFeatures: Set<String>): LinkPlan {
        val bound = sessions.getOriginBoundSession() ?: return LinkPlan(LinkMode.PAIR)
        if (config.getCurrentBackendUrl() != bound.origin ||
            !serverLinksDevices() ||
            WearFeatures.LINK_CLOUD !in watchFeatures
        ) {
            return LinkPlan(LinkMode.PAIR)
        }
        val descriptor = config.getCurrentServerDescriptor()
        if (identityKeyOrNull(bound.session.accountId) == null) return LinkPlan(LinkMode.PAIR)
        if (sessions.getOriginBoundSession() != bound ||
            config.getCurrentBackendUrl() != bound.origin ||
            config.getCurrentServerDescriptor() != descriptor
        ) {
            return LinkPlan(LinkMode.PAIR)
        }
        return LinkPlan(LinkMode.CLOUD, bound.session.accountId, bound.origin)
    }

    override suspend fun bundle(plan: LinkPlan): LinkBundleResult {
        val flags = mapOf(FeatureFlag.WATCH_EXPERIENCE_V2.key to v2Enabled())
        if (plan.mode == LinkMode.PAIR) return LinkBundleResult.Ready(LinkBundle(mode = LinkMode.PAIR, flags = flags))
        if (flags[FeatureFlag.WATCH_EXPERIENCE_V2.key] != true) return LinkBundleResult.Unavailable(PhoneLinkFailure.WATCH_CANNOT_LINK)
        val bound = sessions.getOriginBoundSession()
        if (bound == null ||
            bound.session.accountId != plan.accountId ||
            bound.origin != plan.serverOrigin
        ) {
            return LinkBundleResult.Unavailable(PhoneLinkFailure.SIGNED_OUT)
        }
        val origin = config.getCurrentBackendUrl()
        if (origin != bound.origin) return LinkBundleResult.Unavailable(PhoneLinkFailure.SIGNED_OUT)
        val descriptorSnapshot = config.getCurrentServerDescriptor()
        val identity = identityKeyOrNull(bound.session.accountId) ?: return LinkBundleResult.Unavailable(PhoneLinkFailure.KEYS_UNAVAILABLE)
        val media = mediaKeyOrNull() ?: return LinkBundleResult.Unavailable(PhoneLinkFailure.KEYS_UNAVAILABLE)
        if (sessions.getOriginBoundSession() != bound || config.getCurrentBackendUrl() != origin) {
            return LinkBundleResult.Unavailable(PhoneLinkFailure.SIGNED_OUT)
        }
        val code = minter.mint().getOrElse { error -> return mintFailure(error) }
        val current = sessions.getOriginBoundSession()
        if (current?.origin != bound.origin ||
            current.session.accountId != bound.session.accountId ||
            config.getCurrentBackendUrl() != origin ||
            config.getCurrentServerDescriptor() != descriptorSnapshot
        ) {
            return LinkBundleResult.Unavailable(PhoneLinkFailure.SIGNED_OUT)
        }
        val descriptor = descriptorSnapshot?.let { WearJson.json.encodeToJsonElement(ServerDescriptor.serializer(), it) }
        return LinkBundleResult.Ready(
            LinkBundle(
                mode = LinkMode.CLOUD,
                accountId = bound.session.accountId,
                serverOrigin = bound.origin,
                serverDescriptor = descriptor,
                linkCode = code.code,
                linkCodeExpiresAt = code.expiresAt,
                identityKey = LinkWire.encodeKey(identity),
                identityKeyFingerprint = keys.fingerprint(identity),
                mediaKey = LinkWire.encodeKey(media),
                flags = flags,
            ),
        )
    }

    private suspend fun identityKeyOrNull(accountId: String): ByteArray? =
        try {
            if (keys.canUseForAccount(accountId)) keys.identityKey() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("This phone's identity key could not be read; account linking waits for it", e)
            null
        }

    private suspend fun mediaKeyOrNull(): ByteArray? =
        try {
            keys.mediaKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("This phone's media key could not be read; the watch is linked once it can", e)
            null
        }

    private fun serverLinksDevices(): Boolean =
        config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.DEVICE_LINKING_V1) == true

    private fun mintFailure(error: Throwable): LinkBundleResult.Unavailable {
        if (error is CancellationException) throw error
        Napier.w("A link code could not be minted for the watch", error)
        val refused = error as? DeviceLinkApiException
        if (refused?.code == DeviceLinkErrorCodes.DEVICE_LIMIT_REACHED) {
            return LinkBundleResult.Unavailable(PhoneLinkFailure.DEVICE_LIMIT_REACHED)
        }
        // No answer, or an answer that the server itself is failing; a refusal is the server working.
        val unreachable = error is IOException || (refused != null && refused.status >= HTTP_SERVER_ERROR)
        return LinkBundleResult.Unavailable(PhoneLinkFailure.LINK_CODE_UNAVAILABLE, serverUnreachable = unreachable)
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500
    }
}
