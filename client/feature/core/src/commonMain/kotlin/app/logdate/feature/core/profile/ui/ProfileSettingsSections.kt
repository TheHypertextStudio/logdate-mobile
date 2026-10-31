@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.streak.Campfire
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.streak.campfireHeadline
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_information
import logdate.client.feature.core.generated.resources.authentication
import logdate.client.feature.core.generated.resources.birthday
import logdate.client.feature.core.generated.resources.birthday_not_set
import logdate.client.feature.core.generated.resources.campfire_profile_summary
import logdate.client.feature.core.generated.resources.edit_bio
import logdate.client.feature.core.generated.resources.journaling_stats
import logdate.client.feature.core.generated.resources.personal_information
import logdate.client.feature.core.generated.resources.profile
import logdate.client.feature.core.generated.resources.profile_authenticated
import logdate.client.feature.core.generated.resources.profile_bio_label
import logdate.client.feature.core.generated.resources.profile_bio_not_set
import logdate.client.feature.core.generated.resources.profile_member_since
import logdate.client.feature.core.generated.resources.profile_not_authenticated
import logdate.client.feature.core.generated.resources.username
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun ProfilePersonalInformationSection(
    profile: ProfileDisplayModel,
    onNavigateToBirthday: () -> Unit,
    onSaveBio: (String) -> Unit,
) {
    SettingsSection(
        title = stringResource(Res.string.personal_information),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        val formattedBirthday =
            if (profile.birthday == null || profile.birthday == Instant.DISTANT_PAST) {
                stringResource(Res.string.birthday_not_set)
            } else {
                formatDateLocalized(
                    profile.birthday
                        .toLocalDateTime(TimeZone.UTC)
                        .date,
                )
            }
        SettingsNavigationItem(
            title = stringResource(Res.string.birthday),
            description = formattedBirthday,
            icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
            onClick = onNavigateToBirthday,
        )
        var editingBio by rememberSaveable { mutableStateOf(false) }
        var bioDraft by rememberSaveable(profile.editableBio) { mutableStateOf(profile.editableBio.orEmpty()) }
        if (editingBio) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                OutlinedTextField(
                    value = bioDraft,
                    onValueChange = { bioDraft = it },
                    label = { Text(stringResource(Res.string.profile_bio_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    IconButton(onClick = { editingBio = false }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(UiRes.string.common_cancel))
                    }
                    IconButton(onClick = {
                        onSaveBio(bioDraft)
                        editingBio = false
                    }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(UiRes.string.common_save))
                    }
                }
            }
        } else {
            SettingsRow(
                headlineContent = { Text(stringResource(Res.string.profile_bio_label)) },
                supportingContent = { Text(profile.editableBio ?: stringResource(Res.string.profile_bio_not_set)) },
                trailingContent = {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.edit_bio))
                },
                modifier = Modifier.clickable { editingBio = true },
            )
        }
    }
}

@Composable
internal fun ProfileCampfireSection(campfire: CampfirePresentation) {
    SettingsSection(
        title = stringResource(Res.string.journaling_stats),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsRow(
            leadingContent = {
                Campfire(
                    phase = campfire.phase,
                    size = campfire.size,
                    waitingForToday = campfire.isWaitingForToday,
                    modifier = Modifier.size(40.dp),
                )
            },
            headlineContent = { Text(campfireHeadline(campfire)) },
            supportingContent = {
                Text(stringResource(Res.string.campfire_profile_summary, campfire.runDays, campfire.totalDaysJournaled))
            },
        )
    }
}

@Composable
internal fun ProfileAccountInformationSection(profile: ProfileDisplayModel) {
    SettingsSection(
        title = stringResource(Res.string.account_information),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        profile.username?.let { username ->
            ProfileInfoItem(
                icon = Icons.Default.Person,
                label = stringResource(Res.string.username),
                value = "@$username",
            )
        }

        profile.joinDate?.let { joinDate ->
            ProfileInfoItem(
                icon = Icons.Default.CalendarMonth,
                label = stringResource(Res.string.profile_member_since),
                value =
                    formatDateLocalized(
                        joinDate
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date,
                    ),
            )
        }

        SettingsRow(
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint =
                        if (profile.isAuthenticated) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            },
            headlineContent = {
                Text(stringResource(Res.string.authentication))
            },
            supportingContent = {
                Text(
                    text =
                        if (profile.isAuthenticated) {
                            stringResource(Res.string.profile_authenticated)
                        } else {
                            stringResource(Res.string.profile_not_authenticated)
                        },
                    color =
                        if (profile.isAuthenticated) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            },
        )
    }
}

@Composable
private fun ProfileInfoItem(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        modifier = modifier,
    )
}
