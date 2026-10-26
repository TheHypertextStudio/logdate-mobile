@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.conflict.SyncConflictRecord
import app.logdate.client.sync.metadata.SyncDeadLetterReason
import app.logdate.client.sync.metadata.effectiveReason
import app.logdate.feature.journals.ui.detail.VideoNoteViewerContent
import app.logdate.shared.model.textContent
import app.logdate.ui.audio.LocalAudioPlaybackState
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import coil3.compose.AsyncImage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.sync_issue_connection
import logdate.client.feature.core.generated.resources.sync_issue_failed
import logdate.client.feature.core.generated.resources.sync_issue_missing_media
import logdate.client.feature.core.generated.resources.sync_issue_retry
import logdate.client.feature.core.generated.resources.sync_issue_sign_in
import logdate.client.feature.core.generated.resources.sync_issue_too_large
import logdate.client.feature.core.generated.resources.sync_recovery_empty
import logdate.client.feature.core.generated.resources.sync_recovery_error
import logdate.client.feature.core.generated.resources.sync_recovery_explanation
import logdate.client.feature.core.generated.resources.sync_recovery_media
import logdate.client.feature.core.generated.resources.sync_recovery_pause
import logdate.client.feature.core.generated.resources.sync_recovery_play
import logdate.client.feature.core.generated.resources.sync_recovery_retry
import logdate.client.feature.core.generated.resources.sync_recovery_save
import logdate.client.feature.core.generated.resources.sync_recovery_saved
import logdate.client.feature.core.generated.resources.sync_recovery_saving
import logdate.client.feature.core.generated.resources.sync_recovery_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun DeletionRecoveryScreen(
    onBack: () -> Unit,
    manager: DefaultSyncManager = koinInject(),
    syncManager: SyncManager = koinInject(),
    sessions: SessionStorage = koinInject(),
) {
    var records by remember { mutableStateOf<List<SyncConflictRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var savingId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val feedback = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val issues by remember(syncManager) { syncManager.observeDeadLetters() }.collectAsState(emptyList())
    LaunchedEffect(sessions, refresh) {
        sessions.getSessionFlow().collect {
            records = emptyList()
            loading = true
            try {
                records = manager.deletionRecovery.list()
                error = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Could not read saved edits")
                error = true
            } finally {
                loading = false
            }
        }
    }
    DeletionRecoveryContent(records, savingId, error, loading, onBack, { id ->
        if (savingId == null) {
            scope.launch {
                savingId = id
                try {
                    check(manager.deletionRecovery.saveAsNew(id) != null)
                    syncManager.sync(startNow = true)
                    refresh++
                    scope.launch { feedback.showSnackbar(getString(Res.string.sync_recovery_saved)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Napier.w("Could not recover a saved edit")
                    error = true
                } finally {
                    savingId = null
                }
            }
        }
    }, { refresh++ }, issues = issues, feedback = feedback, onRetryIssue = { id ->
        scope.launch {
            try {
                syncManager.retryDeadLetter(id)
                syncManager.sync(startNow = true)
                refresh++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Could not retry a retained upload")
                error = true
            }
        }
    })
}

@Composable
fun DeletionRecoveryContent(
    records: List<SyncConflictRecord>,
    savingId: String?,
    error: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onRetry: () -> Unit,
    issues: List<app.logdate.client.sync.metadata.SyncDeadLetterRecord> = emptyList(),
    onRetryIssue: (String) -> Unit = {},
    feedback: SnackbarHostState? = null,
) {
    SettingsScaffold(title = stringResource(Res.string.sync_recovery_title), onBack = onBack, snackbarHostState = feedback) {
        if (records.isNotEmpty()) {
            item {
                Text(
                    stringResource(Res.string.sync_recovery_explanation),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (loading) item { CircularProgressIndicator() }
        if (error) {
            item {
                Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(Res.string.sync_recovery_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.sync_recovery_retry)) }
                }
            }
        }
        if (!loading && !error && records.isEmpty() && issues.isEmpty()) {
            item { Text(stringResource(Res.string.sync_recovery_empty), modifier = Modifier.padding(horizontal = Spacing.lg)) }
        }
        records.forEach { record ->
            val edit = record.deletedEdit
            item(key = "${record.id}:summary") {
                Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    edit?.journal?.let {
                        Text(it.title, style = MaterialTheme.typography.titleLarge)
                        if (it.description.isNotBlank()) Text(it.description)
                    }
                    edit
                        ?.draft
                        ?.textContent()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (record.recovered) {
                        Text(stringResource(Res.string.sync_recovery_saved), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { onSave(record.id) }, enabled = savingId == null) {
                            Text(
                                stringResource(
                                    if (savingId ==
                                        record.id
                                    ) {
                                        Res.string.sync_recovery_saving
                                    } else {
                                        Res.string.sync_recovery_save
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            val preview =
                listOfNotNull(edit?.note) + edit?.notes.orEmpty() +
                    edit?.draft?.blocks.orEmpty().mapNotNull { block ->
                        when (block) {
                            is app.logdate.shared.model.SerializableImageBlock -> {
                                block.uri?.let {
                                    JournalNote.Image(block.id, block.timestamp, block.timestamp, it, block.caption)
                                }
                            }

                            is app.logdate.shared.model.SerializableCameraBlock -> {
                                block.uri?.let {
                                    JournalNote.Image(
                                        block.id,
                                        block.timestamp,
                                        block.timestamp,
                                        it,
                                    )
                                }
                            }

                            is app.logdate.shared.model.SerializableVideoBlock -> {
                                block.uri?.let {
                                    JournalNote.Video(block.id, block.timestamp, block.timestamp, it, block.caption)
                                }
                            }

                            is app.logdate.shared.model.SerializableAudioBlock -> {
                                block.uri?.let {
                                    JournalNote.Audio(
                                        it,
                                        block.duration,
                                        block.id,
                                        block.timestamp,
                                        block.timestamp,
                                        transcription = block.transcription.orEmpty(),
                                    )
                                }
                            }

                            else -> {
                                null
                            }
                        }
                    }
            items(preview, key = { "${record.id}:${it.uid}" }) { RecoveryNotePreview(it) }
            item(key = "${record.id}:end") { HorizontalDivider() }
        }
        items(issues, key = { "issue:${it.id}" }) { issue ->
            Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val message =
                    when (issue.effectiveReason()) {
                        SyncDeadLetterReason.MISSING_FILE -> {
                            Res.string.sync_issue_missing_media
                        }

                        SyncDeadLetterReason.FILE_TOO_LARGE -> {
                            Res.string.sync_issue_too_large
                        }

                        SyncDeadLetterReason.SIGN_IN_REQUIRED -> {
                            Res.string.sync_issue_sign_in
                        }

                        SyncDeadLetterReason.NETWORK_UNAVAILABLE, SyncDeadLetterReason.SERVER_UNAVAILABLE -> {
                            Res.string.sync_issue_connection
                        }

                        else -> {
                            Res.string.sync_issue_failed
                        }
                    }
                Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { onRetryIssue(issue.id) }) { Text(stringResource(Res.string.sync_issue_retry)) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RecoveryAudio(note: JournalNote.Audio) {
    val playback = LocalAudioPlaybackState.current
    var startedHere by remember(note.uid) { mutableStateOf(false) }
    DisposableEffect(note.uid) { onDispose { if (startedHere) playback.stop() } }
    val playing = playback.currentlyPlayingId == note.uid && playback.isPlaying
    TextButton(onClick = {
        startedHere = true
        if (playing) playback.pause() else playback.play(note.uid, note.mediaRef, null)
    }) { Text(stringResource(if (playing) Res.string.sync_recovery_pause else Res.string.sync_recovery_play)) }
}

@Composable
private fun RecoveryNotePreview(note: JournalNote) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (note) {
            is JournalNote.Text -> {
                Text(note.content, style = MaterialTheme.typography.bodyLarge)
            }

            is JournalNote.Image -> {
                var loaded by remember(note.mediaRef) { mutableStateOf(false) }
                AsyncImage(
                    note.mediaRef,
                    stringResource(Res.string.sync_recovery_media),
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp)
                        .testTag(if (loaded) "saved-edit-image-ready" else "saved-edit-image-loading"),
                    onSuccess = { loaded = true },
                )
                if (note.caption.isNotBlank()) Text(note.caption)
            }

            is JournalNote.Video -> {
                VideoNoteViewerContent(note.mediaRef, Modifier.heightIn(max = 320.dp))
                if (note.caption.isNotBlank()) Text(note.caption)
            }

            is JournalNote.Audio -> {
                RecoveryAudio(note)
                if (note.caption.isNotBlank()) Text(note.caption)
                val transcript = note.transcript?.plainText ?: note.transcription
                if (transcript.isNotBlank()) Text(transcript)
            }
        }
    }
}
