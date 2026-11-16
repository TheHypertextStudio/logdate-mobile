@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.ui.common.DatePickerDialog
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

const val ONBOARDING_BIRTHDAY_ROOT_TAG = "onboarding_birthday_root"
const val ONBOARDING_BIRTHDAY_SET_TAG = "onboarding_birthday_set"
const val ONBOARDING_BIRTHDAY_CONFIRM_TAG = "onboarding_birthday_confirm"

@Composable
fun OnboardingBirthdayScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    persistBirthday: suspend (Instant) -> Result<Unit>,
) {
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            isSaving = false
        }
    }

    OnboardingBirthdayContent(
        onBack = onBack,
        onBirthdaySelected = { birthday ->
            coroutineScope.launch {
                isSaving = true
                errorMessage = null
                persistBirthday(birthday)
                    .onSuccess {
                        isSaving = false
                        onNext()
                    }.onFailure {
                        errorMessage = getString(Res.string.onboarding_error_save_birthday)
                    }
            }
        },
        isSaving = isSaving,
        errorMessage = errorMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingBirthdayContent(
    onBack: () -> Unit,
    onBirthdaySelected: (Instant) -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    StepScaffold(
        title = stringResource(Res.string.onboarding_birthday_title),
        onBack = onBack,
        modifier = Modifier.testTag(ONBOARDING_BIRTHDAY_ROOT_TAG),
        supportingText = stringResource(Res.string.onboarding_birthday_body),
        hero = { StepHeroIcon(Icons.Rounded.Cake) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepPrimaryButton(
                text = stringResource(Res.string.onboarding_birthday_set),
                onClick = { showDatePicker = true },
                busy = isSaving,
                modifier = Modifier.testTag(ONBOARDING_BIRTHDAY_SET_TAG),
            )
            OnboardingActionError(errorMessage)
        },
    ) {
        OverviewItem(
            title = stringResource(Res.string.onboarding_birthday_card_rewind_title),
            description = stringResource(Res.string.onboarding_birthday_card_rewind_description),
            icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
        )
        OverviewItem(
            title = stringResource(Res.string.onboarding_birthday_card_origin_title),
            description = stringResource(Res.string.onboarding_birthday_card_origin_description),
            icon = { Icon(Icons.Rounded.Timeline, contentDescription = null) },
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(selectableDates = remember { BirthdaySelectableDates() })

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onBirthdaySelected(Instant.fromEpochMilliseconds(millis))
                        }
                        showDatePicker = false
                    },
                    modifier = Modifier.testTag(ONBOARDING_BIRTHDAY_CONFIRM_TAG),
                ) {
                    Text(stringResource(Res.string.onboarding_birthday_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(Res.string.onboarding_birthday_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * Only days before [today] can be a birthday. Age checks rely on this date, so today and later
 * cannot be picked, typed, or confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class BirthdaySelectableDates(
    private val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
) : SelectableDates {
    // The picker reports each calendar day as its UTC midnight, whatever the device's zone.
    private val todayUtcMillis = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis < todayUtcMillis

    override fun isSelectableYear(year: Int): Boolean = year <= today.year
}

@Preview
@Composable
private fun OnboardingBirthdayScreenPreview() {
    LogDateTheme {
        OnboardingBirthdayContent(
            onBack = {},
            onBirthdaySelected = {},
        )
    }
}
