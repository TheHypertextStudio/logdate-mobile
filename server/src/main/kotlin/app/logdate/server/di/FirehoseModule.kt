package app.logdate.server.di

import app.logdate.server.atproto.firehose.AccountPublicRepoGate
import app.logdate.server.atproto.firehose.AccountsPublicRepoDirectory
import app.logdate.server.atproto.firehose.AtprotoFirehoseConfig
import app.logdate.server.atproto.firehose.InMemoryRepoEventStore
import app.logdate.server.atproto.firehose.PublicRecordValidator
import app.logdate.server.atproto.firehose.PublicRepoAccess
import app.logdate.server.atproto.firehose.PublicRepoAnnouncer
import app.logdate.server.atproto.firehose.PublicRepoDirectory
import app.logdate.server.atproto.firehose.PublicRepoGate
import app.logdate.server.atproto.firehose.PublicRepoPolicy
import app.logdate.server.atproto.firehose.RelayCrawler
import app.logdate.server.atproto.firehose.RepoEventSequencer
import app.logdate.server.atproto.firehose.RepoEventStore
import app.logdate.server.atproto.firehose.RepoEventStream
import app.logdate.server.atproto.firehose.SequencingRepoBlockStore
import app.logdate.server.auth.AccountModerationService
import app.logdate.server.auth.AccountStatusEvents
import app.logdate.server.database.PostgreSQLPublicRepoDirectory
import app.logdate.server.database.PostgreSQLRepoBlockStore
import app.logdate.server.database.PostgreSQLRepoEventStore
import app.logdate.server.identity.AtprotoIdentityConfig
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.bind
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RepoBlockStore

/** The repository block store as the database or memory provides it, before anything watches its commits. */
internal class UndecoratedRepoBlocks(
    val store: RepoBlockStore,
)

/**
 * The repository block store, and everything that announces public repositories to the network.
 *
 * With `ATPROTO_FIREHOSE_ENABLED` off, the block store is the plain one and nothing else is
 * registered, so the server behaves as it did before the event stream existed. With it on, the block
 * store reports each commit to the event log, public repositories can be read by anyone, and
 * `com.atproto.sync.subscribeRepos` replays the log.
 */
internal fun Module.firehoseDefinitions(isDatabaseAvailable: Boolean) {
    val firehose = AtprotoFirehoseConfig.fromEnvironment()
    single { firehose }
    single { UndecoratedRepoBlocks(if (isDatabaseAvailable) PostgreSQLRepoBlockStore() else InMemoryRepoBlockStore()) }
    single<RepoBlockStore> {
        val plain = get<UndecoratedRepoBlocks>().store
        if (firehose.enabled) SequencingRepoBlockStore(plain, lazy { get<RepoEventSequencer>() }) else plain
    }
    single { AccountModerationService(accounts = get(), statusEvents = getOrNull<AccountStatusEvents>()) }
    if (!firehose.enabled) return

    single<RepoEventStore> { if (isDatabaseAvailable) PostgreSQLRepoEventStore() else InMemoryRepoEventStore() }
    single { PublicRepoPolicy() }
    single { PublicRecordValidator() }
    single { AccountPublicRepoGate(accounts = get(), events = get()) } bind PublicRepoGate::class
    single {
        RepoEventSequencer(
            store = get(),
            blocks = get<UndecoratedRepoBlocks>().store,
            gate = get(),
            policy = get(),
        )
    }
    single<PublicRepoDirectory> {
        if (isDatabaseAvailable) {
            PostgreSQLPublicRepoDirectory()
        } else {
            AccountsPublicRepoDirectory(accounts = get(), gate = get(), blocks = get<UndecoratedRepoBlocks>().store)
        }
    }
    single {
        PublicRepoAccess(
            gate = get<AccountPublicRepoGate>(),
            policy = get(),
            blocks = get<UndecoratedRepoBlocks>().store,
            directory = get(),
        )
    }
    single {
        RelayCrawler(
            httpClient = get<HttpClient>(),
            relayHosts = firehose.relayHosts,
            serverHostname = RelayCrawler.hostnameFor(get<AtprotoIdentityConfig>().pdsServiceEndpoint),
        )
    }
    single { PublicRepoAnnouncer(accounts = get(), sequencer = get(), crawler = get()) }
    single<AccountStatusEvents> { get<PublicRepoAnnouncer>() }
    single {
        RepoEventStream(
            store = get(),
            wakeups = get<RepoEventSequencer>().announcements,
            pollInterval = firehose.pollInterval,
            maxFollowers = firehose.maxSubscribers,
            retention = firehose.retention,
        )
    }
}
