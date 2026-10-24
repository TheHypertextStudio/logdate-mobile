package app.logdate.server.identity

import app.logdate.server.atproto.firehose.AccountPublicRepoGate
import app.logdate.server.atproto.firehose.AtprotoFirehoseConfig
import app.logdate.server.atproto.firehose.PublicRepoAnnouncer
import app.logdate.server.atproto.firehose.PublicRepoPolicy
import app.logdate.server.atproto.firehose.RelayCrawler
import app.logdate.server.atproto.firehose.RepoEventSequencer
import app.logdate.server.config.AtprotoSigningKeyKek
import app.logdate.server.config.AtprotoSigningKeyKekDecryptOnly
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.PostgreSQLHostedPlcOperationRepository
import app.logdate.server.database.PostgreSQLRepoBlockStore
import app.logdate.server.database.PostgreSQLRepoEventStore
import app.logdate.server.database.PostgreSQLSigningKeyRepository
import app.logdate.server.logging.IdentityPublishingNotices
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import studio.hypertext.atproto.plc.KtorPlcDirectoryClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The first server argument that selects the identity publishing command. */
const val IDENTITY_PUBLISHING_COMMAND: String = "--identity-publishing"

/** A parsed identity publishing command line. */
data class IdentityPublishingInvocation(
    val options: IdentityPublishingOptions,
    val confirmDirectory: String? = null,
    val reportFile: Path? = null,
)

/** Raised for a command line the identity publishing command doesn't accept. */
class IdentityPublishingUsageException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Parses the arguments that follow [IDENTITY_PUBLISHING_COMMAND]:
 *
 * ```
 * status  [--handle HANDLE] [--limit N] [--report-file PATH]
 * publish --dry-run [--reconcile] [--handle HANDLE] [--limit N] [--batch-size N] [--report-file PATH]
 * publish --confirm-directory URL [--reconcile] [--handle HANDLE] [--limit N] [--batch-size N]
 *         [--operations-per-minute N] [--max-failures N] [--report-file PATH]
 * ```
 */
fun parseIdentityPublishingArgs(args: List<String>): IdentityPublishingInvocation {
    val subcommand = args.firstOrNull() ?: throw IdentityPublishingUsageException("Name a subcommand: status or publish")
    val publish = isPublishSubcommand(subcommand)
    val flags = setOf("--dry-run", "--reconcile")
    val valued =
        setOf("--handle", "--limit", "--batch-size", "--operations-per-minute", "--max-failures", "--report-file", "--confirm-directory")
    val publishOnly = setOf("--dry-run", "--reconcile", "--batch-size", "--operations-per-minute", "--max-failures", "--confirm-directory")
    val seenFlags = mutableSetOf<String>()
    val values = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val name = args[index]
        if (!publish && name in publishOnly) throw IdentityPublishingUsageException("$name applies only to publish")
        when (name) {
            in flags -> {
                seenFlags += name
            }

            in valued -> {
                val value = args.getOrNull(index + 1)
                if (value == null || value.startsWith("--")) throw IdentityPublishingUsageException("$name needs a value")
                if (values.put(name, value) != null) throw IdentityPublishingUsageException("$name was given twice")
                index += 1
            }

            else -> {
                throw IdentityPublishingUsageException("Unknown argument $name")
            }
        }
        index += 1
    }
    val dryRun = "--dry-run" in seenFlags
    if (dryRun && "--confirm-directory" in values) {
        throw IdentityPublishingUsageException("--dry-run never writes, so it takes no --confirm-directory")
    }
    if (dryRun && ("--operations-per-minute" in values || "--max-failures" in values)) {
        throw IdentityPublishingUsageException("--dry-run never writes, so pacing and failure limits don't apply")
    }

    fun intValue(name: String): Int? =
        values[name]?.let { raw -> raw.toIntOrNull() ?: throw IdentityPublishingUsageException("$name must be a whole number") }

    val options =
        try {
            IdentityPublishingOptions(
                publish = publish,
                dryRun = dryRun,
                reconcile = "--reconcile" in seenFlags,
                batchSize = intValue("--batch-size") ?: IdentityPublishingOptions.DEFAULT_BATCH_SIZE,
                operationsPerMinute = intValue("--operations-per-minute") ?: IdentityPublishingOptions.DEFAULT_OPERATIONS_PER_MINUTE,
                limit = intValue("--limit"),
                maxFailures = intValue("--max-failures") ?: IdentityPublishingOptions.DEFAULT_MAX_FAILURES,
                handle = values["--handle"]?.trim()?.trim('.')?.lowercase(),
            )
        } catch (invalid: IllegalArgumentException) {
            throw IdentityPublishingUsageException(invalid.message.orEmpty())
        }
    return IdentityPublishingInvocation(
        options = options,
        confirmDirectory = values["--confirm-directory"],
        reportFile = values["--report-file"]?.let(Path::of),
    )
}

private fun isPublishSubcommand(subcommand: String): Boolean =
    when (subcommand) {
        "status" -> false
        "publish" -> true
        else -> throw IdentityPublishingUsageException("Unknown subcommand $subcommand; use status or publish")
    }

/**
 * Returns the notice explaining why [invocation] must not run against [config], or null when it may.
 *
 * Reading is always allowed. Writing needs publishing switched on for the server
 * (`ATPROTO_PLC_PUBLISH_ENABLED=true`) and the operator to name the directory being written to
 * with `--confirm-directory`, so a run can never reach a directory nobody meant to touch.
 */
fun identityPublishingRefusal(
    invocation: IdentityPublishingInvocation,
    config: AtprotoIdentityConfig,
): String? {
    if (config.hostedAccountDidMethod != HostedAccountDidMethod.PLC) return IdentityPublishingNotices.REFUSED_NOT_PLC
    if (!invocation.options.writes) return null
    if (!config.publishesPlcOperations) return IdentityPublishingNotices.REFUSED_PUBLISHING_OFF
    val confirmed = invocation.confirmDirectory?.trim()?.removeSuffix("/")
    if (confirmed == null || !confirmed.equals(config.normalizedPlcDirectoryUrl, ignoreCase = true)) {
        return IdentityPublishingNotices.REFUSED_DIRECTORY_UNCONFIRMED
    }
    return null
}

/**
 * Runs the identity publishing command when the server is started with [IDENTITY_PUBLISHING_COMMAND]
 * and exits with the run's exit code; returns false for any other arguments. It never starts the
 * HTTP server.
 */
fun handleIdentityPublishingCommand(args: Array<String>): Boolean {
    if (args.firstOrNull() != IDENTITY_PUBLISHING_COMMAND) return false
    val exitCode =
        try {
            runIdentityPublishingCommand(args.drop(1))
        } catch (exception: Exception) {
            Napier.e(IdentityPublishingNotices.RUN_FAILED, exception)
            IdentityPublishingRun.EXIT_FAILED
        }
    exitProcess(exitCode)
}

private val reportJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
    }

@OptIn(ExperimentalUuidApi::class)
private fun runIdentityPublishingCommand(args: List<String>): Int {
    val invocation =
        try {
            parseIdentityPublishingArgs(args)
        } catch (usage: IdentityPublishingUsageException) {
            Napier.e(IdentityPublishingNotices.REFUSED_USAGE, usage)
            return IdentityPublishingRun.EXIT_REFUSED
        }
    val config = AtprotoIdentityConfig.fromEnvironment()
    identityPublishingRefusal(invocation, config)?.let { notice ->
        Napier.e(notice)
        return IdentityPublishingRun.EXIT_REFUSED
    }

    val dataSource = DatabaseConfig.createDataSource()
    val httpClient = HttpClient(OkHttp)
    try {
        TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(dataSource, autoMigrate = false)
        val accounts = PostgreSQLAccountRepository()
        val operations = PostgreSQLHostedPlcOperationRepository()
        val signingKeys =
            SigningKeyService(
                repository = PostgreSQLSigningKeyRepository(),
                encryptionKeySeed = AtprotoSigningKeyKek.resolve(),
                decryptOnlyEncryptionKeySeeds = listOfNotNull(AtprotoSigningKeyKekDecryptOnly.resolve()),
            )
        val directory = KtorPlcDirectoryClient(httpClient = httpClient, baseUrl = config.normalizedPlcDirectoryUrl)
        val plcIdentityService =
            PlcIdentityService(
                signingKeyService = signingKeys,
                config = config,
                plcDirectoryClient = directory,
                hostedPlcOperationRepository = operations,
            )
        val identityService = AtprotoIdentityService(accounts, signingKeys, config, plcIdentityService)
        val run =
            IdentityPublishingRun(
                service = IdentityPublishingService(identityService, plcIdentityService, operations, directory, config),
                loadAccounts = accounts::getAllAccounts,
                pacer = PlcWritePacer(invocation.options.operationsPerMinute),
                directoryUrl = config.normalizedPlcDirectoryUrl,
                onPublished = publishedAnnouncer(accounts, config, httpClient),
            )
        val report = runBlocking { run.run(invocation.options) }
        invocation.reportFile?.let { writeIdentityPublishingReport(it, report) }
        return report.exitCode
    } finally {
        httpClient.close()
        (dataSource as? AutoCloseable)?.close()
    }
}

/**
 * What a publishing run does for each identity it publishes: when the repository event stream is on,
 * tell its followers about the account and ask the configured relays to crawl the server, as the
 * server does for an identity it publishes itself. With the stream off, nothing.
 */
@OptIn(ExperimentalUuidApi::class)
private fun publishedAnnouncer(
    accounts: PostgreSQLAccountRepository,
    identityConfig: AtprotoIdentityConfig,
    httpClient: HttpClient,
): suspend (Uuid) -> Unit {
    val firehose = AtprotoFirehoseConfig.fromEnvironment()
    if (!firehose.enabled) return {}
    val blocks = PostgreSQLRepoBlockStore()
    val events = PostgreSQLRepoEventStore()
    val sequencer =
        RepoEventSequencer(
            store = events,
            blocks = blocks,
            gate = AccountPublicRepoGate(accounts, identityConfig, events),
            policy = PublicRepoPolicy(),
        )
    val crawler = RelayCrawler(httpClient, firehose.relayHosts, RelayCrawler.hostnameFor(identityConfig.pdsServiceEndpoint))
    val announcer = PublicRepoAnnouncer(accounts, sequencer, crawler)
    return announcer::accountPublished
}

/** Writes [report] readable only by its owner, replacing any earlier report at [path] in one step. */
fun writeIdentityPublishingReport(
    path: Path,
    report: IdentityPublishingReport,
) {
    val target = path.toAbsolutePath()
    val directory = target.parent
    Files.createDirectories(directory)
    val temporary =
        Files.createTempFile(
            directory,
            ".identity-publishing-",
            ".json",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
    Files.writeString(temporary, reportJson.encodeToString(IdentityPublishingReport.serializer(), report))
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
