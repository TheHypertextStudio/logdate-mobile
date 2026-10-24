@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture

enum class SharedScreenshotSceneId(
    val value: String,
) {
    OnboardingStartSplash("onboarding-start-splash"),
    OnboardingStartLanding("onboarding-start-landing"),
    PersonalIntroName("personal-intro-name"),
    PersonalIntroBio("personal-intro-bio"),
    OnboardingOverview("onboarding-overview"),
    MemoriesImportInfo("memories-import-info"),
    MemorySelectionPopulated("memory-selection-populated"),
    CloudAccountSetupCompact("cloud-account-setup-compact"),
    OnboardingBirthday("onboarding-birthday"),
    OnboardingRecommendations("onboarding-recommendations"),
    OnboardingDayBoundariesConnected("onboarding-day-boundaries-connected"),
    OnboardingLocation("onboarding-location"),
    OnboardingNotifications("onboarding-notifications"),
    OnboardingCompletionStreak("onboarding-completion-streak"),
    OnboardingCompletionCampfire("onboarding-completion-campfire"),
    OnboardingCompletionFinal("onboarding-completion-final"),
    OnboardingWelcomeBack("onboarding-welcome-back"),
    MemorySelectionEmpty("memory-selection-empty"),
    MemorySelectionError("memory-selection-error"),
    MemorySelectionLoading("memory-selection-loading"),
    RecommendationsSaving("recommendations-saving"),
    DayBoundariesPermissionsNeeded("day-boundaries-permissions-needed"),
    DayBoundariesChecking("day-boundaries-checking"),
    NotificationsDecisionHandled("notifications-decision-handled"),
    CloudAccountSelectedSignIn("cloud-account-selected-sign-in"),
    CloudAccountAdaptiveLargeScreen("cloud-account-adaptive-large-screen"),
    MemorySelectionAdaptiveLargeScreen("memory-selection-adaptive-large-screen"),
    CloudAccountWelcome("cloud-account-welcome"),
    CloudAccountSignIn("cloud-account-sign-in"),
    PasskeyAccountCreationFinal("passkey-account-creation-final"),
    SettingsOverview("settings-overview"),
    SettingsRows("settings-components"),
    AccountSettings("account-settings"),
    PrivacySettings("privacy-settings"),
    SignInMethods("sign-in-methods"),
    RecoveryPhrase("recovery-phrase"),
    RecoveryPhraseConfirmed("recovery-phrase-confirmed"),
    RecoveryPhraseFinishSetup("recovery-phrase-finish-setup"),
    Hosting("hosting"),
    DeleteAccount("delete-account"),
    DataSettings("data-settings"),
    MemoriesSettings("memories-settings"),
    VoiceNotesSettings("voice-notes-settings"),
    QuietSaveSettings("quiet-save-settings"),
    SyncSettings("sync-settings"),
    NativeBackupSignedOut("native-backup-signed-out"),
    NativeBackupReady("native-backup-ready"),
    NativeBackupQuota("native-backup-quota"),
    JournalStoragePaused("journal-storage-paused"),
    JournalStorageSealed("journal-storage-sealed"),
    SyncUpdateRequired("sync-update-required"),
    StoragePreviews("storage-previews"),
    LocationSettings("location-settings"),
    LocationTrackingOptions("location-tracking-options"),
    LocationInterval("location-interval"),
    DeveloperTools("developer-tools"),
    FeaturePreviews("feature-previews"),
    DevicesSettings("devices-settings"),
    DevicesLinkedWatch("devices-linked-watch"),
    StreakSettings("streak-settings"),
    CampfireStates("campfire-states"),
    CampfireStreakUnlit("campfire-streak-unlit"),
    CampfireStreakBurning("campfire-streak-burning"),
    CampfireStreakWaiting("campfire-streak-waiting"),
    CampfireStreakEmbers("campfire-streak-embers"),
    CampfireStreakOut("campfire-streak-out"),
    CampfireStreakRekindled("campfire-streak-rekindled"),
    CampfireStreakTrackingOff("campfire-streak-tracking-off"),
    TimelineSettings("timeline-settings"),
    DayBoundarySettings("day-boundary-settings"),
    LibrarySettings("library-settings"),
    RecommendationSettings("recommendation-settings"),
    BirthdaySettings("birthday-settings"),
    AdvancedSettings("advanced-settings"),
    LabsSettings("labs-settings"),
    WatchSettings("watch-settings"),
    WatchSettingsReporting("watch-settings-reporting"),
    WatchSettingsAttention("watch-settings-attention"),
    WatchNotificationSettings("watch-notification-settings"),
    WatchTroubleshooting("watch-troubleshooting"),
    WatchTroubleshootingRequestFailed("watch-troubleshooting-request-failed"),
    EventsSettings("events-settings"),
    CalendarSyncSettings("calendar-sync-settings"),
    SearchIdle("search-idle"),
    SearchSearching("search-searching"),
    SearchEmpty("search-empty"),
    SearchResults("search-results"),
    RewindCoverCard("rewind-cover-card"),
    RewindOverviewCanonical("rewind-overview-canonical"),
    RewindDetailPopulated("rewind-detail-populated"),
    RewindLoading("rewind-loading"),
    RewindError("rewind-error"),
    PastRewinds("past-rewinds"),
    RewindSettings("rewind-settings"),
    RewindUpgradeChip("rewind-upgrade-chip"),
    FirstRewindOnboardingSheet("first-rewind-onboarding-sheet"),
    DayBoundaryRecovery("day-boundary-recovery"),
    CapturedOnWatch("captured-on-watch"),
    CapturedDuringActivity("captured-during-activity"),
}

enum class ScreenshotSceneGroup {
    ONBOARDING,
    SEARCH,
    REWIND,
    SETTINGS,
    SECURITY,
    TIMELINE,
}

/**
 * A window a shared scene renders into.
 *
 * @property fontScale The text scale, where `2f` is the 200% text size people pick in accessibility
 *   settings.
 * @property rightToLeft Whether the scene lays out right-to-left, as it would in Arabic or Hebrew.
 */
enum class ScreenshotViewportId(
    val baselineSuffix: String,
    val widthDp: Int,
    val heightDp: Int,
    val darkTheme: Boolean = false,
    val foldablePosture: FoldablePosture? = null,
    val fontScale: Float = 1f,
    val rightToLeft: Boolean = false,
) {
    PHONE_LIGHT("phone", 411, 891),
    PHONE_DARK("phone-dark", 411, 891, darkTheme = true),
    COMPACT_PHONE("compact-phone", 320, 568),
    PHONE_RTL("phone-rtl", 411, 891, rightToLeft = true),
    PHONE_LARGE_TEXT("phone-large-text", 411, 891, fontScale = 2f),

    /** A floating window about the size Android gives an app bubble. */
    BUBBLE("bubble", 360, 480),
    PHONE_LANDSCAPE("phone-landscape", 891, 411),
    TABLET("tablet", 1280, 800),
    SPLIT_MEDIUM("split-medium", 700, 900),
    TABLET_PORTRAIT("tablet-portrait", 800, 1280),
    DESKTOP_WINDOW("desktop-window", 1440, 900),
    DESKTOP("desktop", 1280, 800),
    DESKTOP_TALL("desktop-tall", 1280, 900),
    DESKTOP_WIDE("desktop-wide", 1366, 900),
    FOLDABLE_BOOK("foldable-book", 1440, 900, foldablePosture = FoldablePosture.Book),
    FOLDABLE_TABLETOP("foldable-tabletop", 1440, 900, foldablePosture = FoldablePosture.Tabletop),
    ;

    /** A half-opened foldable of this viewport's size, with its hinge centered across the fold. */
    val foldableLayoutInfo: FoldableLayoutInfo?
        get() = foldablePosture?.let { posture -> halfOpenedFoldable(posture, widthDp, heightDp) }
}

private val HingeThickness = 24.dp

private fun halfOpenedFoldable(
    posture: FoldablePosture,
    widthDp: Int,
    heightDp: Int,
): FoldableLayoutInfo {
    val width = widthDp.dp
    val height = heightDp.dp
    val bounds =
        when (posture) {
            FoldablePosture.Tabletop -> {
                val top = (height - HingeThickness) / 2
                FoldableHingeBounds(0.dp, top, width, top + HingeThickness, width, HingeThickness)
            }

            else -> {
                val left = (width - HingeThickness) / 2
                FoldableHingeBounds(left, 0.dp, left + HingeThickness, height, HingeThickness, height)
            }
        }
    return FoldableLayoutInfo(
        isFoldable = true,
        posture = posture,
        hinge =
            FoldableHingeInfo(
                orientation =
                    if (posture == FoldablePosture.Tabletop) {
                        FoldableHingeOrientation.Horizontal
                    } else {
                        FoldableHingeOrientation.Vertical
                    },
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = bounds,
                isSeparating = true,
            ),
    )
}

data class ScreenshotSceneVariant(
    val viewport: ScreenshotViewportId,
)

data class SharedScreenshotSceneSpec(
    val id: SharedScreenshotSceneId,
    val group: ScreenshotSceneGroup,
    val variants: List<ScreenshotSceneVariant>,
    val content: @Composable () -> Unit,
)

internal val standardMatrixVariants =
    listOf(
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_LIGHT),
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_DARK),
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_LANDSCAPE),
        ScreenshotSceneVariant(ScreenshotViewportId.TABLET),
    )

/**
 * Scenes for Android OS integration surfaces render in the standard matrix plus the configurations
 * people use system surfaces in: a compact phone, right-to-left, and 200% text.
 */
internal val osIntegrationVariants =
    listOf(
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_LIGHT),
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_DARK),
        ScreenshotSceneVariant(ScreenshotViewportId.COMPACT_PHONE),
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_LANDSCAPE),
        ScreenshotSceneVariant(ScreenshotViewportId.TABLET),
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_RTL),
        ScreenshotSceneVariant(ScreenshotViewportId.PHONE_LARGE_TEXT),
    )

/** OS integration scenes that can appear inside an app bubble also render at bubble size. */
internal val osIntegrationBubbleVariants =
    osIntegrationVariants + ScreenshotSceneVariant(ScreenshotViewportId.BUBBLE)

/** Onboarding steps must hold up on a half-folded device as well as the standard matrix. */
internal val onboardingStepVariants =
    standardMatrixVariants +
        listOf(
            ScreenshotSceneVariant(ScreenshotViewportId.FOLDABLE_BOOK),
            ScreenshotSceneVariant(ScreenshotViewportId.FOLDABLE_TABLETOP),
        )

internal val largeScreenAuditVariants =
    listOf(
        ScreenshotSceneVariant(ScreenshotViewportId.SPLIT_MEDIUM),
        ScreenshotSceneVariant(ScreenshotViewportId.TABLET),
        ScreenshotSceneVariant(ScreenshotViewportId.TABLET_PORTRAIT),
        ScreenshotSceneVariant(ScreenshotViewportId.DESKTOP_WINDOW),
    )

internal val desktopOnlyVariants =
    listOf(
        ScreenshotSceneVariant(ScreenshotViewportId.DESKTOP),
    )

internal val desktopTallVariants =
    listOf(
        ScreenshotSceneVariant(ScreenshotViewportId.DESKTOP_TALL),
    )

internal val desktopWideVariants =
    listOf(
        ScreenshotSceneVariant(ScreenshotViewportId.DESKTOP_WIDE),
    )
