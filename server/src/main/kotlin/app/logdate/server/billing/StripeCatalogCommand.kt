package app.logdate.server.billing

import app.logdate.server.logging.BillingNotices
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** The first server argument that selects the Stripe catalog command. */
const val STRIPE_CATALOG_COMMAND: String = "--stripe-catalog"

/** A parsed Stripe catalog command line. */
data class StripeCatalogInvocation(
    val apply: Boolean,
    val webhookUrl: String? = null,
    val reportFile: Path? = null,
)

/** Raised for a command line the Stripe catalog command doesn't accept. */
class StripeCatalogUsageException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Parses the arguments that follow [STRIPE_CATALOG_COMMAND]:
 *
 * ```
 * status [--webhook-url URL] [--report-file PATH]
 * apply  [--webhook-url URL] [--report-file PATH]
 * ```
 *
 * `status` only reports. `apply` creates the prices and the meter that are missing. With
 * `--webhook-url`, either also checks that the webhook endpoint at that address exists and sends the
 * events the server acts on; the endpoint itself is added in Stripe.
 *
 * Server logs carry only fixed messages, so the log says whether the account is ready and the
 * line for each price, the meter and the webhook is written to `--report-file`.
 */
fun parseStripeCatalogArgs(args: List<String>): StripeCatalogInvocation {
    val apply =
        when (args.firstOrNull()) {
            "status" -> false
            "apply" -> true
            else -> throw StripeCatalogUsageException("Name a subcommand: status or apply")
        }
    var webhookUrl: String? = null
    var reportFile: Path? = null
    val rest = args.drop(1).iterator()
    while (rest.hasNext()) {
        when (val option = rest.next()) {
            "--webhook-url" -> {
                if (!rest.hasNext()) throw StripeCatalogUsageException("--webhook-url needs an address")
                webhookUrl = rest.next()
                if (!webhookUrl.startsWith("https://")) throw StripeCatalogUsageException("--webhook-url must be an https:// address")
            }

            "--report-file" -> {
                if (!rest.hasNext()) throw StripeCatalogUsageException("--report-file needs a path")
                reportFile = Path.of(rest.next())
            }

            else -> {
                throw StripeCatalogUsageException("Unknown option: $option")
            }
        }
    }
    return StripeCatalogInvocation(apply = apply, webhookUrl = webhookUrl, reportFile = reportFile)
}

/** Whether a run left nothing to do: nothing is missing and nothing differs. */
fun catalogIsReady(steps: List<CatalogStep>): Boolean = steps.none { it is CatalogStep.Missing || it is CatalogStep.Differs }

/** One line per step, for the person running the command. */
fun describeCatalogSteps(steps: List<CatalogStep>): List<String> =
    steps.map { step ->
        when (step) {
            is CatalogStep.Present -> "ok       ${step.name}"
            is CatalogStep.Created -> "created  ${step.name}"
            is CatalogStep.Missing -> "missing  ${step.name}"
            is CatalogStep.Differs -> "differs  ${step.name}: ${step.detail}"
        }
    }

/**
 * Runs the Stripe catalog command when the server is started with [STRIPE_CATALOG_COMMAND] and
 * exits; returns false for any other arguments. It never starts the HTTP server and needs only
 * `STRIPE_SECRET_KEY`, so it can be run from a laptop against test mode or live mode.
 */
fun handleStripeCatalogCommand(args: Array<String>): Boolean {
    if (args.firstOrNull() != STRIPE_CATALOG_COMMAND) return false
    exitProcess(runStripeCatalogCommand(args.drop(1)))
}

private const val EXIT_OK = 0
private const val EXIT_NOT_READY = 1
private const val EXIT_REFUSED = 2

private fun runStripeCatalogCommand(
    args: List<String>,
    readEnv: (String) -> String? = System::getenv,
): Int {
    val invocation =
        try {
            parseStripeCatalogArgs(args)
        } catch (usage: StripeCatalogUsageException) {
            Napier.e(BillingNotices.CATALOG_REFUSED_USAGE, usage)
            return EXIT_REFUSED
        }
    val secretKey = readEnv(StripeConfig.SECRET_KEY_ENV)?.trim().orEmpty()
    if (secretKey.isEmpty()) {
        Napier.e(BillingNotices.CATALOG_REFUSED_NO_KEY)
        return EXIT_REFUSED
    }
    val config =
        StripeConfig(
            secretKey = secretKey,
            // The command verifies no webhooks, so it needs no signing secret.
            webhookSecret = "",
            apiBaseUrl =
                readEnv(StripeConfig.API_BASE_URL_ENV)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() }
                    ?: StripeConfig.DEFAULT_API_BASE_URL,
        )
    val http = HttpClient(OkHttp)
    return try {
        val steps = runBlocking { StripeCatalogSetup(HttpStripeApi(http, config)).run(invocation.apply, invocation.webhookUrl) }
        invocation.reportFile?.let { Files.writeString(it, describeCatalogSteps(steps).joinToString(separator = "\n", postfix = "\n")) }
        if (catalogIsReady(steps)) {
            Napier.i(BillingNotices.CATALOG_READY)
            EXIT_OK
        } else {
            Napier.w(BillingNotices.CATALOG_NOT_READY)
            EXIT_NOT_READY
        }
    } catch (e: StripeException) {
        Napier.e(BillingNotices.CATALOG_FAILED, e)
        EXIT_NOT_READY
    } finally {
        http.close()
    }
}
