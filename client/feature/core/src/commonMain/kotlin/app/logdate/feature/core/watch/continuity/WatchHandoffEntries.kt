package app.logdate.feature.core.watch.continuity

import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sharing.SharingLauncher
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.navigation.taggedEntry
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_handoff_missing
import logdate.client.feature.core.generated.resources.watch_handoff_retry
import logdate.client.feature.core.generated.resources.watch_handoff_share_failed
import logdate.client.feature.core.generated.resources.watch_handoff_share_title
import logdate.client.feature.core.generated.resources.watch_handoff_title
import logdate.client.feature.core.generated.resources.watch_handoff_waiting
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private sealed interface WatchHandoffUiState {
    data object Waiting : WatchHandoffUiState

    data object Missing : WatchHandoffUiState

    data object ShareFailed : WatchHandoffUiState
}

fun EntryProviderScope<NavKey>.watchHandoffEntries(
    onOpenEditor: (String) -> Unit,
    onOpenPlayback: (String) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    taggedEntry<WatchHandoffRoute> { route ->
        val notes = koinInject<JournalNotesRepository>()
        val flags = koinInject<FeatureFlagStore>()
        val sharing = koinInject<SharingLauncher>()
        var state by remember(route.noteId, route.target) { mutableStateOf<WatchHandoffUiState>(WatchHandoffUiState.Waiting) }
        var retry by remember(route.noteId, route.target) { mutableStateOf(0) }
        val shareTitle = stringResource(Res.string.watch_handoff_share_title)
        LaunchedEffect(route.noteId, route.target, retry) {
            state = WatchHandoffUiState.Waiting
            if (!handoffArrived(route.noteId, notes, flags)) {
                state = WatchHandoffUiState.Missing
            } else {
                val note = Uuid.parseOrNull(route.noteId)?.let { notes.getNoteById(it) }
                when (route.target) {
                    "edit" -> {
                        onOpenEditor(route.noteId)
                    }

                    "play" -> {
                        onOpenPlayback(route.noteId)
                    }

                    "day" -> {
                        if (note != null) {
                            onOpenDay(note.creationDate())
                        } else {
                            state = WatchHandoffUiState.Missing
                        }
                    }

                    "share" -> {
                        if (note != null) {
                            if (shareHandoffNote(note, sharing, shareTitle)) onBack() else state = WatchHandoffUiState.ShareFailed
                        } else {
                            state = WatchHandoffUiState.Missing
                        }
                    }

                    else -> {
                        state = WatchHandoffUiState.Missing
                    }
                }
            }
        }
        SettingsScaffold(title = stringResource(Res.string.watch_handoff_title), onBack = onBack) {
            item { if (state == WatchHandoffUiState.Waiting) CircularProgressIndicator() }
            item {
                Text(
                    when (state) {
                        WatchHandoffUiState.Waiting -> stringResource(Res.string.watch_handoff_waiting)
                        WatchHandoffUiState.Missing -> stringResource(Res.string.watch_handoff_missing)
                        WatchHandoffUiState.ShareFailed -> stringResource(Res.string.watch_handoff_share_failed)
                    },
                )
            }
            if (state != WatchHandoffUiState.Waiting) {
                item {
                    Button(onClick = { retry++ }) { Text(stringResource(Res.string.watch_handoff_retry)) }
                }
            }
        }
    }
}

private fun JournalNote.creationDate(): LocalDate {
    val zone =
        timeZoneId?.let { zoneId ->
            runCatching { TimeZone.of(zoneId) }.getOrNull()
        } ?: TimeZone.currentSystemDefault()
    return creationTimestamp.toLocalDateTime(zone).date
}

private suspend fun handoffArrived(
    noteId: String,
    notes: JournalNotesRepository,
    flags: FeatureFlagStore,
): Boolean {
    val id = Uuid.parseOrNull(noteId) ?: return false
    if (!flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2)) return false
    return withTimeoutOrNull(20_000) {
        notes.allNotesObserved.first { items -> items.any { it.uid == id } }
        true
    } ?: false
}

private fun shareHandoffNote(
    note: JournalNote,
    sharing: SharingLauncher,
    title: String,
): Boolean =
    try {
        val text =
            when (note) {
                is JournalNote.Text -> note.content
                is JournalNote.Handwriting -> null
                is JournalNote.Audio -> note.caption
                is JournalNote.Image -> note.caption
                is JournalNote.Video -> note.caption
            }
        if (text == null) {
            false
        } else {
            sharing.shareContent(text = text, mediaUris = listOfNotNull(note.mediaRefOrNull()), chooserTitle = title)
            true
        }
    } catch (e: Exception) {
        Napier.w("Couldn't open sharing for the watch moment", e)
        false
    }
