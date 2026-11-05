@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.logdate.client.location.settings.DefaultLocation
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.platform.PlatformKind
import app.logdate.ui.platform.rememberPlatformKind
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.developer_location
import logdate.client.feature.core.generated.resources.location_default_location
import logdate.client.feature.core.generated.resources.location_default_location_altitude
import logdate.client.feature.core.generated.resources.location_default_location_clear
import logdate.client.feature.core.generated.resources.location_default_location_description
import logdate.client.feature.core.generated.resources.location_default_location_invalid_altitude
import logdate.client.feature.core.generated.resources.location_default_location_invalid_latitude
import logdate.client.feature.core.generated.resources.location_default_location_invalid_longitude
import logdate.client.feature.core.generated.resources.location_default_location_latitude
import logdate.client.feature.core.generated.resources.location_default_location_longitude
import logdate.client.feature.core.generated.resources.location_default_location_save
import logdate.client.feature.core.generated.resources.location_server_assist
import logdate.client.feature.core.generated.resources.location_server_assist_description
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeveloperLocationSection(
    settings: LocationTrackingSettings,
    onToggleServerAssist: (Boolean) -> Unit,
    onSetDefaultLocation: (DefaultLocation?) -> Unit,
    modifier: Modifier = Modifier,
) {
    DeveloperLocationSection(
        state = LocationSettingsState.Available(settings),
        onToggleServerAssist = onToggleServerAssist,
        onSetDefaultLocation = onSetDefaultLocation,
        modifier = modifier,
    )
}

@Composable
fun DeveloperLocationSection(
    state: LocationSettingsState,
    onToggleServerAssist: (Boolean) -> Unit,
    onSetDefaultLocation: (DefaultLocation?) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (LocationSetting) -> Unit = {},
    onReload: () -> Unit = {},
) {
    val available = state as? LocationSettingsState.Available
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        SettingsSection(title = stringResource(Res.string.developer_location)) {
            LocationReadFeedback(state, onReload)
            LocationToggleSetting(
                title = stringResource(Res.string.location_server_assist),
                description = stringResource(Res.string.location_server_assist_description),
                state = state,
                setting = LocationSetting.SERVER_ASSIST,
                value = { it.serverAssistEnabled },
                onCheckedChange = onToggleServerAssist,
                onRetry = onRetry,
                onReload = onReload,
            )
        }
        if (rememberPlatformKind() == PlatformKind.Desktop) {
            if (available == null) {
                SettingsSection(title = stringResource(Res.string.location_default_location)) {
                    LocationSettingAvailability(stringResource(Res.string.location_default_location), state, onReload)
                }
            } else {
                DefaultLocationSettingsSection(
                    defaultLocation = available.settings.defaultLocation,
                    state = available,
                    onSetDefaultLocation = onSetDefaultLocation,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/**
 * Desktop-only fallback location editor for devices without reliable location services.
 */
@Composable
private fun DefaultLocationSettingsSection(
    defaultLocation: DefaultLocation?,
    state: LocationSettingsState.Available,
    onSetDefaultLocation: (DefaultLocation?) -> Unit,
    onRetry: (LocationSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    var latitude by
        remember {
            mutableStateOf(defaultLocation?.latitude?.toString().orEmpty())
        }
    var longitude by
        remember {
            mutableStateOf(defaultLocation?.longitude?.toString().orEmpty())
        }
    var altitude by
        remember {
            mutableStateOf(defaultLocation?.altitudeValue?.toString().orEmpty())
        }
    val operation = state.operation(LocationSetting.DEFAULT_LOCATION)
    val saving = operation is LocationSettingOperation.Saving
    LaunchedEffect(defaultLocation, operation) {
        if (!saving && operation !is LocationSettingOperation.Failed) {
            latitude = defaultLocation?.latitude?.toString().orEmpty()
            longitude = defaultLocation?.longitude?.toString().orEmpty()
            altitude = defaultLocation?.altitudeValue?.toString().orEmpty()
        }
    }
    val validation =
        validateDefaultLocationInput(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
        )

    SettingsSection(
        title = stringResource(Res.string.location_default_location),
        modifier = modifier,
    ) {
        Text(stringResource(Res.string.location_default_location_description))
        OutlinedTextField(
            value = latitude,
            onValueChange = { latitude = it },
            label = { Text(stringResource(Res.string.location_default_location_latitude)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = validation.latitudeError != null,
            supportingText = validation.latitudeError?.let { error -> { Text(error.message()) } },
            singleLine = true,
            enabled = !saving,
        )
        OutlinedTextField(
            value = longitude,
            onValueChange = { longitude = it },
            label = { Text(stringResource(Res.string.location_default_location_longitude)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = validation.longitudeError != null,
            supportingText = validation.longitudeError?.let { error -> { Text(error.message()) } },
            singleLine = true,
            enabled = !saving,
        )
        OutlinedTextField(
            value = altitude,
            onValueChange = { altitude = it },
            label = { Text(stringResource(Res.string.location_default_location_altitude)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = validation.altitudeError != null,
            supportingText = validation.altitudeError?.let { error -> { Text(error.message()) } },
            singleLine = true,
            enabled = !saving,
        )
        Button(
            enabled = validation.canSave && !saving,
            onClick = {
                validation.location?.let(onSetDefaultLocation)
            },
        ) {
            Text(stringResource(Res.string.location_default_location_save))
        }
        TextButton(onClick = { onSetDefaultLocation(null) }, enabled = !saving && defaultLocation != null) {
            Text(stringResource(Res.string.location_default_location_clear))
        }
        LocationOperationFeedback(state, LocationSetting.DEFAULT_LOCATION, onRetry)
    }
}

internal data class DefaultLocationInputValidation(
    val location: DefaultLocation?,
    val latitudeError: DefaultLocationInputError? = null,
    val longitudeError: DefaultLocationInputError? = null,
    val altitudeError: DefaultLocationInputError? = null,
) {
    val canSave: Boolean =
        location != null &&
            latitudeError == null &&
            longitudeError == null &&
            altitudeError == null
}

internal enum class DefaultLocationInputError {
    InvalidLatitude,
    InvalidLongitude,
    InvalidAltitude,
}

internal fun validateDefaultLocationInput(
    latitude: String,
    longitude: String,
    altitude: String,
): DefaultLocationInputValidation {
    val parsedLatitude = latitude.trim().toDoubleOrNull()
    val parsedLongitude = longitude.trim().toDoubleOrNull()
    val trimmedAltitude = altitude.trim()
    val parsedAltitude = if (trimmedAltitude.isBlank()) 0.0 else trimmedAltitude.toDoubleOrNull()

    val latitudeError =
        if (parsedLatitude == null || parsedLatitude !in -90.0..90.0) {
            DefaultLocationInputError.InvalidLatitude
        } else {
            null
        }
    val longitudeError =
        if (parsedLongitude == null || parsedLongitude !in -180.0..180.0) {
            DefaultLocationInputError.InvalidLongitude
        } else {
            null
        }
    val altitudeError =
        if (parsedAltitude == null) {
            DefaultLocationInputError.InvalidAltitude
        } else {
            null
        }

    val location =
        if (latitudeError == null && longitudeError == null && altitudeError == null) {
            DefaultLocation(
                latitude = checkNotNull(parsedLatitude),
                longitude = checkNotNull(parsedLongitude),
                altitudeValue = checkNotNull(parsedAltitude),
            )
        } else {
            null
        }

    return DefaultLocationInputValidation(
        location = location,
        latitudeError = latitudeError,
        longitudeError = longitudeError,
        altitudeError = altitudeError,
    )
}

@Composable
private fun DefaultLocationInputError.message(): String =
    when (this) {
        DefaultLocationInputError.InvalidLatitude ->
            stringResource(Res.string.location_default_location_invalid_latitude)
        DefaultLocationInputError.InvalidLongitude ->
            stringResource(Res.string.location_default_location_invalid_longitude)
        DefaultLocationInputError.InvalidAltitude ->
            stringResource(Res.string.location_default_location_invalid_altitude)
    }
