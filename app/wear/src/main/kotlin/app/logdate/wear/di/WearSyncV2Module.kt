package app.logdate.wear.di

import android.content.Context
import app.logdate.client.datastore.LogDateConfigDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.AndroidAppInfoProvider
import app.logdate.client.device.crypto.link.BouncyCastleLinkHandshake
import app.logdate.client.domain.activity.ActivityTracker
import app.logdate.client.domain.activity.toSnapshot
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.datalayer.v2.LinkNonceLedger
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.link.LinkInstaller
import app.logdate.client.sync.peer.link.PairKeyVault
import app.logdate.client.sync.peer.link.SecureStoragePairKeyVault
import app.logdate.client.sync.peer.link.WatchLinkExchange
import app.logdate.shared.model.ServerProtocolFeature
import app.logdate.wear.data.storage.StorageSpaceChecker
import app.logdate.wear.sync.v2.WatchOwnState
import app.logdate.wear.sync.v2.delivery.CloudRelease
import app.logdate.wear.sync.v2.delivery.LinkedDataLayerTransportV2
import app.logdate.wear.sync.v2.delivery.WatchCaptureDelivery
import app.logdate.wear.sync.v2.delivery.WatchCaptureProtocol
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import app.logdate.wear.sync.v2.delivery.WatchMomentDelivery
import app.logdate.wear.sync.v2.delivery.WatchMomentProtocol
import app.logdate.wear.sync.v2.delivery.WatchNoteDelivery
import app.logdate.wear.sync.v2.delivery.WatchNotesV2
import app.logdate.wear.sync.v2.delivery.WatchReceiptApplier
import app.logdate.wear.sync.v2.delivery.WatchRouteConditions
import app.logdate.wear.sync.v2.link.WatchLinkCoordinator
import app.logdate.wear.sync.v2.link.WatchLinkStore
import app.logdate.wear.sync.v2.localNodeIdOrNull
import app.logdate.wear.sync.v2.media.GoogleMediaRangeSource
import app.logdate.wear.sync.v2.watchCounts
import com.google.android.gms.wearable.Wearable
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import org.koin.core.parameter.parametersOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin module for the watch's version 2 sync: linking, delivery and the library window. */
val wearSyncV2Module =
    module {
        includes(wearWatchWindowModule, wearPhoneMediaModule, wearRemovalModule)
        single<PairKeyVault> { SecureStoragePairKeyVault(get(), prefix = "watch.link.pair_key.") }
        single {
            WatchLinkStore(
                storage = get(),
                pairKeys = get(),
                onLinked = get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>()::reset,
            )
        }
        single { WatchLinkExchange(handshake = BouncyCastleLinkHandshake(), nonces = LinkNonceLedger()) }
        single {
            val nodeClient = Wearable.getNodeClient(get<Context>())
            val flags = get<FeatureFlagStore>()
            val config = get<LogDateConfigDataSource>()
            WatchLinkCoordinator(
                exchange = get(),
                store = get(),
                v2Enabled = {
                    val removal = get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>().state()
                    flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) && (removal == null || removal == "removed")
                },
                localNodeId = { nodeClient.localNodeIdOrNull() },
                installer = get<LinkInstaller>(),
                installScope = get<CoroutineScope>(),
                beforeInstall = { config.awaitConfigurationLoaded() },
                afterInstall = { readLinkedAccount(get<DefaultSyncManager>(WatchCloud)) },
            )
        }
        single {
            val nodeClient = Wearable.getNodeClient(get<Context>())
            val flags = get<FeatureFlagStore>()
            WatchDeliveryPeer(
                links = get(),
                sessions = get(),
                config = get(),
                localNodeId = { nodeClient.localNodeIdOrNull() },
                active = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) },
                removed = get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>()::removed,
            )
        }
        factory { parameters ->
            val peer = parameters.get<PeerDeliveryContext>()
            GoogleMediaRangeSource(get(), get<LinkedDataLayerTransportV2> { parametersOf(peer) }, Wearable.getChannelClient(get<Context>()))
        }
        single { PeerDeliveryLedger(get()) }
        single { WatchReceiptApplier(ledger = get(), peers = get()) }
        factory { parameters ->
            val context = get<Context>()
            LinkedDataLayerTransportV2(
                peer = parameters.get<PeerDeliveryContext>(),
                peers = get(),
                dataLayer = get(),
                capabilities = Wearable.getCapabilityClient(context),
                channels = Wearable.getChannelClient(context),
                messages = Wearable.getMessageClient(context),
            )
        }
        single {
            val flags = get<FeatureFlagStore>()
            WatchCaptureProtocol(
                enabled = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) },
                peers = get(),
                states = get(),
                storage = get(),
            )
        }
        single {
            val protocol = get<WatchCaptureProtocol>()
            val peers = get<WatchDeliveryPeer>()
            val receipts = get<WatchReceiptApplier>()
            val koin = getKoin()
            WatchNoteDelivery(
                active = protocol::active,
                peers = peers,
                outbox = get(),
                notes = get(),
                ledger = get(),
                transportFor = { peer -> koin.get<LinkedDataLayerTransportV2> { parametersOf(peer) } },
                conditions = { peer, transport ->
                    WatchRouteConditions(transport, receipts::phoneCloud, get(), get(), get(), peer, peers).current()
                },
                receipts = receipts,
                released = get(),
                cloud = CloudRelease { koin.get<DefaultSyncManager>(WatchCloud).uploadPendingChanges() },
                mediaFilePath = get<MediaFileResolver>()::filePath,
            )
        }
        single {
            val flags = get<FeatureFlagStore>()
            WatchMomentProtocol(
                enabled = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) },
                peers = get(),
                states = get(),
                storage = get(),
            )
        }
        single {
            val flags = get<FeatureFlagStore>()
            val peers = get<WatchDeliveryPeer>()
            val receipts = get<WatchReceiptApplier>()
            val protocol = get<WatchMomentProtocol>()
            val koin = getKoin()
            WatchMomentDelivery(
                active = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) },
                peers = peers,
                outbox = get(),
                moments = get(),
                ledger = get(),
                transportFor = { peer -> koin.get<LinkedDataLayerTransportV2> { parametersOf(peer) } },
                conditions = { peer, transport ->
                    WatchRouteConditions(
                        transport,
                        receipts::phoneCloud,
                        get(),
                        get(),
                        get(),
                        peer,
                        peers,
                        ServerProtocolFeature.MOMENTS_V1,
                    ).current()
                },
                receipts = receipts,
                released = get(),
                cloud = CloudRelease { koin.get<DefaultSyncManager>(WatchCloud).uploadPendingChanges() },
                accepts = protocol::accepts,
            )
        }
        single { WatchCaptureDelivery(get(), get()) } bind WatchNotesV2::class
        single {
            val appInfo = runCatching { AndroidAppInfoProvider(get()).getAppInfo() }.getOrNull()
            WatchOwnState(
                links = get<WatchLinkStore>().observe(),
                features = watchSyncFeatures,
                counts =
                    watchCounts(
                        syncMetadataService = get(),
                        deadLetterStore = get(),
                        freeBytes = get<StorageSpaceChecker>()::getAvailableStorageSpace,
                    ),
                activity =
                    combine(get<FeatureFlagStore>().observe(FeatureFlag.ACTIVITY_AWARENESS), get<ActivityTracker>().local) { on, log ->
                        log.toSnapshot().takeIf { on }
                    },
                appVersionName = appInfo?.versionName,
                appVersionCode = appInfo?.versionCode,
                protocolVersion = WearProtocol.PROTOCOL_VERSION,
                minPeerProtocolVersion = WearProtocol.MIN_COMPATIBLE_PROTOCOL_VERSION,
            )
        }
    }

/** What this watch build implements and advertises in its state document. */
internal val watchSyncFeatures =
    setOf(
        WearFeatures.ACTIVITY_REMINDERS,
        WearFeatures.LIVE,
        WearFeatures.LINK_CLOUD,
        WearFeatures.RECEIPTS,
        WearFeatures.OPERATION_RECEIPTS,
        WearFeatures.MOMENTS,
        WearFeatures.REMINDERS,
        WearFeatures.WINDOW,
        WearFeatures.MEDIA_RANGE,
    )

/**
 * Reads the account a newly linked watch belongs to with its own device token. A failure is logged here
 * and reported by the next sync, which reads the account again before it does anything else.
 */
private suspend fun readLinkedAccount(engine: DefaultSyncManager) {
    val result = engine.refreshServerAccount() ?: return
    result.exceptionOrNull()?.let { Napier.e("The linked account could not be read from the server; the next sync reads it again", it) }
}
