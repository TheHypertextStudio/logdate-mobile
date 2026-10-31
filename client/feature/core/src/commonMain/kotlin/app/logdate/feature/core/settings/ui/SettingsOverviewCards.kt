@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.MaterialCard
import app.logdate.ui.streak.Campfire
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.theme.Spacing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_profile_edit_label
import logdate.client.feature.core.generated.resources.campfire_badge
import logdate.client.feature.core.generated.resources.create_account
import logdate.client.feature.core.generated.resources.logging_since
import logdate.client.feature.core.generated.resources.sign_in
import logdate.client.feature.core.generated.resources.sync_devices_subtitle
import logdate.client.feature.core.generated.resources.sync_promotion_title
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal fun SyncPromotionCard(
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onNavigateToSync: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = stringResource(Res.string.sync_promotion_title),
    description: String = stringResource(Res.string.sync_devices_subtitle),
) {
    MaterialCard(
        modifier =
            modifier.then(
                if (onNavigateToSync !=
                    null
                ) {
                    Modifier.clip(MaterialTheme.shapes.extraLarge).clickable(onClick = onNavigateToSync)
                } else {
                    Modifier
                },
            ),
        title = title,
        titleStyle = MaterialTheme.typography.titleLarge,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Button(onClick = onCreateAccount) { Text(stringResource(Res.string.create_account)) }
                TextButton(onClick = onSignIn) { Text(stringResource(Res.string.sign_in)) }
            }
        }
    }
}

@Composable
internal fun SettingsIdentityCard(
    userProfile: UserProfile,
    onboardedDate: Instant,
    onEditProfile: () -> Unit,
    campfire: CampfirePresentation? = null,
    modifier: Modifier = Modifier,
) {
    val displayName = userProfile.name.ifEmpty { userProfile.username.ifEmpty { "You" } }
    val yearString =
        if (onboardedDate != Instant.DISTANT_PAST) {
            onboardedDate
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .year
                .toString()
        } else {
            null
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onEditProfile)
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (yearString != null) {
                    Text(
                        text = stringResource(Res.string.logging_since, yearString),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                if (campfire != null && campfire.runDays > 0) CampfireBadge(campfire = campfire)
            }
        }

        FilledTonalButton(onClick = onEditProfile) {
            Text(stringResource(Res.string.account_profile_edit_label))
        }
    }
}

@Composable
private fun CampfireBadge(campfire: CampfirePresentation) {
    Row(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Campfire(
            phase = campfire.phase,
            size = campfire.size,
            waitingForToday = campfire.isWaitingForToday,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(Res.string.campfire_badge, campfire.runDays),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
