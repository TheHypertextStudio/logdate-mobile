@file:Suppress("ktlint:standard:function-naming")

package app.logdate

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenshotCoverageTest {
    private val root: File = Files.createTempDirectory("screenshot-coverage").toFile()
    private val roots =
        ScreenshotCoverage.Roots(
            repoRoot = root,
            androidSources = root.resolve("src/screenshotTest/kotlin"),
            androidReferences = root.resolve("src/screenshotTestDebug/reference"),
            desktopReferences = root.resolve("desktop/reference"),
        )

    private val matrixSource =
        """
        @Preview(name = "Phone", device = PHONE)
        @Preview(name = "RTL", device = PHONE, locale = "ar")
        annotation class OsIntegrationPreviewMatrix

        @Preview(name = "Bubble", device = BUBBLE)
        annotation class BubbleWindowPreview
        """.trimIndent()

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun parsesEveryLaneAndIgnoresComments() {
        val requirements =
            ScreenshotCoverage.parseRegistry(
                listOf(
                    "# comment",
                    "",
                    "privacy|android|flows/flow20/Flow20_01_Privacy.kt#S01_Privacy",
                    "privacy|desktop|privacy-settings",
                    "tiles|system|docs/screenshots/android-os-integration/tiles/shade--phone-api37.png",
                    "widget|glance|client/feature/android-widgets/src/test/reference/new-entry.png",
                ),
            )

        assertEquals(
            listOf(
                ScreenshotCoverage.Requirement.AndroidPreview("privacy", "flows/flow20/Flow20_01_Privacy.kt", "S01_Privacy"),
                ScreenshotCoverage.Requirement.DesktopScene("privacy", "privacy-settings"),
                ScreenshotCoverage.Requirement.SystemCapture(
                    "tiles",
                    "docs/screenshots/android-os-integration/tiles/shade--phone-api37.png",
                ),
                ScreenshotCoverage.Requirement.GlanceReference("widget", "client/feature/android-widgets/src/test/reference/new-entry.png"),
            ),
            requirements,
        )
    }

    @Test fun rejectsMalformedRegistryLines() {
        assertFailsWith<IllegalArgumentException> { ScreenshotCoverage.parseRegistry(listOf("privacy|android")) }
        assertFailsWith<IllegalArgumentException> { ScreenshotCoverage.parseRegistry(listOf("privacy|tablet|x")) }
        assertFailsWith<IllegalArgumentException> { ScreenshotCoverage.parseRegistry(listOf("privacy|android|NoFunction.kt")) }
    }

    @Test fun readsPreviewNamesForOneMatrixAnnotation() {
        assertEquals(listOf("Phone", "RTL"), ScreenshotCoverage.previewNames(matrixSource, "OsIntegrationPreviewMatrix"))
        assertEquals(listOf("Bubble"), ScreenshotCoverage.previewNames(matrixSource, "BubbleWindowPreview"))
    }

    @Test fun acceptsAnAndroidPreviewWithEveryMatrixReference() {
        writeAndroidPreview(annotations = "@OsIntegrationPreviewMatrix")
        writeReference("S01_Privacy_Phone_abc_0.png")
        writeReference("S01_Privacy_RTL_def_0.png")

        assertEquals(emptyList(), verifyAndroidPreview())
    }

    @Test fun reportsAMissingMatrixReference() {
        writeAndroidPreview(annotations = "@OsIntegrationPreviewMatrix")
        writeReference("S01_Privacy_Phone_abc_0.png")

        val problems = verifyAndroidPreview()

        assertEquals(1, problems.size)
        assertTrue("RTL" in problems.single(), problems.single())
    }

    @Test fun requiresTheBubbleReferenceWhenTheBubblePreviewIsApplied() {
        writeAndroidPreview(annotations = "@OsIntegrationPreviewMatrix\n@BubbleWindowPreview")
        writeReference("S01_Privacy_Phone_abc_0.png")
        writeReference("S01_Privacy_RTL_def_0.png")

        assertTrue(verifyAndroidPreview().single().contains("Bubble"))
    }

    @Test fun rejectsAPreviewThatDoesNotUseTheOsIntegrationMatrix() {
        writeAndroidPreview(annotations = "@ScreenshotPreviewMatrix")

        assertTrue(verifyAndroidPreview().any { "@OsIntegrationPreviewMatrix" in it })
    }

    @Test fun reportsARegisteredFunctionThatNoLongerExists() {
        writeAndroidPreview(annotations = "@OsIntegrationPreviewMatrix", function = "S02_Renamed")

        assertTrue(verifyAndroidPreview().single().contains("S01_Privacy"))
    }

    @Test fun requiresTheAccessibilityViewportsForADesktopScene() {
        listOf("phone", "phone-dark", "compact-phone", "phone-rtl").forEach { suffix ->
            roots.desktopReferences.resolve("privacy-settings--$suffix.png").writePng()
        }

        val problems =
            ScreenshotCoverage.verify(
                listOf(ScreenshotCoverage.Requirement.DesktopScene("privacy", "privacy-settings")),
                roots,
                matrixSource,
            )

        assertEquals(1, problems.size)
        assertTrue("phone-large-text" in problems.single(), problems.single())
    }

    @Test fun requiresSystemCapturesToBeRealPngFiles() {
        root.resolve("docs/shade.png").apply {
            parentFile.mkdirs()
            writeText("not an image")
        }

        val problems =
            ScreenshotCoverage.verify(
                listOf(
                    ScreenshotCoverage.Requirement.SystemCapture("tiles", "docs/shade.png"),
                    ScreenshotCoverage.Requirement.SystemCapture("tiles", "docs/missing.png"),
                ),
                roots,
                matrixSource,
            )

        assertEquals(2, problems.size)
    }

    @Test fun acceptsTextEvidenceForSystemCaptures() {
        root.resolve("docs/app-functions.txt").apply {
            parentFile.mkdirs()
            writeText("createTextEntry -> success")
        }

        val problems =
            ScreenshotCoverage.verify(
                listOf(ScreenshotCoverage.Requirement.SystemCapture("app-functions", "docs/app-functions.txt")),
                roots,
                matrixSource,
            )

        assertEquals(emptyList(), problems)
    }

    @Test fun findsReferencesWhoseFunctionOrFileNoLongerExists() {
        writeAndroidPreview(annotations = "@OsIntegrationPreviewMatrix")
        writeReference("S01_Privacy_Phone_abc_0.png")
        writeReference("S00_Removed_Phone_abc_0.png")
        roots.androidReferences.resolve("app/logdate/screenshots/flows/flow20/DeletedScreenshotsKt/S01_Gone_Phone_abc_0.png").writePng()

        val stale = ScreenshotCoverage.staleAndroidReferences(roots)

        assertEquals(
            listOf(
                "app/logdate/screenshots/flows/flow20/DeletedScreenshotsKt/S01_Gone_Phone_abc_0.png",
                "app/logdate/screenshots/flows/flow20/Flow20_01_PrivacyKt/S00_Removed_Phone_abc_0.png",
            ),
            stale,
        )
    }

    @Test fun findsOsIntegrationPreviewsMissingFromTheRegistry() {
        writeAndroidPreview(annotations = "@PreviewTest\n@OsIntegrationPreviewMatrix")

        val unregistered = ScreenshotCoverage.unregisteredOsIntegrationPreviews(emptyList(), roots, "app/logdate/screenshots/flows/flow20")

        assertEquals(listOf("app/logdate/screenshots/flows/flow20/Flow20_01_Privacy.kt#S01_Privacy"), unregistered)
    }

    private fun verifyAndroidPreview(): List<String> =
        ScreenshotCoverage.verify(
            listOf(
                ScreenshotCoverage.Requirement.AndroidPreview(
                    "privacy",
                    "app/logdate/screenshots/flows/flow20/Flow20_01_Privacy.kt",
                    "S01_Privacy",
                ),
            ),
            roots,
            matrixSource,
        )

    private fun writeAndroidPreview(
        annotations: String,
        function: String = "S01_Privacy",
    ) {
        roots.androidSources.resolve("app/logdate/screenshots/flows/flow20/Flow20_01_Privacy.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package app.logdate.screenshots.flows.flow20

                /** Privacy settings. */
                $annotations
                @Composable
                fun $function() {
                    ScreenshotTheme { }
                }
                """.trimIndent(),
            )
        }
    }

    private fun writeReference(name: String) {
        roots.androidReferences.resolve("app/logdate/screenshots/flows/flow20/Flow20_01_PrivacyKt/$name").writePng()
    }

    private fun File.writePng() {
        parentFile.mkdirs()
        writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0))
    }
}
