package app.logdate.feature.journals.ui.merge

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalMergeCandidate
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.shared.model.Journal
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceHosted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class JournalMergeActivityTest {
    private val source = Journal(title = "Summer in the mountains")
    private val destination = Journal(title = "Journeys with friends and family")
    private val preview = JournalMergePreview(source, destination, setOf(Uuid.random()), setOf(Uuid.random()))

    @Test
    fun `wide picker stays bounded with source context above the choices`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalWorkspaceHosted provides true) {
                        JournalMergeScreenContent(
                            JournalMergeUiState(
                                sourceTitle = source.title,
                                loading = false,
                                candidates = listOf(JournalMergeCandidate(destination, 2)),
                            ),
                        )
                    }
                }
            }
            onAllNodesWithTag("workspace_search").assertCountEquals(0)
            onNodeWithText("Search journals").assertIsDisplayed()
            val sourceBounds = onNodeWithTag("journal_merge_source").getUnclippedBoundsInRoot()
            val pickerBounds = onNodeWithTag("journal_merge_picker").getUnclippedBoundsInRoot()
            assertTrue(sourceBounds.bottom <= onNodeWithTag("journal_merge_search").getUnclippedBoundsInRoot().top)
            assertTrue(pickerBounds.right - pickerBounds.left in 1000.dp..1120.dp, "The collection uses the available wide canvas")
        }

    @Test
    fun `compact picker shows the first destination title before scrolling`() =
        runDesktopComposeUiTest(width = 320, height = 600) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        JournalMergeUiState(
                            sourceTitle = source.title,
                            loading = false,
                            candidates = listOf(JournalMergeCandidate(destination, 2)),
                        ),
                    )
                }
            }
            onNodeWithText(destination.title).assertIsDisplayed()
        }

    @Test
    fun `wide review balances unframed journals with a substantial decision card`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                }
            }
            val identities = onNodeWithTag("journal_merge_identities").getUnclippedBoundsInRoot()
            val collapsedDecision = onNodeWithTag("journal_merge_decision").getUnclippedBoundsInRoot()
            assertTrue(
                (collapsedDecision.right - collapsedDecision.left) >= 460.dp,
                "The decision has a comparable share of the tablet composition",
            )
            val viewport = onRoot().getUnclippedBoundsInRoot()
            assertTrue(collapsedDecision.top <= viewport.top + 24.dp, "The panel begins at the safe top gutter")
            assertTrue(collapsedDecision.bottom >= viewport.bottom - 24.dp, "The panel fills the safe height")
            assertTrue((identities.bottom - identities.top) <= 580.dp, "The books do not stretch into a full-height empty stage")
            val heading = onNodeWithText("Merge journals?").getUnclippedBoundsInRoot()
            assertTrue(heading.bottom - heading.top <= 32.dp, "Heading stays at normal reading scale")
            onNodeWithText("Merge journals").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
            onNodeWithText("Details").assertDoesNotExist()
            val outcome = onNodeWithTag("journal_merge_outcome").getUnclippedBoundsInRoot()
            val actions = onNodeWithTag("journal_merge_actions").getUnclippedBoundsInRoot()
            val decision = onNodeWithTag("journal_merge_decision").getUnclippedBoundsInRoot()
            assertTrue(identities.right <= decision.left, "Journals and decision have their own space")
            assertTrue((identities.right - identities.left) >= 500.dp)
            val destinationCover = onNodeWithTag("journal_merge_destination_cover").getUnclippedBoundsInRoot()
            assertTrue(destinationCover.right - destinationCover.left >= 240.dp)
            onNodeWithTag("journal_merge_books").assertIsDisplayed()
            onNodeWithText("What changes").assertDoesNotExist()
            onNodeWithText("After merging").assertDoesNotExist()
            assertTrue(actions.top >= outcome.bottom, "Actions have a distinct region below the decision")
            onNodeWithText("Merge journals").performScrollTo().assertIsDisplayed()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            onNodeWithText("Search journals").assertDoesNotExist()
            onAllNodesWithTag("journal_merge_actions").assertCountEquals(1)
        }

    @Test
    fun `review keeps controls and metadata in the decision and covers purely visual`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var changes = 0
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)),
                        onChangeDestination = { changes++ },
                    )
                }
            }
            val keep = onNodeWithTag("journal_merge_decision").getUnclippedBoundsInRoot()
            val change = onNodeWithContentDescription("Change destination").getUnclippedBoundsInRoot()
            assertTrue(change.left >= keep.left && change.right <= keep.right)
            assertTrue(change.top >= keep.top && change.bottom <= keep.bottom)
            val books = onNodeWithTag("journal_merge_books")
            books.assert(!hasAnyDescendant(hasText("From")))
            books.assert(!hasAnyDescendant(hasText("Into")))
            books.assert(!hasAnyDescendant(hasContentDescription("Change destination")))
            books.assert(!hasAnyDescendant(hasText("1 item")))
            onNodeWithContentDescription("Change destination").performClick()
            assertEquals(1, changes)
        }

    @Test
    fun `journal selection uses two complete tappable cards with aligned names`() =
        runDesktopComposeUiTest(width = 452, height = 320) {
            var sourceChanges = 0
            var destinationChanges = 0
            setContent {
                LogDateTheme {
                    JournalMergeSelection(
                        preview,
                        onChangeDestination = { destinationChanges++ },
                        enabled = true,
                        onChangeSource = { sourceChanges++ },
                    )
                }
            }
            val sourceCard = onNodeWithContentDescription("Change source journal", useUnmergedTree = true)
            val destinationCard = onNodeWithContentDescription("Change destination", useUnmergedTree = true)
            sourceCard.assert(hasClickAction())
            destinationCard.assert(hasClickAction())
            sourceCard.assert(hasAnyDescendant(hasText(source.title)))
            destinationCard.assert(hasAnyDescendant(hasText(destination.title)))
            val from = sourceCard.getUnclippedBoundsInRoot()
            val into = destinationCard.getUnclippedBoundsInRoot()
            assertEquals(from.left, into.left)
            assertEquals(from.right, into.right)
            assertTrue(from.bottom < into.top)
            onNodeWithText("Move contents from", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Add them to", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Change").assertDoesNotExist()
            sourceCard.performClick()
            destinationCard.performClick()
            assertEquals(1, sourceChanges)
            assertEquals(1, destinationChanges)
        }

    @Test
    fun `journal card role stays on one line at intermediate large text`() =
        runDesktopComposeUiTest(width = 520, height = 400) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                        JournalMergeSelection(preview, {}, true)
                    }
                }
            }
            val role = onNodeWithText("Move contents from", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(role.bottom - role.top <= 24.dp, "The instruction has the card width rather than a narrow label column")
        }

    @Test
    fun `review puts the source overlap and resulting counts in the respective journal cards`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val ids = List(36) { Uuid.random() }
            val overlapping = JournalMergePreview(source, destination, ids.take(12).toSet(), ids.drop(8).toSet())
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(overlapping)))
                }
            }
            onNodeWithTag("journal_merge_quantities").assertDoesNotExist()
            onNodeWithText("Items").assertDoesNotExist()
            val from = onNodeWithContentDescription("Change source journal", useUnmergedTree = true)
            val into = onNodeWithContentDescription("Change destination", useUnmergedTree = true)
            from.assert(hasAnyDescendant(hasContentDescription("12 items in source journal")))
            from.assert(hasAnyDescendant(hasContentDescription("4 items in both journals")))
            into.assert(hasAnyDescendant(hasContentDescription("28 items in destination journal")))
            into.assert(hasAnyDescendant(hasContentDescription("36 items after merging")))
            onNodeWithText("Name, description, and cover stay with Keep.").assertDoesNotExist()
            onNodeWithContentDescription("28 items in destination journal").assertIsDisplayed()
            onNodeWithContentDescription("12 items in source journal").assertIsDisplayed()
            onNodeWithContentDescription("36 items after merging").assertIsDisplayed()
            onNodeWithContentDescription("4 items in both journals").assertIsDisplayed()
        }

    @Test
    fun `review compares original and final sizes without addition and keeps consequences beside them`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                }
            }
            onNodeWithText("+").assertDoesNotExist()
            onNodeWithText("=").assertDoesNotExist()
            onNodeWithText("Added").assertDoesNotExist()
            val summary = onNodeWithTag("journal_merge_outcome").getUnclippedBoundsInRoot()
            val consequence = onNodeWithTag("journal_merge_consequences").getUnclippedBoundsInRoot()
            val info = onNodeWithTag("journal_merge_info", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(info.left >= consequence.left && info.right <= consequence.right)
            assertTrue(info.top >= consequence.top && info.bottom <= consequence.bottom)
            assertTrue(consequence.top >= summary.bottom && consequence.top - summary.bottom <= 24.dp)
            onNodeWithText("Your notes and media stay. The source journal is removed permanently.").assertDoesNotExist()
        }

    @Test
    fun `floating back leaves top edge scrolling to the content behind it`() =
        runDesktopComposeUiTest(width = 411, height = 600) {
            var backs = 0
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        JournalMergeScreenContent(
                            JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)),
                            onBack = { backs++ },
                        )
                    }
                }
            }
            val coverBefore = onNodeWithTag("journal_merge_source_cover").getUnclippedBoundsInRoot()
            onRoot().performTouchInput { swipeUp(startY = 48f, endY = 4f) }
            val coverAfter = onNodeWithTag("journal_merge_source_cover").getUnclippedBoundsInRoot()
            assertTrue(coverAfter.top < coverBefore.top, "The transparent top area does not intercept the scroll")
            assertTrue(coverAfter.top < onNodeWithContentDescription("Back").getUnclippedBoundsInRoot().bottom)
            onNodeWithContentDescription("Back").performClick()
            assertEquals(1, backs)
        }

    @Test
    fun `normal phone and tablet put cancel beside merge within the decision bounds`() {
        listOf(411 to 891, 1280 to 800).forEach { (width, height) ->
            runDesktopComposeUiTest(width = width, height = height) {
                setContent {
                    LogDateTheme {
                        JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                    }
                }
                val merge = onNodeWithText("Merge journals").getUnclippedBoundsInRoot()
                val cancel = onNodeWithText("Cancel").getUnclippedBoundsInRoot()
                val decision = onNodeWithTag("journal_merge_decision").getUnclippedBoundsInRoot()
                assertTrue(cancel.right <= merge.left, "Secondary action precedes primary action")
                assertTrue(cancel.top < merge.bottom && merge.top < cancel.bottom, "Both actions share a row when there is room")
                assertTrue(cancel.left >= decision.left && merge.right <= decision.right)
            }
        }
    }

    @Test
    fun `short landscape shows both complete books before scrolling the decision`() =
        runDesktopComposeUiTest(width = 891, height = 411) {
            val overlapping = preview.copy(sourceContentIds = preview.sourceContentIds + preview.destinationContentIds)
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(overlapping)))
                }
            }
            val viewport = onRoot().getUnclippedBoundsInRoot()
            listOf("journal_merge_source_cover", "journal_merge_destination_cover").forEach { tag ->
                val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
                assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom, "Both book titles fit before scrolling")
            }
            onNodeWithText("Merge journals").performScrollTo().assertIsDisplayed()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            listOf("Merge journals", "Cancel").forEach { action ->
                val bounds = onNodeWithText(action).getUnclippedBoundsInRoot()
                assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom, "Complete actions fit after scrolling")
            }
        }

    @Test
    fun `portrait tablet gives readable journals the space above the decision`() =
        runDesktopComposeUiTest(width = 800, height = 1280) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                }
            }
            val identities = onNodeWithTag("journal_merge_identities").getUnclippedBoundsInRoot()
            val decision = onNodeWithTag("journal_merge_decision").getUnclippedBoundsInRoot()
            val cover = onNodeWithTag("journal_merge_destination_cover").getUnclippedBoundsInRoot()
            assertTrue(identities.bottom <= decision.top, "Portrait uses the space for large books above the decision")
            assertTrue(cover.right - cover.left >= 240.dp)
            onNodeWithText("Merge journals").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
        }

    @Test
    fun `rtl wide picker keeps the same bounded vertical reading order`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        JournalMergeScreenContent(JournalMergeUiState(sourceTitle = source.title, loading = false))
                    }
                }
            }
            val sourceBounds = onNodeWithTag("journal_merge_source").getUnclippedBoundsInRoot()
            val pickerBounds = onNodeWithTag("journal_merge_picker").getUnclippedBoundsInRoot()
            assertTrue(sourceBounds.left >= pickerBounds.left && sourceBounds.right <= pickerBounds.right)
            assertTrue(sourceBounds.bottom <= onNodeWithTag("journal_merge_search").getUnclippedBoundsInRoot().top)
        }

    @Test
    fun `review reuses journal covers without monograms or repeated identities`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                }
            }
            onNodeWithText("S").assertDoesNotExist()
            onNodeWithText("J").assertDoesNotExist()
            onNodeWithText("Bring them together").assertDoesNotExist()
            onNodeWithTag("journal_merge_source_cover").assert(hasAnyDescendant(hasText(source.title)))
            onNodeWithTag("journal_merge_destination_cover").assert(hasAnyDescendant(hasText(destination.title)))
            onNodeWithTag("journal_merge_source_cover").assertIsDisplayed()
            onNodeWithTag("journal_merge_destination_cover").assertIsDisplayed()
            onNodeWithText("Keeps its name, description, and cover").assertDoesNotExist()
            onNodeWithTag("journal_merge_outcome").assertIsDisplayed()
            onNodeWithText("Merge journals").assertIsDisplayed()
        }

    @Test
    fun `narrow tall review scrolls both complete covers without compressing them`() =
        runDesktopComposeUiTest(width = 320, height = 891) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                }
            }
            val sourceCover = onNodeWithTag("journal_merge_source_cover").getUnclippedBoundsInRoot()
            val destinationCover = onNodeWithTag("journal_merge_destination_cover").getUnclippedBoundsInRoot()
            assertTrue(sourceCover.bottom - sourceCover.top >= 400.dp)
            assertTrue(destinationCover.bottom - destinationCover.top >= 400.dp)
            assertTrue(destinationCover.top >= sourceCover.bottom)
            onNodeWithText("Merge journals").performScrollTo().assertIsDisplayed()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `large text gives each actual cover enough width for readable titles`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                    }
                }
            }
            val sourceCover = onNodeWithTag("journal_merge_source_cover").getUnclippedBoundsInRoot()
            val destinationCover = onNodeWithTag("journal_merge_destination_cover").getUnclippedBoundsInRoot()
            assertTrue(sourceCover.right - sourceCover.left >= 240.dp)
            assertTrue(destinationCover.right - destinationCover.left >= 240.dp)
            assertTrue(destinationCover.top >= sourceCover.bottom, "Large text gives both identities their own readable space")
            onNodeWithText("Merge journals").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `large text short landscape scrolls to complete actions without hiding them`() =
        runDesktopComposeUiTest(width = 891, height = 411) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                    }
                }
            }
            onNodeWithText("Merge journals").performScrollTo().assertIsDisplayed()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `changing destination restores the activity local query`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var state by mutableStateOf(
                JournalMergeUiState(
                    sourceTitle = source.title,
                    loading = false,
                    candidates = listOf(JournalMergeCandidate(destination, 2)),
                ),
            )
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        state,
                        onQueryChange = { state = state.copy(query = it) },
                        onChoose = { state = state.copy(stage = JournalMergeStage.Review(preview)) },
                        onChangeDestination = { state = state.copy(stage = JournalMergeStage.Picker) },
                    )
                }
            }
            onNodeWithText("Search journals").performTextInput("Journeys")
            onNodeWithText(destination.title).performClick()
            onNodeWithContentDescription("Change destination").performClick()
            onNodeWithText("Journeys").assertIsDisplayed()
        }

    @Test
    fun `narrow rtl book uses one safe physical region rather than crossing the hinge`() =
        runDesktopComposeUiTest(width = 700, height = 800) {
            val book =
                FoldableLayoutInfo(
                    true,
                    FoldablePosture.Book,
                    FoldableHingeInfo(
                        FoldableHingeOrientation.Vertical,
                        FoldableHingeState.HalfOpened,
                        FoldableOcclusionType.Full,
                        FoldableHingeBounds(340.dp, 0.dp, 360.dp, 800.dp, 20.dp, 800.dp),
                        true,
                    ),
                )
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        provideFoldableLayoutInfo(book) {
                            JournalMergeScreenContent(JournalMergeUiState(sourceTitle = source.title, loading = false))
                        }
                    }
                }
            }
            val picker = onNodeWithTag("journal_merge_picker").getUnclippedBoundsInRoot()
            assertTrue(picker.right <= 340.dp || picker.left >= 360.dp, "Compact content must fit one physical side")
        }

    @Test
    fun `keyboard traverses picker results then review choices before explicit confirmation`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var confirmed = 0
            var state by mutableStateOf(
                JournalMergeUiState(
                    sourceTitle = source.title,
                    loading = false,
                    candidates = listOf(JournalMergeCandidate(destination, 2)),
                ),
            )
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        state,
                        onChoose = { state = state.copy(stage = JournalMergeStage.Review(preview)) },
                        onConfirm = { confirmed++ },
                    )
                }
            }
            onNodeWithTag("journal_merge_search").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onRoot().performKeyInput { pressKey(Key.Tab) }
            onNodeWithText(destination.title).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("Change source journal").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onRoot().performKeyInput { pressKey(Key.Tab) }
            onNodeWithContentDescription("Change destination").assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Tab) }
            onNodeWithText("Cancel").assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Tab) }
            onNodeWithText("Merge journals").assertIsFocused()
            assertEquals(0, confirmed)
            onRoot().performKeyInput { pressKey(Key.Enter) }
            assertEquals(1, confirmed)
        }

    @Test
    fun `narrow container at large text keeps complete actions and quantities reachable`() =
        runDesktopComposeUiTest(width = 280, height = 600) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                    }
                }
            }
            onNodeWithContentDescription("2 items after merging").performScrollTo().assertIsDisplayed()
            onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            onNodeWithText("Merge journals").performScrollTo().assertIsDisplayed()
            val viewport = onRoot().getUnclippedBoundsInRoot()
            val merge = onNodeWithText("Merge journals").getUnclippedBoundsInRoot()
            assertTrue(merge.left >= viewport.left && merge.right <= viewport.right)
            onNodeWithContentDescription("Change destination").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `phone review fits confirmation on its first layout without a corrective frame`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            mainClock.autoAdvance = false
            val overlapping = preview.copy(sourceContentIds = preview.sourceContentIds + preview.destinationContentIds)
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(overlapping)))
                }
            }
            val viewport = onRoot().getUnclippedBoundsInRoot()
            val cancel = onNodeWithText("Cancel").getUnclippedBoundsInRoot()
            assertTrue(cancel.bottom <= viewport.bottom, "Confirmation fits before a follow-up measurement frame")
            val merge = onNode(hasText("Merge journals") and hasClickAction()).getUnclippedBoundsInRoot()
            assertTrue(
                merge.bottom <= viewport.bottom - 16.dp,
                "The complete primary touch target fits above the bottom gutter on the first layout",
            )
            onNodeWithText("Merge journals").assertIsDisplayed()
        }

    @Test
    fun `phone review keeps confirmation reachable without scrolling the consequences`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(preview)))
                }
            }
            onNodeWithText("Merge journals").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
        }
}
