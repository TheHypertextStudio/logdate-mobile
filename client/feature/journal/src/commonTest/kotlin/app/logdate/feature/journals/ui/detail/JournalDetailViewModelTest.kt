package app.logdate.feature.journals.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.logdate.client.domain.search.SearchInJournalUseCase
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.shared.model.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class JournalDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val journal = Journal(id = Uuid.random(), title = "Field notes")
    private val older = text("Older", "2026-10-01T09:00:00Z")
    private val newer = text("Newer", "2026-10-02T09:00:00Z")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `changing the sort order reorders entries without reloading memberships`() =
        runTest(dispatcher) {
            val contents = CountingContents(listOf(older, newer))
            val detail =
                JournalDetailViewModel(
                    repository = FakeDetailJournalRepository(listOf(journal)),
                    journalContentRepository = contents,
                    getJournalMembership = GetJournalMembershipUseCase(contents),
                    searchInJournal = SearchInJournalUseCase(NoSearch),
                    savedStateHandle = SavedStateHandle(),
                    workContext = dispatcher,
                )
            detail.setSelectedJournalId(journal.id)
            backgroundScope.launch { detail.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(listOf(newer.uid, older.uid), entryIds(detail))

            detail.toggleSortOrder()
            advanceUntilIdle()

            assertEquals(listOf(older.uid, newer.uid), entryIds(detail))
            assertEquals(1, contents.membershipSubscriptions)
        }

    private fun entryIds(detail: JournalDetailViewModel): List<Uuid> =
        (detail.uiState.value as JournalDetailUiState.Success).entries.map { it.id }

    private fun text(
        content: String,
        at: String,
    ) = JournalNote.Text(content = content, creationTimestamp = Instant.parse(at), lastUpdated = Instant.parse(at))

    private class CountingContents(
        private val notes: List<JournalNote>,
        private val delegate: FakeDetailJournalContentRepository = FakeDetailJournalContentRepository(),
    ) : JournalContentRepository by delegate {
        var membershipSubscriptions = 0
            private set

        override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(notes)

        override fun observeJournalsForContents(contentIds: Set<Uuid>): Flow<Map<Uuid, List<Journal>>> =
            delegate.observeJournalsForContents(contentIds).onStart { membershipSubscriptions++ }
    }

    private object NoSearch : SearchRepository {
        override fun search(query: String): Flow<List<SearchResult>> = flowOf(emptyList())

        override fun searchWithLimit(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = flowOf(emptyList())

        override fun searchWithSnippets(query: String): Flow<List<SearchResult>> = flowOf(emptyList())

        override fun searchRanked(
            query: String,
            limit: Int,
        ): Flow<List<SearchResult>> = flowOf(emptyList())

        override fun searchInJournal(
            query: String,
            journalId: Uuid,
            limit: Int,
        ): Flow<List<SearchResult>> = flowOf(emptyList())
    }
}
