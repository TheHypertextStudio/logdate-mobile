package app.logdate.client.e2e

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.domain.rewind.GenerateBasicRewindResult
import app.logdate.client.domain.rewind.GetCurrentWeekRewindUseCase
import app.logdate.client.domain.rewind.RefreshCurrentWeekRewindUseCase
import app.logdate.client.domain.rewind.RewindQueryResult
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.feature.rewind.ui.RewindOverviewScreen
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Uses real journal persistence, local generation and overview wiring on a fresh managed device. */
@RunWith(AndroidJUnit4::class)
class CurrentWeekRewindE2ETest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstMemoryCanBeRewoundAndUpdatedWithoutWaitingForNextWeek() =
        runBlocking {
            check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
            val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
            val koin = GlobalContext.get()
            val notes = koin.get<JournalNotesRepository>()
            val rewinds = koin.get<RewindRepository>()
            val query = koin.get<GetCurrentWeekRewindUseCase>()
            val update = koin.get<RefreshCurrentWeekRewindUseCase>()
            val existingIds =
                rewinds
                    .getAllRewinds()
                    .first()
                    .map { it.uid }
                    .toSet()
            assertTrue(notes.allNotesObserved.first().isEmpty(), "First-use acceptance requires a fresh managed-device journal")
            val now = Clock.System.now()
            val first =
                JournalNote.Text(
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "A slower start. I took the long way home and stopped by the park.",
                )
            val second =
                JournalNote.Text(
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "A little room to breathe before the afternoon. The walk was worth keeping.",
                )
            var createdRewind: Uuid? = null
            try {
                notes.create(first)
                val initial = withTimeout(90_000) { query().filterIsInstance<RewindQueryResult.Success>().first().rewind }
                createdRewind = initial.uid.takeUnless { it in existingIds }
                assertTrue(initial.content.any { it.sourceId == first.uid })
                assertEquals(
                    initial.uid,
                    query()
                        .filterIsInstance<RewindQueryResult.Success>()
                        .first()
                        .rewind.uid,
                )
                rewinds.markAsViewed(initial.uid)
                notes.create(second)
                val refreshed = assertIs<GenerateBasicRewindResult.Success>(withTimeout(90_000) { update() }).rewind
                assertEquals(initial.uid, refreshed.uid)
                assertTrue(refreshed.isViewed)
                assertTrue(refreshed.content.any { it.sourceId == second.uid })
                assertEquals(
                    refreshed.endDate.toEpochMilliseconds(),
                    query()
                        .filterIsInstance<RewindQueryResult.Success>()
                        .first()
                        .rewind.endDate
                        .toEpochMilliseconds(),
                )

                var opened: Uuid? = null
                compose.runOnUiThread {
                    val window = compose.activity.window
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = true
                        isAppearanceLightNavigationBars = true
                    }
                }
                compose.setContent {
                    LogDateTheme {
                        RewindOverviewScreen(
                            onOpenRewind = { opened = it },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
                compose.waitUntil(
                    timeoutMillis = 30_000,
                ) { compose.onAllNodesWithText("This week so far").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("This week so far").performClick()
                compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText("Update Rewind").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Update Rewind").assertIsDisplayed()
                compose.onNodeWithText("This week's story").assertIsDisplayed()
                compose.onNodeWithText("This week's story").performClick()
                assertEquals(initial.uid, opened)
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val file = File(output, "current-week-rewind-${device.displayWidth}x${device.displayHeight}.png")
                check(device.takeScreenshot(file))
            } finally {
                notes.removeById(first.uid)
                notes.removeById(second.uid)
                createdRewind?.let { rewinds.deleteRewind(it) }
            }
        }
}
