@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.FirstLaunchTheme
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.cloud_offer_beta_body
import logdate.client.feature.core.generated.resources.cloud_offer_beta_title
import logdate.client.feature.core.generated.resources.cloud_offer_see_plans
import logdate.client.feature.core.generated.resources.cloud_offer_subscription_body
import logdate.client.feature.core.generated.resources.cloud_plan_base_name
import logdate.client.feature.core.generated.resources.cloud_plan_base_perk_backup
import logdate.client.feature.core.generated.resources.cloud_plan_base_perk_free
import logdate.client.feature.core.generated.resources.cloud_plan_base_perk_sync
import logdate.client.feature.core.generated.resources.cloud_plan_base_price
import logdate.client.feature.core.generated.resources.cloud_plan_base_status
import logdate.client.feature.core.generated.resources.cloud_plan_plus_name
import logdate.client.feature.core.generated.resources.cloud_plan_plus_perk_base
import logdate.client.feature.core.generated.resources.cloud_plan_plus_perk_extra
import logdate.client.feature.core.generated.resources.cloud_plan_plus_perk_room
import logdate.client.feature.core.generated.resources.cloud_plan_plus_price
import logdate.client.feature.core.generated.resources.cloud_plans_body
import logdate.client.feature.core.generated.resources.cloud_plans_done
import logdate.client.feature.core.generated.resources.cloud_plans_title
import org.jetbrains.compose.resources.stringResource

/**
 * What LogDate Cloud costs the person looking at it.
 *
 * LogDate Cloud is a subscription service. The welcome step always says so, in one of two ways.
 */
sealed interface CloudOffer {
    /**
     * The beta: LogDate Cloud is free, and everyone who joins now keeps it free once
     * subscriptions start.
     */
    data object FreeDuringBeta : CloudOffer

    /**
     * A paid subscription. [price] is the store's own wording for the plan, already in the
     * person's currency, such as "$4.99 a month". The plan comes from the server's plan catalog
     * and its price from the store, never from a string in the app.
     */
    data class Subscription(
        val price: String,
    ) : CloudOffer
}

const val CLOUD_OFFER_TAG = "cloud_offer"

/** The offer, set on peach so it reads as what is being offered and not as another paragraph. */
@Composable
internal fun CloudOfferCard(
    offer: CloudOffer,
    modifier: Modifier = Modifier,
    onSeePlans: (() -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onTertiaryContainer
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CLOUD_OFFER_TAG)
                .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.extraLarge)
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text =
                when (offer) {
                    CloudOffer.FreeDuringBeta -> stringResource(Res.string.cloud_offer_beta_title)
                    is CloudOffer.Subscription -> offer.price
                },
            style = MaterialTheme.typography.titleMedium,
            color = ink,
        )
        Text(
            text =
                when (offer) {
                    CloudOffer.FreeDuringBeta -> stringResource(Res.string.cloud_offer_beta_body)
                    is CloudOffer.Subscription -> stringResource(Res.string.cloud_offer_subscription_body)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = 0.86f),
        )
        if (onSeePlans != null) {
            Text(
                text = stringResource(Res.string.cloud_offer_see_plans),
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                textDecoration = TextDecoration.Underline,
                modifier =
                    Modifier
                        .testTag(CLOUD_OFFER_PLANS_TAG)
                        .clickable(role = Role.Button, onClick = onSeePlans)
                        .padding(top = Spacing.sm, bottom = Spacing.xs),
            )
        }
    }
}

/**
 * The two LogDate Cloud plans: what each one gives, and what it will cost.
 *
 * Both are paid once LogDate launches; during the beta the first is free. The prices are the
 * published launch prices from `docs/product-info/logdate-cloud-plans.md`. Once a plan is on sale
 * its price comes from the store, in the person's own currency, and replaces the one written here.
 */
@Composable
fun CloudAccountPlansContent(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FirstLaunchTheme {
        StepScaffold(
            title = stringResource(Res.string.cloud_plans_title),
            onBack = onBack,
            modifier = modifier.testTag(CLOUD_PLANS_ROOT_TAG),
            supportingText = stringResource(Res.string.cloud_plans_body),
            actions = {
                StepPrimaryButton(
                    text = stringResource(Res.string.cloud_plans_done),
                    onClick = onDone,
                    modifier = Modifier.testTag(CLOUD_PLANS_DONE_TAG),
                )
            },
        ) {
            CloudPlan(
                name = stringResource(Res.string.cloud_plan_base_name),
                status = stringResource(Res.string.cloud_plan_base_status),
                perks =
                    listOf(
                        stringResource(Res.string.cloud_plan_base_perk_backup),
                        stringResource(Res.string.cloud_plan_base_perk_sync),
                        stringResource(Res.string.cloud_plan_base_perk_free),
                    ),
                price = stringResource(Res.string.cloud_plan_base_price),
                highlighted = true,
            )
            CloudPlan(
                name = stringResource(Res.string.cloud_plan_plus_name),
                status = null,
                perks =
                    listOf(
                        stringResource(Res.string.cloud_plan_plus_perk_base),
                        stringResource(Res.string.cloud_plan_plus_perk_room),
                        stringResource(Res.string.cloud_plan_plus_perk_extra),
                    ),
                price = stringResource(Res.string.cloud_plan_plus_price),
                highlighted = false,
            )
        }
    }
}

const val CLOUD_OFFER_PLANS_TAG = "cloud_offer_plans"
const val CLOUD_PLANS_ROOT_TAG = "cloud_plans_root"
const val CLOUD_PLANS_DONE_TAG = "cloud_plans_done"

/**
 * One plan, in the order someone decides by: which plan it is, what it gives, and last and
 * quietest what it costs. [status] is a short note beside the name, such as that the plan is free
 * for now.
 */
@Composable
private fun CloudPlan(
    name: String,
    status: String?,
    perks: List<String>,
    price: String,
    highlighted: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge
    val ink = if (highlighted) colors.onTertiaryContainer else colors.onSurface
    val quiet = if (highlighted) ink.copy(alpha = 0.8f) else colors.onSurfaceVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (highlighted) colors.tertiaryContainer else colors.surfaceContainerLowest, shape)
                .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = ink)
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (highlighted) colors.onPrimary else colors.onSurfaceVariant,
                    modifier =
                        Modifier
                            .background(if (highlighted) colors.primary else colors.surfaceContainerHigh, CircleShape)
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
        Column(
            modifier = Modifier.padding(vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            perks.forEach { perk ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Nudged down to sit on the first line of a perk that wraps.
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = ink, modifier = Modifier.padding(top = 3.dp).size(18.dp))
                    Text(perk, style = MaterialTheme.typography.bodyLarge, color = ink)
                }
            }
        }
        Text(price, style = MaterialTheme.typography.bodyMedium, color = quiet)
    }
}
