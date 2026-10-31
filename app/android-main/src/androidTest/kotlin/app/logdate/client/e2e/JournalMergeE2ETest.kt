package app.logdate.client.e2e

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.domain.search.SearchInJournalUseCase
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.sync.NoOpSyncManager
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.journals.navigation.JournalsOverviewRoute
import app.logdate.feature.journals.navigation.completeJournalMerge
import app.logdate.feature.journals.ui.detail.JournalDetailScreen
import app.logdate.feature.journals.ui.detail.JournalDetailViewModel
import app.logdate.feature.journals.ui.list.JournalsList
import app.logdate.feature.journals.ui.merge.JournalMergeScreen
import app.logdate.feature.journals.ui.merge.JournalMergeStage
import app.logdate.feature.journals.ui.merge.JournalMergeViewModel
import app.logdate.navigation.rememberWorkspaceRouteDecorator
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.Journal
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerProtocolFeature
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Uses isolated Room storage and no transport. Run only on an emulator or Managed Device. */
@RunWith(AndroidJUnit4::class)
class JournalMergeE2ETest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val stores = ViewModelStore()
    private lateinit var database: LogDateDatabase
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val source = journal("Mountain trips")
    private val destination = journal("Life with friends")
    private val other = journal("Favorites")
    private val origin = "https://merge-device.example"

    @After
    fun close() {
        composeRule.runOnUiThread { stores.clear() }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun overflowPickerReviewAndDestinationCommitRealRoomMerge() {
        database =
            getRoomDatabase(
                Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogDateDatabase::class.java),
                driver = null,
            )
        val incoming = TextNoteEntity("Sunrise on the trail", lastUpdated = now, created = now)
        val overlap = TextNoteEntity("A shared memory", lastUpdated = now, created = now)
        val existing = TextNoteEntity("Dinner with friends", lastUpdated = now, created = now)
        runBlocking {
            listOf(source, destination, other).forEach { database.journalDao().create(it) }
            listOf(incoming, overlap, existing).forEach { database.textNoteDao().addNote(it) }
            listOf(
                source.id to incoming.uid,
                source.id to overlap.uid,
                destination.id to overlap.uid,
                destination.id to existing.uid,
                other.id to incoming.uid,
            ).forEach { (journal, note) ->
                database.journalContentDao().addContentToJournal(JournalContentEntityLink(journal, note))
            }
        }
        val repository =
            OfflineFirstJournalRepository(
                journalDao = database.journalDao(),
                remoteDataSource = unexpected<RemoteJournalDataSource>(),
                draftRepository = unexpected<DraftRepository>(),
                syncMetadataService = unexpected<SyncMetadataService>(),
                database = database,
                currentScope = { JournalMergeScope("isolated-device-owner", origin) },
                mergeTransactionManager = RoomSyncTransactionManager(database),
            )
        runBlocking {
            val preview = requireNotNull(repository.previewMerge(source.id, destination.id))
            assertEquals(3, preview.combinedCount)
            assertEquals(1, preview.overlapCount)
        }
        val memberships =
            object : JournalContentRepository by unexpected<JournalContentRepository>() {
                override fun observeJournalItemCounts() =
                    database.journalContentDao().observeAllLinks().map { links ->
                        links.groupingBy { it.journalId }.eachCount()
                    }

                override fun observeContentForJournal(journalId: Uuid) =
                    combine(
                        database.journalContentDao().getContentForJournal(journalId),
                        database.textNoteDao().getAllNotes(),
                    ) { ids, notes ->
                        notes.filter { it.uid in ids }.map { JournalNote.Text(it.uid, it.created, it.lastUpdated, it.content) }
                    }

                override fun observeJournalsForContents(contentIds: Set<Uuid>) = flowOf(emptyMap<Uuid, List<Journal>>())
            }
        val config = DefaultLogDateConfigRepository(initialBackendUrl = origin)
        runBlocking {
            config.updateServerDescriptor(
                ServerDescriptor(
                    serverOrigin = origin,
                    apiBaseUrl = "$origin/api",
                    deploymentKind = DeploymentKind.SELF_HOSTED,
                    displayName = "Device test",
                    protocolFeatures = listOf(ServerProtocolFeature.JOURNAL_MERGE_V1),
                ),
            )
        }
        val flags =
            object : FeatureFlagStore {
                override fun observe(flag: FeatureFlag) = flowOf(flag == FeatureFlag.JOURNAL_MERGE)

                override suspend fun setEnabled(
                    flag: FeatureFlag,
                    enabled: Boolean,
                ) = error("Test flags are fixed")
            }
        val stack = mutableStateListOf<NavKey>(JournalsOverviewRoute, JournalDetailsRoute(source.id.toString()))
        val details = mutableMapOf<Uuid, JournalDetailViewModel>()
        lateinit var merge: JournalMergeViewModel
        composeRule.runOnUiThread {
            listOf(source, destination).forEach { journal ->
                details[journal.id] =
                    ownedViewModel(journal.id.toString()) {
                        JournalDetailViewModel(
                            repository,
                            memberships,
                            GetJournalMembershipUseCase(memberships),
                            SearchInJournalUseCase(unexpected<SearchRepository>()),
                            SavedStateHandle(),
                        )
                    }
            }
        }
        composeRule.setContent {
            LogDateTheme {
                CompositionLocalProvider(LocalWorkspaceSearchAction provides {}) {
                    NavDisplay(
                        backStack = stack,
                        onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>({ true })),
                        entryDecorators =
                            listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberWorkspaceRouteDecorator(),
                            ),
                        entryProvider =
                            entryProvider {
                                taggedEntry<JournalsOverviewRoute>(ActivityPresentation.Collection("journals").asMetadata()) {
                                    val journals by repository.allJournalsObserved.collectAsState(emptyList())
                                    JournalsList(journals, onJournalClick = { stack.add(JournalDetailsRoute(it.id.toString())) })
                                }
                                taggedEntry<JournalDetailsRoute>(ActivityPresentation.Detail("journals", "journal").asMetadata()) { route ->
                                    JournalDetailScreen(
                                        Uuid.parse(route.journalId),
                                        onGoBack = { stack.removeAt(stack.lastIndex) },
                                        onJournalDeleted = {},
                                        onNavigateToMerge = { id ->
                                            merge =
                                                ownedViewModel("merge-${Uuid.random()}") {
                                                    JournalMergeViewModel(
                                                        MergeJournalsUseCase(repository, memberships, NoOpSyncManager),
                                                        SavedStateHandle(),
                                                    )
                                                }
                                            stack.add(JournalMergeRoute(id.toString()))
                                        },
                                        mergedSourceTitle = route.mergedSourceTitle,
                                        flags = flags,
                                        config = config,
                                        viewModel = details.getValue(Uuid.parse(route.journalId)),
                                    )
                                }
                                taggedEntry<JournalMergeRoute>(ActivityPresentation.Replacement.asMetadata()) { route ->
                                    check(!LocalWorkspaceHosted.current) { "Merge must not inherit a Home host" }
                                    JournalMergeScreen(
                                        Uuid.parse(route.sourceId),
                                        onBack = { stack.removeAt(stack.lastIndex) },
                                        onJournalMerged = { stack.completeJournalMerge(it) },
                                        flags = flags,
                                        config = config,
                                        viewModel = merge,
                                    )
                                }
                            },
                    )
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(source.title)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Journal options").performClick()
        composeRule.onNode(hasText("Merge into…") and hasAnyAncestor(isPopup())).performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(destination.title)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithTag("workspace_search").assertCountEquals(0)
        composeRule.onNodeWithTag("journal_merge_activity").assertIsDisplayed()
        composeRule.onNodeWithTag("journal_merge_search").performTextInput("Life")
        composeRule.onNodeWithText(destination.title).performClick()
        awaitReview(merge)
        composeRule.onNodeWithContentDescription("Change source journal").performScrollTo().performClick()
        composeRule.onNodeWithText("Move contents from…").assertIsDisplayed()
        composeRule.onNodeWithText(source.title).performScrollTo().performClick()
        awaitReview(merge)
        composeRule.onNodeWithContentDescription("Change destination").performScrollTo().performClick()
        composeRule.onNodeWithTag("journal_merge_search").assertTextContains("Life")
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        awaitReview(merge)
        composeRule.onNodeWithText("Details").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("3 items after merging").performScrollTo().assertIsDisplayed()
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: composeRule.activity.filesDir.path)
        output.mkdirs()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(output, "merge-e2e-count-review.png"))
        composeRule.onNodeWithContentDescription("1 item in both journals").performScrollTo().assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("journal_merge_search").assertTextContains("Life")
        composeRule.onAllNodesWithTag("workspace_search").assertCountEquals(0)
        composeRule.onNodeWithText(destination.title).performClick()
        awaitReview(merge)
        if (!composeRule.onNodeWithText("Cancel").isDisplayed()) composeRule.onNodeWithText("Cancel").performScrollTo()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(JournalDetailsRoute(source.id.toString()), stack.last()) }
        runBlocking {
            assertEquals(source, database.journalDao().getJournalById(source.id))
            assertTrue(repository.pendingJournalMerges().isEmpty())
        }
        composeRule.onNodeWithContentDescription("Journal options").performClick()
        composeRule.onNode(hasText("Merge into…") and hasAnyAncestor(isPopup())).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("journal_merge_search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("journal_merge_search").performTextInput("Life")
        composeRule.onNodeWithText(destination.title).performClick()
        awaitReview(merge)
        val confirm = composeRule.onNode(hasText("Merge journals") and hasClickAction())
        if (!confirm.isDisplayed()) confirm.performScrollTo()
        confirm.performClick()
        composeRule.waitUntil(10_000) {
            stack.last() is JournalDetailsRoute &&
                (stack.last() as JournalDetailsRoute).journalId == destination.id.toString()
        }
        val message = "Merged ‘${source.title}’ into ‘${destination.title}’."
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(incoming.content)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(incoming.content).performScrollTo().assertIsDisplayed()
        runBlocking {
            assertNull(database.journalDao().getJournalById(source.id))
            assertEquals(destination, database.journalDao().getJournalById(destination.id))
            assertEquals(
                setOf(incoming.uid, overlap.uid, existing.uid),
                database
                    .journalContentDao()
                    .getContentForJournal(destination.id)
                    .first()
                    .toSet(),
            )
            assertEquals(listOf(incoming.uid), database.journalContentDao().getContentForJournal(other.id).first())
            assertEquals(incoming, database.textNoteDao().getNoteOneOff(incoming.uid))
            assertEquals(destination.id, repository.resolveJournalId(source.id))
            assertEquals(1, repository.pendingJournalMerges().size)
        }
        composeRule.runOnIdle {
            assertEquals(JournalsOverviewRoute, stack.first())
            assertTrue(stack.none { it is JournalMergeRoute || it == JournalDetailsRoute(source.id.toString()) })
        }
    }

    private fun journal(title: String) =
        JournalEntity(title = title, description = "Keep this description", created = now, lastUpdated = now)

    private fun awaitReview(viewModel: JournalMergeViewModel) {
        composeRule.waitUntil(10_000) {
            viewModel.uiState.value.stage is JournalMergeStage.Review || viewModel.uiState.value.error != null
        }
        composeRule.runOnIdle {
            assertTrue("Review failed: ${viewModel.uiState.value.error}", viewModel.uiState.value.stage is JournalMergeStage.Review)
        }
    }

    private inline fun <reified T : ViewModel> ownedViewModel(
        key: String,
        crossinline build: () -> T,
    ): T =
        ViewModelProvider(
            stores,
            object : ViewModelProvider.Factory {
                override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = requireNotNull(modelClass.cast(build()))
            },
        ).get(key, T::class.java)

    private inline fun <reified T> unexpected(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Isolated merge must not invoke ${T::class.simpleName}.${method.name}")
        } as T
}
