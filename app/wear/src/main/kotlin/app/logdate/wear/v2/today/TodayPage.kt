package app.logdate.wear.v2.today

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import app.logdate.client.domain.activity.ActivityKind
import app.logdate.client.domain.activity.ActivityUiState
import app.logdate.client.domain.today.TodaySummary
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.glyphs.LogDateGlyph
import app.logdate.ui.glyphs.vector
import app.logdate.ui.streak.Campfire
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.streak.campfireShortDescription
import app.logdate.wear.R
import app.logdate.wear.capture.Feeling
import app.logdate.wear.v2.ambient.AmbientRecordingFace
import app.logdate.wear.v2.ambient.currentAmbientMode
import app.logdate.wear.v2.moments.formatClockDuration
import app.logdate.wear.v2.moments.momentClockTime
import app.logdate.wear.v2.moments.momentLabel
import app.logdate.wear.v2.recorder.RecordButtonAction
import app.logdate.wear.v2.recorder.RecordEdgeButton
import app.logdate.wear.v2.recorder.RecorderState
import app.logdate.wear.v2.recorder.RecordingRing
import app.logdate.wear.v2.recorder.TodayRecorder
import app.logdate.wear.v2.recorder.gesture.startLocked
import app.logdate.wear.v2.recorder.recordButton
import app.logdate.wear.v2.recorder.recorderItems
import app.logdate.wear.v2.recorder.replacesSummary
import app.logdate.wear.v2.recorder.showsRecordButton
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

object TodayTags {
    const val PAGE = "today_page"
    const val CAMPFIRE = "today_campfire"
    const val SUMMARY = "today_summary"
    const val LATEST = "today_latest"
    const val GET_PHONE_APP = "today_get_phone_app"
    const val SET_UP_PHONE = "today_set_up_phone"
    const val MARK = "today_mark"
    const val ADD = "today_add"
    const val RECORD = "today_record"
    const val MOMENTS = "today_moments"
    const val ACTIVITY_SUGGESTION = "today_activity_suggestion"
    const val MORNING_START = "today_morning_start"
    const val ACTIVITY_DISMISS = "today_activity_dismiss"
    const val VOICE_FIRST = "today_voice_first"
}

/**
 * The controls Today shows. Each appears only when it is set, and the change that makes a control
 * work is the change that sets it, so Today never shows a control that does nothing.
 */
data class TodayControls(
    val recorder: TodayRecorder? = null,
    val onMark: (() -> Unit)? = null,
    val onAdd: (() -> Unit)? = null,
    val onOpenMoments: (() -> Unit)? = null,
)

/**
 * Home: the date in the time text, the campfire, the day's summary line, the latest
 * moment and, when phone setup is unfinished, a card that opens the next step on the phone.
 * Record sits at the end as the edge button, with Mark and + above it; while the person is moving,
 * a Record button also leads the list, so voice capture comes first. While the recorder has
 * something to show, it takes the summary's place: the live recording with Pause, Stop and
 * Discard, then the saved recording with Undo and the five feelings, or what went wrong.
 */
@Composable
fun TodayPage(
    state: TodayUiState,
    handoff: HandoffState,
    onGetPhoneApp: () -> Unit,
    onSetUpPhone: () -> Unit,
    onHandoffDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    controls: TodayControls = TodayControls(),
    onUseActivity: () -> Unit = {},
    onDismissActivity: () -> Unit = {},
    listState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    timeSource: TimeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()),
) {
    val recorderState = controls.recorder?.state
    val ambient = currentAmbientMode()
    if (recorderState is RecorderState.Live && ambient is AmbientMode.Ambient) {
        AmbientRecordingFace(elapsed = recorderState.elapsed, burnInProtection = ambient.isBurnInProtectionRequired, modifier = modifier)
        return
    }
    val timeText: @Composable () -> Unit = { TodayTimeText(state = state, timeSource = timeSource) }
    TodayScaffold(listState = listState, timeText = timeText, recorder = controls.recorder) { contentPadding ->
        if (recorderState is RecorderState.Live) RecordingRing(levels = recorderState.levels, modifier = Modifier.fillMaxSize())
        TodayList(
            state = state,
            handoff = handoff,
            controls = controls,
            onGetPhoneApp = onGetPhoneApp,
            onSetUpPhone = onSetUpPhone,
            onUseActivity = onUseActivity,
            onDismissActivity = onDismissActivity,
            listState = listState,
            contentPadding = contentPadding,
            modifier = modifier,
        )
    }
    val continueOnPhone = OpenOnPhoneDialogDefaults.text
    val curvedTextStyle = OpenOnPhoneDialogDefaults.curvedTextStyle
    OpenOnPhoneDialog(
        visible = handoff == HandoffState.SENT,
        onDismissRequest = onHandoffDismissed,
        curvedText = { openOnPhoneDialogCurvedText(text = continueOnPhone, style = curvedTextStyle) },
    )
}

/**
 * The time text Today shows: the date, when there is one, then the time. [TodayPage] places it in
 * its scaffold, which fades it in; a screenshot places it at the app level to capture it.
 */
@Composable
fun TodayTimeText(
    state: TodayUiState,
    modifier: Modifier = Modifier,
    timeSource: TimeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()),
) {
    val dateText = (state as? TodayUiState.Ready)?.let { ready -> todayDateText(ready.date) }
    TimeText(modifier = modifier, timeSource = timeSource) { time ->
        if (dateText != null) {
            timeTextCurvedText(dateText)
            timeTextSeparator()
        }
        timeTextCurvedText(time)
    }
}

@Composable
private fun TodayScaffold(
    listState: TransformingLazyColumnState,
    timeText: @Composable () -> Unit,
    recorder: TodayRecorder?,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    if (recorder == null) {
        ScreenScaffold(scrollState = listState, timeText = timeText, content = content)
        return
    }
    val edgeButton: @Composable BoxScope.() -> Unit = { if (recorder.state.showsRecordButton) RecordEdgeButton(recorder) }
    ScreenScaffold(scrollState = listState, edgeButton = edgeButton, timeText = timeText, content = content)
}

@Composable
private fun TodayList(
    state: TodayUiState,
    handoff: HandoffState,
    controls: TodayControls,
    onGetPhoneApp: () -> Unit,
    onSetUpPhone: () -> Unit,
    onUseActivity: () -> Unit,
    onDismissActivity: () -> Unit,
    listState: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    val spec = rememberTransformationSpec()
    val recorder = controls.recorder?.takeIf { it.state.replacesSummary }
    val voiceFirst = controls.recorder?.takeIf { recorder == null && leadsWithVoice(state, it) }
    val padding =
        if (recorder == null &&
            voiceFirst == null &&
            startsWithCampfire(state)
        ) {
            contentPadding
        } else {
            contentPadding.belowTimeText()
        }
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = modifier.testTag(TodayTags.PAGE)) {
        if (recorder != null) {
            recorderItems(recorder)
            return@TransformingLazyColumn
        }
        when (state) {
            TodayUiState.Loading -> {
                Unit
            }

            TodayUiState.Failed -> {
                item { CenteredText(stringResource(R.string.wear_today_failed)) }
            }

            is TodayUiState.Ready -> {
                voiceFirst?.let { ready ->
                    item { VoiceFirstButton(ready, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
                }
                state.campfire?.let { campfire -> item { TodayCampfire(campfire) } }
                item { CenteredText(summaryText(state.summary, state.feeling), Modifier.testTag(TodayTags.SUMMARY)) }
                state.summary.latestMoment?.let { latest -> item { LatestMoment(latest, state.timeZone) } }
                activityItems(state.activity, onUseActivity, onDismissActivity, spec)
                if (state.phoneCard is PhoneCard.GetApp) {
                    item {
                        GetPhoneAppCard(
                            handoff = handoff,
                            onClick = onGetPhoneApp,
                            modifier = Modifier.transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        )
                    }
                }
                if (state.phoneCard is PhoneCard.SetUp) {
                    item {
                        SetUpPhoneCard(
                            handoff = handoff,
                            onClick = onSetUpPhone,
                            modifier = Modifier.transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        )
                    }
                }
            }
        }
        controls.onOpenMoments?.let { open ->
            item {
                Button(
                    onClick = open,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag(TodayTags.MOMENTS),
                    transformation = SurfaceTransformation(spec),
                    icon = { Icon(Icons.Filled.ViewTimeline, contentDescription = null) },
                    label = { Text(stringResource(R.string.wear_today_browse_moments)) },
                )
            }
        }
        if (controls.onMark != null || controls.onAdd != null) item { TodayButtonGroup(controls) }
    }
}

private fun startsWithCampfire(state: TodayUiState): Boolean = state is TodayUiState.Ready && state.campfire != null

private fun leadsWithVoice(
    state: TodayUiState,
    recorder: TodayRecorder,
): Boolean = state is TodayUiState.Ready && state.activity.voiceFirst && recorder.state.recordButton().action == RecordButtonAction.START

/** Starts a locked recording, the same as a tap on Record, at the top of Today while the person is moving. */
@Composable
private fun VoiceFirstButton(
    recorder: TodayRecorder,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    Button(
        onClick = { recorder.gesture.startLocked() },
        modifier = modifier.fillMaxWidth().testTag(TodayTags.VOICE_FIRST),
        transformation = transformation,
        icon = { Icon(imageVector = LogDateGlyph.RECORD.vector(), contentDescription = null) },
        label = { Text(stringResource(R.string.wear_today_voice_first)) },
    )
}

/** Text is wider than the campfire, so a list that starts with text clears the curve of the time text. */
@Composable
private fun PaddingValues.belowTimeText(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding() + TIME_TEXT_CLEARANCE,
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding(),
    )
}

@Composable
private fun CenteredText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TodayCampfire(campfire: CampfirePresentation) {
    Campfire(
        phase = campfire.phase,
        size = campfire.size,
        modifier = Modifier.size(campfireSize).testTag(TodayTags.CAMPFIRE),
        waitingForToday = campfire.isWaitingForToday,
        contentDescription = campfireShortDescription(campfire),
    )
}

@Composable
private fun LatestMoment(
    note: JournalNote,
    timeZone: TimeZone,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TodayTags.LATEST),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = momentClockTime(note.creationTimestamp, timeZone),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = momentLabel(note),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The offer to record a moment after a walk, ride or trip, or the morning start after a night's sleep,
 * each with Not now beneath it. Tapping the card starts a recording.
 */
private fun TransformingLazyColumnScope.activityItems(
    activity: ActivityUiState,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
    spec: TransformationSpec,
) {
    val suggestion = activity.suggestion
    val morning = activity.morningStart
    if (suggestion == null && morning == null) return
    item {
        val title: String
        val body: String
        val tag: String
        if (suggestion != null) {
            title = stringResource(R.string.wear_today_activity_title)
            body =
                if (suggestion.activity.kind == ActivityKind.IN_VEHICLE) {
                    pluralStringResource(R.plurals.wear_today_activity_trip_body, suggestion.minutes, suggestion.minutes)
                } else {
                    pluralStringResource(R.plurals.wear_today_activity_moving_body, suggestion.minutes, suggestion.minutes)
                }
            tag = TodayTags.ACTIVITY_SUGGESTION
        } else {
            title = stringResource(R.string.wear_today_morning_title)
            body = stringResource(R.string.wear_today_morning_body)
            tag = TodayTags.MORNING_START
        }
        TitleCard(
            onClick = onUse,
            title = { Text(title) },
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag(tag),
            transformation = SurfaceTransformation(spec),
        ) {
            Text(body)
        }
    }
    item {
        CompactButton(
            onClick = onDismiss,
            label = { Text(stringResource(R.string.wear_today_activity_dismiss)) },
            modifier = Modifier.transformedHeight(this, spec).testTag(TodayTags.ACTIVITY_DISMISS),
            transformation = SurfaceTransformation(spec),
        )
    }
}

@Composable
private fun GetPhoneAppCard(
    handoff: HandoffState,
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    val body = if (handoff == HandoffState.FAILED) R.string.wear_today_get_phone_app_failed else R.string.wear_today_get_phone_app_body
    TitleCard(
        onClick = onClick,
        title = { Text(stringResource(R.string.wear_today_get_phone_app_title)) },
        modifier = modifier.fillMaxWidth().testTag(TodayTags.GET_PHONE_APP),
        enabled = handoff != HandoffState.SENDING,
        transformation = transformation,
    ) {
        Text(stringResource(body))
    }
}

@Composable
private fun SetUpPhoneCard(
    handoff: HandoffState,
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    val body = if (handoff == HandoffState.FAILED) R.string.wear_today_setup_phone_failed else R.string.wear_today_setup_phone_body
    TitleCard(
        onClick = onClick,
        title = { Text(stringResource(R.string.wear_today_setup_phone_title)) },
        modifier = modifier.fillMaxWidth().testTag(TodayTags.SET_UP_PHONE),
        enabled = handoff != HandoffState.SENDING,
        transformation = transformation,
    ) {
        Text(stringResource(body))
    }
}

@Composable
private fun TodayButtonGroup(controls: TodayControls) {
    val addLabel = stringResource(R.string.wear_today_add)
    val markLabel = stringResource(R.string.wear_today_mark)
    ButtonGroup(modifier = Modifier.fillMaxWidth()) {
        controls.onMark?.let { onMark ->
            FilledTonalButton(onClick = onMark, modifier = Modifier.weight(1f).testTag(TodayTags.MARK)) {
                CenteredContent { Icon(imageVector = LogDateGlyph.MARK.vector(), contentDescription = markLabel) }
            }
        }
        controls.onAdd?.let { onAdd ->
            FilledTonalButton(
                onClick = onAdd,
                modifier = Modifier.weight(1f).testTag(TodayTags.ADD).semantics { contentDescription = addLabel },
            ) {
                CenteredContent { Text(stringResource(R.string.wear_today_add_symbol)) }
            }
        }
    }
}

@Composable
private fun RowScope.CenteredContent(content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun summaryText(
    summary: TodaySummary,
    feeling: Feeling?,
): String {
    val line = summaryLine(summary)
    return if (feeling == null) line else "${feeling.emoji} $line"
}

@Composable
private fun summaryLine(summary: TodaySummary): String {
    if (summary.momentCount == 0) return stringResource(R.string.wear_today_no_moments)
    val moments = pluralStringResource(R.plurals.wear_today_moments, summary.momentCount, summary.momentCount)
    val places =
        if (summary.placeCount > 0) {
            pluralStringResource(R.plurals.wear_today_places, summary.placeCount, summary.placeCount)
        } else {
            null
        }
    val voice =
        if (summary.voiceDuration > Duration.ZERO) {
            stringResource(R.string.wear_today_voice, formatClockDuration(summary.voiceDuration))
        } else {
            null
        }
    return listOfNotNull(moments, places, voice).joinToString(stringResource(R.string.wear_today_summary_separator))
}

/** [date] as a short weekday, month and day in the user's locale, such as "Sat, Oct 3". */
@Composable
private fun todayDateText(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, date) {
        val pattern = DateFormat.getBestDateTimePattern(locale, DATE_SKELETON)
        DateTimeFormatter.ofPattern(pattern, locale).format(date.toJavaLocalDate())
    }
}

private const val DATE_SKELETON = "EEEMMMd"
private val campfireSize = 48.dp
private val TIME_TEXT_CLEARANCE = 28.dp
