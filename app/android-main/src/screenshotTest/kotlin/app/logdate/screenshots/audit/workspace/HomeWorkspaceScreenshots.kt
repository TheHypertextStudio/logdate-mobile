@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package app.logdate.screenshots.audit.workspace

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.journals.ui.JournalLayoutMode
import app.logdate.feature.journals.ui.JournalListItemUiState
import app.logdate.feature.journals.ui.JournalSortOption
import app.logdate.feature.journals.ui.JournalsOverviewScreenContent
import app.logdate.feature.library.ui.LibraryScreenContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.rewind.ui.RewindScreenContent
import app.logdate.feature.rewind.ui.overview.RewindOverviewPeriod
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindSceneMemory
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.components.home_timeline.HistoryMapFixture
import app.logdate.screenshots.components.home_timeline.historyDay
import app.logdate.screenshots.components.library.LibraryScreenshotData
import app.logdate.screenshots.flows.flow02_home_timeline.mostRecentRewind
import app.logdate.screenshots.flows.flow02_home_timeline.pastRewinds
import app.logdate.screenshots.flows.flow02_home_timeline.timelineDays
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.timeline.TimelinePane
import app.logdate.ui.timeline.TimelineUiState
import app.logdate.ui.workspace.AdaptiveWorkspaceLayout
import app.logdate.ui.workspace.LocalWorkspaceCaptionBar
import app.logdate.ui.workspace.WorkspaceCaptionBar
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceRailJournal
import app.logdate.ui.workspace.WorkspaceRailJournals
import app.logdate.ui.workspace.WorkspaceScaffold
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest

@Preview(name = "Phone", device = ScreenshotTestData.PHONE, showBackground = true)
@Preview(name = "Compact", device = ScreenshotTestData.COMPACT_PHONE, showBackground = true)
@Preview(
    name = "Dark",
    device = ScreenshotTestData.PHONE,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Large text", device = ScreenshotTestData.PHONE, fontScale = 2f, showBackground = true)
@Preview(name = "Landscape", device = ScreenshotTestData.PHONE_LANDSCAPE, showBackground = true)
@Preview(name = "Tablet", device = ScreenshotTestData.TABLET, showBackground = true)
@Preview(name = "Tablet portrait", device = ScreenshotTestData.TABLET_PORTRAIT, showBackground = true)
@Preview(name = "Wide RTL", device = ScreenshotTestData.TABLET, locale = "ar", showBackground = true)
@Preview(name = "RTL", device = ScreenshotTestData.PHONE, locale = "ar", showBackground = true)
@Preview(name = "Bubble", device = ScreenshotTestData.BUBBLE, showBackground = true)
annotation class WorkspacePreviewMatrix

internal val workspaceRailJournals =
    WorkspaceRailJournals(
        items =
            listOf("Daily Reflections", "Travel Log", "Recipe Notes", "Garden Journal", "Dream Log", "Reading List")
                .mapIndexed { index, title -> WorkspaceRailJournal(index.toString(), title) },
        onOpen = {},
    )

internal val workspaceDestinations =
    listOf(
        WorkspaceDestination("timeline", "Timeline", Icons.Filled.Timeline, Icons.Outlined.Timeline),
        WorkspaceDestination("locations", "Places", Icons.Filled.Place, Icons.Outlined.Place),
        WorkspaceDestination("journals", "Journals", Icons.Filled.Book, Icons.Outlined.Book),
        WorkspaceDestination("library", "Library", Icons.Filled.Image, Icons.Outlined.Image),
        WorkspaceDestination("rewind", "Rewind", Icons.Filled.History, Icons.Outlined.History),
    )

@Composable
internal fun PlacesOptionsFixture() {
    IconButton(onClick = {}) { Icon(PlatformIcons.more(), "More place options", Modifier.size(20.dp)) }
}

@Composable
internal fun WorkspaceScene(
    key: String,
    detail: (@Composable () -> Unit)? = null,
    inDesktopWindow: Boolean = false,
    content: @Composable () -> Unit,
) {
    ScreenshotTheme {
        if (inDesktopWindow) {
            DesktopWindow { WorkspaceSceneContent(key, detail, content) }
        } else {
            WorkspaceSceneContent(key, detail, content)
        }
    }
}

@Composable
private fun WorkspaceSceneContent(
    key: String,
    detail: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val image =
        remember(
            context,
        ) { BitmapFactory.decodeResource(context.resources, app.logdate.client.R.drawable.workspace_sample_photo).asImage() }
    val handler = remember(image) { AsyncImagePreviewHandler { image } }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides handler) {
        WorkspaceScaffold(
            workspaceDestinations,
            key,
            {},
            onCreate = {}.takeIf { key != "locations" },
            onSearch = {},
            journals = workspaceRailJournals,
            actions = {
                app.logdate.feature.core.main.HomeWorkspaceAccountAction(
                    app.logdate.feature.core.sync.SyncPresentation
                        .Pending(2),
                    app.logdate.ui.streak
                        .CampfirePresentation(app.logdate.ui.streak.CampfirePhase.BURNING, runDays = 4),
                    {},
                    {},
                    {},
                )
            },
        ) {
            if (key == "locations" && detail == null) {
                content()
            } else {
                AdaptiveWorkspaceLayout(
                    Modifier.fillMaxSize(),
                    browseOnStart = true,
                    focusConstraints =
                        if (key == "timeline" && detail == null) {
                            app.logdate.ui.workspace.PanelConstraints.ReadingCollection
                        } else if (key ==
                            "library"
                        ) {
                            app.logdate.ui.workspace.PanelConstraints.Visual
                        } else {
                            app.logdate.ui.workspace.PanelConstraints.Reading
                        },
                    browse = content.takeIf { detail != null },
                    focus =
                        detail ?: content,
                )
            }
        }
    }
}

@Composable internal fun TimelineFixture() = TimelinePane(TimelineUiState(timelineDays), {}, {})

@Composable internal fun JournalsFixture() =
    JournalsOverviewScreenContent(
        journals = ScreenshotTestData.sampleJournals.map(JournalListItemUiState::ExistingJournal),
        layoutMode = JournalLayoutMode.GRID,
        sortOption = JournalSortOption.LAST_UPDATED,
        searchQuery = "",
        onOpenJournal = {},
        onBrowseJournals = {},
        onCreateJournal = {},
        onQueryChange = {},
        onToggleLayoutMode = {},
        onSortOptionSelected = {},
    )

@Composable internal fun LibraryFixture() = LibraryScreenContent(LibraryScreenshotData.gridContent, {}, modifier = Modifier.fillMaxSize())

@Composable private fun RewindFixture() {
    CompositionLocalProvider(app.logdate.ui.platform.LocalReduceMotionOverride provides true) {
        RewindScreenContent(
            RewindOverviewScreenUiState.Ready(
                mostRecentRewind =
                    mostRecentRewind.copy(
                        heroImageUri = "workspace://week-photo",
                        isViewed = false,
                        highlightedQuote = "I took the long way home and stopped by the park.",
                        memories =
                            listOf(
                                RewindSceneMemory.Visual("workspace://week-photo"),
                                RewindSceneMemory.Writing("I took the long way home and stopped by the park."),
                                RewindSceneMemory.Recording(42000, "The afternoon outside was worth keeping."),
                            ),
                        start = kotlinx.datetime.LocalDate(2026, 10, 26),
                        end = kotlinx.datetime.LocalDate(2026, 10, 28),
                    ),
                pastRewinds =
                    pastRewinds.mapIndexed { index, week ->
                        week.copy(
                            memories =
                                listOf(
                                    RewindSceneMemory.Writing(
                                        if (index % 2 == 0) {
                                            "The train was late, so we finally tried the café beside the station."
                                        } else {
                                            "Spent the afternoon sketching the next version of the app."
                                        },
                                    ),
                                ),
                        )
                    },
            ),
            {
            },
            selectedPeriod = RewindOverviewPeriod.ThisWeek,
            onSelectPeriod = {},
            onRefreshCurrentWeek = {},
        )
    }
}

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceTimeline() = WorkspaceScene("timeline") { TimelineFixture() }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceLocations() =
    WorkspaceScene("locations") {
        HumanLocationHistoryContent(historyDay, HumanLocationHistoryActions(), toolbarActions = {
            PlacesOptionsFixture()
        }, mapContent = { HistoryMapFixture(it) })
    }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceJournals() = WorkspaceScene("journals") { JournalsFixture() }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceLibrary() = WorkspaceScene("library") { LibraryFixture() }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceRewind() = WorkspaceScene("rewind") { RewindFixture() }

@Preview(name = "Desktop window", device = ScreenshotTestData.TABLET, showBackground = true)
@Preview(
    name = "Desktop window dark",
    device = ScreenshotTestData.TABLET,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "Narrow desktop window", device = "spec:width=720dp,height=600dp", showBackground = true)
@Preview(name = "Desktop window RTL", device = ScreenshotTestData.TABLET, locale = "ar", showBackground = true)
@Preview(name = "Desktop window large text", device = ScreenshotTestData.TABLET, fontScale = 2f, showBackground = true)
annotation class DesktopWindowPreviewMatrix

/**
 * Home in an Android desktop window, with the app bar in the window's caption bar. Android draws the
 * caption's app menu and window buttons itself; [SystemCaptionControls] stands in for them so the
 * references show what the app bar avoids. In right-to-left the system swaps their sides.
 */
@Composable
private fun DesktopWindow(content: @Composable () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val appMenu = 160.dp
    val windowButtons = 144.dp
    val captionBar =
        if (rtl) {
            WorkspaceCaptionBar(height = 48.dp, left = windowButtons, right = appMenu)
        } else {
            WorkspaceCaptionBar(height = 48.dp, left = appMenu, right = windowButtons)
        }
    CompositionLocalProvider(LocalWorkspaceCaptionBar provides captionBar) {
        if (LocalDensity.current.fontScale >= 1.5f) {
            // Large text keeps the app bar out of the caption bar. On a device the window's caption
            // inset then places the workspace below the bar; previews have no insets, so the
            // stand-in bar is laid out above it.
            Column(Modifier.fillMaxSize()) {
                SystemCaptionControls(captionBar.height, appMenu, windowButtons)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                content()
                SystemCaptionControls(captionBar.height, appMenu, windowButtons)
            }
        }
    }
}

@Composable
private fun SystemCaptionControls(
    height: androidx.compose.ui.unit.Dp,
    appMenuWidth: androidx.compose.ui.unit.Dp,
    windowButtonsWidth: androidx.compose.ui.unit.Dp,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(appMenuWidth).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text("LogDate", color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            Icon(Icons.Default.ExpandMore, null, tint = color)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.width(windowButtonsWidth), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Icons.Default.Remove, Icons.Default.CropSquare, Icons.Default.Close).forEach { Icon(it, null, tint = color) }
        }
    }
}
