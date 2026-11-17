@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.account.plan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.account.CloudPlanProblem
import app.logdate.client.domain.storage.StoredKind
import app.logdate.feature.core.settings.ui.StorageUsageCard
import app.logdate.feature.core.settings.ui.formatByteSize
import app.logdate.feature.core.settings.ui.toStorageQuotaUi
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.PlanOption
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.MaterialCard
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.cloud_plan_action_bill_monthly
import logdate.client.feature.core.generated.resources.cloud_plan_action_bill_yearly
import logdate.client.feature.core.generated.resources.cloud_plan_action_subscribe
import logdate.client.feature.core.generated.resources.cloud_plan_action_upgrade
import logdate.client.feature.core.generated.resources.cloud_plan_awaiting_payment
import logdate.client.feature.core.generated.resources.cloud_plan_beta_body
import logdate.client.feature.core.generated.resources.cloud_plan_beta_title
import logdate.client.feature.core.generated.resources.cloud_plan_bill_confirm
import logdate.client.feature.core.generated.resources.cloud_plan_bill_title_monthly
import logdate.client.feature.core.generated.resources.cloud_plan_bill_title_yearly
import logdate.client.feature.core.generated.resources.cloud_plan_cancel
import logdate.client.feature.core.generated.resources.cloud_plan_cancel_body_free_after
import logdate.client.feature.core.generated.resources.cloud_plan_cancel_body_none_after
import logdate.client.feature.core.generated.resources.cloud_plan_cancel_title
import logdate.client.feature.core.generated.resources.cloud_plan_check_again
import logdate.client.feature.core.generated.resources.cloud_plan_dismiss
import logdate.client.feature.core.generated.resources.cloud_plan_extra_in_use
import logdate.client.feature.core.generated.resources.cloud_plan_extra_in_use_priced
import logdate.client.feature.core.generated.resources.cloud_plan_extra_storage
import logdate.client.feature.core.generated.resources.cloud_plan_extra_storage_priced
import logdate.client.feature.core.generated.resources.cloud_plan_fallback_name
import logdate.client.feature.core.generated.resources.cloud_plan_fix_payment
import logdate.client.feature.core.generated.resources.cloud_plan_interval_monthly
import logdate.client.feature.core.generated.resources.cloud_plan_interval_yearly
import logdate.client.feature.core.generated.resources.cloud_plan_keep
import logdate.client.feature.core.generated.resources.cloud_plan_none
import logdate.client.feature.core.generated.resources.cloud_plan_not_now
import logdate.client.feature.core.generated.resources.cloud_plan_not_on_sale
import logdate.client.feature.core.generated.resources.cloud_plan_payment_details
import logdate.client.feature.core.generated.resources.cloud_plan_payment_details_description
import logdate.client.feature.core.generated.resources.cloud_plan_price_monthly
import logdate.client.feature.core.generated.resources.cloud_plan_price_yearly
import logdate.client.feature.core.generated.resources.cloud_plan_problem_included
import logdate.client.feature.core.generated.resources.cloud_plan_problem_no_billing
import logdate.client.feature.core.generated.resources.cloud_plan_problem_not_on_sale
import logdate.client.feature.core.generated.resources.cloud_plan_problem_offline
import logdate.client.feature.core.generated.resources.cloud_plan_problem_out_of_date
import logdate.client.feature.core.generated.resources.cloud_plan_problem_payments_down
import logdate.client.feature.core.generated.resources.cloud_plan_problem_signed_out
import logdate.client.feature.core.generated.resources.cloud_plan_problem_storage
import logdate.client.feature.core.generated.resources.cloud_plan_problem_unknown
import logdate.client.feature.core.generated.resources.cloud_plan_section_billing
import logdate.client.feature.core.generated.resources.cloud_plan_section_less
import logdate.client.feature.core.generated.resources.cloud_plan_section_more
import logdate.client.feature.core.generated.resources.cloud_plan_status_ends
import logdate.client.feature.core.generated.resources.cloud_plan_status_none
import logdate.client.feature.core.generated.resources.cloud_plan_status_past_due
import logdate.client.feature.core.generated.resources.cloud_plan_status_renews_no_price
import logdate.client.feature.core.generated.resources.cloud_plan_storage_description
import logdate.client.feature.core.generated.resources.cloud_plan_storage_description_unlimited
import logdate.client.feature.core.generated.resources.cloud_plan_storage_included
import logdate.client.feature.core.generated.resources.cloud_plan_storage_total
import logdate.client.feature.core.generated.resources.cloud_plan_storage_unlimited
import logdate.client.feature.core.generated.resources.cloud_plan_switch_body_down
import logdate.client.feature.core.generated.resources.cloud_plan_switch_body_up
import logdate.client.feature.core.generated.resources.cloud_plan_switch_confirm
import logdate.client.feature.core.generated.resources.cloud_plan_switch_title
import logdate.client.feature.core.generated.resources.cloud_plan_title
import logdate.client.feature.core.generated.resources.cloud_plan_try_again
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

const val CLOUD_PLAN_ROOT_TAG = "cloud_plan_root"
const val CLOUD_PLAN_STORAGE_TAG = "cloud_plan_storage"
const val CLOUD_PLAN_PROBLEM_TAG = "cloud_plan_problem"
const val CLOUD_PLAN_BETA_TAG = "cloud_plan_beta"

/** The test tag of the card for the plan [planId]. */
fun cloudPlanCardTag(planId: String): String = "cloud_plan_card_$planId"

/** The test tag of the button on the card for the plan [planId]. */
fun cloudPlanActionTag(planId: String): String = "cloud_plan_action_$planId"

/** The LogDate Cloud plan an account is on, with buying, changing and cancelling where the server sells plans. */
@Composable
fun CloudPlanScreen(
    onBack: () -> Unit,
    onReview: ((StorageReview) -> Unit)? = null,
    onCleanUp: ((StoredKind) -> Unit)? = null,
    viewModel: CloudPlanViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel) {
        viewModel.pages.collect { address -> uriHandler.openUri(address) }
    }
    // Paying happens in the browser. Coming back to the app is when its result is worth reading.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.checkPayment() }

    CloudPlanContent(
        state = state,
        onBack = onBack,
        onReview = onReview,
        onCleanUp = onCleanUp,
        actions =
            CloudPlanActions(
                onRetry = viewModel::load,
                onSelectInterval = viewModel::selectInterval,
                onBuy = viewModel::buy,
                onChange = viewModel::change,
                onCancel = viewModel::cancel,
                onKeep = viewModel::keep,
                onPaymentDetails = viewModel::openPaymentDetails,
                onCheckPayment = viewModel::checkPayment,
                onDismissProblem = viewModel::dismissProblem,
            ),
    )
}

/** What the Plan screen can ask for. */
data class CloudPlanActions(
    val onRetry: () -> Unit = {},
    val onSelectInterval: (BillingInterval) -> Unit = {},
    val onBuy: (planId: String) -> Unit = {},
    val onChange: (planId: String) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onKeep: () -> Unit = {},
    val onPaymentDetails: () -> Unit = {},
    val onCheckPayment: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
)

/** A change that charges or ends something, waiting for the person to confirm it. */
private sealed interface PendingChange {
    data class MoveTo(
        val plan: PlanOption,
    ) : PendingChange

    data class Bill(
        val plan: PlanOption,
        val interval: BillingInterval,
    ) : PendingChange

    data object Cancel : PendingChange
}

@Composable
fun CloudPlanContent(
    state: CloudPlanUiState,
    onBack: () -> Unit,
    actions: CloudPlanActions = CloudPlanActions(),
    onReview: ((StorageReview) -> Unit)? = null,
    onCleanUp: ((StoredKind) -> Unit)? = null,
) {
    var pending by remember { mutableStateOf<PendingChange?>(null) }

    SettingsScaffold(
        title = stringResource(Res.string.cloud_plan_title),
        onBack = onBack,
        modifier = Modifier.testTag(CLOUD_PLAN_ROOT_TAG),
    ) {
        when (state) {
            CloudPlanUiState.Loading -> {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            is CloudPlanUiState.Unavailable -> {
                item { UnavailablePlan(state.problem, actions.onRetry) }
            }

            is CloudPlanUiState.Ready -> {
                val subscription = state.subscription
                item { StorageInUse(state, onReview, onCleanUp) }
                if (subscription.includedWithBeta) {
                    item { BetaMoment() }
                }
                if (subscription.status == EntitlementStatusWire.PAST_DUE) {
                    item {
                        Notice(
                            message = stringResource(Res.string.cloud_plan_status_past_due),
                            action = stringResource(Res.string.cloud_plan_fix_payment),
                            onAction = actions.onPaymentDetails,
                            enabled = !state.working && subscription.paymentDetailsAvailable,
                            isError = true,
                        )
                    }
                }
                if (state.awaitingPayment) {
                    item {
                        Notice(
                            message = stringResource(Res.string.cloud_plan_awaiting_payment),
                            action = stringResource(Res.string.cloud_plan_check_again),
                            onAction = actions.onCheckPayment,
                            enabled = !state.working,
                        )
                    }
                }
                state.problem?.let { problem ->
                    item {
                        Notice(
                            message = problemText(problem),
                            action = stringResource(Res.string.cloud_plan_dismiss),
                            onAction = actions.onDismissProblem,
                            isError = true,
                            messageTag = CLOUD_PLAN_PROBLEM_TAG,
                        )
                    }
                }
                val offers = state.offers
                if (offers.isNotEmpty()) {
                    item {
                        Offers(
                            state = state,
                            offers = offers,
                            onSelectInterval = actions.onSelectInterval,
                            onAction = { plan, action ->
                                when (action) {
                                    PlanAction.Buy -> actions.onBuy(plan.id)
                                    PlanAction.Switch -> pending = PendingChange.MoveTo(plan)
                                    is PlanAction.BillAt, PlanAction.None -> Unit
                                }
                            },
                        )
                    }
                }
                if (state.isPaying) {
                    item {
                        Billing(
                            state = state,
                            actions = actions,
                            onBillAt = { plan, interval -> pending = PendingChange.Bill(plan, interval) },
                            onCancel = { pending = PendingChange.Cancel },
                        )
                    }
                }
            }
        }
    }

    val ready = state as? CloudPlanUiState.Ready
    val change = pending
    if (ready != null && change != null) {
        ConfirmChange(
            change = change,
            state = ready,
            onConfirm = {
                pending = null
                when (change) {
                    is PendingChange.MoveTo -> {
                        actions.onChange(change.plan.id)
                    }

                    is PendingChange.Bill -> {
                        actions.onSelectInterval(change.interval)
                        actions.onChange(change.plan.id)
                    }

                    PendingChange.Cancel -> {
                        actions.onCancel()
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }
}

/** A paid plan that is still renewing can be cancelled, where the server can carry that out. */
private val CloudPlanUiState.Ready.canCancel: Boolean
    get() = isPaying && !isEnding && subscription.purchasesAvailable

/**
 * The plans to move to. The plan the account is on is the storage card, and the plan a beta member
 * keeps at no charge is reached by cancelling, so neither is offered here.
 */
private val CloudPlanUiState.Ready.offers: List<PlanOption>
    get() = plans.filter { it.id != subscription.planId && it.id != subscription.freePlanId }

@Composable
private fun UnavailablePlan(
    problem: CloudPlanProblem,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = problemText(problem),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(CLOUD_PLAN_PROBLEM_TAG),
        )
        // Signing in and switching provider happen elsewhere; trying again only helps the rest.
        if (problem != CloudPlanProblem.SignedOut && problem != CloudPlanProblem.NoBilling) {
            FilledTonalButton(onClick = onRetry) { Text(stringResource(Res.string.cloud_plan_try_again)) }
        }
    }
}

/**
 * The first thing on the screen: which plan the account is on and how full it is. Extra storage in
 * use counts as room the account has, since it pays for it.
 */
@Composable
private fun StorageInUse(
    state: CloudPlanUiState.Ready,
    onReview: ((StorageReview) -> Unit)?,
    onCleanUp: ((StoredKind) -> Unit)?,
) {
    val subscription = state.subscription
    val plan = state.plans.firstOrNull { it.id == subscription.planId }
    Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (subscription.planId == null) {
            MaterialCard(
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                contentPadding = Spacing.xl,
            ) {
                Text(
                    stringResource(Res.string.cloud_plan_none),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(Res.string.cloud_plan_status_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            return@Column
        }
        val included = subscription.storageIncludedBytes
        val total = included?.let { it + (plan?.extraStorage?.stepBytes ?: 0L) * subscription.extraStorageSteps }
        val used = formatByteSize(subscription.storageUsedBytes)
        // What the plan costs this account, in one line under its name.
        val price = subscription.interval?.let { interval -> plan?.priceFor(interval)?.let { priceText(it.formatted(), interval) } }
        StorageUsageCard(
            title = plan?.name ?: stringResource(Res.string.cloud_plan_fallback_name),
            status = price,
            used = used,
            // The share in use, beside the amount: "64% of 50 GB".
            total =
                total?.let {
                    val percent = if (it > 0) (subscription.storageUsedBytes * PERCENT / it).coerceIn(0, PERCENT) else 0
                    stringResource(Res.string.cloud_plan_storage_total, "$percent%", formatByteSize(it))
                },
            usageDescription =
                if (total != null) {
                    stringResource(Res.string.cloud_plan_storage_description, used, formatByteSize(total))
                } else {
                    stringResource(Res.string.cloud_plan_storage_description_unlimited, used)
                },
            fraction = if (total != null && total > 0) subscription.storageUsedBytes.toFloat() / total else 0f,
            categories =
                state.storageBreakdown
                    ?.toStorageQuotaUi()
                    ?.categories
                    .orEmpty(),
            modifier = Modifier.testTag(CLOUD_PLAN_STORAGE_TAG),
            actionFor = { category -> reviewAction(category, onReview, onCleanUp) },
        )
    }
}

/**
 * For someone who joined during the beta: a badge and two lines saying the plan is theirs for
 * free. It stands on the page by itself, under the storage it is about. The badge turns slowly
 * and spins when touched; with reduced motion it holds still.
 */
@Composable
private fun BetaMoment() {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalLogDateHaptics.current
    val reduceMotion by rememberSystemReduceMotion()
    var spins by remember { mutableIntStateOf(0) }
    val spin by animateFloatAsState(spins * BADGE_SPIN, MaterialTheme.motionScheme.slowSpatialSpec())
    val drift =
        if (reduceMotion) {
            0f
        } else {
            val turning = rememberInfiniteTransition()
            val angle by turning.animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(BADGE_TURN_MILLIS, easing = LinearEasing)))
            angle
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).testTag(CLOUD_PLAN_BETA_TAG),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(BADGE_SIZE)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .clickable {
                        spins++
                        haptics.detentTick()
                    }.clearAndSetSemantics { },
        ) {
            Box(
                Modifier
                    .size(BADGE_SIZE)
                    .graphicsLayer { rotationZ = drift + spin }
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(colors.tertiary),
            )
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = colors.onTertiary, modifier = Modifier.size(BADGE_ICON_SIZE))
        }
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(stringResource(Res.string.cloud_plan_beta_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(Res.string.cloud_plan_beta_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private const val BADGE_SPIN = 120f
private const val FULL_TURN = 360f
private const val BADGE_TURN_MILLIS = 48_000
private val BADGE_SIZE = 72.dp
private val BADGE_ICON_SIZE = 30.dp

/** One line that needs attention, with the one thing to do about it. */
@Composable
private fun Notice(
    message: String,
    action: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    messageTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    MaterialCard(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        containerColor = if (isError) colors.errorContainer else colors.surfaceContainerHigh,
        contentColor = if (isError) colors.onErrorContainer else colors.onSurface,
        contentPadding = Spacing.sm,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm)
                        .then(if (messageTag != null) Modifier.testTag(messageTag) else Modifier),
            )
            TextButton(onClick = onAction, enabled = enabled) {
                Text(action, color = if (isError) colors.onErrorContainer else colors.primary)
            }
        }
    }
}

/**
 * The storage to move to, in one card. How to be billed is chosen inside that card, right above the
 * price it changes; with more than one plan on offer the choice leads the card and applies to all.
 */
@Composable
private fun Offers(
    state: CloudPlanUiState.Ready,
    offers: List<PlanOption>,
    onSelectInterval: (BillingInterval) -> Unit,
    onAction: (PlanOption, PlanAction) -> Unit,
) {
    val current = if (state.subscription.planId == null) 0L else state.subscription.storageIncludedBytes ?: Long.MAX_VALUE
    val isMore = offers.all { (it.storageBytesLimit ?: Long.MAX_VALUE) > current }
    // Choosing how to be billed only means something where a plan can be bought or changed.
    val choosesInterval = offers.offerBothIntervals() && offers.any { state.actionFor(it) != PlanAction.None }
    val intervalChoice: @Composable (Modifier) -> Unit = { modifier ->
        IntervalChoice(selected = state.interval, onSelect = onSelectInterval, enabled = !state.working, modifier = modifier)
    }
    Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Text(
            text = stringResource(if (isMore) Res.string.cloud_plan_section_more else Res.string.cloud_plan_section_less),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = Spacing.sm).semantics { heading() },
        )
        // Only the storage card is tinted and extra round. The offer has the surface and the shape of
        // a settings group, so it reads as something to do, not as a second summary.
        MaterialCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentPadding = Spacing.lg) {
            val sharedChoice = choosesInterval && offers.size > 1
            if (sharedChoice) intervalChoice(Modifier)
            offers.forEachIndexed { index, plan ->
                val action = state.actionFor(plan)
                Offer(
                    plan = plan,
                    state = state,
                    action = action,
                    isMore = isMore,
                    onAction = { onAction(plan, action) },
                    intervalChoice = intervalChoice.takeIf { choosesInterval && !sharedChoice },
                    modifier = Modifier.padding(top = if (index > 0 || sharedChoice) Spacing.xl else 0.dp),
                )
            }
        }
    }
}

/** Monthly or yearly: two halves of one control, because picking one unpicks the other. */
@Composable
private fun IntervalChoice(
    selected: BillingInterval,
    onSelect: (BillingInterval) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        BillingInterval.entries.forEachIndexed { index, interval ->
            ToggleButton(
                checked = selected == interval,
                onCheckedChange = { onSelect(interval) },
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes =
                    if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
            ) {
                Text(
                    stringResource(
                        when (interval) {
                            BillingInterval.MONTH -> Res.string.cloud_plan_interval_monthly
                            BillingInterval.YEAR -> Res.string.cloud_plan_interval_yearly
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One plan, in the order someone decides by: which plan it is, how much it holds, and last and
 * quietest what it costs, beside the one thing that can be done about it.
 */
@Composable
private fun Offer(
    plan: PlanOption,
    state: CloudPlanUiState.Ready,
    action: PlanAction,
    isMore: Boolean,
    onAction: () -> Unit,
    intervalChoice: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.testTag(cloudPlanCardTag(plan.id))) {
        Text(plan.name, style = MaterialTheme.typography.titleLarge)
        Text(
            text =
                plan.storageBytesLimit?.let { stringResource(Res.string.cloud_plan_storage_included, formatByteSize(it)) }
                    ?: stringResource(Res.string.cloud_plan_storage_unlimited),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        plan.extraStorage?.let { extra ->
            val step = formatByteSize(extra.stepBytes)
            Text(
                text =
                    extra.monthlyPrice?.let { stringResource(Res.string.cloud_plan_extra_storage_priced, it.formatted(), step) }
                        ?: stringResource(Res.string.cloud_plan_extra_storage, step),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        intervalChoice?.invoke(Modifier.padding(top = Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = priceLine(plan, state).orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val buttonModifier = Modifier.testTag(cloudPlanActionTag(plan.id))
            when (action) {
                PlanAction.Buy -> {
                    Button(onClick = onAction, enabled = !state.working, modifier = buttonModifier) {
                        Text(
                            stringResource(
                                if (state.subscription.planId == null) {
                                    Res.string.cloud_plan_action_subscribe
                                } else {
                                    Res.string.cloud_plan_action_upgrade
                                },
                            ),
                        )
                    }
                }

                PlanAction.Switch -> {
                    if (isMore) {
                        Button(onClick = onAction, enabled = !state.working, modifier = buttonModifier) {
                            Text(stringResource(Res.string.cloud_plan_action_upgrade))
                        }
                    } else {
                        FilledTonalButton(onClick = onAction, enabled = !state.working, modifier = buttonModifier) {
                            Text(stringResource(Res.string.cloud_plan_switch_confirm))
                        }
                    }
                }

                is PlanAction.BillAt, PlanAction.None -> {}
            }
        }
    }
}

/** What a paid plan costs and when, and the things that can be done about it. */
@Composable
private fun Billing(
    state: CloudPlanUiState.Ready,
    actions: CloudPlanActions,
    onBillAt: (PlanOption, BillingInterval) -> Unit,
    onCancel: () -> Unit,
) {
    val subscription = state.subscription
    val plan = state.plans.firstOrNull { it.id == subscription.planId }
    val canKeep = state.isEnding && subscription.purchasesAvailable
    val ends = subscription.endsAtEpochSeconds?.let { stringResource(Res.string.cloud_plan_status_ends, dateText(it)) }
    // The price is on the storage card, under the plan's name; this row carries the date.
    val renews = subscription.renewsAtEpochSeconds?.let { stringResource(Res.string.cloud_plan_status_renews_no_price, dateText(it)) }
    // The same plan billed the other way, where the server will do that.
    val otherInterval = BillingInterval.entries.firstOrNull { it != subscription.interval }
    val billOther =
        if (plan != null && otherInterval != null && state.copy(interval = otherInterval).actionFor(plan) is PlanAction.BillAt) {
            plan.priceFor(otherInterval)
        } else {
            null
        }
    val extraBytes = (plan?.extraStorage?.stepBytes ?: 0L) * subscription.extraStorageSteps
    val extraPrice = plan?.extraStorage?.monthlyPrice

    // Facts are plain text. The rows under them are only the things that can be done.
    val facts =
        listOfNotNull(
            ends ?: renews,
            when {
                extraBytes <= 0 -> {
                    null
                }

                extraPrice != null -> {
                    stringResource(
                        Res.string.cloud_plan_extra_in_use_priced,
                        formatByteSize(extraBytes),
                        formatMoney(extraPrice.amountMinor * subscription.extraStorageSteps, extraPrice.currency),
                    )
                }

                else -> {
                    stringResource(Res.string.cloud_plan_extra_in_use, formatByteSize(extraBytes))
                }
            },
        )
    Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Column(modifier = Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(Res.string.cloud_plan_section_billing),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            facts.forEach { fact ->
                Text(fact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MaterialContainer {
            if (canKeep) {
                SimpleSettingsItem(
                    title = stringResource(Res.string.cloud_plan_keep),
                    description = null,
                    onClick = actions.onKeep,
                    enabled = !state.working,
                    action = {},
                )
            }
            if (subscription.paymentDetailsAvailable) {
                SettingsNavigationItem(
                    title = stringResource(Res.string.cloud_plan_payment_details),
                    description = stringResource(Res.string.cloud_plan_payment_details_description),
                    onClick = actions.onPaymentDetails,
                    enabled = !state.working,
                )
            }
            if (plan != null && otherInterval != null && billOther != null) {
                SimpleSettingsItem(
                    title = stringResource(otherInterval.billActionLabel()),
                    description = priceText(billOther.formatted(), otherInterval),
                    onClick = { onBillAt(plan, otherInterval) },
                    enabled = !state.working,
                    action = {},
                )
            }
            if (state.canCancel) {
                SimpleSettingsItem(
                    title = stringResource(Res.string.cloud_plan_cancel),
                    description = null,
                    onClick = onCancel,
                    enabled = !state.working,
                    action = {},
                )
            }
        }
    }
}

private fun BillingInterval.billActionLabel() =
    when (this) {
        BillingInterval.MONTH -> Res.string.cloud_plan_action_bill_monthly
        BillingInterval.YEAR -> Res.string.cloud_plan_action_bill_yearly
    }

/** What the plan costs at the chosen interval, or null when no price is known. */
@Composable
private fun priceLine(
    plan: PlanOption,
    state: CloudPlanUiState.Ready,
): String? {
    val price = (plan.priceFor(state.interval) ?: plan.prices.firstOrNull())?.let { priceText(it.formatted(), it.interval) }
    val notOnSale = stringResource(Res.string.cloud_plan_not_on_sale)
    return when {
        price == null && plan.onSale -> null
        price == null -> notOnSale
        plan.onSale -> price
        else -> "$price · $notOnSale"
    }
}

@Composable
private fun priceText(
    amount: String,
    interval: BillingInterval,
): String =
    stringResource(
        when (interval) {
            BillingInterval.MONTH -> Res.string.cloud_plan_price_monthly
            BillingInterval.YEAR -> Res.string.cloud_plan_price_yearly
        },
        amount,
    )

@Composable
private fun dateText(epochSeconds: Long): String =
    formatDateLocalized(Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault()).date)

@Composable
private fun problemText(problem: CloudPlanProblem): String =
    when (problem) {
        CloudPlanProblem.SignedOut -> {
            stringResource(Res.string.cloud_plan_problem_signed_out)
        }

        CloudPlanProblem.NoBilling -> {
            stringResource(Res.string.cloud_plan_problem_no_billing)
        }

        CloudPlanProblem.NotOnSale -> {
            stringResource(Res.string.cloud_plan_problem_not_on_sale)
        }

        is CloudPlanProblem.StorageExceedsPlan -> {
            stringResource(
                Res.string.cloud_plan_problem_storage,
                formatByteSize(problem.usedBytes),
                formatByteSize(problem.includedBytes),
            )
        }

        CloudPlanProblem.IncludedWithBeta -> {
            stringResource(Res.string.cloud_plan_problem_included)
        }

        CloudPlanProblem.OutOfDate -> {
            stringResource(Res.string.cloud_plan_problem_out_of_date)
        }

        CloudPlanProblem.PaymentProviderUnavailable -> {
            stringResource(Res.string.cloud_plan_problem_payments_down)
        }

        CloudPlanProblem.Offline -> {
            stringResource(Res.string.cloud_plan_problem_offline)
        }

        CloudPlanProblem.Unknown -> {
            stringResource(Res.string.cloud_plan_problem_unknown)
        }
    }

@Composable
private fun ConfirmChange(
    change: PendingChange,
    state: CloudPlanUiState.Ready,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val subscription = state.subscription
    val currentPlan = state.plans.firstOrNull { it.id == subscription.planId }
    val currentName = currentPlan?.name ?: stringResource(Res.string.cloud_plan_fallback_name)
    val title: String
    val body: String
    val confirm: String
    val dismiss: String
    when (change) {
        is PendingChange.MoveTo, is PendingChange.Bill -> {
            val plan = if (change is PendingChange.MoveTo) change.plan else (change as PendingChange.Bill).plan
            val interval = if (change is PendingChange.Bill) change.interval else state.interval
            val newPrice = plan.priceFor(interval)
            val oldPrice = subscription.interval?.let { currentPlan?.priceFor(it) }
            val price = newPrice?.let { priceText(it.formatted(), interval) }.orEmpty()
            // Compared by the month, so a yearly price and a monthly one line up.
            val costsMore = (newPrice?.perMonth() ?: 0L) > (oldPrice?.perMonth() ?: 0L)
            title =
                when (change) {
                    is PendingChange.MoveTo -> {
                        stringResource(Res.string.cloud_plan_switch_title, plan.name)
                    }

                    else -> {
                        stringResource(
                            if (interval ==
                                BillingInterval.YEAR
                            ) {
                                Res.string.cloud_plan_bill_title_yearly
                            } else {
                                Res.string.cloud_plan_bill_title_monthly
                            },
                        )
                    }
                }
            body = stringResource(if (costsMore) Res.string.cloud_plan_switch_body_up else Res.string.cloud_plan_switch_body_down, price)
            confirm =
                stringResource(
                    if (change is PendingChange.MoveTo) Res.string.cloud_plan_switch_confirm else Res.string.cloud_plan_bill_confirm,
                )
            dismiss = stringResource(Res.string.cloud_plan_not_now)
        }

        PendingChange.Cancel -> {
            val until = dateText(subscription.renewsAtEpochSeconds ?: subscription.endsAtEpochSeconds ?: 0L)
            title = stringResource(Res.string.cloud_plan_cancel_title, currentName)
            body =
                stringResource(
                    if (subscription.freePlanId !=
                        null
                    ) {
                        Res.string.cloud_plan_cancel_body_free_after
                    } else {
                        Res.string.cloud_plan_cancel_body_none_after
                    },
                    until,
                )
            confirm = stringResource(Res.string.cloud_plan_cancel)
            dismiss = stringResource(Res.string.cloud_plan_keep)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

private const val MONTHS_PER_YEAR = 12
private const val PERCENT = 100L

private fun app.logdate.shared.model.PlanPrice.perMonth(): Long =
    if (interval ==
        BillingInterval.YEAR
    ) {
        amountMinor / MONTHS_PER_YEAR
    } else {
        amountMinor
    }
