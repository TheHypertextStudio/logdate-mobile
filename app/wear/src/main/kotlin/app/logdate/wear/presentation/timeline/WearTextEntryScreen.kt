package app.logdate.wear.presentation.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.R
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun WearTextEntryScreen(
    noteId: Uuid,
    onNavigateBack: () -> Unit,
) {
    val notes = koinInject<JournalNotesRepository>()
    var note by remember(noteId) { mutableStateOf<JournalNote.Text?>(null) }
    var loading by remember(noteId) { mutableStateOf(true) }
    LaunchedEffect(noteId) {
        note =
            try {
                notes.getNoteById(noteId) as? JournalNote.Text
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Couldn't open the watch text entry", error)
                null
            }
        loading = false
    }

    val currentNote = note
    val listState = rememberScalingLazyListState()
    ScreenScaffold(timeText = { TimeText() }, scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
        ) {
            item {
                Text(
                    text = stringResource(R.string.wear_text_entry_title),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                loading -> {
                    item { CircularProgressIndicator() }
                }

                currentNote == null -> {
                    item {
                        Text(
                            text = stringResource(R.string.wear_text_entry_missing),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                else -> {
                    item {
                        Text(
                            text = formatDetailHeader(currentNote.creationTimestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    item {
                        Text(
                            text = currentNote.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onNavigateBack,
                    label = { Text(stringResource(R.string.wear_text_entry_back)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
