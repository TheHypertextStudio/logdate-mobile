package app.logdate.screenshots.audit.adaptive

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.R
import app.logdate.client.awareness.daylight.DaylightPeriod
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.feature.core.account.CloudAccountWelcomeContent
import app.logdate.feature.core.profile.ui.ProfileScreenContent
import app.logdate.feature.core.profile.ui.ProfileUiState
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.feature.editor.ui.video.VideoPlayerContent
import app.logdate.feature.journals.ui.JournalLayoutMode
import app.logdate.feature.journals.ui.JournalListItemUiState
import app.logdate.feature.journals.ui.JournalSortOption
import app.logdate.feature.journals.ui.JournalsOverviewScreenContent
import app.logdate.feature.journals.ui.creation.JournalCreationScreenContent
import app.logdate.feature.journals.ui.detail.AudioNoteViewerContent
import app.logdate.feature.journals.ui.detail.AudioNoteViewerUiState
import app.logdate.feature.journals.ui.detail.AudioPlaybackUiState
import app.logdate.feature.journals.ui.detail.EntryDisplayData
import app.logdate.feature.journals.ui.detail.JournalContext
import app.logdate.feature.journals.ui.detail.JournalDetailScreenContent
import app.logdate.feature.journals.ui.detail.JournalDetailUiState
import app.logdate.feature.journals.ui.detail.NoteViewerScaffoldContent
import app.logdate.feature.journals.ui.detail.NoteViewerShared
import app.logdate.feature.journals.ui.settings.JournalSettingsScreenContent
import app.logdate.feature.journals.ui.settings.JournalSettingsUiState
import app.logdate.feature.journals.ui.share.ShareJournalScreenContent
import app.logdate.feature.journals.ui.share.ShareJournalUiState
import app.logdate.feature.library.ui.detail.MediaDetailContent
import app.logdate.feature.library.ui.detail.MediaDetailUiState
import app.logdate.feature.onboarding.ui.CloudAccountSetupContent
import app.logdate.feature.onboarding.ui.OnboardingStartScreenContent
import app.logdate.feature.onboarding.ui.PersonalIntroContent
import app.logdate.feature.onboarding.ui.PersonalIntroStep
import app.logdate.feature.onboarding.ui.PersonalIntroUiState
import app.logdate.feature.search.ui.SearchScreenContent
import app.logdate.feature.search.ui.SearchScreenState
import app.logdate.screenshots.common.LargeScreenAuditPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.components.library.LibraryScreenshotData
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.profile.LogDateProfile
import app.logdate.ui.audio.AudioContext
import app.logdate.ui.audio.AudioPlaybackDisplayInfo
import app.logdate.ui.audio.AudioPlaybackState
import app.logdate.ui.audio.LocalAudioPlaybackState
import app.logdate.ui.audio.model.AudioPalette
import app.logdate.ui.audio.model.AudioSegment
import app.logdate.ui.audio.model.SegmentType
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val BOOK_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val TABLETOP_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val SAMPLE_IMAGE_URI =
    "android.resource://studio.hypertext.logdate.debug/drawable/sample_note_photo"
private const val SAMPLE_VIDEO_URI = "android.resource://studio.hypertext.logdate.debug/mipmap/ic_launcher"

private val bookPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 708.dp,
                        top = 0.dp,
                        right = 732.dp,
                        bottom = 900.dp,
                        width = 24.dp,
                        height = 900.dp,
                    ),
                isSeparating = true,
            ),
    )

private val tabletopPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 0.dp,
                        top = 438.dp,
                        right = 1440.dp,
                        bottom = 462.dp,
                        width = 1440.dp,
                        height = 24.dp,
                    ),
                isSeparating = true,
            ),
    )

private val auditSearchResults =
    listOf(
        SearchResult(
            uid = Uuid.parse("00000000-0000-0000-0000-000000000121"),
            content = "Captured the train ride home before dinner and tagged it for the weekly rewind.",
            created = ScreenshotTestData.baseInstant,
            contentType = SearchContentType.TEXT_NOTE,
        ),
        SearchResult(
            uid = Uuid.parse("00000000-0000-0000-0000-000000000122"),
            content = "Voice memo about redesigning the journals overview for larger windows.",
            created = ScreenshotTestData.baseInstant - 2.hours,
            contentType = SearchContentType.TRANSCRIPTION,
        ),
    )

private val auditJournalEntries =
    listOf(
        EntryDisplayData.TextEntry(
            id = Uuid.parse("00000000-0000-0000-0000-000000000131"),
            content = "Moved the journal list into a deterministic audit harness for tablet and desktop previews.",
            timestamp = ScreenshotTestData.baseInstant,
        ),
        EntryDisplayData.TextEntry(
            id = Uuid.parse("00000000-0000-0000-0000-000000000132"),
            content = "Trimmed excess horizontal padding so medium-width windows keep both context and readability.",
            timestamp = ScreenshotTestData.baseInstant - 3.hours,
        ),
    )

private val auditJournalState =
    JournalDetailUiState.Success(
        journalId = ScreenshotTestData.sampleJournal.id,
        title = ScreenshotTestData.sampleJournal.title,
        entries = auditJournalEntries,
    )

private val auditJournals =
    ScreenshotTestData.sampleJournals.map { journal ->
        JournalListItemUiState.ExistingJournal(journal)
    } +
        JournalListItemUiState.CreateJournalPlaceholder

internal val auditProfile = LogDateProfile(displayName = "Alex Johnson")
internal val auditAccount =
    LogDateAccount(
        username = "alex_j",
        displayName = "Alex Johnson",
        passkeyCredentialIds = listOf("credential-1"),
        // Pinned: createdAt defaults to Clock.System.now(), and the profile renders it as
        // "Member since <date>", so leaving it unset rots these references every night.
        createdAt = ScreenshotTestData.baseInstant,
    )

private val auditNoteSiblingIds =
    listOf(
        Uuid.parse("00000000-0000-0000-0000-000000000191"),
        Uuid.parse("00000000-0000-0000-0000-000000000192"),
        Uuid.parse("00000000-0000-0000-0000-000000000193"),
    )

private val auditNoteShared =
    NoteViewerShared(
        noteId = auditNoteSiblingIds[1],
        createdAt = ScreenshotTestData.baseInstant,
        lastUpdated = ScreenshotTestData.baseInstant,
        location =
            NoteLocation(
                coordinates = NoteCoordinates(latitude = 37.7749, longitude = -122.4194),
                place =
                    NotePlace(
                        id = Uuid.parse("00000000-0000-0000-0000-000000000194"),
                        name = "Mission District",
                        latitude = 37.7749,
                        longitude = -122.4194,
                    ),
            ),
        journalContext =
            JournalContext(
                journalId = ScreenshotTestData.sampleJournal.id,
                journalTitle = ScreenshotTestData.sampleJournal.title,
                siblingNoteIds = auditNoteSiblingIds,
                currentIndex = 1,
            ),
    )

private val auditAudioContext =
    AudioContext(
        amplitudes = ScreenshotTestData.mockAmplitudes,
        segments =
            listOf(
                AudioSegment(timestampMs = 8_000L, type = SegmentType.SPEECH_ONSET),
                AudioSegment(timestampMs = 34_000L, type = SegmentType.VOLUME_PEAK),
            ),
        daylightPeriod = DaylightPeriod.GOLDEN_HOUR,
        palette =
            AudioPalette(
                waveformGradientStart = 0xFFE8A044,
                waveformGradientEnd = 0xFFD4603A,
                playedFillColor = 0xFFE8A044,
                accentColor = 0xFFE8A044,
                immersiveBackground = 0xFF1A0F05,
            ),
    )

private val auditAudioPlaybackState =
    AudioPlaybackState(
        currentlyPlayingId = Uuid.parse("00000000-0000-0000-0000-000000000195"),
        currentUri = "preview://adaptive-audit/audio",
        isPlaying = true,
        progress = 0.38f,
        duration = 182_000L.milliseconds,
        displayInfo =
            AudioPlaybackDisplayInfo(
                title = "Audio note",
                subtitle = "3:02",
                accentColor = 0xFFE8A044,
            ),
    )

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A01_OnboardingStartLanding() {
    ScreenshotTheme {
        OnboardingStartScreenContent(
            showLanding = true,
            onGetStarted = {},
            onSignIn = {},
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A02_PersonalIntroBioStep() {
    ScreenshotTheme {
        PersonalIntroContent(
            uiState =
                PersonalIntroUiState(
                    currentStep = PersonalIntroStep.Bio,
                    name = "Alex",
                    bio = "Runner, photographer, and obsessive note-taker who likes seeing context stay visible.",
                ),
            onNameChanged = {},
            onBioChanged = {},
            onProceedToBio = {},
            onGoBackToName = {},
            onProcessWithLlm = {},
            onBack = {},
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A03_CloudAccountSetup() {
    ScreenshotTheme {
        CloudAccountSetupContent(
            onBack = {},
            onContinue = {},
            onSkip = {},
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A04_CloudAccountIntro() {
    ScreenshotTheme {
        CloudAccountWelcomeContent(
            onContinue = {},
            onSignIn = {},
            onSkip = {},
            serverSelectionState = ServerSelectionState(),
            onSelectServerPreset = {},
            onCustomServerUrlChange = {},
            onShowCustomServerInfo = {},
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A05_SearchWithResults() {
    ScreenshotTheme {
        SearchScreenContent(
            searchState = SearchScreenState.Results(query = "train", results = auditSearchResults),
            onQueryChange = {},
            onCommitSearch = {},
            onResultClick = {},
            onResultOpenDay = {},
            onGoBack = {},
            queryText = "train",
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A06_JournalsOverview() {
    ScreenshotTheme {
        JournalsOverviewScreenContent(
            journals = auditJournals,
            layoutMode = JournalLayoutMode.CAROUSEL,
            sortOption = JournalSortOption.LAST_UPDATED,
            searchQuery = "audit",
            entryResults = auditSearchResults,
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onNavigateToDay = {},
            onNavigationClick = {},
            onQueryChange = {},
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A07_JournalDetail() {
    ScreenshotTheme {
        JournalDetailScreenContent(
            uiState = auditJournalState,
            onGoBack = {},
        )
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A08_ProfileDefault() {
    val snackbarHostState = remember { SnackbarHostState() }

    ScreenshotTheme {
        ProfileScreenContent(
            uiState =
                ProfileUiState(
                    localProfile = auditProfile,
                    account = auditAccount,
                    userData = null,
                ),
            onBack = {},
            onStartEditingDisplayName = {},
            onCancelEditing = {},
            onSaveDisplayName = {},
            onSaveBio = {},
            onNavigateToBirthday = {},
            snackbarHostState = snackbarHostState,
        )
    }
}

@PreviewTest
@Preview(name = "Journals overview book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A40_JournalsOverviewBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            JournalsOverviewScreenContent(
                journals = auditJournals,
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                searchQuery = "audit",
                entryResults = auditSearchResults,
                onOpenJournal = {},
                onBrowseJournals = {},
                onCreateJournal = {},
                onNavigateToDay = {},
                onNavigationClick = {},
                onQueryChange = {},
                onToggleLayoutMode = {},
                onSortOptionSelected = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Journal detail book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A41_JournalDetailBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            JournalDetailScreenContent(
                uiState = auditJournalState,
                onGoBack = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Empty journals book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A42_EmptyJournalsBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            JournalsOverviewScreenContent(
                journals = emptyList(),
                layoutMode = JournalLayoutMode.CAROUSEL,
                sortOption = JournalSortOption.LAST_UPDATED,
                searchQuery = "",
                entryResults = emptyList(),
                onOpenJournal = {},
                onBrowseJournals = {},
                onCreateJournal = {},
                onNavigateToDay = {},
                onNavigationClick = {},
                onQueryChange = {},
                onToggleLayoutMode = {},
                onSortOptionSelected = {},
            )
        }
    }
}

@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun A43_EmptyJournalsResponsiveStates() {
    ScreenshotTheme {
        JournalsOverviewScreenContent(
            journals = emptyList(),
            layoutMode = JournalLayoutMode.CAROUSEL,
            sortOption = JournalSortOption.LAST_UPDATED,
            searchQuery = "",
            entryResults = emptyList(),
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onNavigateToDay = {},
            onNavigationClick = {},
            onQueryChange = {},
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
        )
    }
}

@PreviewTest
@Preview(name = "Journal creation book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A44_JournalCreationBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            JournalCreationScreenContent(
                onGoBack = {},
                onNewJournal = {},
                initialTitle = "Route Screenshot Rollout",
                selectedNoteIds = setOf(Uuid.parse("00000000-0000-0000-0000-000000000141")),
                selectedMediaUris = listOf("content://logdate/audit-cover.jpg"),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Journal settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A45_JournalSettingsBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            JournalSettingsScreenContent(
                uiState =
                    JournalSettingsUiState.Loaded(
                        journal = ScreenshotTestData.sampleJournal,
                        editedName = "Route Screenshot Rollout",
                        editedDescription = "A journal used to validate foldable settings flows.",
                        hasUnsavedChanges = true,
                    ),
                onGoBack = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Journal settings delete book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A46_JournalSettingsDeleteBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            JournalSettingsScreenContent(
                uiState =
                    JournalSettingsUiState.Loaded(
                        journal = ScreenshotTestData.sampleJournal,
                        editedName = ScreenshotTestData.sampleJournal.title,
                        editedDescription = "A journal used to validate foldable settings flows.",
                    ),
                onGoBack = {},
                showDeleteConfirmation = true,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Share journal book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A47_ShareJournalBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            ShareJournalScreenContent(
                uiState =
                    ShareJournalUiState.Success(
                        journal = ScreenshotTestData.sharedJournal,
                        lastUpdatedDisplay = "Last updated Feb 20, 2025",
                    ),
                onGoBack = {},
                onShareToInstagram = {},
                onShareQrCode = {},
                onShareJournal = {},
                previewCoverPainter = painterResource(R.drawable.sample_note_photo),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Note viewer text book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A48_NoteViewerTextBookPosture() {
    BookPostureNoteViewerScene {
        Text(
            text =
                "Captured the train ride home before the details blurred. The city felt quieter than usual, " +
                    "so I wrote down the stops, the light through the windows, and the idea for tomorrow's entry.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@PreviewTest
@Preview(name = "Note viewer image book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A49_NoteViewerImageBookPosture() {
    BookPostureNoteViewerScene {
        AuditNoteImage()
    }
}

@PreviewTest
@Preview(name = "Note viewer video book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A50_NoteViewerVideoBookPosture() {
    BookPostureNoteViewerScene {
        AuditNoteVideo()
    }
}

@PreviewTest
@Preview(name = "Note viewer audio book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A51_NoteViewerAudioBookPosture() {
    FoldableAudioNoteViewerScene(bookPostureLayoutInfo)
}

@PreviewTest
@Preview(name = "Note viewer text tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A52_NoteViewerTextTabletopPosture() {
    TabletopPostureNoteViewerScene {
        Text(
            text =
                "Captured the train ride home before the details blurred. The top pane keeps the note readable " +
                    "while the lower pane keeps navigation and journal actions reachable.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@PreviewTest
@Preview(name = "Note viewer image tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A53_NoteViewerImageTabletopPosture() {
    TabletopPostureNoteViewerScene {
        AuditNoteImage()
    }
}

@PreviewTest
@Preview(name = "Note viewer video tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A54_NoteViewerVideoTabletopPosture() {
    TabletopPostureNoteViewerScene {
        AuditNoteVideo()
    }
}

@PreviewTest
@Preview(name = "Note viewer audio tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A55_NoteViewerAudioTabletopPosture() {
    FoldableAudioNoteViewerScene(tabletopPostureLayoutInfo)
}

@PreviewTest
@Preview(name = "Media detail image book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A56_MediaDetailImageBookPosture() {
    FoldableMediaDetailScene(
        foldableLayoutInfo = bookPostureLayoutInfo,
        state = LibraryScreenshotData.imageDetail.copy(mediaRef = SAMPLE_IMAGE_URI),
    )
}

@PreviewTest
@Preview(name = "Media detail video book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A57_MediaDetailVideoBookPosture() {
    FoldableMediaDetailScene(
        foldableLayoutInfo = bookPostureLayoutInfo,
        state = LibraryScreenshotData.videoDetail.copy(mediaRef = SAMPLE_VIDEO_URI),
    )
}

@PreviewTest
@Preview(name = "Media detail image tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A58_MediaDetailImageTabletopPosture() {
    FoldableMediaDetailScene(
        foldableLayoutInfo = tabletopPostureLayoutInfo,
        state = LibraryScreenshotData.imageDetail.copy(mediaRef = SAMPLE_IMAGE_URI),
    )
}

@PreviewTest
@Preview(name = "Media detail video tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A59_MediaDetailVideoTabletopPosture() {
    FoldableMediaDetailScene(
        foldableLayoutInfo = tabletopPostureLayoutInfo,
        state = LibraryScreenshotData.videoDetail.copy(mediaRef = SAMPLE_VIDEO_URI),
    )
}

@Composable
private fun FoldableMediaDetailScene(
    foldableLayoutInfo: FoldableLayoutInfo,
    state: MediaDetailUiState,
) {
    provideFoldableLayoutInfo(foldableLayoutInfo) {
        ScreenshotTheme {
            MediaDetailContent(
                state = state,
                isExpanded = true,
                onBack = {},
            )
        }
    }
}

@Composable
private fun BookPostureNoteViewerScene(noteContent: @Composable () -> Unit) {
    FoldableNoteViewerScene(
        foldableLayoutInfo = bookPostureLayoutInfo,
        noteContent = noteContent,
    )
}

@Composable
private fun TabletopPostureNoteViewerScene(noteContent: @Composable () -> Unit) {
    FoldableNoteViewerScene(
        foldableLayoutInfo = tabletopPostureLayoutInfo,
        noteContent = noteContent,
    )
}

@Composable
private fun FoldableNoteViewerScene(
    foldableLayoutInfo: FoldableLayoutInfo,
    noteContent: @Composable () -> Unit,
) {
    provideFoldableLayoutInfo(foldableLayoutInfo) {
        ScreenshotTheme {
            NoteViewerScaffoldContent(
                shared = auditNoteShared,
                onGoBack = {},
                noteContent = noteContent,
            )
        }
    }
}

@Composable
private fun AuditNoteImage() {
    Image(
        painter = painterResource(R.drawable.sample_note_photo),
        contentDescription = null,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun AuditNoteVideo() {
    VideoPlayerContent(
        uri = SAMPLE_VIDEO_URI,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large),
    )
}

@Composable
private fun FoldableAudioNoteViewerScene(foldableLayoutInfo: FoldableLayoutInfo) {
    provideFoldableLayoutInfo(foldableLayoutInfo) {
        ScreenshotTheme {
            CompositionLocalProvider(LocalAudioPlaybackState provides auditAudioPlaybackState) {
                AudioNoteViewerContent(
                    uiState =
                        AudioNoteViewerUiState.Ready(
                            mediaRef = "preview://adaptive-audit/audio",
                            durationMs = 182_000L,
                            createdAt = ScreenshotTestData.baseInstant,
                            context = auditAudioContext,
                            playbackState = AudioPlaybackUiState(progress = 0.38f, isPlaying = true),
                        ),
                    onGoBack = {},
                )
            }
        }
    }
}
