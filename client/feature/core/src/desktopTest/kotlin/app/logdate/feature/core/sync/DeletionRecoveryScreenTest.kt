package app.logdate.feature.core.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.conflict.DeletedEdit
import app.logdate.client.sync.conflict.SyncConflictRecord
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class DeletionRecoveryScreenTest {
    @Test
    fun `saving an offline edit requires a deliberate action`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var saved: String? = null
            val record =
                SyncConflictRecord(
                    "recovery",
                    "NOTE",
                    "original",
                    2,
                    3,
                    1,
                    2,
                    "DELETION_CONFLICT",
                    3,
                    scope = UploadScope("owner", "origin"),
                    deletedEdit =
                        DeletedEdit(
                            note =
                                JournalNote.Text(
                                    creationTimestamp = Instant.fromEpochMilliseconds(1),
                                    lastUpdated = Instant.fromEpochMilliseconds(2),
                                    content = "An edit made on the train",
                                ),
                        ),
                )
            setContent {
                LogDateTheme(darkTheme = false) {
                    DeletionRecoveryContent(listOf(record), null, false, false, {}, { saved = it }, {})
                }
            }
            onNodeWithText("An edit made on the train").assertExists()
            assertEquals(null, saved)
            onNodeWithText("Save as a new item").performClick()
            assertEquals("recovery", saved)
            onNodeWithText("Restore").assertDoesNotExist()
        }

    @Test fun phoneLight() = render("phone", 411, 891, false, false, 1f)

    @Test fun tabletDark() = render("tablet-dark", 840, 1100, true, false, 1f)

    @Test fun phoneRtlLargeText() = render("phone-rtl-large", 411, 891, true, true, 2f)

    @Test fun phoneLandscape() = render("phone-landscape", 891, 411, false, false, 1f)

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean,
        rtl: Boolean,
        scale: Float,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val root =
            generateSequence(
                File(System.getProperty("user.dir")),
            ) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val now = Instant.fromEpochMilliseconds(1)
        val record =
            SyncConflictRecord(
                "trip",
                "JOURNAL",
                "original",
                1,
                null,
                1,
                null,
                "DELETION_CONFLICT",
                1,
                deletedEdit =
                    DeletedEdit(
                        journal =
                            app.logdate.shared.model
                                .Journal(title = "A day by the water"),
                        notes =
                            listOf(
                                JournalNote.Text(
                                    creationTimestamp = now,
                                    lastUpdated = now,
                                    content = "We took the long way home. I wanted to remember how quiet the water was.",
                                ),
                                JournalNote.Image(
                                    creationTimestamp = now,
                                    lastUpdated = now,
                                    mediaRef =
                                        File(
                                            root,
                                            "client/feature/core/src/desktopTest/resources/recovery/photo.jpg",
                                        ).toURI().toString(),
                                    caption = "The view before we headed home",
                                ),
                            ),
                    ),
            )
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                LogDateTheme(darkTheme = dark) {
                    DeletionRecoveryContent(listOf(record), null, false, false, {}, {}, {})
                }
            }
        }
        onNodeWithText("Save as a new item").performScrollTo()
        val summary =
            onNodeWithText(
                "These items were deleted before your edits could sync. Your edits are saved here. Saving a new item keeps the original deleted.",
            )
        summary.performScrollTo()
        val bounds = summary.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 16f && bounds.right <= width - 16f)
        if (scale == 1f && height > 500) {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("saved-edit-image-ready").fetchSemanticsNodes().isNotEmpty() }
        }
        waitForIdle()
        val bitmap = onRoot().captureToImage()
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val renderRoot =
            System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it, "renders") }
                ?: File("build/reports/sync-renders")
        val target = File(renderRoot, "$name.png")
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
        onNodeWithText("Save as a new item").performScrollTo().assertExists()
    }

    @Test
    fun `retained failed uploads have a working retry action`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var retried: String? = null
            val issue =
                app.logdate.client.sync.metadata.SyncDeadLetterRecord(
                    "failure",
                    "NOTE",
                    "note",
                    "CREATE",
                    6,
                    "MISSING_FILE",
                    1,
                    reason = app.logdate.client.sync.metadata.SyncDeadLetterReason.MISSING_FILE,
                )
            setContent {
                LogDateTheme {
                    DeletionRecoveryContent(
                        emptyList(),
                        null,
                        false,
                        false,
                        {},
                        {},
                        {},
                        issues = listOf(issue),
                        onRetryIssue = { retried = it },
                    )
                }
            }
            onNodeWithText("A media file is unavailable on this device.").assertExists()
            onNodeWithText("Try syncing this item again").performClick()
            assertEquals("failure", retried)
            onNodeWithText("No edits to deleted items need review.").assertDoesNotExist()
        }
}
