@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.logdate.ui.platform

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIImageRenderingMode
import platform.UIKit.UIImageSymbolConfiguration
import platform.posix.memcpy
import org.jetbrains.skia.Image as SkiaImage

/**
 * Resolves the named SF Symbol via `UIImage.systemImageNamed(...)`, renders it as a 96-pt
 * template PNG, and decodes the bytes through Skia into a `BitmapPainter`. Rendering mode is
 * `AlwaysTemplate`, so the resulting bitmap is effectively alpha-only — Compose's `Icon`
 * composable can tint it with `LocalContentColor` like any vector painter.
 *
 * Symbols missing from the running OS (older deployment targets, typos) fall back to the
 * Material vector counterpart so we never render a blank icon.
 */
private const val SF_SYMBOL_RENDER_POINT_SIZE: Double = 96.0

@Composable
private fun rememberSfSymbol(
    name: String,
    fallback: ImageVector,
): Painter {
    val cached: Painter? = remember(name) { loadSfSymbolAsPainter(name) }
    return cached ?: rememberVectorPainter(fallback)
}

private fun loadSfSymbolAsPainter(name: String): Painter? {
    val configuration = UIImageSymbolConfiguration.configurationWithPointSize(SF_SYMBOL_RENDER_POINT_SIZE)
    val baseImage = UIImage.systemImageNamed(name, withConfiguration = configuration) ?: return null
    val template = baseImage.imageWithRenderingMode(UIImageRenderingMode.UIImageRenderingModeAlwaysTemplate)
    val pngData: NSData = UIImagePNGRepresentation(template) ?: return null
    val bytes = pngData.toByteArray()
    val skia = SkiaImage.makeFromEncoded(bytes)
    return BitmapPainter(skia.toComposeImageBitmap())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val buffer = ByteArray(size)
    buffer.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return buffer
}

actual object PlatformIcons {
    @Composable actual fun back(): Painter = rememberSfSymbol("chevron.left", Icons.AutoMirrored.Filled.ArrowBack)

    @Composable actual fun close(): Painter = rememberSfSymbol("xmark", Icons.Filled.Close)

    @Composable actual fun edit(): Painter = rememberSfSymbol("square.and.pencil", Icons.Filled.Edit)

    @Composable actual fun search(): Painter = rememberSfSymbol("magnifyingglass", Icons.Filled.Search)

    @Composable actual fun settings(): Painter = rememberSfSymbol("gearshape", Icons.Filled.Settings)

    @Composable actual fun add(): Painter = rememberSfSymbol("plus", Icons.Filled.Add)

    @Composable actual fun more(): Painter = rememberSfSymbol("ellipsis", Icons.Filled.MoreVert)

    @Composable actual fun history(): Painter = rememberSfSymbol("clock.arrow.circlepath", Icons.Filled.History)

    @Composable actual fun brush(): Painter = rememberSfSymbol("paintbrush", Icons.Filled.Brush)

    @Composable actual fun delete(): Painter = rememberSfSymbol("trash", Icons.Filled.Delete)

    @Composable actual fun share(): Painter = rememberSfSymbol("square.and.arrow.up", Icons.Filled.Share)

    @Composable actual fun check(): Painter = rememberSfSymbol("checkmark", Icons.Filled.Check)

    @Composable actual fun info(): Painter = rememberSfSymbol("info.circle", Icons.Filled.Info)

    @Composable actual fun play(): Painter = rememberSfSymbol("play.fill", Icons.Filled.PlayArrow)

    @Composable actual fun pause(): Painter = rememberSfSymbol("pause.fill", Icons.Filled.Pause)

    @Composable actual fun newEntry(): Painter = rememberSfSymbol("square.and.pencil", Icons.Filled.EditNote)

    @Composable actual fun rewind(): Painter = rememberSfSymbol("memorychip", Icons.Filled.Replay)

    @Composable actual fun journal(): Painter = rememberSfSymbol("book", Icons.AutoMirrored.Filled.MenuBook)

    @Composable actual fun library(): Painter = rememberSfSymbol("photo", Icons.Filled.Image)

    @Composable actual fun timeline(): Painter = rememberSfSymbol("calendar.day.timeline.left", Icons.Filled.Today)

    @Composable actual fun location(): Painter = rememberSfSymbol("location.fill", Icons.Filled.LocationOn)

    @Composable actual fun drafts(): Painter = rememberSfSymbol("doc.text", Icons.Filled.Drafts)

    @Composable actual fun save(): Painter = rememberSfSymbol("checkmark", Icons.Filled.Save)

    @Composable actual fun text(): Painter = rememberSfSymbol("textformat", Icons.Filled.TextFields)

    @Composable actual fun audioFile(): Painter = rememberSfSymbol("waveform", Icons.Filled.AudioFile)

    @Composable actual fun videoFile(): Painter = rememberSfSymbol("film", Icons.Filled.VideoFile)

    @Composable actual fun camera(): Painter = rememberSfSymbol("camera.fill", Icons.Filled.Camera)

    @Composable actual fun mic(): Painter = rememberSfSymbol("mic.fill", Icons.Filled.Mic)

    @Composable actual fun photoLibrary(): Painter = rememberSfSymbol("photo.on.rectangle", Icons.Filled.PhotoLibrary)

    @Composable actual fun addPhoto(): Painter = rememberSfSymbol("photo.badge.plus", Icons.Outlined.AddPhotoAlternate)

    @Composable actual fun expandMore(): Painter = rememberSfSymbol("chevron.down", Icons.Filled.ExpandMore)

    @Composable actual fun stop(): Painter = rememberSfSymbol("stop.fill", Icons.Filled.Stop)

    @Composable actual fun forward10(): Painter = rememberSfSymbol("goforward.10", Icons.Filled.Forward10)

    @Composable actual fun replay10(): Painter = rememberSfSymbol("gobackward.10", Icons.Filled.Replay10)

    @Composable actual fun refresh(): Painter = rememberSfSymbol("arrow.clockwise", Icons.Filled.Refresh)

    @Composable actual fun error(): Painter = rememberSfSymbol("exclamationmark.triangle.fill", Icons.Filled.Error)

    @Composable actual fun forward(): Painter = rememberSfSymbol("chevron.right", Icons.AutoMirrored.Filled.ArrowForward)

    @Composable actual fun playCircle(): Painter = rememberSfSymbol("play.circle.fill", Icons.Filled.PlayCircleFilled)

    @Composable actual fun openInNew(): Painter = rememberSfSymbol("arrow.up.right.square", Icons.AutoMirrored.Filled.OpenInNew)

    @Composable actual fun people(): Painter = rememberSfSymbol("person.2.fill", Icons.Filled.PeopleAlt)

    @Composable actual fun note(): Painter = rememberSfSymbol("note.text", Icons.AutoMirrored.Filled.Note)

    @Composable actual fun video(): Painter = rememberSfSymbol("video.fill", Icons.Filled.Videocam)

    @Composable actual fun calendar(): Painter = rememberSfSymbol("calendar", Icons.Filled.CalendarMonth)

    @Composable actual fun reply(): Painter = rememberSfSymbol("arrowshape.turn.up.left.fill", Icons.AutoMirrored.Filled.Reply)

    @Composable actual fun filterOff(): Painter = rememberSfSymbol("line.3.horizontal.decrease.circle.slash", Icons.Filled.FilterAltOff)

    @Composable actual fun copy(): Painter = rememberSfSymbol("doc.on.doc", Icons.Filled.ContentCopy)

    @Composable actual fun checkCircle(): Painter = rememberSfSymbol("checkmark.circle.fill", Icons.Filled.CheckCircle)

    @Composable actual fun travel(): Painter = rememberSfSymbol("airplane", Icons.Filled.Flight)

    @Composable actual fun focusedWork(): Painter = rememberSfSymbol("briefcase.fill", Icons.Filled.Work)

    @Composable actual fun quiet(): Painter = rememberSfSymbol("leaf.fill", Icons.Filled.SelfImprovement)

    @Composable actual fun milestone(): Painter = rememberSfSymbol("trophy.fill", Icons.Filled.EmojiEvents)

    @Composable actual fun mixed(): Painter = rememberSfSymbol("sparkles", Icons.Filled.AutoAwesome)
}
