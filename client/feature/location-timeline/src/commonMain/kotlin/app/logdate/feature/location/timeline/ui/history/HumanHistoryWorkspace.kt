@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.ui.common.PlatformBackHandler
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.PanelContainment
import app.logdate.ui.workspace.PanelGroup
import app.logdate.ui.workspace.WorkspaceSearchScope
import app.logdate.ui.workspace.WorkspaceSectionSwitch
import app.logdate.ui.workspace.WorkspaceSupportingSheet
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_close_replay
import logdate.client.feature.location.timeline.generated.resources.history_places_count
import logdate.client.feature.location.timeline.generated.resources.history_replay
import logdate.client.feature.location.timeline.generated.resources.history_search
import logdate.client.feature.location.timeline.generated.resources.history_your_day
import logdate.client.feature.location.timeline.generated.resources.history_your_places
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HumanHistoryWorkspace(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier,
    mapContent: @Composable (Modifier) -> Unit,
    toolbarActions: @Composable RowScope.() -> Unit,
    supportingDetail: (@Composable () -> Unit)?,
    initialSupportingExtent: app.logdate.ui.workspace.SupportingExtent,
    mapAvailable: Boolean,
) {
    WorkspaceSearchScope(
        state.placesQuery,
        stringResource(Res.string.history_search),
        actions.onPlacesQuery,
        enabled = state.tab == HistoryTab.Places && supportingDetail == null && !state.detailVisible,
    )
    var replayVisible by rememberSaveable { mutableStateOf(false) }
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    val placesState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    PlatformBackHandler(enabled = state.detailVisible) { actions.onCloseDetail() }
    WorkspaceSupportingSheet(
        initialExtent = initialSupportingExtent,
        focusAvailable = mapAvailable,
        supportingContainment =
            if (state.tab == HistoryTab.Day ||
                supportingDetail != null ||
                state.detailVisible
            ) {
                PanelContainment.Working
            } else {
                PanelContainment.Collection
            },
        summary =
            state.recoveryMessage?.asString()
                ?: if (state.tab == HistoryTab.Day) {
                    state.dateLabel.asString()
                } else {
                    stringResource(Res.string.history_places_count, state.places.size)
                },
        modifier = modifier.fillMaxSize(),
        header = {
            WorkspaceSectionSwitch(
                HistoryTab.entries.map {
                    stringResource(
                        if (it ==
                            HistoryTab.Day
                        ) {
                            Res.string.history_your_day
                        } else {
                            Res.string.history_your_places
                        },
                    )
                },
                state.tab.ordinal,
                { actions.onTab(HistoryTab.entries[it]) },
            )
        },
        headerActions = toolbarActions,
        summaryDetail = state.daySummary.asString().takeIf { state.tab == HistoryTab.Day },
        supportingContextKey = if (supportingDetail != null) "place" else state.selectedItemId.takeIf { state.detailVisible },
        focus = { mapContent(Modifier.fillMaxSize()) },
        supporting = {
            when {
                supportingDetail != null -> {
                    supportingDetail()
                }

                state.detailVisible && state.selectedItem != null -> {
                    HistoryDetail(state.selectedItem!!, actions)
                }

                state.tab == HistoryTab.Places -> {
                    HistoryPlacesList(
                        state,
                        actions,
                        placesState,
                        modifier = if (mapAvailable) Modifier else Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                        mapAvailable = mapAvailable,
                    ) { HistoryRecovery(state, actions) }
                }

                else -> {
                    HistoryDayList(state, actions, Modifier.fillMaxSize(), listState = listState) {
                        HistoryRecovery(state, actions)
                        HistoryDateHeader(state, actions)
                        TextButton(
                            onClick = {
                                replayVisible = !replayVisible
                                actions.onReplayPlaying?.invoke(false)
                            },
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        ) { Text(stringResource(if (replayVisible) Res.string.history_close_replay else Res.string.history_replay)) }
                        if (replayVisible) HistoryReplay(state, actions)
                    }
                }
            }
        },
    )
}

@Composable
private fun HistoryRecovery(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
) {
    state.recoveryMessage?.let { message ->
        PanelGroup(Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text(message.asString())
            state.recoveryActionLabel?.let { label -> actions.onRecover?.let { TextButton(onClick = it) { Text(label.asString()) } } }
        }
    }
}
