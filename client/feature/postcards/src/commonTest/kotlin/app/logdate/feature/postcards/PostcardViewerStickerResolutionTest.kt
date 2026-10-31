package app.logdate.feature.postcards

import androidx.lifecycle.SavedStateHandle
import app.logdate.client.repository.postcards.Postcard
import app.logdate.client.repository.stickers.Sticker
import app.logdate.feature.postcards.fakes.FakePostcardRepository
import app.logdate.feature.postcards.fakes.FakeStickerRepository
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.PostcardDocument
import app.logdate.feature.postcards.ui.PostcardViewerUiState
import app.logdate.feature.postcards.ui.PostcardViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies that the Postcard viewer resolves sticker images together with the Postcard, so it
 * never shows a Postcard whose stickers come from a stale or missing lookup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostcardViewerStickerResolutionTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `viewer shows a postcard only once its stickers are loaded`() =
        runTest {
            val stickerRef = Uuid.random()
            val document = documentWithSticker(stickerRef)
            val stickers = FakeStickerRepository(initial = null)
            val viewModel = newViewer(document, stickers)
            val states = collectStates(viewModel)
            advanceUntilIdle()

            assertEquals(PostcardViewerUiState.Loading, viewModel.uiState.value)

            stickers.setStickers(listOf(Sticker(id = stickerRef, imageUri = "file:///stickers/cat.png")))
            advanceUntilIdle()

            val loaded = assertIs<PostcardViewerUiState.Loaded>(viewModel.uiState.value)
            assertEquals(document, loaded.document)
            assertEquals(mapOf(stickerRef to "file:///stickers/cat.png"), loaded.stickerUriMap)
            assertTrue(states.filterIsInstance<PostcardViewerUiState.Loaded>().all { it.stickerUriMap.isNotEmpty() })
        }

    @Test
    fun `viewer follows changes to the sticker library`() =
        runTest {
            val stickerRef = Uuid.random()
            val unrelated = Sticker(id = Uuid.random(), imageUri = "file:///stickers/dog.png")
            val stickers =
                FakeStickerRepository(
                    initial = listOf(Sticker(id = stickerRef, imageUri = "file:///stickers/cat.png"), unrelated),
                )
            val viewModel = newViewer(documentWithSticker(stickerRef), stickers)
            collectStates(viewModel)
            advanceUntilIdle()

            assertEquals(mapOf(stickerRef to "file:///stickers/cat.png"), loadedStickers(viewModel))

            stickers.setStickers(listOf(Sticker(id = stickerRef, imageUri = "file:///stickers/cat-2.png"), unrelated))
            advanceUntilIdle()
            assertEquals(mapOf(stickerRef to "file:///stickers/cat-2.png"), loadedStickers(viewModel))

            stickers.setStickers(listOf(unrelated))
            advanceUntilIdle()
            assertEquals(emptyMap<Uuid, String>(), loadedStickers(viewModel))
        }

    private fun newViewer(
        document: PostcardDocument,
        stickers: FakeStickerRepository,
    ): PostcardViewerViewModel =
        PostcardViewerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("postcardId" to document.id.toString())),
            postcardRepository = FakePostcardRepository(listOf(document.toPostcard())),
            stickerRepository = stickers,
        )

    private fun TestScope.collectStates(viewModel: PostcardViewerViewModel): List<PostcardViewerUiState> {
        val states = mutableListOf<PostcardViewerUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.toList(states) }
        return states
    }

    private fun loadedStickers(viewModel: PostcardViewerViewModel): Map<Uuid, String> =
        assertIs<PostcardViewerUiState.Loaded>(viewModel.uiState.value).stickerUriMap
}

private val documentTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

private fun documentWithSticker(stickerRef: Uuid): PostcardDocument =
    PostcardDocument(
        id = Uuid.random(),
        title = "Beach day",
        createdAt = documentTime,
        modifiedAt = documentTime,
        elements = listOf(CanvasElement.Sticker(id = Uuid.random(), stickerRef = stickerRef, zIndex = 0)),
    )

private fun PostcardDocument.toPostcard(): Postcard =
    Postcard(
        id = id,
        title = title,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        documentJson = PostcardDocument.json.encodeToString(PostcardDocument.serializer(), this),
    )
