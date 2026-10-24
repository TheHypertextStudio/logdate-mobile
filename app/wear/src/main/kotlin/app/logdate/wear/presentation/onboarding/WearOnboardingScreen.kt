package app.logdate.wear.presentation.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.logdate.wear.R
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

private const val ONBOARDING_PREFS = "wear_onboarding"
private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
private const val PERMISSION_GRANTED_DELAY_MS = 600L

/**
 * Horizontal content padding for round screens.
 *
 * On a 227dp round watch face, the inscribed rectangle leaves ~10% of
 * the width clipped by the bezel on each side. 24dp gives comfortable
 * margins for both round and square displays.
 */
private val screenAwareContentPadding = PaddingValues(horizontal = 24.dp)

fun isOnboardingComplete(context: Context): Boolean =
    context
        .getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_COMPLETE, false)

private fun markOnboardingComplete(context: Context) {
    context
        .getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ONBOARDING_COMPLETE, true)
        .apply()
}

@Composable
fun WearOnboardingScreen(
    onComplete: () -> Unit,
    onboardingViewModel: WearOnboardingViewModel = koinViewModel(),
) {
    var currentPage by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    when (currentPage) {
        0 -> {
            WelcomePage(onNext = { currentPage = 1 })
        }

        1 -> {
            PermissionsPage(onNext = { currentPage = 2 })
        }

        2 -> {
            PhoneConnectionPage(
                viewModel = onboardingViewModel,
                onNext = { currentPage = 3 },
            )
        }

        3 -> {
            CompletePage(
                onStart = {
                    markOnboardingComplete(context)
                    onComplete()
                },
            )
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = screenAwareContentPadding,
        ) {
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_welcome_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_welcome_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
            item {
                Button(
                    onClick = onNext,
                    label = { Text(stringResource(R.string.wear_onboarding_welcome_button)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionsPage(onNext: () -> Unit) {
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            micGranted = grants[Manifest.permission.RECORD_AUDIO] == true || micGranted
            locationGranted =
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                locationGranted
        }

    val permissionsState = OnboardingPermissionsUiState(micGranted, locationGranted)

    // Auto-advance once mic and location are both granted. If the user only
    // grants the mic (location is optional but strongly encouraged), they can
    // continue manually via the "Maybe later" button below; geotagging can be
    // enabled afterwards from Settings.
    LaunchedEffect(permissionsState.allRequiredGranted) {
        if (permissionsState.allRequiredGranted) {
            delay(PERMISSION_GRANTED_DELAY_MS)
            onNext()
        }
    }

    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = screenAwareContentPadding,
        ) {
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_permissions_title),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(32.dp)
                            .padding(top = 4.dp),
                    tint =
                        if (micGranted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_permissions_mic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(32.dp)
                            .padding(top = 4.dp),
                    tint =
                        if (locationGranted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_permissions_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (permissionsState.allRequiredGranted) {
                item {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .padding(top = 8.dp),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.wear_onboarding_permissions_granted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                if (permissionsState.showAllowButton) {
                    item {
                        Button(
                            onClick = {
                                permissionLauncher.launch(onboardingPermissionRequest())
                            },
                            label = { Text(stringResource(R.string.wear_onboarding_permissions_allow)) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                        )
                    }
                }
                if (permissionsState.showSkipLocationButton) {
                    item {
                        FilledTonalButton(
                            onClick = onNext,
                            label = { Text(stringResource(R.string.wear_onboarding_permissions_skip)) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneConnectionPage(
    viewModel: WearOnboardingViewModel,
    onNext: () -> Unit,
) {
    val phoneCheckState by viewModel.phoneCheckState.collectAsState()
    val phoneActionState by viewModel.phoneActionState.collectAsState()
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = screenAwareContentPadding,
        ) {
            item(key = "phone-icon") {
                if (phoneCheckState is PhoneCheckState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        imageVector =
                            if (phoneCheckState is PhoneCheckState.Connected) Icons.Default.PhoneAndroid else Icons.Default.PhonelinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item(key = "phone-title") {
                Text(
                    text =
                        stringResource(
                            when (phoneCheckState) {
                                PhoneCheckState.Checking -> R.string.wear_onboarding_phone_checking
                                is PhoneCheckState.Connected -> R.string.wear_onboarding_phone_connected
                                is PhoneCheckState.AppMissing -> R.string.wear_onboarding_phone_app_missing
                                PhoneCheckState.NotConnected -> R.string.wear_onboarding_phone_not_connected
                                PhoneCheckState.Unsupported -> R.string.wear_onboarding_phone_unsupported
                            },
                        ),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            item(key = "phone-guidance") {
                Text(
                    text =
                        stringResource(
                            when (phoneCheckState) {
                                PhoneCheckState.Checking -> R.string.wear_onboarding_phone_checking_detail
                                is PhoneCheckState.Connected -> R.string.wear_onboarding_phone_connected_detail
                                is PhoneCheckState.AppMissing -> R.string.wear_onboarding_phone_app_missing_detail
                                PhoneCheckState.NotConnected -> R.string.wear_onboarding_phone_not_connected_detail
                                PhoneCheckState.Unsupported -> R.string.wear_onboarding_phone_unsupported_detail
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (phoneCheckState is PhoneCheckState.Connected || phoneCheckState is PhoneCheckState.AppMissing) {
                item(key = "phone-setup") {
                    Button(
                        onClick = viewModel::openPhoneSetup,
                        enabled = phoneActionState != PhoneActionState.SENDING,
                        label = {
                            Text(
                                stringResource(
                                    if (phoneCheckState is PhoneCheckState.Connected) {
                                        R.string.wear_onboarding_phone_open_setup
                                    } else {
                                        R.string.wear_onboarding_phone_install
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (phoneActionState == PhoneActionState.SENT || phoneActionState == PhoneActionState.FAILED) {
                item(key = "phone-action-result") {
                    Text(
                        text =
                            stringResource(
                                if (phoneActionState == PhoneActionState.SENT) {
                                    R.string.wear_onboarding_phone_sent
                                } else {
                                    R.string.wear_onboarding_phone_open_failed
                                },
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (phoneCheckState !is PhoneCheckState.Checking && phoneCheckState !is PhoneCheckState.Unsupported) {
                item(key = "phone-recheck") {
                    FilledTonalButton(
                        onClick = viewModel::checkPhoneConnection,
                        label = { Text(stringResource(R.string.wear_onboarding_phone_check_again)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            item(key = "phone-continue") {
                FilledTonalButton(
                    onClick = onNext,
                    label = { Text(stringResource(R.string.wear_onboarding_phone_continue_watch)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CompletePage(onStart: () -> Unit) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = screenAwareContentPadding,
        ) {
            item {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_done_title),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wear_onboarding_done_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = onStart,
                    label = { Text(stringResource(R.string.wear_onboarding_done_button)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                )
            }
        }
    }
}
