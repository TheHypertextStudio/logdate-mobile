package app.logdate.feature.core.settings.account.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.account.CloudPlanBilling
import app.logdate.client.domain.account.CloudPlanOverview
import app.logdate.client.domain.account.CloudPlanProblem
import app.logdate.client.domain.account.CloudPlanResult
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudStorageQuota
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.PlanOption
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Plan screen shows. */
sealed interface CloudPlanUiState {
    data object Loading : CloudPlanUiState

    /** The plan could not be read. [problem] says why, and reading it again may work. */
    data class Unavailable(
        val problem: CloudPlanProblem,
    ) : CloudPlanUiState

    data class Ready(
        val subscription: CloudSubscription,
        /** The server's plans, smallest first. */
        val plans: List<PlanOption>,
        /** The billing interval the plan prices are shown for, and a new plan is bought at. */
        val interval: BillingInterval,
        /** A change is being made; every action waits for it. */
        val working: Boolean = false,
        /** A checkout was opened in the browser and its result has not been read back yet. */
        val awaitingPayment: Boolean = false,
        /** Why the last action failed, until the next one starts. */
        val problem: CloudPlanProblem? = null,
        /** What kinds of things take up the storage, when the server has said. */
        val storageBreakdown: CloudStorageQuota? = null,
    ) : CloudPlanUiState {
        /** The account pays for its plan, so the plan can be changed, cancelled and billed differently. */
        val isPaying: Boolean get() = subscription.interval != null

        /** The paid plan is set to stop. */
        val isEnding: Boolean get() = subscription.endsAtEpochSeconds != null
    }
}

/**
 * The Plan screen: the LogDate Cloud plan an account is on, and buying, changing and cancelling it.
 *
 * Paying happens in the browser. [pages] carries each address to open; when the person comes back,
 * [checkPayment] has the server read the result from the payment provider. Nothing here assumes a
 * payment went through.
 */
class CloudPlanViewModel(
    private val billing: CloudPlanBilling,
    storageUse: Flow<CloudStorageQuota> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow<CloudPlanUiState>(CloudPlanUiState.Loading)
    val state: StateFlow<CloudPlanUiState> = _state.asStateFlow()

    private val _pages = Channel<String>(Channel.BUFFERED)

    /** Addresses to open in the browser: a checkout, or the payment details page. */
    val pages: Flow<String> = _pages.receiveAsFlow()

    private var storageBreakdown: CloudStorageQuota? = null

    init {
        load()
        viewModelScope.launch {
            // The breakdown only adds detail to the storage figure, so the screen stands without it.
            storageUse
                .catch { error -> Napier.w("Could not read the storage breakdown", error) }
                .collect { quota ->
                    storageBreakdown = quota
                    _state.update { if (it is CloudPlanUiState.Ready) it.copy(storageBreakdown = quota) else it }
                }
        }
    }

    fun load() {
        _state.value = CloudPlanUiState.Loading
        viewModelScope.launch {
            _state.value =
                when (val result = billing.overview()) {
                    is CloudPlanResult.Done -> result.value.toReady(interval = null)
                    is CloudPlanResult.Failed -> CloudPlanUiState.Unavailable(result.problem)
                }
        }
    }

    fun selectInterval(interval: BillingInterval) {
        _state.update { if (it is CloudPlanUiState.Ready) it.copy(interval = interval) else it }
    }

    /** Opens a checkout for [planId] at the selected interval, for an account that pays for nothing yet. */
    fun buy(planId: String) {
        val interval = ready()?.interval ?: return
        act { ready ->
            when (val result = billing.startCheckout(CloudPlanSelection(planId, interval))) {
                is CloudPlanResult.Done -> {
                    _pages.send(result.value)
                    ready.copy(awaitingPayment = true)
                }

                is CloudPlanResult.Failed -> {
                    ready.afterFailure(result.problem)
                }
            }
        }
    }

    /** Moves the paid plan to [planId] at the selected interval. */
    fun change(planId: String) {
        val interval = ready()?.interval ?: return
        act { ready -> ready.after(billing.changePlan(CloudPlanSelection(planId, interval))) }
    }

    fun cancel() = act { ready -> ready.after(billing.cancel()) }

    fun keep() = act { ready -> ready.after(billing.keep()) }

    fun openPaymentDetails() =
        act { ready ->
            when (val result = billing.paymentDetails()) {
                is CloudPlanResult.Done -> {
                    _pages.send(result.value)
                    ready
                }

                is CloudPlanResult.Failed -> {
                    ready.afterFailure(result.problem)
                }
            }
        }

    /**
     * Reads the plan back from the payment provider. Called when the person returns from the
     * browser, and by the "Check again" action; either way the wait for a payment is over.
     */
    fun checkPayment() {
        if (ready()?.awaitingPayment != true) return
        act { ready -> ready.after(billing.refresh()).copy(awaitingPayment = false) }
    }

    fun dismissProblem() {
        _state.update { if (it is CloudPlanUiState.Ready) it.copy(problem = null) else it }
    }

    private fun ready(): CloudPlanUiState.Ready? = _state.value as? CloudPlanUiState.Ready

    /** Runs [action] unless another is under way, showing the screen as working meanwhile. */
    private fun act(action: suspend (CloudPlanUiState.Ready) -> CloudPlanUiState.Ready) {
        val current = ready()?.takeIf { !it.working } ?: return
        val started = current.copy(working = true, problem = null)
        _state.value = started
        viewModelScope.launch {
            val finished = action(started)
            // The interval may have been switched while the action ran; keep the person's choice.
            _state.update { latest ->
                finished.copy(
                    working = false,
                    interval =
                        (latest as? CloudPlanUiState.Ready)?.interval ?: finished.interval,
                )
            }
            if (finished.problem == CloudPlanProblem.OutOfDate) reloadQuietly()
        }
    }

    /** Reads the plan again without leaving the screen, after the server said the app's view was stale. */
    private suspend fun reloadQuietly() {
        val result = billing.overview() as? CloudPlanResult.Done ?: return
        _state.update { latest ->
            val ready = latest as? CloudPlanUiState.Ready ?: return@update latest
            ready.copy(subscription = result.value.subscription, plans = result.value.plans.ifEmpty { ready.plans })
        }
    }

    private fun CloudPlanUiState.Ready.after(result: CloudPlanResult<CloudPlanOverview>): CloudPlanUiState.Ready =
        when (result) {
            is CloudPlanResult.Done -> {
                copy(subscription = result.value.subscription, plans = result.value.plans.ifEmpty { plans })
            }

            is CloudPlanResult.Failed -> {
                afterFailure(result.problem)
            }
        }

    private fun CloudPlanUiState.Ready.afterFailure(problem: CloudPlanProblem): CloudPlanUiState.Ready = copy(problem = problem)

    private fun CloudPlanOverview.toReady(interval: BillingInterval?): CloudPlanUiState.Ready =
        CloudPlanUiState.Ready(
            subscription = subscription,
            plans = plans,
            interval = interval ?: subscription.interval ?: BillingInterval.MONTH,
            storageBreakdown = storageBreakdown,
        )
}
