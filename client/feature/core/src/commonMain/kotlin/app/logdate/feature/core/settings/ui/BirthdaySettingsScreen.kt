@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.profile.ui.ProfileViewModel
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.DatePickerDialog
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.birthday
import logdate.client.feature.core.generated.resources.birthday_age_just_getting_started
import logdate.client.feature.core.generated.resources.birthday_age_less_than_one_year
import logdate.client.feature.core.generated.resources.birthday_age_prime_of_life
import logdate.client.feature.core.generated.resources.birthday_age_teenage_years
import logdate.client.feature.core.generated.resources.birthday_age_very_wise
import logdate.client.feature.core.generated.resources.birthday_age_wisdom
import logdate.client.feature.core.generated.resources.birthday_age_young_adult
import logdate.client.feature.core.generated.resources.birthday_age_young_and_bright
import logdate.client.feature.core.generated.resources.birthday_detail_description
import logdate.client.feature.core.generated.resources.birthday_not_set
import logdate.client.feature.core.generated.resources.your_birthday
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_confirm
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Detail screen for birthday personalization.
 *
 * Explains what features the user's birthday powers (timeline origin,
 * birthday celebrations, age-aware personalization) and provides a
 * clean settings item to set or change it via a date picker dialog.
 */
@Composable
fun BirthdaySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentBirthday = uiState.localProfile.birthday

    BirthdaySettingsContent(
        currentBirthday = currentBirthday,
        onBack = onBack,
        onSave = viewModel::saveBirthday,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaySettingsContent(
    currentBirthday: Instant?,
    onBack: () -> Unit,
    onSave: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    val resolvedBirthday =
        currentBirthday?.takeIf { it != Instant.DISTANT_PAST }

    val hasBirthday = resolvedBirthday != null

    val formattedBirthday =
        resolvedBirthday?.let {
            formatDateLocalized(it.toLocalDateTime(TimeZone.UTC).date)
        }

    val age by remember(resolvedBirthday) {
        derivedStateOf {
            resolvedBirthday?.let { calculateAge(it) }
        }
    }

    val ageMessage = age?.let { formatAgeMessage(it) } ?: ""

    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
            ) {
                BirthdayHero()
                BirthdayDescription()
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
            ) {
                BirthdaySettingSection(
                    formattedBirthday = formattedBirthday,
                    onOpenDatePicker = { showDatePicker = true },
                )
                BirthdayAgeMessage(
                    hasBirthday = hasBirthday,
                    ageMessage = ageMessage,
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.birthday),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    BirthdayHero()
                }

                item {
                    BirthdayDescription()
                }

                item {
                    BirthdaySettingSection(
                        formattedBirthday = formattedBirthday,
                        onOpenDatePicker = { showDatePicker = true },
                    )
                }

                if (hasBirthday) {
                    item {
                        BirthdayAgeMessage(
                            hasBirthday = hasBirthday,
                            ageMessage = ageMessage,
                        )
                    }
                }
            }
        },
    )

    // Date picker dialog
    if (showDatePicker) {
        val datePickerState =
            rememberDatePickerState(
                initialSelectedDateMillis = resolvedBirthday?.toEpochMilliseconds(),
            )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onSave(Instant.fromEpochMilliseconds(millis))
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(UiRes.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(UiRes.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun BirthdayHero() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Cake,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun BirthdayDescription() {
    Text(
        text = stringResource(Res.string.birthday_detail_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
}

@Composable
private fun BirthdaySettingSection(
    formattedBirthday: String?,
    onOpenDatePicker: () -> Unit,
) {
    SettingsSection(
        title = stringResource(Res.string.your_birthday),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        val birthdayDescription =
            formattedBirthday
                ?: stringResource(Res.string.birthday_not_set)
        SimpleSettingsItem(
            title = stringResource(Res.string.birthday),
            description = birthdayDescription,
            onClick = onOpenDatePicker,
        ) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun BirthdayAgeMessage(
    hasBirthday: Boolean,
    ageMessage: String,
) {
    AnimatedVisibility(
        visible = hasBirthday && ageMessage.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Celebration,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.size(Spacing.md))

            Text(
                text = ageMessage,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Calculate age in years from a birthday Instant.
 */
private fun calculateAge(birthday: Instant): Int {
    val now = Clock.System.now()
    val duration = now - birthday
    return floor(duration.inWholeDays / 365.25).toInt()
}

/**
 * Format an age message based on the age.
 */
@Composable
private fun formatAgeMessage(age: Int): String =
    when {
        age < 0 -> ""
        age == 0 -> stringResource(Res.string.birthday_age_less_than_one_year)
        age < 3 -> pluralStringResource(Res.plurals.birthday_age_just_getting_started, age, age)
        age < 13 -> stringResource(Res.string.birthday_age_young_and_bright, age)
        age < 20 -> stringResource(Res.string.birthday_age_teenage_years, age)
        age < 30 -> stringResource(Res.string.birthday_age_young_adult, age)
        age < 40 -> stringResource(Res.string.birthday_age_prime_of_life, age)
        age < 60 -> stringResource(Res.string.birthday_age_wisdom, age)
        else -> stringResource(Res.string.birthday_age_very_wise, age)
    }
