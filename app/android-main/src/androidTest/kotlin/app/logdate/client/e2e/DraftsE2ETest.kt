package app.logdate.client.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.EditorActivity
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.PendingMediaRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * End-to-end tests for the drafts system in the editor.
 *
 * These tests verify the full user flows:
 * - Writing content, saving, and verifying drafts are cleaned up
 * - Using "Save Draft" from the exit confirmation dialog
 * - Saving immediately on exit before the debounce window elapses
 * - Loading a pre-existing draft and publishing it
 *
 * Run with:
 * ```
 * ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.e2e.DraftsE2ETest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DraftsE2ETest {
    private val fakeDraftRepository = FakeDraftRepository()

    private val testModule =
        module {
            single<EntryDraftRepository> { fakeDraftRepository }
        }

    private val koinRule = DraftsKoinModuleOverrideRule(testModule)
    private val composeRule = createAndroidComposeRule<EditorActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(composeRule)

    /**
     * Test: Write content → save → verify draft is cleaned up.
     *
     * This is the primary regression test for the bug where drafts were
     * never deleted after publishing because the combine block overwrote draftId.
     */
    @Test
    fun `write content and save draft is deleted after publish`() {
        startTextEntry()

        // Type some content
        composeRule.onNodeWithTag("editor_text_input").performTextInput("My journal entry for today")
        composeRule.waitForIdle()

        // Wait for auto-save to kick in (2s debounce + processing)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeDraftRepository.currentDrafts.isNotEmpty()
        }

        // Verify a draft was auto-saved
        assertEquals("Exactly one durable draft should exist before publish", 1, fakeDraftRepository.currentDrafts.size)

        // Save the entry
        composeRule.onNodeWithContentDescription(SAVE_ENTRY).performClick()

        // Wait for save to complete and draft to be cleaned up
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDraftRepository.currentDrafts.isEmpty()
        }

        // Verify draft was deleted
        assertTrue("Draft must be deleted after publishing", fakeDraftRepository.currentDrafts.isEmpty())
    }

    /**
     * Test: Write content → auto-save → back → tap Save Draft → navigate → exact draft persists.
     */
    @Test
    fun `write content and save as draft draft persists after exit`() {
        val activity = composeRule.activity
        startTextEntry()

        // Type content
        composeRule.onNodeWithTag("editor_text_input").performTextInput("Draft content to save")
        composeRule.waitForIdle()

        // Wait for auto-save to persist the draft before exiting.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDraftRepository.currentDrafts.isNotEmpty()
        }

        // Exercise the real confirmation action and wait for host navigation.
        closeEntry()
        composeRule.onNodeWithTag("exit_dialog_save_draft").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            activity.isFinishing
        }
        assertTrue("Save Draft must navigate out of the editor", activity.isFinishing)

        val savedDraft = fakeDraftRepository.currentDrafts.single()
        assertEquals(
            "Draft content to save",
            savedDraft.notes
                .filterIsInstance<JournalNote.Text>()
                .single()
                .content,
        )
    }

    /** Test: Write content → back before debounce → Save Draft → local draft persists. */
    @Test
    fun `write content and save as draft before debounce draft persists after exit`() {
        val activity = composeRule.activity
        val draftsBeforeTest = fakeDraftRepository.currentDrafts.size

        startTextEntry()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("editor_text_input").performTextInput("Temporary content")
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(
                "Normal debounce must not create a draft before the explicit exit action",
                draftsBeforeTest,
                fakeDraftRepository.currentDrafts.size,
            )

            // Each step is given less time than the debounce, so leaving is what saves the draft.
            composeRule.onNodeWithContentDescription(RETURN_TO_ENTRY).performClick()
            composeRule.mainClock.advanceTimeBy(LEAVING_STEP_MILLIS)
            composeRule.onNodeWithContentDescription(CLOSE).performClick()
            composeRule.mainClock.advanceTimeBy(LEAVING_STEP_MILLIS)
            composeRule.onNodeWithText("Save Draft").assertIsDisplayed().performClick()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDraftRepository.currentDrafts.size == draftsBeforeTest + 1
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            activity.isFinishing
        }
        assertTrue("Immediate Save Draft must navigate out of the editor", activity.isFinishing)

        val savedDraft = fakeDraftRepository.currentDrafts.last()
        assertEquals(
            "Immediate Save Draft must persist the entered text before navigation",
            "Temporary content",
            savedDraft.notes
                .filterIsInstance<JournalNote.Text>()
                .single()
                .content,
        )
    }

    /** Test: activity recreation keeps the active editor and its durable draft visible. */
    @Test
    fun `write content and recreate activity editor restores exact draft`() {
        val content = "Text that must survive rotation"
        startTextEntry()

        composeRule.onNodeWithTag("editor_text_input").performTextInput(content)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeDraftRepository.currentDrafts
                .singleOrNull()
                ?.notes
                ?.filterIsInstance<JournalNote.Text>()
                ?.singleOrNull()
                ?.content == content
        }

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(content).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(content).assertIsDisplayed()
    }

    /** Test: Write content → auto-save → back → Discard → navigate only after durable deletion. */
    @Test
    fun `write content and discard draft is deleted before exit`() {
        val activity = composeRule.activity
        startTextEntry()
        composeRule.onNodeWithTag("editor_text_input").performTextInput("Discard this exact draft")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeDraftRepository.currentDrafts
                .singleOrNull()
                ?.notes
                ?.filterIsInstance<JournalNote.Text>()
                ?.singleOrNull()
                ?.content == "Discard this exact draft"
        }

        closeEntry()
        composeRule.onNodeWithTag("exit_dialog_discard").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDraftRepository.currentDrafts.isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            activity.isFinishing
        }
        assertTrue("Discard must delete the durable draft before navigation", fakeDraftRepository.currentDrafts.isEmpty())
        assertTrue("Discard must navigate out of the editor", activity.isFinishing)
    }

    /**
     * Test: Pre-seed a draft → open drafts → select it → content loads → save → draft deleted.
     *
     * Tests the full draft lifecycle from loading through publishing.
     */
    @Test
    fun `load draft and publish draft is deleted after save`() {
        // Pre-seed a draft
        val draftContent = "Previously saved draft content"
        fakeDraftRepository.seedDraft(draftContent)

        openDraftsDialog()

        // The sheet fetches drafts asynchronously after it opens. Wait for the durable
        // preview rather than asserting during the loading frame on slower devices.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText(draftContent, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(draftContent, substring = true).assertIsDisplayed()

        // Select the draft
        composeRule.onNodeWithText(draftContent, substring = true).performClick()
        composeRule.waitForIdle()

        // The draft's words are on the entry page.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("drafts_empty_state").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithContentDescription(SAVE_ENTRY).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(draftContent, substring = true).assertIsDisplayed()

        // Save the entry
        composeRule.onNodeWithContentDescription(SAVE_ENTRY).performClick()

        // Wait for draft to be cleaned up
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeDraftRepository.currentDrafts.isEmpty()
        }

        // Verify draft was deleted after publishing
        assertTrue("Draft should be deleted after publishing", fakeDraftRepository.currentDrafts.isEmpty())
    }

    /** Test: with no drafts, the entry's options do not offer to open them. */
    @Test
    fun `no drafts means the entry does not offer to open them`() {
        assertTrue("This test must start without drafts", fakeDraftRepository.currentDrafts.isEmpty())

        openEntryOptions()

        composeRule.onNodeWithTag("entry_open_drafts").assertIsNotEnabled()
    }

    /** Opens the entry's options, which the entry shows only while no piece of it is open. */
    private fun openEntryOptions() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(RETURN_TO_ENTRY).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription(ENTRY_OPTIONS).fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription(ENTRY_OPTIONS).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription(RETURN_TO_ENTRY).performClick()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(ENTRY_OPTIONS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(ENTRY_OPTIONS).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("entry_open_drafts").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openDraftsDialog() {
        openEntryOptions()
        composeRule.onNodeWithTag("entry_open_drafts").performClick()
        composeRule.waitForIdle()
    }

    /** Starts a piece of writing from the entry page's row, which comes in once the entry has loaded. */
    private fun startTextEntry() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(WRITE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(WRITE).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithTag("editor_text_input")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** Leaves the piece of writing that is open, then asks to close the entry it belongs to. */
    private fun closeEntry() {
        composeRule.onNodeWithContentDescription(RETURN_TO_ENTRY).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(CLOSE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(CLOSE).performClick()
    }

    private companion object {
        // What the entry page's controls are called.
        const val WRITE = "Write"
        const val SAVE_ENTRY = "Save entry"
        const val RETURN_TO_ENTRY = "Return to entry"
        const val CLOSE = "Close"
        const val ENTRY_OPTIONS = "Entry options"
        const val LEAVING_STEP_MILLIS = 400L
    }
}

/**
 * Fake [EntryDraftRepository] for E2E tests.
 * Provides direct access to draft state for assertions.
 */
private class FakeDraftRepository : EntryDraftRepository {
    private val drafts = MutableStateFlow<List<EntryDraft>>(emptyList())

    val currentDrafts: List<EntryDraft>
        get() = drafts.value

    fun seedDraft(textContent: String): Uuid {
        val now = Clock.System.now()
        val id = Uuid.random()
        val draft =
            EntryDraft(
                id = id,
                notes =
                    listOf(
                        JournalNote.Text(
                            uid = Uuid.random(),
                            creationTimestamp = now,
                            lastUpdated = now,
                            content = textContent,
                        ),
                    ),
                createdAt = now,
                updatedAt = now,
            )
        drafts.value = drafts.value + draft
        return id
    }

    override fun getDrafts(): Flow<List<EntryDraft>> = drafts

    override fun getDraft(uid: Uuid): Flow<Result<EntryDraft>> {
        val draft = drafts.value.firstOrNull { it.id == uid }
        return if (draft != null) {
            flowOf(Result.success(draft))
        } else {
            flowOf(Result.failure(NoSuchElementException("Draft not found")))
        }
    }

    override suspend fun createDraft(notes: List<JournalNote>): Uuid =
        createDraft(
            uid = Uuid.random(),
            notes = notes,
            pendingMedia = emptyList(),
            selectedJournalIds = emptyList(),
        )

    override suspend fun createDraft(
        uid: Uuid,
        notes: List<JournalNote>,
        pendingMedia: List<PendingMediaRecord>,
        selectedJournalIds: List<Uuid>,
    ): Uuid {
        val now = Clock.System.now()
        val draft =
            EntryDraft(
                id = uid,
                notes = notes,
                createdAt = now,
                updatedAt = now,
                pendingMedia = pendingMedia,
                selectedJournalIds = selectedJournalIds,
            )
        drafts.value = drafts.value.filterNot { it.id == uid } + draft
        return draft.id
    }

    override suspend fun updateDraft(
        uid: Uuid,
        notes: List<JournalNote>,
    ): Uuid {
        val existing = drafts.value.firstOrNull { it.id == uid }
        return updateDraft(
            uid = uid,
            notes = notes,
            pendingMedia = existing?.pendingMedia.orEmpty(),
            selectedJournalIds = existing?.selectedJournalIds.orEmpty(),
        )
    }

    override suspend fun updateDraft(
        uid: Uuid,
        notes: List<JournalNote>,
        pendingMedia: List<PendingMediaRecord>,
        selectedJournalIds: List<Uuid>,
    ): Uuid {
        val now = Clock.System.now()
        val existing = drafts.value.firstOrNull { it.id == uid }
        val updated =
            if (existing != null) {
                existing.copy(
                    notes = notes,
                    pendingMedia = pendingMedia,
                    selectedJournalIds = selectedJournalIds,
                    updatedAt = now,
                )
            } else {
                EntryDraft(
                    id = uid,
                    notes = notes,
                    createdAt = now,
                    updatedAt = now,
                    pendingMedia = pendingMedia,
                    selectedJournalIds = selectedJournalIds,
                )
            }
        drafts.value = drafts.value.filterNot { it.id == uid } + updated
        return uid
    }

    override suspend fun setPendingMedia(
        uid: Uuid,
        pendingMedia: List<PendingMediaRecord>,
    ) {
        val existing = drafts.value.firstOrNull { it.id == uid } ?: return
        drafts.value = drafts.value.filterNot { it.id == uid } + existing.copy(pendingMedia = pendingMedia)
    }

    override suspend fun setSelectedJournalIds(
        uid: Uuid,
        selectedJournalIds: List<Uuid>,
    ) {
        val existing = drafts.value.firstOrNull { it.id == uid } ?: return
        drafts.value = drafts.value.filterNot { it.id == uid } + existing.copy(selectedJournalIds = selectedJournalIds)
    }

    override suspend fun deleteDraft(uid: Uuid) {
        drafts.value = drafts.value.filterNot { it.id == uid }
    }

    override suspend fun deleteAllDrafts() {
        drafts.value = emptyList()
    }

    override suspend fun deleteExpiredDrafts(maxAge: Duration): Int = 0
}

private class DraftsKoinModuleOverrideRule(
    private val module: Module,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                startKoinIfAppHasNot(context)
                loadKoinModules(module)
                base.evaluate()
            }
        }
}
