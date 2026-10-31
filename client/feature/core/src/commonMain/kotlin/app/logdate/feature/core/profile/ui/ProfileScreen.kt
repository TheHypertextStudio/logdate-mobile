@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.profile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.text.resolve
import app.logdate.feature.core.streak.CampfireViewModel
import app.logdate.shared.model.profile.LogDateProfile
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.DefaultSettingsContentContainer
import app.logdate.ui.common.applyScreenStyles
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.birthday
import logdate.client.feature.core.generated.resources.profile
import logdate.client.feature.core.generated.resources.profile_updated_successfully
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onNavigateToBirthday: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
    campfireViewModel: CampfireViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val campfire by campfireViewModel.presentation.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val profileUpdatedMessage = stringResource(Res.string.profile_updated_successfully)

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message.resolve())
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.updateState) {
        if (uiState.updateState is ProfileUpdateState.Success) {
            snackbarHostState.showSnackbar(profileUpdatedMessage)
            viewModel.clearUpdateState()
        } else if (uiState.updateState is ProfileUpdateState.Error) {
            snackbarHostState.showSnackbar((uiState.updateState as ProfileUpdateState.Error).message.resolve())
            viewModel.clearUpdateState()
        }
    }

    ProfileScreenContent(
        uiState = uiState,
        campfire = campfire,
        onBack = onBack,
        onNavigateToBirthday = onNavigateToBirthday,
        onStartEditingDisplayName = viewModel::startEditingDisplayName,
        onCancelEditing = viewModel::cancelEditing,
        onSaveDisplayName = viewModel::saveDisplayName,
        onSaveBio = viewModel::saveBio,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreenContent(
    uiState: ProfileUiState,
    campfire: CampfirePresentation? = null,
    onBack: () -> Unit,
    onNavigateToBirthday: () -> Unit,
    onStartEditingDisplayName: () -> Unit,
    onCancelEditing: () -> Unit,
    onSaveDisplayName: (String) -> Unit,
    onSaveBio: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val profile =
        createProfileDisplayModel(
            localProfile = uiState.localProfile,
            account = uiState.account,
            userData = uiState.userData,
        )

    Scaffold(
        modifier =
            modifier
                .applyScreenStyles()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(UiRes.string.common_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column {
            AnimatedVisibility(
                visible = uiState.updateState is ProfileUpdateState.Updating,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            ProfileAdaptiveContent(
                profile = profile,
                editState = uiState.editState,
                campfire = campfire,
                contentPadding = paddingValues,
                onNavigateToBirthday = onNavigateToBirthday,
                onStartEditingDisplayName = onStartEditingDisplayName,
                onCancelEditing = onCancelEditing,
                onSaveDisplayName = onSaveDisplayName,
                onSaveBio = onSaveBio,
            )
        }
    }
}

@Composable
private fun ProfileAdaptiveContent(
    profile: ProfileDisplayModel,
    editState: ProfileEditState,
    campfire: CampfirePresentation?,
    contentPadding: PaddingValues,
    onNavigateToBirthday: () -> Unit,
    onStartEditingDisplayName: () -> Unit,
    onCancelEditing: () -> Unit,
    onSaveDisplayName: (String) -> Unit,
    onSaveBio: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            ProfileContentList(
                profile = profile,
                editState = editState,
                campfire = campfire,
                contentPadding = contentPadding,
                onNavigateToBirthday = onNavigateToBirthday,
                onStartEditingDisplayName = onStartEditingDisplayName,
                onCancelEditing = onCancelEditing,
                onSaveDisplayName = onSaveDisplayName,
                onSaveBio = onSaveBio,
                includeSections = false,
            )
        },
        endPane = {
            ProfileContentList(
                profile = profile,
                editState = editState,
                campfire = campfire,
                contentPadding = contentPadding,
                onNavigateToBirthday = onNavigateToBirthday,
                onStartEditingDisplayName = onStartEditingDisplayName,
                onCancelEditing = onCancelEditing,
                onSaveDisplayName = onSaveDisplayName,
                onSaveBio = onSaveBio,
                includeHeader = false,
            )
        },
        standardContent = {
            DefaultSettingsContentContainer {
                ProfileContentList(
                    profile = profile,
                    editState = editState,
                    campfire = campfire,
                    contentPadding = contentPadding,
                    onNavigateToBirthday = onNavigateToBirthday,
                    onStartEditingDisplayName = onStartEditingDisplayName,
                    onCancelEditing = onCancelEditing,
                    onSaveDisplayName = onSaveDisplayName,
                    onSaveBio = onSaveBio,
                )
            }
        },
    )
}

@Composable
private fun ProfileContentList(
    profile: ProfileDisplayModel,
    editState: ProfileEditState,
    campfire: CampfirePresentation?,
    contentPadding: PaddingValues,
    onNavigateToBirthday: () -> Unit,
    onStartEditingDisplayName: () -> Unit,
    onCancelEditing: () -> Unit,
    onSaveDisplayName: (String) -> Unit,
    onSaveBio: (String) -> Unit,
    includeHeader: Boolean = true,
    includeSections: Boolean = true,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (includeHeader) {
            item {
                ProfileHeader(
                    profile = profile,
                    editState = editState,
                    onStartEditingDisplayName = onStartEditingDisplayName,
                    onCancelEditing = onCancelEditing,
                    onSaveDisplayName = onSaveDisplayName,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.lg)
                            .padding(vertical = Spacing.lg),
                )
            }
        }

        if (includeSections) {
            item {
                ProfilePersonalInformationSection(
                    profile = profile,
                    onNavigateToBirthday = onNavigateToBirthday,
                    onSaveBio = onSaveBio,
                )
            }

            if (campfire != null) {
                item {
                    ProfileCampfireSection(campfire = campfire)
                }
            }

            if (profile.hasCloudAccount) {
                item {
                    ProfileAccountInformationSection(profile = profile)
                }
            }
        }
    }
}

@Preview
@Composable
fun ProfileScreenContentPreview() {
    ProfileScreenContent(
        uiState =
            ProfileUiState(
                localProfile =
                    LogDateProfile(
                        displayName = "John Doe",
                        birthday = null,
                    ),
                account = null,
                userData = null,
                isLoading = false,
                editState = ProfileEditState.None,
                updateState = ProfileUpdateState.Idle,
            ),
        onBack = {},
        onNavigateToBirthday = {},
        onStartEditingDisplayName = {},
        onCancelEditing = {},
        onSaveDisplayName = {},
        onSaveBio = {},
        snackbarHostState = SnackbarHostState(),
    )
}
