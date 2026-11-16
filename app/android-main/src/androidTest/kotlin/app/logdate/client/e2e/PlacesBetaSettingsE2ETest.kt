package app.logdate.client.e2e

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.ui.navigation.LocationTimelineRoute
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.AdvancedSettingsRoute
import app.logdate.feature.core.settings.navigation.LabsSettingsRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.navigation.LogDateNavDisplay
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Production Settings/Home/NavDisplay with real app preferences; no onboarding or data resets. */
@RunWith(AndroidJUnit4::class)
class PlacesBetaSettingsE2ETest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var pending by mutableStateOf<NavKey?>(null)
    private var current by mutableStateOf<NavKey?>(null)
    private val flags get() = GlobalContext.get().get<FeatureFlagStore>()

    @Test fun ordinarySettingsGatesBothBrowsersWithoutChangingRecordingOrSync() =
        runBlocking {
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            val flags = GlobalContext.get().get<FeatureFlagStore>()
            val preferences = GlobalContext.get().get<LogdatePreferencesDataSource>()
            val original = rawFlag(FeatureFlag.PLACES_BETA)
            val developer = preferences.observeDeveloperModeEnabled().first()
            val collection = rawFlag(FeatureFlag.HUMAN_LOCATION_HISTORY)
            val tracking = GlobalContext.get().get<LocationTrackingSettingsRepository>()
            val originalTracking = tracking.getSettings()
            try {
                flags.setEnabled(FeatureFlag.PLACES_BETA, false)
                showApp()
                compose.waitUntil(15_000) { current == HomeRoute }
                compose.onNodeWithText("Places").assertDoesNotExist()
                compose.onNodeWithText("Rewind").assertIsDisplayed()

                compose.runOnIdle { pending = LocationTimelineRoute }
                compose.waitUntil(10_000) { pending == null && current == HomeRoute }
                compose.onNodeWithText("Places").assertDoesNotExist()

                for (legacySync in listOf(false, true)) {
                    flags.setEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY, legacySync)
                    verifyHomeToggle()
                    verifyDirectRouteToggle()
                    assertEquals(legacySync, flags.isEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY))
                }
                assertEquals(developer, preferences.observeDeveloperModeEnabled().first())
                assertEquals(originalTracking, tracking.getSettings())
            } finally {
                try {
                    restoreFlag(FeatureFlag.PLACES_BETA, original)
                } finally {
                    restoreFlag(FeatureFlag.HUMAN_LOCATION_HISTORY, collection)
                }
            }
        }

    @Test fun disablingAnOpenHistoryDetailRetainsItsVisitAndLinkedMemory() =
        runBlocking {
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            val koin = GlobalContext.get()
            val history = koin.get<LocationHistoryService>()
            val notes = koin.get<JournalNotesRepository>()
            val tracking = koin.get<LocationTrackingSettingsRepository>()
            val originalTracking = tracking.getSettings()
            val originalBeta = rawFlag(FeatureFlag.PLACES_BETA)
            val originalSync = rawFlag(FeatureFlag.HUMAN_LOCATION_HISTORY)
            val records = koin.get<HistoryRecordStore>()
            val owner = koin.get<CanonicalOwnerProvider>().getCanonicalOwnerId()
            val origin = koin.get<LogDateConfigRepository>().getCurrentBackendUrl().trimEnd('/')
            // Span the next few minutes so a midnight boundary still includes the fixture.
            val end = Clock.System.now()
            val date = end.toLocalDateTime(TimeZone.currentSystemDefault()).date
            val place = SemanticPlace(Uuid.random().toString(), "Beta continuity café", 36.167, -115.148, userConfirmed = true)
            val memory = JournalNote.Text(creationTimestamp = end, lastUpdated = end, content = "A memory retained across beta choices")
            try {
                flags.setEnabled(FeatureFlag.PLACES_BETA, false)
                flags.setEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY, false)
                history.addVisit(end - 1.minutes, end + 5.minutes, place)
                notes.create(memory)
                val ownedVisit =
                    withTimeout(15_000) {
                        history
                            .observeDay(date)
                            .first { snapshot ->
                                snapshot.items.filterIsInstance<PlaceVisit>().any { it.place?.id == place.id }
                            }.items
                            .filterIsInstance<PlaceVisit>()
                            .single { it.place?.id == place.id }
                    }
                history.linkMemory(ownedVisit.evidenceIds.first(), memory.uid.toString())
                showApp()
                compose.waitUntil(15_000) { current == HomeRoute }
                flags.setEnabled(FeatureFlag.PLACES_BETA, true)
                compose.runOnIdle { pending = LocationTimelineRoute }
                compose.waitUntil(15_000) { current == LocationTimelineRoute }
                assertHumanBrowser()
                awaitText(place.name)
                compose.onNodeWithText(place.name, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                compose
                    .onNode(
                        hasClickAction() and hasAnyDescendant(hasContentDescription("See details")) and
                            hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(place.name))),
                        useUnmergedTree = true,
                    ).performScrollTo()
                    .performClick()
                awaitText("Visit details")
                compose.onNodeWithText("Visit details").performScrollTo().assertIsDisplayed()
                awaitText(memory.content)
                compose
                    .onAllNodesWithText(memory.content)
                    .onLast()
                    .performScrollTo()
                    .assertIsDisplayed()
                capture("places-history-open-detail")

                flags.setEnabled(FeatureFlag.PLACES_BETA, false)
                compose.waitUntil(15_000) { current == HomeRoute }
                compose.onNodeWithText("Visit details").assertDoesNotExist()
                compose.onNodeWithText("Your day").assertDoesNotExist()
                val retained =
                    withTimeout(15_000) {
                        history.observeDay(date).first { snapshot ->
                            snapshot.items.filterIsInstance<PlaceVisit>().any {
                                it.place?.id == place.id && memory.uid.toString() in it.memoryIds
                            }
                        }
                    }
                assertTrue(retained.items.filterIsInstance<PlaceVisit>().any { it.place?.id == place.id })
                assertEquals(memory.content, (notes.getNoteById(memory.uid) as JournalNote.Text).content)
                assertEquals(false, flags.isEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY))
                assertEquals(originalTracking, tracking.getSettings())
                capture("places-history-hidden-retained")

                flags.setEnabled(FeatureFlag.PLACES_BETA, true)
                compose.runOnIdle { pending = LocationTimelineRoute }
                compose.waitUntil(15_000) { current == LocationTimelineRoute }
                assertHumanBrowser()
                awaitText(place.name)
                compose.onNodeWithText(place.name, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                awaitText(memory.content)
                compose.onNodeWithText(memory.content).performScrollTo().assertIsDisplayed()
                capture("places-history-retained-after-reenable")
            } finally {
                try {
                    // Identify only this fixture, including partial writes before an earlier failure.
                    val json = Json { ignoreUnknownKeys = true }
                    val owned =
                        records.records(owner, origin).filter { record ->
                            val payload =
                                record.payload?.let {
                                    runCatching { json.decodeFromString(HistoryPayload.serializer(), it) }.getOrNull()
                                }
                            when (payload) {
                                is HistoryPayload.Place -> payload.value.id == place.id
                                is HistoryPayload.Manual -> payload.value.placeId == place.id
                                is HistoryPayload.MemoryLink -> payload.value.noteId == memory.uid.toString()
                                else -> false
                            }
                        }
                    records.putBatch(
                        owner,
                        origin,
                        owned.map { it.copy(payload = null, deleted = true, dirty = true, deviceVersion = it.deviceVersion + 1) },
                    )
                } finally {
                    try {
                        notes.removeById(memory.uid)
                    } finally {
                        try {
                            restoreFlag(FeatureFlag.PLACES_BETA, originalBeta)
                        } finally {
                            restoreFlag(FeatureFlag.HUMAN_LOCATION_HISTORY, originalSync)
                        }
                    }
                }
            }
        }

    private val storedFlags get() = GlobalContext.get().get<DataStore<Preferences>>(named("mainDataStore"))

    private suspend fun rawFlag(flag: FeatureFlag): Boolean? = storedFlags.data.first()[booleanPreferencesKey(flag.key)]

    private suspend fun restoreFlag(
        flag: FeatureFlag,
        original: Boolean?,
    ) {
        storedFlags.updateData { current ->
            current.toMutablePreferences().apply {
                val key = booleanPreferencesKey(flag.key)
                if (original == null) remove(key) else this[key] = original
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertHumanBrowser() {
        awaitText("Your day")
        // The compact workspace exposes the other section in its menu, not as a second tab.
        val compact = compose.onAllNodesWithContentDescription("Choose section").fetchSemanticsNodes().isNotEmpty()
        if (compact) compose.onNodeWithContentDescription("Choose section").performClick()
        compose.onNodeWithText("Your places").assertIsDisplayed().performClick()
        awaitText("Your places")
        if (compact) compose.onNodeWithContentDescription("Choose section").performClick()
        compose.onNodeWithText("Your day").assertIsDisplayed().performClick()
        awaitText("Your day")
        if (compact) {
            // Peek shows only the summary; use the real panel controls before querying visits.
            for (action in listOf("Browse", "Expand")) {
                if (compose.onAllNodesWithContentDescription(action).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithContentDescription(action).performClick()
                }
            }
        }
    }

    private fun showApp() {
        compose.setContent {
            LogDateTheme(dynamicColor = false) {
                LogDateNavDisplay(
                    appUiState = GlobalAppUiLoadedState(isLoaded = true, isOnboarded = true),
                    onShowUnlockPrompt = {},
                    pendingNavKey = pending,
                    onPendingNavKeyConsumed = { pending = null },
                    onCurrentNavKeyChanged = { current = it },
                )
            }
        }
    }

    private fun verifyHomeToggle() {
        compose.onNodeWithTag("workspace_account").performClick()
        compose.onNodeWithText("Settings").performClick()
        openLabs()
        compose.onNodeWithText("Places & location history · Beta").performScrollTo().assertIsDisplayed()
        capture("places-beta-settings-off")
        compose.onNodeWithText("Places & location history · Beta").performClick()
        compose.waitUntil(10_000) { runBlocking { flags.isEnabled(FeatureFlag.PLACES_BETA) } }
        capture("places-beta-settings-on")
        leaveSettings()
        compose.onNodeWithText("Places").performClick()
        assertHumanBrowser()
        capture("places-beta-home-on")

        compose.onNodeWithTag("workspace_account").performClick()
        compose.onNodeWithText("Settings").performClick()
        openLabs()
        compose.onNodeWithText("Places & location history · Beta").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !flags.isEnabled(FeatureFlag.PLACES_BETA) } }
        leaveSettings()
        compose.onNodeWithText("Places").assertDoesNotExist()
        compose.onNodeWithText("Timeline").assertIsDisplayed()
        compose.onNodeWithText("Rewind").assertIsDisplayed()
        capture("places-beta-home-off")
    }

    private suspend fun verifyDirectRouteToggle() {
        flags.setEnabled(FeatureFlag.PLACES_BETA, true)
        compose.runOnIdle { pending = LocationTimelineRoute }
        compose.waitUntil(10_000) { current == LocationTimelineRoute }
        assertHumanBrowser()
        compose.runOnIdle { pending = SettingsRoute() }
        compose.waitUntil(10_000) { current is SettingsRoute }
        openLabs()
        compose.onNodeWithText("Places & location history · Beta").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !flags.isEnabled(FeatureFlag.PLACES_BETA) } }
        leaveSettings()
        compose.onNodeWithText("Places").assertDoesNotExist()
    }

    private fun openLabs() {
        compose.onNodeWithText("Places & location history · Beta").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("About LogDate"))
        compose.onNodeWithText("About LogDate").performClick()
        compose.waitUntil(10_000) { current == AdvancedSettingsRoute }
        compose.onNodeWithText("Labs").performScrollTo().performClick()
        compose.waitUntil(10_000) { current == LabsSettingsRoute }
    }

    private fun leaveSettings() {
        repeat(3) {
            device.pressBack()
            compose.waitForIdle()
        }
        compose.waitUntil(10_000) { current == HomeRoute }
    }

    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }
}
