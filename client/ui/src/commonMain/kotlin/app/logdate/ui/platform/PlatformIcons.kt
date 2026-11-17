package app.logdate.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/**
 * Cross-platform icon set. Each accessor returns a [Painter] so the iOS actual can hand back an
 * SF Symbol rasterized from `UIImage.systemImageNamed`, while Android and desktop continue to
 * render the Material vector counterpart.
 *
 * Callers prefer this object over `Icons.Default.*` for any icon that appears on iOS — it keeps
 * the visual language consistent with the host platform without forcing every site to branch
 * on [currentPlatform].
 */
expect object PlatformIcons {
    @Composable fun back(): Painter

    @Composable fun close(): Painter

    @Composable fun edit(): Painter

    @Composable fun search(): Painter

    @Composable fun settings(): Painter

    @Composable fun add(): Painter

    @Composable fun more(): Painter

    @Composable fun history(): Painter

    @Composable fun brush(): Painter

    @Composable fun delete(): Painter

    @Composable fun share(): Painter

    @Composable fun check(): Painter

    @Composable fun info(): Painter

    @Composable fun play(): Painter

    @Composable fun pause(): Painter

    @Composable fun newEntry(): Painter

    @Composable fun rewind(): Painter

    @Composable fun journal(): Painter

    @Composable fun library(): Painter

    @Composable fun timeline(): Painter

    @Composable fun location(): Painter

    @Composable fun drafts(): Painter

    @Composable fun save(): Painter

    @Composable fun text(): Painter

    @Composable fun audioFile(): Painter

    @Composable fun videoFile(): Painter

    @Composable fun camera(): Painter

    @Composable fun mic(): Painter

    @Composable fun photoLibrary(): Painter

    @Composable fun addPhoto(): Painter

    @Composable fun expandMore(): Painter

    @Composable fun stop(): Painter

    @Composable fun forward10(): Painter

    @Composable fun replay10(): Painter

    @Composable fun refresh(): Painter

    @Composable fun error(): Painter

    @Composable fun forward(): Painter

    @Composable fun playCircle(): Painter

    @Composable fun openInNew(): Painter

    @Composable fun people(): Painter

    @Composable fun note(): Painter

    @Composable fun video(): Painter

    @Composable fun calendar(): Painter

    @Composable fun reply(): Painter

    @Composable fun filterOff(): Painter

    @Composable fun copy(): Painter

    @Composable fun checkCircle(): Painter

    @Composable fun travel(): Painter

    @Composable fun focusedWork(): Painter

    @Composable fun quiet(): Painter

    @Composable fun milestone(): Painter

    @Composable fun mixed(): Painter
}
