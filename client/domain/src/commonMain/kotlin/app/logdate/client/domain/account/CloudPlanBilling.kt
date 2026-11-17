package app.logdate.client.domain.account

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.CloudBillingApiClientContract
import app.logdate.client.networking.CloudBillingException
import app.logdate.client.networking.PlanCatalogClient
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.PlanOption

/** The signed-in account's LogDate Cloud plan, with the plans its server offers. */
data class CloudPlanOverview(
    val subscription: CloudSubscription,
    /** The server's plans, smallest first. Empty when the catalog could not be read. */
    val plans: List<PlanOption>,
)

/** Why a plan could not be read or changed, in terms the app can word for a person. */
sealed interface CloudPlanProblem {
    /** Nobody is signed in. */
    data object SignedOut : CloudPlanProblem

    /** The server has no billing at all, as a self-hosted server does. There is nothing to show. */
    data object NoBilling : CloudPlanProblem

    /** Plans cannot be bought here yet. */
    data object NotOnSale : CloudPlanProblem

    /** The account stores more than the chosen plan holds. */
    data class StorageExceedsPlan(
        val usedBytes: Long,
        val includedBytes: Long,
    ) : CloudPlanProblem

    /** The account already has this plan at no charge. */
    data object IncludedWithBeta : CloudPlanProblem

    /** What the app showed is no longer true on the server; reading the plan again puts it right. */
    data object OutOfDate : CloudPlanProblem

    /** The server could not reach the payment provider. Nothing changed. */
    data object PaymentProviderUnavailable : CloudPlanProblem

    /** The server could not be reached. Nothing changed. */
    data object Offline : CloudPlanProblem

    /** Anything else. Nothing is known to have changed. */
    data object Unknown : CloudPlanProblem
}

sealed interface CloudPlanResult<out T> {
    data class Done<T>(
        val value: T,
    ) : CloudPlanResult<T>

    data class Failed(
        val problem: CloudPlanProblem,
    ) : CloudPlanResult<Nothing>
}

/**
 * Reads and changes the signed-in account's LogDate Cloud plan.
 *
 * Paying happens on the payment provider's own pages: [startCheckout] and [paymentDetails] return
 * an address to open in a browser. The app never decides that a plan changed; it asks the server
 * with [refresh] when the person comes back.
 */
interface CloudPlanBilling {
    suspend fun overview(): CloudPlanResult<CloudPlanOverview>

    /** Has the server read the plan back from the payment provider. */
    suspend fun refresh(): CloudPlanResult<CloudPlanOverview>

    /** The checkout page to open for [selection], for an account that pays for nothing yet. */
    suspend fun startCheckout(selection: CloudPlanSelection): CloudPlanResult<String>

    /** Moves a paid plan to another plan or billing interval. */
    suspend fun changePlan(selection: CloudPlanSelection): CloudPlanResult<CloudPlanOverview>

    /** Sets a paid plan to stop at the end of what has been paid for. */
    suspend fun cancel(): CloudPlanResult<CloudPlanOverview>

    /** Undoes [cancel] while the plan has not yet stopped. */
    suspend fun keep(): CloudPlanResult<CloudPlanOverview>

    /** The payment provider's page for payment methods and receipts. */
    suspend fun paymentDetails(): CloudPlanResult<String>
}

/** [CloudPlanBilling] against the connected server, with the signed-in session. */
class ServerCloudPlanBilling(
    private val sessionStorage: SessionStorage,
    private val billing: CloudBillingApiClientContract,
    private val catalog: PlanCatalogClient,
    /** Renews the session's access token and says whether it worked. */
    private val refreshSession: suspend () -> Boolean,
) : CloudPlanBilling {
    override suspend fun overview(): CloudPlanResult<CloudPlanOverview> = authenticated { billing.subscription(it) }.withPlans()

    override suspend fun refresh(): CloudPlanResult<CloudPlanOverview> = authenticated { billing.refreshSubscription(it) }.withPlans()

    override suspend fun startCheckout(selection: CloudPlanSelection): CloudPlanResult<String> =
        authenticated { billing.startCheckout(it, selection) }.map { it.url }

    override suspend fun changePlan(selection: CloudPlanSelection): CloudPlanResult<CloudPlanOverview> =
        authenticated { billing.changePlan(it, selection) }.withPlans()

    override suspend fun cancel(): CloudPlanResult<CloudPlanOverview> = authenticated { billing.cancel(it) }.withPlans()

    override suspend fun keep(): CloudPlanResult<CloudPlanOverview> = authenticated { billing.keep(it) }.withPlans()

    override suspend fun paymentDetails(): CloudPlanResult<String> = authenticated { billing.paymentDetails(it) }.map { it.url }

    /** Runs [call] with the session's token, refreshing the session once if the server rejects it. */
    private suspend fun <T> authenticated(call: suspend (accessToken: String) -> Result<T>): CloudPlanResult<T> {
        val session = sessionStorage.getSession() ?: return CloudPlanResult.Failed(CloudPlanProblem.SignedOut)
        val first = call(session.accessToken)
        val rejected = (first.exceptionOrNull() as? CloudBillingException)?.status == UNAUTHORIZED
        val result =
            if (rejected && refreshSession()) {
                val refreshed = sessionStorage.getSession() ?: return CloudPlanResult.Failed(CloudPlanProblem.SignedOut)
                call(refreshed.accessToken)
            } else {
                first
            }
        return result.fold(
            onSuccess = { CloudPlanResult.Done(it) },
            onFailure = { CloudPlanResult.Failed(problemOf(it)) },
        )
    }

    private suspend fun CloudPlanResult<CloudSubscription>.withPlans(): CloudPlanResult<CloudPlanOverview> =
        when (this) {
            is CloudPlanResult.Failed -> this
            is CloudPlanResult.Done -> CloudPlanResult.Done(CloudPlanOverview(value, catalog.fetchPlans().getOrDefault(emptyList())))
        }

    private fun <T, R> CloudPlanResult<T>.map(transform: (T) -> R): CloudPlanResult<R> =
        when (this) {
            is CloudPlanResult.Failed -> this
            is CloudPlanResult.Done -> CloudPlanResult.Done(transform(value))
        }

    private fun problemOf(error: Throwable): CloudPlanProblem {
        val failure = error as? CloudBillingException ?: return CloudPlanProblem.Unknown
        return when (failure.code) {
            CloudBillingException.UNAVAILABLE -> {
                CloudPlanProblem.NoBilling
            }

            CloudBillingException.NETWORK -> {
                CloudPlanProblem.Offline
            }

            "PLANS_NOT_ON_SALE" -> {
                CloudPlanProblem.NotOnSale
            }

            "INCLUDED_WITH_BETA" -> {
                CloudPlanProblem.IncludedWithBeta
            }

            "ALREADY_SUBSCRIBED", "NO_SUBSCRIPTION", "UNKNOWN_PLAN" -> {
                CloudPlanProblem.OutOfDate
            }

            "BILLING_PROVIDER_UNAVAILABLE" -> {
                CloudPlanProblem.PaymentProviderUnavailable
            }

            "STORAGE_EXCEEDS_PLAN" -> {
                val used = failure.details["usedBytes"]?.toLongOrNull()
                val included = failure.details["includedBytes"]?.toLongOrNull()
                if (used != null && included != null) CloudPlanProblem.StorageExceedsPlan(used, included) else CloudPlanProblem.Unknown
            }

            else -> {
                if (failure.status == UNAUTHORIZED) CloudPlanProblem.SignedOut else CloudPlanProblem.Unknown
            }
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
