@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.logdate.client.domain.today.TodaySummary
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailureMessage
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_provider_campfire
import logdate.client.feature.core.generated.resources.watch_provider_mark
import logdate.client.feature.core.generated.resources.watch_provider_record
import logdate.client.feature.core.generated.resources.watch_v2_campfire
import logdate.client.feature.core.generated.resources.watch_v2_campfire_provider
import logdate.client.feature.core.generated.resources.watch_v2_complications
import logdate.client.feature.core.generated.resources.watch_v2_complications_description
import logdate.client.feature.core.generated.resources.watch_v2_fix_open
import logdate.client.feature.core.generated.resources.watch_v2_handwriting
import logdate.client.feature.core.generated.resources.watch_v2_mark
import logdate.client.feature.core.generated.resources.watch_v2_mark_provider
import logdate.client.feature.core.generated.resources.watch_v2_memory_preview_label
import logdate.client.feature.core.generated.resources.watch_v2_memory_provider
import logdate.client.feature.core.generated.resources.watch_v2_microphone
import logdate.client.feature.core.generated.resources.watch_v2_microphone_fix
import logdate.client.feature.core.generated.resources.watch_v2_photo
import logdate.client.feature.core.generated.resources.watch_v2_preview_label
import logdate.client.feature.core.generated.resources.watch_v2_record
import logdate.client.feature.core.generated.resources.watch_v2_record_provider
import logdate.client.feature.core.generated.resources.watch_v2_tile_empty
import logdate.client.feature.core.generated.resources.watch_v2_tile_memory_empty
import logdate.client.feature.core.generated.resources.watch_v2_tile_moments
import logdate.client.feature.core.generated.resources.watch_v2_tile_years
import logdate.client.feature.core.generated.resources.watch_v2_tiles
import logdate.client.feature.core.generated.resources.watch_v2_tiles_anniversary
import logdate.client.feature.core.generated.resources.watch_v2_tiles_anniversary_description
import logdate.client.feature.core.generated.resources.watch_v2_tiles_today_description
import logdate.client.feature.core.generated.resources.watch_v2_today
import logdate.client.feature.core.generated.resources.watch_v2_video
import logdate.client.feature.core.generated.resources.watch_v2_voice
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun WatchTilesScreen(onBack: () -> Unit) {
    val preview: WatchTodayPreviewViewModel = koinViewModel()
    val today by preview.summary.collectAsState()
    val memory by preview.memory.collectAsState()
    SettingsScaffold(title = stringResource(Res.string.watch_v2_tiles), onBack = onBack) {
        item { WatchSurfaceGallery(today, memory) }
        item {
            app.logdate.ui.common.SettingsSection(
                stringResource(Res.string.watch_v2_complications),
                Modifier.padding(horizontal = Spacing.lg),
            ) {
                ComplicationProvider(
                    Res.drawable.watch_provider_campfire,
                    Res.string.watch_v2_campfire,
                    Res.string.watch_v2_campfire_provider,
                )
                ComplicationProvider(Res.drawable.watch_provider_record, Res.string.watch_v2_record, Res.string.watch_v2_record_provider)
                ComplicationProvider(Res.drawable.watch_provider_mark, Res.string.watch_v2_mark, Res.string.watch_v2_mark_provider)
                SimpleSettingsItem(
                    stringResource(Res.string.watch_v2_tiles_anniversary),
                    stringResource(Res.string.watch_v2_memory_provider),
                    onClick = null,
                    action = {},
                )
            }
        }
        item {
            MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                SimpleSettingsItem(
                    stringResource(
                        Res.string.watch_v2_today,
                    ),
                    stringResource(Res.string.watch_v2_tiles_today_description),
                    onClick = null,
                    action = {
                    },
                )
                SimpleSettingsItem(
                    stringResource(
                        Res.string.watch_v2_tiles_anniversary,
                    ),
                    stringResource(Res.string.watch_v2_tiles_anniversary_description),
                    onClick = null,
                    action = {
                    },
                )
                SimpleSettingsItem(
                    stringResource(
                        Res.string.watch_v2_complications,
                    ),
                    stringResource(Res.string.watch_v2_complications_description),
                    onClick = null,
                    action = {
                    },
                )
            }
        }
    }
}

@Composable
private fun ComplicationProvider(
    glyph: org.jetbrains.compose.resources.DrawableResource,
    title: org.jetbrains.compose.resources.StringResource,
    description: org.jetbrains.compose.resources.StringResource,
) {
    SettingsRow(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        leadingContent = {
            androidx.compose.material3.Icon(
                org.jetbrains.compose.resources
                    .painterResource(glyph),
                null,
            )
        },
    )
}

@Composable
private fun WatchSurfaceGallery(
    today: TodaySummary?,
    memory: WatchMemoryPreview?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        app.logdate.ui.watch.NativeTodayTilePreview(
            today?.let {
                app.logdate.ui.watch.TodayTilePresentation(
                    title = stringResource(Res.string.watch_v2_today),
                    count =
                        if (it.momentCount == 0) {
                            stringResource(Res.string.watch_v2_tile_empty)
                        } else {
                            org.jetbrains.compose.resources.pluralStringResource(
                                Res.plurals.watch_v2_tile_moments,
                                it.momentCount,
                                it.momentCount,
                            )
                        },
                    latest =
                        when (val note = it.latestMoment) {
                            is app.logdate.client.repository.journals.JournalNote.Text -> {
                                note.content.take(30)
                            }

                            is app.logdate.client.repository.journals.JournalNote.Handwriting -> {
                                stringResource(Res.string.watch_v2_handwriting)
                            }

                            is app.logdate.client.repository.journals.JournalNote.Audio -> {
                                stringResource(Res.string.watch_v2_voice)
                            }

                            is app.logdate.client.repository.journals.JournalNote.Image -> {
                                stringResource(Res.string.watch_v2_photo)
                            }

                            is app.logdate.client.repository.journals.JournalNote.Video -> {
                                stringResource(Res.string.watch_v2_video)
                            }

                            null -> {
                                null
                            }
                        },
                    record = stringResource(Res.string.watch_v2_record),
                )
            },
        )
        Text(stringResource(Res.string.watch_v2_preview_label), style = MaterialTheme.typography.labelMedium)
        if (memory != null) {
            app.logdate.ui.watch.NativeOnThisDayTilePreview(
                app.logdate.ui.watch.OnThisDayTilePresentation(
                    title = stringResource(Res.string.watch_v2_tiles_anniversary),
                    yearsAgo =
                        memory.takeIf { it.memory != null }?.let {
                            org.jetbrains.compose.resources
                                .pluralStringResource(Res.plurals.watch_v2_tile_years, it.yearsAgo, it.yearsAgo)
                        },
                    summary = memory.memory?.summary ?: stringResource(Res.string.watch_v2_tile_memory_empty),
                ),
            )
        }
        Text(stringResource(Res.string.watch_v2_memory_preview_label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun WatchMicrophoneScreen(
    onBack: () -> Unit,
    onOpenWatch: () -> Unit,
    requestFailure: WatchRequestFailure? = null,
    sendingRequest: Boolean = false,
) {
    SettingsScaffold(title = stringResource(Res.string.watch_v2_microphone), onBack = onBack) {
        item {
            MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                Text(stringResource(Res.string.watch_v2_microphone_fix), Modifier.padding(Spacing.md))
                TextButton(onClick = onOpenWatch, enabled = !sendingRequest) { Text(stringResource(Res.string.watch_v2_fix_open)) }
                requestFailure?.let { WatchRequestFailureMessage(it, Modifier.padding(Spacing.md)) }
            }
        }
    }
}
