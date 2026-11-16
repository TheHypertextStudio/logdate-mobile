@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.feature.widgets

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.koin.android.ext.android.inject

private const val LOAD_MORE_THRESHOLD = 10
private const val SEARCH_THRESHOLD = 8

class FixedMemoryWidgetConfigActivity : SecureWidgetConfigActivity() {
    internal override val widgetSetupTarget = WidgetSetupTarget.PINNED_MEMORY
    private val notesRepository: JournalNotesRepository by inject()
    private val transcriptionRepository: TranscriptionRepository by inject()
    private val userStateRepository: UserStateRepository by inject()
    private val widgetId by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }
    private val setup: FixedMemorySetupViewModel by viewModels {
        viewModelFactory {
            initializer {
                FixedMemorySetupViewModel(
                    notesRepository,
                    transcriptionRepository,
                    WidgetInstanceSettings(this@FixedMemoryWidgetConfigActivity).chosenNoteId(widgetId),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        lifecycleScope.launch {
            if (!unlockSetup(userStateRepository)) return@launch
            setContent {
                LogDateTheme {
                    val state by setup.state.collectAsStateWithLifecycle()
                    FixedMemorySetup(
                        state = state,
                        recoveryState = widgetSetupSaveState.collectAsStateWithLifecycle().value,
                        onManageStorage = ::openWidgetStorageSettings,
                        onSearch = setup::search,
                        onLoadMore = setup::loadMore,
                        onSelect = { note ->
                            setup.select(note.uid)
                            cancelPendingWidgetSetup()
                        },
                    ) { note ->
                        persistWidgetSetup(WidgetSetupRequest.pinned(widgetId, note.uid))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixedMemorySetup(
    state: FixedMemorySetupState,
    recoveryState: WidgetSetupSaveState,
    onManageStorage: () -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (JournalNote) -> Unit,
    onDone: (JournalNote) -> Unit,
) {
    val selected = state.selected
    val visibleChoices = state.choices
    val listState = rememberLazyListState()
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, state.canLoadMore) {
        if (!state.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged()
            .filter { nearEnd -> nearEnd }
            .collect { currentOnLoadMore() }
    }
    val context = LocalContext.current
    val displayPreview = selected?.note?.toFixedWidgetState(context, selected.transcript) ?: sampleFixedMemory(context)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Scaffold(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxHeight().fillMaxWidth(),
            topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_fixed_memory_title)) }) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                WidgetSetupRecoveryBanner(recoveryState, onManageStorage)
                if (state.isLoading) {
                    Spacer(Modifier.weight(1f))
                } else if (state.loadedCount == 0) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        WidgetSetupPreview(FixedMemoryWidget(displayPreview), displayPreview)
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(stringResource(R.string.widget_config_fixed_memory_empty), modifier = Modifier.padding(16.dp))
                    }
                } else {
                    WidgetSetupPreview(FixedMemoryWidget(displayPreview), displayPreview)
                    if (state.loadedCount > SEARCH_THRESHOLD || state.query.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        TextField(
                            value = state.query,
                            onValueChange = onSearch,
                            placeholder = { Text(stringResource(R.string.widget_config_fixed_memory_search)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (visibleChoices.isEmpty()) {
                            item(key = "no-matches") {
                                Text(
                                    stringResource(R.string.widget_config_fixed_memory_no_matches),
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(visibleChoices, key = { it.note.uid.toString() }) { choice ->
                            val note = choice.note
                            val content = choice.content
                            val rowTranscript = choice.transcript
                            val thumbnail by produceState<ImageBitmap?>(null, content.imageUri) {
                                value =
                                    content.imageUri?.let { uri ->
                                        withContext(Dispatchers.IO) { loadScaledThumbnail(context, uri)?.asImageBitmap() }
                                    }
                            }
                            WidgetSetupChoice(
                                selected = selected?.note?.uid == note.uid,
                                onClick = { onSelect(note) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val loadedThumbnail = thumbnail
                                    if (loadedThumbnail != null) {
                                        Image(
                                            bitmap = loadedThumbnail,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
                                        )
                                    } else {
                                        val icon =
                                            when (content.kind) {
                                                WidgetEntryKind.AUDIO -> Icons.Rounded.Mic
                                                WidgetEntryKind.TEXT -> Icons.Rounded.Notes
                                                else -> Icons.Rounded.PhotoLibrary
                                            }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(content.summary.ifBlank { rowTranscript ?: content.dateIso }, maxLines = 2)
                                        Text(
                                            LocalDate.parse(content.dateIso).formatForDisplay(context),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (selected?.note?.uid == note.uid) Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { selected?.note?.let(onDone) },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.widget_config_add_widget))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
