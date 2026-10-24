package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.logdate.client.device.restore.NativeBackupQuotaFailure
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.device.restore.NativeRestoreCustodyStatus
import app.logdate.client.domain.export.ExportCounts
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.domain.restore.ArchivePreview
import app.logdate.client.sync.SyncStatus
import app.logdate.feature.core.export.ExportBottomSheet
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.ImportOptions
import app.logdate.feature.core.restore.RestoreBottomSheet
import app.logdate.feature.core.restore.RestoreState
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ArchiveSettingsVisualAcceptanceTest {
    @Test
    fun unreadableCloudEntriesKeepRecoveryActionVisibleOnTabletAndLargeTextPhone() {
        listOf(Variant("tablet", 1280, 800), Variant("phone-200", 411, 891, 2f)).forEach { variant ->
            runDesktopComposeUiTest(width = variant.width, height = variant.height) {
                var phraseOpens = 0
                setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
                        LogDateTheme(dynamicColor = false) {
                            SyncSettingsContent(
                                onBack = {},
                                syncStatus =
                                    SyncStatus(
                                        isEnabled = true,
                                        lastSyncTime = null,
                                        pendingUploads = 0,
                                        isSyncing = false,
                                        hasErrors = false,
                                        unreadableCloudCount = 1,
                                    ),
                                isAuthenticated = true,
                                onSyncNow = {},
                                onNavigateToSignIn = {},
                                onNavigateToRecoveryPhrase = { phraseOpens++ },
                                quotaUsage = StorageQuotaUi(0, 0, 0f, formattedTotal = "0 GB", formattedUsed = "0 GB"),
                                isQuotaAvailable = false,
                                snackbarHostState = SnackbarHostState(),
                            )
                        }
                    }
                }
                onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Enter recovery phrase"))
                onNodeWithText("Enter recovery phrase").assertIsDisplayed()
                save("backup-unreadable-${variant.name}", onRoot().captureToImage())
                onNodeWithText("Enter recovery phrase").performClick()
                runOnIdle { assertEquals(1, phraseOpens) }
            }
        }
    }

    @Test
    fun dataSettingsDialogsPaintAboveTheBookPostureBackground() {
        listOf(false, true).forEach { importing ->
            val introductions = mutableListOf<ImageBitmap>()
            listOf(true, false).forEach { standalone ->
                runDesktopComposeUiTest(width = 1440, height = 900) {
                    setContent {
                        CompositionLocalProvider(LocalInspectionMode provides true) {
                            LogDateTheme(dynamicColor = false, darkTheme = false) {
                                provideFoldableLayoutInfo(archiveBookPosture()) {
                                    dataSettingsOverlayFixture(importing, standalone)
                                }
                            }
                        }
                    }
                    val introduction = if (importing) "Review what's in this archive." else "Export your data"
                    introductions += onNodeWithText(introduction).captureToImage()
                    if (!standalone) {
                        save("data-settings-${if (importing) "import" else "export"}-book", onRoot().captureToImage())
                    }
                }
            }
            val expected = introductions[0]
            val actual = introductions[1]
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            assertContentEquals(
                renderedPixels(expected),
                renderedPixels(actual),
                "Settings rows must not paint over the dialog introduction",
            )
        }
    }

    private fun renderedPixels(image: ImageBitmap): IntArray {
        val pixels = image.toPixelMap()
        return IntArray(image.width * image.height) { index -> pixels[index % image.width, index / image.width].toArgb() }
    }

    @Test
    fun exportAndImportAdaptAcrossPhoneTabletShortLandscapeAndLargeText() {
        listOf(
            Variant("phone", 411, 891),
            Variant("tablet", 1280, 800),
            Variant("short-landscape", 1280, 500),
            Variant("tablet-200", 1280, 800, 2f),
        ).forEach { variant ->
            listOf(false, true).forEach { importing ->
                runDesktopComposeUiTest(width = variant.width, height = variant.height) {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, variant.fontScale),
                            LocalInspectionMode provides true,
                        ) {
                            LogDateTheme(dynamicColor = false) {
                                ExportSettingsContent(
                                    onBack = {},
                                    exportState =
                                        if (importing) {
                                            ExportState.Idle
                                        } else {
                                            ExportState.Configuring(
                                                counts = ExportCounts(5, 42, 3, 18),
                                            )
                                        },
                                    onShowExportOptions = {},
                                    onUpdateExportOptions = {},
                                    onConfirmExport = {},
                                    onCancelExport = {},
                                    onRetryExport = {},
                                    onDismissExport = {},
                                    restoreState =
                                        if (importing) {
                                            RestoreState.Previewing(
                                                preview =
                                                    ArchivePreview(
                                                        ExportSchemaVersion.CURRENT,
                                                        Instant.parse("2026-10-15T12:00:00Z"),
                                                        "1.0",
                                                        ExportStats(5, 42, 3, 18),
                                                    ),
                                                fileName = "logdate-journal-2026.zip",
                                                options = ImportOptions(),
                                            )
                                        } else {
                                            RestoreState.Idle
                                        },
                                    onShowRestoreSheet = {},
                                    onSelectRestoreFile = {},
                                    onUpdateImportOptions = {},
                                    onConfirmImport = {},
                                    onCancelRestore = {},
                                    onRetryRestore = {},
                                    onDismissRestore = {},
                                    snackbarHostState = SnackbarHostState(),
                                )
                            }
                        }
                    }
                    onNodeWithText(if (importing) "Review what's in this archive." else "Export your data").assertIsDisplayed()
                    save("${if (importing) "import" else "export"}-${variant.name}", onRoot().captureToImage())
                }
            }
        }
    }

    @Test
    fun backupRecoveryStatesUseTheSameSettingsCompositionAtPhoneTabletAndLargeText() {
        listOf(
            Variant("phone", 411, 891),
            Variant("tablet", 1280, 800),
            Variant("phone-200", 411, 891, 2f),
            Variant("phone-dark", 411, 891),
        ).forEach { variant ->
            listOf(
                NativeRestoreCustodyStatus.RECOVERING to "Finishing device transfer",
                NativeRestoreCustodyStatus.NEEDS_TRANSFER to "Recover missing files",
            ).forEach { (custody, label) ->
                runDesktopComposeUiTest(width = variant.width, height = variant.height) {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
                            LogDateTheme(dynamicColor = false, darkTheme = variant.name.endsWith("dark")) {
                                SyncSettingsContent(
                                    onBack = {},
                                    syncStatus = null,
                                    isAuthenticated = false,
                                    onSyncNow = {},
                                    onNavigateToSignIn = {},
                                    quotaUsage = StorageQuotaUi(5_000_000_000, 0, 0f, formattedTotal = "5 GB", formattedUsed = "0 GB"),
                                    isQuotaAvailable = false,
                                    snackbarHostState = SnackbarHostState(),
                                    nativeBackupStatus = NativeBackupStatus.Available(custodyStatus = custody),
                                )
                            }
                        }
                    }
                    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
                    onNodeWithText(label).assertIsDisplayed()
                    save("backup-${custody.name.lowercase()}-${variant.name}", onRoot().captureToImage())
                }
            }
        }
    }

    @Test
    fun archiveActionsRemainReachableInPopulatedDataAndBackupPages() {
        listOf(Variant("phone", 411, 891), Variant("tablet", 1280, 800), Variant("phone-200", 411, 891, 2f)).forEach { variant ->
            listOf("data", "backup").forEach { page ->
                runDesktopComposeUiTest(width = variant.width, height = variant.height) {
                    var exports = 0
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
                            LogDateTheme(dynamicColor = false) {
                                if (page == "data") {
                                    ArchiveSettingsDataFixture { exports++ }
                                } else {
                                    SyncSettingsContent(
                                        onBack = {},
                                        syncStatus = null,
                                        isAuthenticated = false,
                                        onSyncNow = {},
                                        onNavigateToSignIn = {},
                                        quotaUsage = StorageQuotaUi(5_000_000_000, 0, 0f, formattedTotal = "5 GB", formattedUsed = "0 GB"),
                                        isQuotaAvailable = false,
                                        snackbarHostState = SnackbarHostState(),
                                        nativeBackupStatus = NativeBackupStatus.Available(NativeBackupQuotaFailure(30_000_000, 25_000_000)),
                                        onExportArchive = { exports++ },
                                    )
                                }
                            }
                        }
                    }
                    save("$page-${variant.name}-initial", onRoot().captureToImage())
                    val label = if (page == "data") "Export data" else "Export & Import"
                    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
                    onNodeWithText(label).assertIsDisplayed().performClick()
                    runOnIdle { assertEquals(1, exports) }
                    save("$page-${variant.name}-export", onRoot().captureToImage())
                    onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Cloud backup"))
                    save("$page-${variant.name}-coverage", onRoot().captureToImage())
                }
            }
        }
    }

    private data class Variant(
        val name: String,
        val width: Int,
        val height: Int,
        val fontScale: Float = 1f,
    )

    private fun save(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = File(System.getenv("LOGDATE_SETTINGS_FOLLOWUP_RENDER_DIR") ?: "/tmp/logdate-settings-followup-renders")
        directory.mkdirs()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        ImageIO.write(output, "png", File(directory, "$name.png"))
    }
}

private fun archiveBookPosture() =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(708.dp, 0.dp, 732.dp, 900.dp, 24.dp, 900.dp),
                isSeparating = true,
            ),
    )

@Composable
private fun dataSettingsOverlayFixture(
    importing: Boolean,
    standalone: Boolean,
) {
    val exportState = if (importing) ExportState.Idle else ExportState.Configuring(counts = ExportCounts(5, 42, 3, 18))
    val restoreState =
        if (importing) {
            RestoreState.Previewing(
                ArchivePreview(ExportSchemaVersion.CURRENT, Instant.parse("2026-10-15T12:00:00Z"), "1.0", ExportStats(5, 42, 3, 18)),
                "journal.zip",
                ImportOptions(),
            )
        } else {
            RestoreState.Idle
        }
    if (standalone) {
        if (importing) {
            RestoreBottomSheet(restoreState, {}, {}, {}, {}, {}, {})
        } else {
            ExportBottomSheet(exportState, {}, {}, {}, {}, {})
        }
        return
    }
    DataSettingsContent(
        onBack = {},
        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 B", formattedUsed = "0 B"),
        isQuotaAvailable = false,
        exportState = exportState,
        onShowExportOptions = {},
        onUpdateExportOptions = {},
        onConfirmExport = {},
        onCancelExport = {},
        onRetryExport = {},
        onDismissExport = {},
        restoreState = restoreState,
        onShowRestoreSheet = {},
        onSelectRestoreFile = {},
        onUpdateImportOptions = {},
        onConfirmImport = {},
        onCancelRestore = {},
        onRetryRestore = {},
        onDismissRestore = {},
        integrityState = IntegrityState(),
        onRunIntegrityCheck = {},
        onRepairIntegrity = {},
        snackbarHostState = SnackbarHostState(),
        isAuthenticated = false,
    )
}
