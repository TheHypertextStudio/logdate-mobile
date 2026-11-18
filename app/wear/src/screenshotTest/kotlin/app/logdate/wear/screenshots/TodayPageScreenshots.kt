package app.logdate.wear.screenshots

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeSource
import app.logdate.client.domain.activity.Activity
import app.logdate.client.domain.activity.ActivityKind
import app.logdate.client.domain.activity.ActivitySuggestion
import app.logdate.client.domain.activity.ActivityUiState
import app.logdate.client.domain.today.TodaySummary
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.streak.CampfirePhase
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.streak.CampfireSize
import app.logdate.wear.capture.Feeling
import app.logdate.wear.v2.recorder.RecorderState
import app.logdate.wear.v2.recorder.TodayRecorder
import app.logdate.wear.v2.theme.LogDateWearTheme
import app.logdate.wear.v2.today.HandoffState
import app.logdate.wear.v2.today.PhoneCard
import app.logdate.wear.v2.today.TodayControls
import app.logdate.wear.v2.today.TodayPage
import app.logdate.wear.v2.today.TodayTimeText
import app.logdate.wear.v2.today.TodayUiState
import com.android.tools.screenshot.PreviewTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Every Today state, on the graphite theme screenshots can render. */
class TodayPageScreenshots {
    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S01_TodayLoading() {
        TodayScene(TodayUiState.Loading)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S02_TodayEmpty() {
        TodayScene(ready(summary = TodaySummary.Empty, campfire = CampfirePresentation(phase = CampfirePhase.UNLIT)))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S03_TodayBusy() {
        TodayScene(ready(summary = busySummary, feeling = Feeling.GOOD))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S04_TodayLatestNote() {
        TodayScene(ready(summary = busySummary.copy(latestMoment = latestNote, voiceDuration = 0.seconds)))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S05_TodayWithoutCampfire() {
        TodayScene(ready(summary = busySummary, campfire = null))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S06_TodayGetPhoneApp() {
        TodayScene(ready(summary = TodaySummary.Empty, phoneCard = PhoneCard.GetApp(nodeId = "phone")))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S07_TodayGetPhoneAppFailed() {
        TodayScene(ready(summary = TodaySummary.Empty, phoneCard = PhoneCard.GetApp(nodeId = "phone")), handoff = HandoffState.FAILED)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S08_TodayFailed() {
        TodayScene(TodayUiState.Failed)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S09_TodayWithControls() {
        TodayScene(ready(summary = busySummary, feeling = Feeling.GOOD), controls = allControls, scrolledToEnd = true)
    }

    @PreviewTest
    @WearLargeFontPreview
    @Composable
    fun S10_TodayBusyLargeFont() {
        TodayScene(ready(summary = busySummary, feeling = Feeling.GOOD))
    }

    @PreviewTest
    @WearLargeFontPreview
    @Composable
    fun S11_TodayWithControlsLargeFont() {
        TodayScene(ready(summary = busySummary, feeling = Feeling.GOOD), controls = allControls, scrolledToEnd = true)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S12_TodayAfterWalk() {
        val walk = Activity(ActivityKind.MOVING, at(8, 10), at(8, 50))
        TodayScene(
            ready(
                summary = TodaySummary.Empty,
                campfire = null,
                activity = ActivityUiState(suggestion = ActivitySuggestion(walk, minutes = 40)),
            ),
        )
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S13_TodayMorningStart() {
        val night = Activity(ActivityKind.SLEEP, at(0, 0) - 1.hours, at(7, 0))
        TodayScene(ready(summary = TodaySummary.Empty, campfire = null, activity = ActivityUiState(morningStart = night)))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S14_TodayWhileMoving() {
        TodayScene(ready(summary = busySummary, activity = ActivityUiState(voiceFirst = true)), controls = allControls)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S15_TodayFinishSetUpOnPhone() {
        TodayScene(ready(summary = TodaySummary.Empty, phoneCard = PhoneCard.SetUp(nodeId = "phone")))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @Composable
    fun S16_TodayFinishSetUpOnPhoneFailed() {
        TodayScene(ready(summary = TodaySummary.Empty, phoneCard = PhoneCard.SetUp(nodeId = "phone")), handoff = HandoffState.FAILED)
    }
}

private const val LAST_ITEM_INDEX = 3
private val zone = TimeZone.currentSystemDefault()
private val saturday = LocalDate(2026, 10, 3)
private val burning = CampfirePresentation(phase = CampfirePhase.BURNING, loggedToday = true, runDays = 4, size = CampfireSize.SMALL)
private val allControls =
    TodayControls(recorder = TodayRecorder(RecorderState.Ready, PreviewRecorder, PreviewRecorder), onMark = {}, onAdd = {})

private fun at(
    hour: Int,
    minute: Int,
): Instant = LocalDateTime(2026, 10, 3, hour, minute).toInstant(zone)

private val latestVoice =
    JournalNote.Audio(mediaRef = "audio/walk.m4a", durationMs = 42_000, creationTimestamp = at(16, 20), lastUpdated = at(16, 20))
private val latestNote =
    JournalNote.Text(
        creationTimestamp = at(17, 5),
        lastUpdated = at(17, 5),
        content = "Lunch by the water with Sam, then the long way home",
    )
private val busySummary =
    TodaySummary(momentCount = 3, placeCount = 2, voiceDuration = 252.seconds, latestMoment = latestVoice, feelingTag = "good")

private fun ready(
    summary: TodaySummary,
    feeling: Feeling? = null,
    campfire: CampfirePresentation? = burning,
    phoneCard: PhoneCard = PhoneCard.None,
    activity: ActivityUiState = ActivityUiState(),
) = TodayUiState.Ready(
    date = saturday,
    timeZone = zone,
    summary = summary,
    feeling = feeling,
    campfire = campfire,
    phoneCard = phoneCard,
    activity = activity,
)

@Composable
private fun TodayScene(
    state: TodayUiState,
    handoff: HandoffState = HandoffState.IDLE,
    controls: TodayControls = TodayControls(),
    scrolledToEnd: Boolean = false,
) {
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = if (scrolledToEnd) LAST_ITEM_INDEX else 0)
    LogDateWearTheme(dynamicColor = false) {
        AppScaffold(timeText = { if (!scrolledToEnd) TodayTimeText(state = state, timeSource = FixedTimeSource) }) {
            TodayPage(
                state = state,
                handoff = handoff,
                onGetPhoneApp = {},
                onSetUpPhone = {},
                onHandoffDismissed = {},
                controls = controls,
                listState = listState,
                timeSource = FixedTimeSource,
            )
        }
    }
}

/** A fixed clock reading, so the time text renders the same on every run. */
private object FixedTimeSource : TimeSource {
    @Composable
    override fun currentTime(): String = "10:09"
}
