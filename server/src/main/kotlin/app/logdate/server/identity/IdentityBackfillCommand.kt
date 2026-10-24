package app.logdate.server.identity

import app.logdate.server.di.initializeDatabase
import app.logdate.server.di.serverModule
import app.logdate.server.logging.IdentityNotices
import io.github.aakira.napier.Napier
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import kotlin.system.exitProcess

private const val BACKFILL_FLAG = "--backfill-identities"
private const val DRY_RUN_FLAG = "--dry-run"
private const val ALLOW_PLC_PUBLISHING_FLAG = "--allow-plc-publishing"
private const val EXIT_FAILED = 1
private const val EXIT_REFUSED = 2

/**
 * How the operator ran `--backfill-identities`. A dry run only counts. A real run provisions, and
 * when the server publishes PLC operations it refuses unless [allowPlcPublishing] says the operator
 * means to publish every new identity to the public PLC directory.
 */
data class IdentityBackfillOptions(
    val dryRun: Boolean,
    val allowPlcPublishing: Boolean,
) {
    companion object {
        /** Returns null when [args] are not a backfill command; throws on an unknown option. */
        fun parse(args: Array<String>): IdentityBackfillOptions? {
            if (args.firstOrNull() != BACKFILL_FLAG) return null
            val options = args.drop(1)
            val unknown = options.filterNot { it == DRY_RUN_FLAG || it == ALLOW_PLC_PUBLISHING_FLAG }
            require(unknown.isEmpty()) { "Unknown identity backfill options" }
            return IdentityBackfillOptions(
                dryRun = DRY_RUN_FLAG in options,
                allowPlcPublishing = ALLOW_PLC_PUBLISHING_FLAG in options,
            )
        }
    }
}

/** What one backfill run did. */
sealed interface IdentityBackfillOutcome {
    data class DryRun(
        val pending: Int,
    ) : IdentityBackfillOutcome

    data class Completed(
        val result: IdentityBackfillResult,
    ) : IdentityBackfillOutcome

    data object RefusedPublishing : IdentityBackfillOutcome
}

/** Counts, or provisions, the accounts that still lack an AT Protocol identity. */
suspend fun runIdentityBackfill(
    identityService: AtprotoIdentityService,
    options: IdentityBackfillOptions,
): IdentityBackfillOutcome {
    if (options.dryRun) return IdentityBackfillOutcome.DryRun(identityService.accountsMissingIdentity().size)
    if (identityService.config.publishesPlcOperations && !options.allowPlcPublishing) {
        return IdentityBackfillOutcome.RefusedPublishing
    }
    return IdentityBackfillOutcome.Completed(identityService.backfillMissingIdentities())
}

/**
 * Dispatches `--backfill-identities` before the HTTP server starts. The process exits non-zero
 * when the run was refused or any account failed, so the job that runs it reports a failure.
 */
fun handleIdentityBackfillCommand(args: Array<String>): Boolean {
    val options = IdentityBackfillOptions.parse(args) ?: return false
    val outcome =
        try {
            check(initializeDatabase()) { "The identity backfill needs the production database" }
            val koin = koinApplication { modules(serverModule(isDatabaseAvailable = true)) }.koin
            runBlocking { runIdentityBackfill(koin.get(), options) }
        } catch (error: Exception) {
            Napier.e(IdentityNotices.BACKFILL_FAILED, error)
            exitProcess(EXIT_FAILED)
        }
    when (outcome) {
        is IdentityBackfillOutcome.DryRun -> {
            Napier.i(IdentityNotices.backfillDryRun(outcome.pending))
        }

        IdentityBackfillOutcome.RefusedPublishing -> {
            Napier.e(IdentityNotices.BACKFILL_REFUSED_PUBLISHING)
            exitProcess(EXIT_REFUSED)
        }

        is IdentityBackfillOutcome.Completed -> {
            Napier.i(
                IdentityNotices.backfillComplete(
                    provisioned = outcome.result.provisioned,
                    skipped = outcome.result.skipped,
                    failed = outcome.result.failed,
                ),
            )
            if (outcome.result.failed > 0) exitProcess(EXIT_FAILED)
        }
    }
    return true
}
