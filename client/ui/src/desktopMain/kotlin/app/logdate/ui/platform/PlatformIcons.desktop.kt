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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter

actual object PlatformIcons {
    @Composable actual fun back(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack)

    @Composable actual fun close(): Painter = rememberVectorPainter(Icons.Filled.Close)

    @Composable actual fun edit(): Painter = rememberVectorPainter(Icons.Filled.Edit)

    @Composable actual fun search(): Painter = rememberVectorPainter(Icons.Filled.Search)

    @Composable actual fun settings(): Painter = rememberVectorPainter(Icons.Filled.Settings)

    @Composable actual fun add(): Painter = rememberVectorPainter(Icons.Filled.Add)

    @Composable actual fun more(): Painter = rememberVectorPainter(Icons.Filled.MoreVert)

    @Composable actual fun history(): Painter = rememberVectorPainter(Icons.Filled.History)

    @Composable actual fun brush(): Painter = rememberVectorPainter(Icons.Filled.Brush)

    @Composable actual fun delete(): Painter = rememberVectorPainter(Icons.Filled.Delete)

    @Composable actual fun share(): Painter = rememberVectorPainter(Icons.Filled.Share)

    @Composable actual fun check(): Painter = rememberVectorPainter(Icons.Filled.Check)

    @Composable actual fun info(): Painter = rememberVectorPainter(Icons.Filled.Info)

    @Composable actual fun play(): Painter = rememberVectorPainter(Icons.Filled.PlayArrow)

    @Composable actual fun pause(): Painter = rememberVectorPainter(Icons.Filled.Pause)

    @Composable actual fun newEntry(): Painter = rememberVectorPainter(Icons.Filled.EditNote)

    @Composable actual fun rewind(): Painter = rememberVectorPainter(Icons.Filled.Replay)

    @Composable actual fun journal(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook)

    @Composable actual fun library(): Painter = rememberVectorPainter(Icons.Filled.Image)

    @Composable actual fun timeline(): Painter = rememberVectorPainter(Icons.Filled.Today)

    @Composable actual fun location(): Painter = rememberVectorPainter(Icons.Filled.LocationOn)

    @Composable actual fun drafts(): Painter = rememberVectorPainter(Icons.Filled.Drafts)

    @Composable actual fun save(): Painter = rememberVectorPainter(Icons.Filled.Save)

    @Composable actual fun text(): Painter = rememberVectorPainter(Icons.Filled.TextFields)

    @Composable actual fun audioFile(): Painter = rememberVectorPainter(Icons.Filled.AudioFile)

    @Composable actual fun videoFile(): Painter = rememberVectorPainter(Icons.Filled.VideoFile)

    @Composable actual fun camera(): Painter = rememberVectorPainter(Icons.Filled.Camera)

    @Composable actual fun mic(): Painter = rememberVectorPainter(Icons.Filled.Mic)

    @Composable actual fun photoLibrary(): Painter = rememberVectorPainter(Icons.Filled.PhotoLibrary)

    @Composable actual fun addPhoto(): Painter = rememberVectorPainter(Icons.Outlined.AddPhotoAlternate)

    @Composable actual fun expandMore(): Painter = rememberVectorPainter(Icons.Filled.ExpandMore)

    @Composable actual fun stop(): Painter = rememberVectorPainter(Icons.Filled.Stop)

    @Composable actual fun forward10(): Painter = rememberVectorPainter(Icons.Filled.Forward10)

    @Composable actual fun replay10(): Painter = rememberVectorPainter(Icons.Filled.Replay10)

    @Composable actual fun refresh(): Painter = rememberVectorPainter(Icons.Filled.Refresh)

    @Composable actual fun error(): Painter = rememberVectorPainter(Icons.Filled.Error)

    @Composable actual fun forward(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward)

    @Composable actual fun playCircle(): Painter = rememberVectorPainter(Icons.Filled.PlayCircleFilled)

    @Composable actual fun openInNew(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.OpenInNew)

    @Composable actual fun people(): Painter = rememberVectorPainter(Icons.Filled.PeopleAlt)

    @Composable actual fun note(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.Note)

    @Composable actual fun video(): Painter = rememberVectorPainter(Icons.Filled.Videocam)

    @Composable actual fun calendar(): Painter = rememberVectorPainter(Icons.Filled.CalendarMonth)

    @Composable actual fun reply(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.Reply)

    @Composable actual fun filterOff(): Painter = rememberVectorPainter(Icons.Filled.FilterAltOff)

    @Composable actual fun copy(): Painter = rememberVectorPainter(Icons.Filled.ContentCopy)

    @Composable actual fun checkCircle(): Painter = rememberVectorPainter(Icons.Filled.CheckCircle)

    @Composable actual fun travel(): Painter = rememberVectorPainter(Icons.Filled.Flight)

    @Composable actual fun focusedWork(): Painter = rememberVectorPainter(Icons.Filled.Work)

    @Composable actual fun quiet(): Painter = rememberVectorPainter(Icons.Filled.SelfImprovement)

    @Composable actual fun milestone(): Painter = rememberVectorPainter(Icons.Filled.EmojiEvents)

    @Composable actual fun mixed(): Painter = rememberVectorPainter(Icons.Filled.AutoAwesome)
}
