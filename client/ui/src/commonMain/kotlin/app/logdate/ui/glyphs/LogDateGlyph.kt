package app.logdate.ui.glyphs

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.glyph_campfire
import logdate.client.ui.generated.resources.glyph_lock
import logdate.client.ui.generated.resources.glyph_mark
import logdate.client.ui.generated.resources.glyph_record
import logdate.client.ui.generated.resources.glyph_watch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

/**
 * The glyphs the phone and the watch share. Each is a 24 by 24 vector in `composeResources/drawable`
 * named [fileName]. A glyph the watch draws outside Compose, in a notification or complication, also
 * ships there as a white Android drawable, `ic_<fileName>`.
 */
enum class LogDateGlyph(
    val fileName: String,
) {
    /** A moment captured on the watch. */
    WATCH("glyph_watch"),

    /** A marked moment. */
    MARK("glyph_mark"),

    /** Start recording. */
    RECORD("glyph_record"),

    /** A recording locked so it continues without a finger on the button. */
    LOCK("glyph_lock"),

    /** The journaling campfire. Its flame is the only orange in the palette. */
    CAMPFIRE("glyph_campfire"),
}

val LogDateGlyph.resource: DrawableResource
    get() =
        when (this) {
            LogDateGlyph.WATCH -> Res.drawable.glyph_watch
            LogDateGlyph.MARK -> Res.drawable.glyph_mark
            LogDateGlyph.RECORD -> Res.drawable.glyph_record
            LogDateGlyph.LOCK -> Res.drawable.glyph_lock
            LogDateGlyph.CAMPFIRE -> Res.drawable.glyph_campfire
        }

@Composable
fun LogDateGlyph.vector(): ImageVector = vectorResource(resource)
