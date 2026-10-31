package app.logdate.feature.core.settings.ui.labs

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.ui.platform.PlatformKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LabsSettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Android offers flags that are off by default and allowed to turn on`() {
        val offered = labsFlags(PlatformKind.Android)

        assertTrue(FeatureFlag.WATCH_EXPERIENCE_V2 in offered)
        assertFalse(FeatureFlag.LIBRARY in offered, "a launched feature is not offered")
        assertFalse(FeatureFlag.EVENTS in offered, "a deferred feature cannot be turned on")
    }

    @Test
    fun `the filter drops flags that are on by default or deferred from launch`() {
        val candidates =
            listOf(
                FeatureFlag.LIBRARY,
                FeatureFlag.EVENTS,
                FeatureFlag.JOURNAL_MERGE,
                FeatureFlag.WATCH_EXPERIENCE_V2,
            )

        assertEquals(listOf(FeatureFlag.WATCH_EXPERIENCE_V2), labsFlags(PlatformKind.Android, candidates))
    }

    @Test
    fun `iOS and desktop have no watch to pair so they do not offer the watch switch`() {
        val platformsWithoutWatch =
            listOf(
                PlatformKind.Ios,
                PlatformKind.IpadOs,
                PlatformKind.MacCatalyst,
                PlatformKind.Desktop,
            )

        platformsWithoutWatch.forEach { platform ->
            assertFalse(FeatureFlag.WATCH_EXPERIENCE_V2 in labsFlags(platform), "$platform offers the watch switch")
            assertFalse(FeatureFlag.ACTIVITY_AWARENESS in labsFlags(platform), "$platform offers activity awareness")
        }
    }

    @Test
    fun `android phones can try activity awareness`() {
        assertTrue(FeatureFlag.ACTIVITY_AWARENESS in labsFlags(PlatformKind.Android))
    }

    @Test
    fun `every flag Labs offers has a title and description`() {
        val offeredAnywhere = PlatformKind.entries.flatMap { platform -> labsFlags(platform) }.distinct()

        assertTrue(offeredAnywhere.all { flag -> flag.labsCopy() != null })
    }

    @Test
    fun `a flag without Labs wording is not offered even when it is off by default`() {
        val offered = labsFlags(PlatformKind.Android)
        val withoutWording = FeatureFlag.entries.filter { it.labsCopy() == null }

        assertTrue(withoutWording.isNotEmpty(), "the repo has flags that Labs does not describe")
        assertTrue(withoutWording.none { it in offered })
        assertFalse(FeatureFlag.JOURNAL_MERGE in offered, "journal merge stays gated until the server passes acceptance")
        assertFalse(FeatureFlag.QUICK_SETTINGS_TILES in offered, "an unverified feature is not offered")
    }

    @Test
    fun `the page loads each flag with its stored value`() =
        runTest {
            val viewModel = LabsSettingsViewModel(FakeFeatureFlagStore(), flags = listOf(FeatureFlag.WATCH_EXPERIENCE_V2))
            assertEquals(LabsUiState.Loading, viewModel.uiState.value)

            backgroundScope.launch { viewModel.uiState.collect { } }
            advanceUntilIdle()

            assertEquals(
                LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = false))),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `turning a flag on stores it and shows it on`() =
        runTest {
            val store = FakeFeatureFlagStore()
            val viewModel = LabsSettingsViewModel(store, flags = listOf(FeatureFlag.WATCH_EXPERIENCE_V2))
            backgroundScope.launch { viewModel.uiState.collect { } }

            viewModel.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true)
            advanceUntilIdle()

            assertEquals(listOf(FeatureFlag.WATCH_EXPERIENCE_V2 to true), store.writes)
            assertEquals(
                LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true))),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `turning a flag back off shows it off`() =
        runTest {
            val store = FakeFeatureFlagStore()
            val viewModel = LabsSettingsViewModel(store, flags = listOf(FeatureFlag.WATCH_EXPERIENCE_V2))
            backgroundScope.launch { viewModel.uiState.collect { } }

            viewModel.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true)
            advanceUntilIdle()
            viewModel.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = false)
            advanceUntilIdle()

            assertEquals(
                listOf(FeatureFlag.WATCH_EXPERIENCE_V2 to true, FeatureFlag.WATCH_EXPERIENCE_V2 to false),
                store.writes,
            )
            assertEquals(
                LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = false))),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `a flag changed elsewhere shows its new value`() =
        runTest {
            val store = FakeFeatureFlagStore()
            val viewModel = LabsSettingsViewModel(store, flags = listOf(FeatureFlag.WATCH_EXPERIENCE_V2))
            backgroundScope.launch { viewModel.uiState.collect { } }
            advanceUntilIdle()

            store.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true)
            advanceUntilIdle()

            assertEquals(
                LabsUiState.Ready(listOf(LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true))),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `with nothing in testing the page shows an empty list`() =
        runTest {
            val viewModel = LabsSettingsViewModel(FakeFeatureFlagStore(), flags = emptyList())
            backgroundScope.launch { viewModel.uiState.collect { } }
            advanceUntilIdle()

            assertEquals(LabsUiState.Ready(emptyList()), viewModel.uiState.value)
        }
}
