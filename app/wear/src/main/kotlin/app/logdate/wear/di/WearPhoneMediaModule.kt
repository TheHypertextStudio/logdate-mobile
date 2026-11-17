@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.logdate.wear.di

import android.content.Context
import androidx.work.WorkManager
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.domain.watch.StampedPreferenceStore
import app.logdate.client.domain.watch.WatchAudioDownloadPolicy
import app.logdate.client.media.audio.AudioPlaybackSourceFactory
import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.wear.playback.PhoneSyncedAudioResolver
import app.logdate.wear.playback.WearSyncedAudioResolver
import app.logdate.wear.playback.WindowPhoneAudioResolver
import app.logdate.wear.sync.v2.delivery.LinkedDataLayerTransportV2
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import app.logdate.wear.sync.v2.media.GoogleMediaRangeSource
import app.logdate.wear.sync.v2.media.WatchAudioDataSources
import app.logdate.wear.sync.v2.media.WatchMediaPrefetchScheduler
import app.logdate.wear.sync.v2.media.WatchMediaPrefetcher
import app.logdate.wear.sync.v2.media.WatchPhoneMedia
import app.logdate.wear.sync.v2.media.WatchPrefetchConditions
import app.logdate.wear.sync.v2.window.WindowMembershipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import java.io.File

val wearPhoneMediaModule =
    module {
        single {
            val koin = getKoin()
            val peers = get<WatchDeliveryPeer>()
            val states = get<PeerStateStore>()
            WatchPhoneMedia(
                peers = peers,
                notes = get(),
                cache = get(),
                storage = get(),
                request = { peer, path, payload -> koin.get<LinkedDataLayerTransportV2> { parametersOf(peer) }.request(path, payload) },
                source = { peer -> koin.get<GoogleMediaRangeSource> { parametersOf(peer) } },
                supported = { peer ->
                    peers.isCurrent(peer) &&
                        WearFeatures.MEDIA_RANGE in
                        states
                            .documents(PeerRole.PHONE)
                            .first()[peer.phoneNode]
                            ?.phone
                            ?.features
                            .orEmpty()
                },
            )
        }
        single { WatchPrefetchConditions(get(), get()) }
        single {
            val peers = get<WatchDeliveryPeer>()
            val states = get<PeerStateStore>()
            val flags = get<FeatureFlagStore>()
            val media = get<WatchPhoneMedia>()
            val shared = get<StampedPreferenceStore>()
            val conditions = get<WatchPrefetchConditions>()
            WatchMediaPrefetcher(
                peers = peers,
                members = get(),
                notes = get(),
                outbox = get(),
                enabled = {
                    flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) &&
                        conditions.permits(
                            WatchAudioDownloadPolicy.fromStored(
                                SharedPreferenceKey.AudioDownloadPolicy.read(
                                    shared.snapshot()[SharedPreferenceKey.AudioDownloadPolicy.name],
                                ),
                            ),
                        )
                },
                supported = { peer ->
                    WearFeatures.MEDIA_RANGE in
                        states
                            .documents(PeerRole.PHONE)
                            .first()[peer.phoneNode]
                            ?.phone
                            ?.features
                            .orEmpty()
                },
                download = { note, peer, allowed ->
                    media.download(note, peer, allowed)
                },
                media = get<StoredMediaReferences>(),
            )
        }
        single {
            WatchMediaPrefetchScheduler(
                WorkManager.getInstance(get<Context>()),
                get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                get<StampedPreferenceStore>().observe().map { state ->
                    WatchAudioDownloadPolicy.fromStored(
                        SharedPreferenceKey.AudioDownloadPolicy.read(state[SharedPreferenceKey.AudioDownloadPolicy.name]),
                    )
                },
            )
        }
        single<AudioPlaybackSourceFactory> { WatchAudioDataSources(get()) }
        single<WearSyncedAudioResolver> {
            val flags = get<FeatureFlagStore>()
            val peers = get<WatchDeliveryPeer>()
            val members = get<WindowMembershipStore>()
            val files = get<MediaFileResolver>()
            val media = get<WatchPhoneMedia>()
            WindowPhoneAudioResolver(
                legacy = get<PhoneSyncedAudioResolver>(),
                enabled = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) },
                managed = { note -> peers.current()?.let { note.uid.toString() in members.read(it).entries } ?: false },
                ownedRecording = { note ->
                    files.filePath(note.mediaRef)?.let { files.refFor(it)?.collection == MediaCollection.Recordings && File(it).isFile }
                        ?: false
                },
                prepare = { note -> withContext(Dispatchers.IO) { media.prepare(note) } },
            )
        }
    }
