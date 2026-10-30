package app.logdate.feature.editor.ui.image

import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.media.MediaObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecentImageLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val denied = ImagePickerLibraryState.PermissionRequired(permanentlyDenied = false)
    private val photo =
        MediaObject.Image(
            uri = "content://photo/1",
            size = 1,
            name = "Photo",
            timestamp = Instant.parse("2026-10-01T12:00:00Z"),
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `restarting the load never flashes an error`() =
        runTest(dispatcher) {
            val library = RecentImageLibraryViewModel(RecentMedia(MutableStateFlow(listOf(photo))))
            val seen = mutableListOf<ImagePickerLibraryState>()
            val watcher = backgroundScope.launch { library.state.collect { seen += it } }

            library.load(hasFullAccess = true, permissionRevoked = denied)
            advanceUntilIdle()
            library.load(hasFullAccess = true, permissionRevoked = denied)
            advanceUntilIdle()

            assertFalse(seen.any { it is ImagePickerLibraryState.Error })
            assertEquals(ImagePickerLibraryState.Loaded(listOf(photo)), library.state.value)
            watcher.cancel()
        }

    @Test
    fun `partial access shows the shared photos with the selection affordance`() =
        runTest(dispatcher) {
            val library = RecentImageLibraryViewModel(RecentMedia(MutableStateFlow(listOf(photo))))

            library.load(hasFullAccess = false, permissionRevoked = denied)
            advanceUntilIdle()

            assertEquals(ImagePickerLibraryState.PartialAccess(listOf(photo)), library.state.value)
        }

    @Test
    fun `withdrawn access asks for permission again`() =
        runTest(dispatcher) {
            val library = RecentImageLibraryViewModel(RecentMedia(flow { throw SecurityException("revoked") }))

            library.load(hasFullAccess = true, permissionRevoked = denied)
            advanceUntilIdle()

            assertEquals(denied, library.state.value)
        }

    @Test
    fun `a failed read shows the error`() =
        runTest(dispatcher) {
            val library = RecentImageLibraryViewModel(RecentMedia(flow { throw IllegalStateException("cursor closed") }))

            library.load(hasFullAccess = true, permissionRevoked = denied)
            advanceUntilIdle()

            assertEquals(ImagePickerLibraryState.Error, library.state.value)
        }

    private class RecentMedia(
        private val media: Flow<List<MediaObject>>,
    ) : InMemoryMediaManager() {
        override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = media
    }
}
