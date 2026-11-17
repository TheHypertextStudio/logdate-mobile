package app.logdate.feature.core.settings.account.plan

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.account.CloudPlanProblem
import app.logdate.client.domain.storage.StoredKind
import app.logdate.feature.core.settings.ui.STORAGE_USAGE_DETAILS_TAG
import app.logdate.feature.core.settings.ui.STORAGE_USAGE_SHAPES_TAG
import app.logdate.feature.core.settings.ui.STORAGE_USAGE_TOGGLE_TAG
import app.logdate.feature.core.settings.ui.storageActionTag
import app.logdate.feature.core.settings.ui.storageCategoryTag
import app.logdate.feature.core.settings.ui.storageSegmentTag
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudObjectType
import app.logdate.shared.model.CloudStorageCategoryUsage
import app.logdate.shared.model.CloudStorageQuota
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.PlanOption
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Plan screen as someone meets it: what it says about their plan and what it lets them do. */
@OptIn(ExperimentalTestApi::class)
class CloudPlanContentTest {
    private class Calls {
        val bought = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val intervals = mutableListOf<BillingInterval>()
        var cancels = 0
        var keeps = 0
        var paymentDetails = 0
        var checks = 0
        var retries = 0
        var dismissals = 0
        val reviewed = mutableListOf<StorageReview>()

        val actions =
            CloudPlanActions(
                onRetry = { retries++ },
                onSelectInterval = { intervals += it },
                onBuy = { bought += it },
                onChange = { changed += it },
                onCancel = { cancels++ },
                onKeep = { keeps++ },
                onPaymentDetails = { paymentDetails++ },
                onCheckPayment = { checks++ },
                onDismissProblem = { dismissals++ },
            )
    }

    private fun ready(
        subscription: CloudSubscription,
        plans: List<PlanOption> = CloudPlanFixtures.plans(onSale = subscription.purchasesAvailable),
        interval: BillingInterval = subscription.interval ?: BillingInterval.MONTH,
        awaitingPayment: Boolean = false,
        working: Boolean = false,
        problem: CloudPlanProblem? = null,
        breakdown: CloudStorageQuota? = breakdownOf(subscription),
    ) = CloudPlanUiState.Ready(subscription, plans, interval, working, awaitingPayment, problem, breakdown)

    /** What the stored bytes are made of: mostly photos and videos, as a journal's storage is. */
    private fun breakdownOf(subscription: CloudSubscription): CloudStorageQuota? {
        val used = subscription.storageUsedBytes
        if (used == 0L) return null
        val photos = used * 6 / 10
        val videos = used * 3 / 10
        val recordings = used * 8 / 100
        return CloudStorageQuota(
            totalBytes = subscription.storageIncludedBytes ?: used,
            usedBytes = used,
            categories =
                listOf(
                    CloudStorageCategoryUsage(CloudObjectType.IMAGE_NOTES, photos),
                    CloudStorageCategoryUsage(CloudObjectType.VIDEO_NOTES, videos),
                    CloudStorageCategoryUsage(CloudObjectType.VOICE_NOTES, recordings),
                    CloudStorageCategoryUsage(CloudObjectType.TEXT_NOTES, used - photos - videos - recordings),
                ),
        )
    }

    private fun planScreen(
        state: CloudPlanUiState,
        name: String? = null,
        dark: Boolean = false,
        width: Int = 411,
        test: DesktopComposeUiTest.(Calls) -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = 1100) {
        val calls = Calls()
        setContent {
            LogDateTheme(darkTheme = dark, dynamicColor = false) {
                CloudPlanContent(state = state, onBack = {}, actions = calls.actions, onReview = { calls.reviewed += it })
            }
        }
        if (name != null) saveScreenshot(name, onRoot().captureToImage())
        test(calls)
    }

    private fun DesktopComposeUiTest.scrollTo(text: String) {
        onNodeWithTag(CLOUD_PLAN_ROOT_TAG).performScrollToNode(hasText(text))
    }

    @Test
    fun aBetaMemberSeesTheirFreePlanItsStorageAndNothingToBuy() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = false)), name = "plan-beta") {
            onNodeWithText("You were here early").assertIsDisplayed()
            onNodeWithText("Your LogDate Cloud is free for good.").assertIsDisplayed()
            onNodeWithTag(CLOUD_PLAN_STORAGE_TAG).assertIsDisplayed()
            onNodeWithContentDescription("Storage used: 32 GB of 50 GB").assertExists()
            // Their own plan is the header; only the plan they could move to is listed below it.
            onNodeWithTag(cloudPlanCardTag("cloud")).assertDoesNotExist()
            onNodeWithText("250 GB of storage").assertIsDisplayed()
            onNodeWithText("Extra space: $1 a month per 50 GB").assertIsDisplayed()
            onNodeWithText("$7.99 a month · Not on sale yet").assertIsDisplayed()

            // Before plans go on sale the server sells nothing, so the screen offers nothing.
            onNodeWithTag(cloudPlanActionTag("cloud")).assertDoesNotExist()
            onNodeWithTag(cloudPlanActionTag("cloud_plus")).assertDoesNotExist()
            onNodeWithText("Cancel plan").assertDoesNotExist()
            onNodeWithText("Payment and receipts").assertDoesNotExist()
            onNodeWithText("Monthly").assertDoesNotExist()
        }

    @Test
    fun theStorageCardOpensToShowWhatTakesUpTheSpace() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))) {
            onNodeWithTag(STORAGE_USAGE_DETAILS_TAG).assertDoesNotExist()

            onNodeWithContentDescription("Show details").performClick()

            onNodeWithTag(STORAGE_USAGE_DETAILS_TAG).assertIsDisplayed()
            onNodeWithText("Photos").assertIsDisplayed()
            onNodeWithText("Videos").assertIsDisplayed()
            saveScreenshot("plan-details", onRoot().captureToImage())

            onNodeWithContentDescription("Hide details").performClick()
            onNodeWithTag(STORAGE_USAGE_DETAILS_TAG).assertDoesNotExist()
        }

    @Test
    fun touchingAKindInTheBarOpensTheListAndSinglesThatKindOut() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))) {
            onNodeWithTag(STORAGE_USAGE_DETAILS_TAG).assertDoesNotExist()

            onNodeWithContentDescription("Videos, 9.6 GB, 19%").performClick()

            onNodeWithTag(STORAGE_USAGE_DETAILS_TAG).assertIsDisplayed()
            onNodeWithTag(storageSegmentTag(1)).assertIsSelected()
            onNodeWithTag(storageCategoryTag(1)).assertIsSelected()
            onNodeWithTag(storageCategoryTag(0)).assertIsNotSelected()
            saveScreenshot("plan-chosen", onRoot().captureToImage())

            // The list chooses in the bar too, and touching the chosen kind again lets go of it.
            onNodeWithTag(storageCategoryTag(0)).performClick()
            onNodeWithTag(storageSegmentTag(0)).assertIsSelected()
            onNodeWithTag(storageSegmentTag(1)).assertIsNotSelected()
            onNodeWithTag(storageSegmentTag(0)).performClick()
            onNodeWithTag(storageSegmentTag(0)).assertIsNotSelected()
        }

    @Test
    fun aChosenKindOffersThePlaceToLookThroughIt() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))) { calls ->
            onNodeWithTag(storageActionTag(0)).assertDoesNotExist()

            // The shapes choose a kind the same way the bar does.
            onNodeWithTag(STORAGE_USAGE_SHAPES_TAG).performTouchInput { click(percentOffset(0.15f, 0.5f)) }
            onNodeWithTag(storageSegmentTag(0)).assertIsSelected()
            onNodeWithText("Open Library").performClick()

            onNodeWithTag(storageCategoryTag(2)).performClick()
            onNodeWithText("Search recordings").performClick()

            assertEquals(listOf(StorageReview.Photos, StorageReview.Recordings), calls.reviewed)
        }

    @Test
    fun withTheCleanUpPageAChosenKindOpensIt() =
        runDesktopComposeUiTest(width = 411, height = 1100) {
            val opened = mutableListOf<StoredKind>()
            val reviewed = mutableListOf<StorageReview>()
            setContent {
                LogDateTheme(darkTheme = false, dynamicColor = false) {
                    CloudPlanContent(
                        state = ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true)),
                        onBack = {},
                        onReview = { reviewed += it },
                        onCleanUp = { opened += it },
                    )
                }
            }

            onNodeWithTag(STORAGE_USAGE_SHAPES_TAG).performTouchInput { click(percentOffset(0.15f, 0.5f)) }
            onNodeWithText("Open Library").assertDoesNotExist()
            onNodeWithText("Clean up photos").performClick()

            onNodeWithTag(storageCategoryTag(2)).performClick()
            onNodeWithText("Clean up recordings").performClick()

            assertEquals(listOf(StoredKind.Photos, StoredKind.Recordings), opened)
            assertTrue(reviewed.isEmpty())
        }

    @Test
    fun everyWayOfChoosingAKindIsALargeEnoughTarget() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))) {
            onNodeWithContentDescription("Show details").performClick()
            val smallest = 48.dp
            (0..3).forEach { index ->
                onNodeWithTag(storageSegmentTag(index)).assertHeightIsAtLeast(smallest)
                onNodeWithTag(storageCategoryTag(index)).assertHeightIsAtLeast(smallest)
            }
            onNodeWithTag(STORAGE_USAGE_TOGGLE_TAG).assertHeightIsAtLeast(smallest).assertWidthIsAtLeast(smallest)
        }

    @Test
    fun withoutABreakdownTheStorageCardHasNothingToOpen() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(), breakdown = null)) {
            onNodeWithContentDescription("Storage used: 32 GB of 50 GB").assertExists()
            onNodeWithTag(STORAGE_USAGE_TOGGLE_TAG).assertDoesNotExist()
        }

    @Test
    fun aPaidPlanCanBeBilledTheOtherWayAfterConfirming() =
        planScreen(ready(CloudPlanFixtures.payingForPlus())) { calls ->
            scrollTo("Bill yearly")
            onNodeWithText("$79.99 a year").assertIsDisplayed()
            onNodeWithText("Bill yearly").performClick()

            onNodeWithText("Bill yearly?").assertIsDisplayed()
            assertTrue(calls.changed.isEmpty())

            onNodeWithText("Change").performClick()
            assertEquals(listOf(BillingInterval.YEAR), calls.intervals)
            assertEquals(listOf("cloud_plus"), calls.changed)
        }

    @Test
    fun onceOnSaleABetaMemberCanGetPlusButIsNeverSoldTheirFreePlan() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true)), name = "plan-on-sale") { calls ->
            onNodeWithTag(cloudPlanActionTag("cloud")).assertDoesNotExist()
            onNodeWithText("$7.99 a month").assertIsDisplayed()

            // One plan on offer: how to be billed is chosen inside its card, above the price.
            onNodeWithText("Monthly").assertIsDisplayed()
            onNodeWithText("Upgrade").assertIsDisplayed().performClick()

            assertEquals(listOf("cloud_plus"), calls.bought)
        }

    @Test
    fun choosingYearlyShowsYearlyPricesAndBuysYearly() =
        planScreen(ready(CloudPlanFixtures.noPlan(), interval = BillingInterval.YEAR)) { calls ->
            onNodeWithText("No plan").assertIsDisplayed()
            onNodeWithText("Backing up is off.").assertIsDisplayed()
            onNodeWithText("$39.99 a year").assertIsDisplayed()
            onNodeWithText("$79.99 a year").assertIsDisplayed()

            onNodeWithText("Monthly").performClick()
            assertEquals(listOf(BillingInterval.MONTH), calls.intervals)

            onNodeWithTag(cloudPlanActionTag("cloud")).performClick()
            assertEquals(listOf("cloud"), calls.bought)
        }

    @Test
    fun aPlusSubscriberSeesWhatTheyPayWhenItRenewsAndTheExtraStorageInUse() =
        planScreen(ready(CloudPlanFixtures.payingForPlus()), name = "plan-plus") { calls ->
            onNodeWithContentDescription("Storage used: 281 GB of 300 GB").assertExists()
            // The plan they are on is the storage card, and the free plan is reached by cancelling.
            onNodeWithTag(cloudPlanCardTag("cloud_plus")).assertDoesNotExist()
            onNodeWithTag(cloudPlanCardTag("cloud")).assertDoesNotExist()
            onNodeWithText("$7.99 a month").assertIsDisplayed()
            scrollTo("Renews March 3, 2027.")
            onNodeWithText("Renews March 3, 2027.").assertIsDisplayed()
            onNodeWithText("Extra storage: 50 GB in use, $1 a month.").assertIsDisplayed()

            scrollTo("Payment and receipts")
            onNodeWithText("Payment and receipts").performClick()
            assertEquals(1, calls.paymentDetails)
        }

    @Test
    fun cancellingAsksFirstAndSaysWhatHappensAfter() =
        planScreen(ready(CloudPlanFixtures.payingForPlus())) { calls ->
            scrollTo("Cancel plan")
            onNodeWithText("Cancel plan").performClick()

            onNodeWithText("Cancel LogDate Cloud Plus?").assertIsDisplayed()
            onNodeWithText(
                "You keep it until March 3, 2027. After that LogDate Cloud stays free for you, and nothing is deleted.",
            ).assertIsDisplayed()
            assertEquals(0, calls.cancels, "nothing is cancelled until it is confirmed")

            onNodeWithText("Keep plan").performClick()
            assertEquals(0, calls.cancels)
            onNodeWithText("Cancel LogDate Cloud Plus?").assertDoesNotExist()

            onNodeWithText("Cancel plan").performClick()
            onAllNodesWithText("Cancel plan")[1].performClick()
            assertEquals(1, calls.cancels)
        }

    @Test
    fun someoneWhoJoinedAfterLaunchIsToldBackingUpStopsWhenTheyCancel() =
        planScreen(ready(CloudPlanFixtures.payingForBase())) {
            scrollTo("Cancel plan")
            onNodeWithText("Cancel plan").performClick()

            onNodeWithText("You keep it until March 3, 2027. After that backing up stops, and nothing is deleted.").assertIsDisplayed()
        }

    @Test
    fun switchingPlanStatesThePriceAndWaitsForConfirmation() =
        planScreen(ready(CloudPlanFixtures.payingForBase())) { calls ->
            onNodeWithText("Upgrade").performClick()

            onNodeWithText("Switch to LogDate Cloud Plus?").assertIsDisplayed()
            onNodeWithText("$7.99 a month. The difference for this period is charged now.").assertIsDisplayed()
            assertTrue(calls.changed.isEmpty())

            onNodeWithText("Switch").performClick()
            assertEquals(listOf("cloud_plus"), calls.changed)
        }

    @Test
    fun aPlanThatIsEndingSaysWhenAndCanBeKept() =
        planScreen(
            ready(
                CloudPlanFixtures.payingForPlus().copy(renewsAtEpochSeconds = null, endsAtEpochSeconds = CloudPlanFixtures.RENEWAL),
            ),
            name = "plan-ending",
        ) { calls ->
            onNodeWithText("Ends March 3, 2027. Nothing is deleted.").assertIsDisplayed()
            onNodeWithText("Cancel plan").assertDoesNotExist()

            onNodeWithText("Keep plan").performClick()
            assertEquals(1, calls.keeps)
        }

    @Test
    fun aFailedPaymentSaysSoAndLeadsToFixingIt() =
        planScreen(ready(CloudPlanFixtures.payingForPlus(status = EntitlementStatusWire.PAST_DUE)), name = "plan-past-due") { calls ->
            onNodeWithText("A payment didn’t go through.").assertIsDisplayed()

            onNodeWithText("Fix payment").performClick()
            assertEquals(1, calls.paymentDetails)
        }

    @Test
    fun whileACheckoutIsOpenThePageSaysToFinishInTheBrowser() =
        planScreen(
            ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true), awaitingPayment = true),
            name = "plan-awaiting",
        ) { calls ->
            onNodeWithText("Finish paying in your browser.").assertIsDisplayed()

            onNodeWithText("Check again").performClick()
            assertEquals(1, calls.checks)
        }

    @Test
    fun aRefusedDowngradeSaysHowMuchIsStoredAndHowMuchThePlanHolds() =
        planScreen(
            ready(
                CloudPlanFixtures.payingForPlus(usedBytes = 70 * CloudPlanFixtures.GIB),
                problem = CloudPlanProblem.StorageExceedsPlan(70 * CloudPlanFixtures.GIB, 50 * CloudPlanFixtures.GIB),
            ),
            name = "plan-problem",
        ) { calls ->
            onNodeWithTag(CLOUD_PLAN_PROBLEM_TAG).assertIsDisplayed()
            onNodeWithText("You’re using 70 GB. That plan holds 50 GB.").assertIsDisplayed()

            onNodeWithText("Dismiss").performClick()
            assertEquals(1, calls.dismissals)
        }

    @Test
    fun actionsWaitWhileAChangeIsBeingMade() =
        planScreen(ready(CloudPlanFixtures.payingForBase(), working = true)) {
            onNodeWithTag(cloudPlanActionTag("cloud_plus")).assertIsNotEnabled()
        }

    @Test
    fun aPlanThatCannotBeReadSaysWhyAndCanBeTriedAgain() =
        planScreen(CloudPlanUiState.Unavailable(CloudPlanProblem.Offline)) { calls ->
            onNodeWithText("Can’t reach LogDate Cloud. Try again.").assertIsDisplayed()

            onNodeWithText("Try again").assertIsEnabled().performClick()
            assertEquals(1, calls.retries)
        }

    @Test
    fun aProviderWithoutPlansSaysSoAndOffersNothingToRetry() =
        planScreen(CloudPlanUiState.Unavailable(CloudPlanProblem.NoBilling)) {
            onNodeWithText("This provider has no plans.").assertIsDisplayed()
            onNodeWithText("Try again").assertDoesNotExist()
        }

    @Test
    fun theScreenFillsATabletWithoutStretchingItsParts() =
        planScreen(ready(CloudPlanFixtures.betaOnBase(purchasesAvailable = true)), name = "plan-tablet", width = 840) {
            onNodeWithTag(CLOUD_PLAN_STORAGE_TAG).assertIsDisplayed()
            onNodeWithTag(CLOUD_PLAN_BETA_TAG).assertIsDisplayed()
        }

    @Test
    fun theScreenReadsInDarkTheme() =
        planScreen(ready(CloudPlanFixtures.payingForPlus()), name = "plan-plus-dark", dark = true) {
            onNodeWithContentDescription("Storage used: 281 GB of 300 GB").assertExists()
        }

    private fun saveScreenshot(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_SETTINGS_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
