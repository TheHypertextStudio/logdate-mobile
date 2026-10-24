package app.logdate.wear.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.continuity.HandoffFailure
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.platform.LogDateHaptics
import app.logdate.wear.R
import app.logdate.wear.launch.WearLaunchRequest
import app.logdate.wear.presentation.camera.WearRemoteCameraScreen
import app.logdate.wear.presentation.camera.WearRemoteCameraViewModel
import app.logdate.wear.sync.PhoneReachability
import app.logdate.wear.v2.ambient.WatchAmbientScope
import app.logdate.wear.v2.archive.ArchiveScreen
import app.logdate.wear.v2.archive.ArchiveViewModel
import app.logdate.wear.v2.capture.CaptureConfirmation
import app.logdate.wear.v2.capture.CaptureSheet
import app.logdate.wear.v2.capture.CaptureSheetViewModel
import app.logdate.wear.v2.capture.rememberRemoteTextInput
import app.logdate.wear.v2.continuity.WatchPlaybackHandoff
import app.logdate.wear.v2.flow.FlowActions
import app.logdate.wear.v2.flow.FlowPage
import app.logdate.wear.v2.flow.FlowViewModel
import app.logdate.wear.v2.mark.MarkConfirmation
import app.logdate.wear.v2.mark.MarkViewModel
import app.logdate.wear.v2.memories.MemoriesScreen
import app.logdate.wear.v2.memories.MemoriesViewModel
import app.logdate.wear.v2.moments.MomentActions
import app.logdate.wear.v2.moments.MomentScreen
import app.logdate.wear.v2.moments.MomentViewModel
import app.logdate.wear.v2.navigation.WearRoute
import app.logdate.wear.v2.navigation.toV2BackStack
import app.logdate.wear.v2.permissions.rememberPermissionRequester
import app.logdate.wear.v2.playback.MomentPlayer
import app.logdate.wear.v2.playon.OutputPicker
import app.logdate.wear.v2.playon.OutputPickerRoute
import app.logdate.wear.v2.recorder.RecorderState
import app.logdate.wear.v2.recorder.RecorderViewModel
import app.logdate.wear.v2.recorder.TodayRecorder
import app.logdate.wear.v2.recorder.canStartRecording
import app.logdate.wear.v2.recorder.gesture.startLocked
import app.logdate.wear.v2.settings.PhoneSettingsHandoffDialog
import app.logdate.wear.v2.settings.WatchSettingsScreen
import app.logdate.wear.v2.settings.WatchSettingsViewModel
import app.logdate.wear.v2.settings.rememberWatchSettingsActions
import app.logdate.wear.v2.theme.LogDateWearTheme
import app.logdate.wear.v2.today.TodayControls
import app.logdate.wear.v2.today.TodayPage
import app.logdate.wear.v2.today.TodayViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

object WearAppV2Tags {
    const val SHELL = "wear_app_v2_shell"
}

/**
 * The redesigned watch app, shown while WATCH_EXPERIENCE_V2 is on.
 *
 * [initialRequest] picks the first back stack; a recreated activity restores the one it saved
 * instead. Each item of [newRequests] replaces the back stack, as a cold launch from a tile does.
 * Swiping right dismisses the top screen. Each screen's view models live as long as its entry; the
 * recorder and Mark are resolved here, outside every entry. The recorder belongs to the app so a
 * locked session continues on the watch face; permissions are requested here. While recording,
 * the app stays on screen in ambient mode where the watch supports it.
 */
@Composable
fun WearAppV2(
    initialRequest: WearLaunchRequest?,
    newRequests: Flow<WearLaunchRequest>,
) {
    val recorder = koinInject<RecorderViewModel>()
    val marker = koinViewModel<MarkViewModel>()
    val recorderState by recorder.state.collectAsState()
    val backStack = rememberNavBackStack(*initialRequest.toV2BackStack().toTypedArray())
    var recordLaunchHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (recordLaunchHandled || initialRequest != WearLaunchRequest.Record) return@LaunchedEffect
        recordLaunchHandled = true
        recorder.startForLaunch()
    }
    LaunchedEffect(newRequests) {
        newRequests.collect { request ->
            backStack.clear()
            backStack.addAll(request.toV2BackStack())
            if (request != WearLaunchRequest.Record) return@collect
            recordLaunchHandled = true
            recorder.startForLaunch()
        }
    }
    val open: (WearRoute) -> Unit = { route -> if (backStack.lastOrNull() != route) backStack.add(route) }
    PermissionRequests(recorder, marker)
    WatchAmbientScope(enabled = recorderState is RecorderState.Live) {
        LogDateWearTheme {
            CompositionLocalProvider(LocalLogDateHaptics provides koinInject<LogDateHaptics>()) {
                AppScaffold(modifier = Modifier.testTag(WearAppV2Tags.SHELL)) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.removeLastOrNull() },
                        entryDecorators =
                            listOf(
                                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                                rememberViewModelStoreNavEntryDecorator<NavKey>(),
                            ),
                        sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy<NavKey>()),
                        entryProvider =
                            entryProvider {
                                entry<WearRoute.Home> {
                                    HomeEntry(recorder, marker, onAdd = { open(WearRoute.CaptureSheet) }, navigate = open)
                                }
                                entry<WearRoute.Flow> { FlowEntry(navigate = open) }
                                entry<WearRoute.Memories> { MemoriesEntry(navigate = open) }
                                entry<WearRoute.Settings> { SettingsEntry() }
                                entry<WearRoute.Moment> { route -> MomentEntry(route.id, onClose = { backStack.removeLastOrNull() }) }
                                entry<WearRoute.Archive> { ArchiveEntry(navigate = open) }
                                entry<WearRoute.CaptureSheet> {
                                    CaptureSheetEntry(
                                        onOpenPhoneCamera = { open(WearRoute.PhoneCamera) },
                                        onDone = { backStack.removeLastOrNull() },
                                    )
                                }
                                entry<WearRoute.PhoneCamera> { PhoneCameraEntry(onDone = { backStack.removeLastOrNull() }) }
                            },
                    )
                }
            }
        }
    }
}

/** Starts a locked recording for an external Record action, leaving active recording or saving alone. */
private fun RecorderViewModel.startForLaunch() {
    if (!state.value.canStartRecording) return
    startLocked()
}

/** Asks for the permissions the recorder and Mark need at the moment they need them, and gives them the answers. */
@Composable
private fun PermissionRequests(
    recorder: RecorderViewModel,
    marker: MarkViewModel,
) {
    val requester =
        rememberPermissionRequester { permission, result ->
            recorder.onPermissionResult(permission, result)
            marker.onPermissionResult(permission)
        }
    LaunchedEffect(recorder, marker, requester) {
        merge(recorder.permissionRequests, marker.permissionRequests).collect { permission -> requester.request(permission) }
    }
}

@Composable
private fun HomeEntry(
    recorder: RecorderViewModel,
    marker: MarkViewModel,
    onAdd: () -> Unit,
    navigate: (WearRoute) -> Unit,
) {
    val recorderState by recorder.state.collectAsState()
    val markFeedback by marker.feedback.collectAsState()
    val controls =
        remember(recorderState, recorder, marker) {
            TodayControls(
                recorder = TodayRecorder(recorderState, gesture = recorder, actions = recorder),
                onMark = marker::mark,
                onAdd = onAdd,
                onOpenMoments = { navigate(WearRoute.Flow) },
            )
        }
    TodayEntry(controls)
    MarkConfirmation(feedback = markFeedback, onDismiss = marker::dismissFeedback)
}

@Composable
private fun TodayEntry(controls: TodayControls) {
    val viewModel = koinViewModel<TodayViewModel>()
    val state by viewModel.uiState.collectAsState()
    val handoff by viewModel.handoff.collectAsState()
    TodayPage(
        state = state,
        handoff = handoff,
        onGetPhoneApp = viewModel::getPhoneApp,
        onSetUpPhone = viewModel::setUpPhone,
        onHandoffDismissed = viewModel::dismissHandoff,
        controls = controls,
        onUseActivity = {
            controls.recorder?.gesture?.startLocked()
            viewModel.usedActivity()
        },
        onDismissActivity = viewModel::dismissActivity,
    )
}

@Composable
private fun FlowEntry(navigate: (WearRoute) -> Unit) {
    val viewModel = koinViewModel<FlowViewModel>()
    val player = koinInject<MomentPlayer>()
    val state by viewModel.uiState.collectAsState()
    val playback by player.state.collectAsState()
    val openMoment: (JournalNote) -> Unit = { note -> navigate(WearRoute.Moment(note.uid.toString())) }
    FlowPage(
        state = state,
        playback = playback,
        actions =
            FlowActions(
                onOpenMoment = openMoment,
                onPlayPause = player::playOrPause,
                onOpenArchive = { navigate(WearRoute.Archive) },
                onOpenMemories = { navigate(WearRoute.Memories) },
                onOpenSettings = { navigate(WearRoute.Settings) },
            ),
    )
}

@Composable
private fun MemoriesEntry(navigate: (WearRoute) -> Unit) {
    val viewModel = koinViewModel<MemoriesViewModel>()
    val cards by viewModel.cards.collectAsState()
    MemoriesScreen(cards = cards, onOpenMoment = { note -> navigate(WearRoute.Moment(note.uid.toString())) })
}

@Composable
private fun SettingsEntry() {
    val viewModel = koinViewModel<WatchSettingsViewModel>()
    val state by viewModel.uiState.collectAsState()
    val actions = rememberWatchSettingsActions(viewModel)
    WatchSettingsScreen(state = state, actions = actions)
    PhoneSettingsHandoffDialog(state.handoff, viewModel::dismissHandoff)
}

@Composable
private fun ArchiveEntry(navigate: (WearRoute) -> Unit) {
    val viewModel = koinViewModel<ArchiveViewModel>()
    val player = koinInject<MomentPlayer>()
    val state by viewModel.uiState.collectAsState()
    val playback by player.state.collectAsState()
    ArchiveScreen(
        state = state,
        playback = playback,
        onOpenMoment = { note -> navigate(WearRoute.Moment(note.uid.toString())) },
        onPlayPause = player::playOrPause,
    )
}

@Composable
private fun MomentEntry(
    id: String,
    onClose: () -> Unit,
) {
    val viewModel = koinViewModel<MomentViewModel>(parameters = { parametersOf(id) })
    val state by viewModel.uiState.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    val picker = koinInject<OutputPicker>()
    val canPick = picker.route != OutputPickerRoute.NONE
    val handoff = koinInject<WatchPlaybackHandoff>()
    val reachability = koinInject<PhoneReachability>()
    val scope = rememberCoroutineScope()
    val openingMessage = stringResource(R.string.wear_moment_phone_opening)
    val playingMessage = stringResource(R.string.wear_moment_phone_playing)
    val playingOpenFailedMessage = stringResource(R.string.wear_moment_phone_playing_open_failed)
    val audioNotReadyMessage = stringResource(R.string.wear_moment_phone_audio_not_ready)
    val needsSetupMessage = stringResource(R.string.wear_moment_phone_needs_setup)
    val playFailedMessage = stringResource(R.string.wear_moment_phone_play_failed)
    val openedMessage = stringResource(R.string.wear_moment_phone_opened)
    val openRetryFailedMessage = stringResource(R.string.wear_moment_phone_playing_open_retry_failed)
    val openFailedMessage = stringResource(R.string.wear_moment_phone_open_failed)
    val outputFailedMessage = stringResource(R.string.wear_moment_output_open_failed)
    var phoneAvailable by remember { mutableStateOf(false) }
    var handoffBusy by remember { mutableStateOf(false) }
    var handoffMessage by remember { mutableStateOf<String?>(null) }
    var phoneOpenFailed by remember { mutableStateOf(false) }
    val playOn: (() -> Unit)? =
        if (canPick) {
            ({ handoffMessage = if (picker.open()) null else outputFailedMessage })
        } else {
            null
        }
    LaunchedEffect(handoff, reachability) {
        reachability.observe().collect { phoneAvailable = it != null && handoff.available() }
    }
    val onPhone: (() -> Unit)? =
        if (phoneAvailable && !handoffBusy) {
            (
                {
                    scope.launch {
                        handoffBusy = true
                        phoneOpenFailed = false
                        handoffMessage = openingMessage
                        val noteId = kotlin.uuid.Uuid.parse(id)
                        val failure = handoff.playOnPhone(noteId)
                        handoffMessage =
                            when (failure) {
                                null -> {
                                    phoneOpenFailed = !handoff.openOnPhone(noteId, target = "play")
                                    if (phoneOpenFailed) {
                                        playingOpenFailedMessage
                                    } else {
                                        playingMessage
                                    }
                                }

                                HandoffFailure.AUDIO_NOT_ON_PHONE -> {
                                    audioNotReadyMessage
                                }

                                HandoffFailure.PHONE_NEEDS_SETUP -> {
                                    needsSetupMessage
                                }

                                else -> {
                                    playFailedMessage
                                }
                            }
                        handoffBusy = false
                    }
                }
            )
        } else {
            null
        }
    LaunchedEffect(deleted) {
        if (deleted) onClose()
    }
    MomentScreen(
        state = state,
        actions =
            MomentActions(
                onPlayPause = {
                    handoffMessage = null
                    viewModel.playPause()
                },
                onCrown = viewModel::onCrown,
                onCycleSpeed = viewModel::cycleSpeed,
                onSkipSilenceChange = viewModel::setSkipSilence,
                onDelete = viewModel::delete,
                onPlayOn = playOn,
                onPlayOnPhone = if (phoneOpenFailed) null else onPhone,
                onOpenOnPhone =
                    if (phoneAvailable && !handoffBusy) {
                        (
                            {
                                scope.launch {
                                    handoffBusy = true
                                    val retryPlaybackView = phoneOpenFailed
                                    val opened =
                                        handoff.openOnPhone(
                                            kotlin.uuid.Uuid.parse(id),
                                            target = if (retryPlaybackView) "play" else "edit",
                                        )
                                    phoneOpenFailed = retryPlaybackView && !opened
                                    handoffMessage =
                                        when {
                                            opened && retryPlaybackView -> playingMessage
                                            opened -> openedMessage
                                            retryPlaybackView -> openRetryFailedMessage
                                            else -> openFailedMessage
                                        }
                                    handoffBusy = false
                                }
                            }
                        )
                    } else {
                        null
                    },
                phoneHandoffMessage = handoffMessage,
            ),
    )
}

@Composable
private fun CaptureSheetEntry(
    onOpenPhoneCamera: () -> Unit,
    onDone: () -> Unit,
) {
    val viewModel = koinViewModel<CaptureSheetViewModel>()
    val state by viewModel.uiState.collectAsState()
    val write = rememberRemoteTextInput(onText = viewModel::saveText)
    CaptureSheet(
        state = state,
        onWrite = write,
        onToggleFeelings = viewModel::toggleFeelings,
        onFeeling = viewModel::saveFeeling,
        onPhoneCamera = onOpenPhoneCamera,
    )
    CaptureConfirmation(result = state.result, onSavedDismissed = onDone, onFailedDismissed = viewModel::dismissResult)
}

/**
 * The existing remote camera screen. It asks to leave by setting its state rather than calling back
 * after composition, so it leaves from an effect here instead of during composition.
 */
@Composable
private fun PhoneCameraEntry(onDone: () -> Unit) {
    val viewModel = koinViewModel<WearRemoteCameraViewModel>()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.navigateBack) {
        if (state.navigateBack) onDone()
    }
    if (state.navigateBack) return
    WearRemoteCameraScreen(viewModel = viewModel, onNavigateBack = {})
}
