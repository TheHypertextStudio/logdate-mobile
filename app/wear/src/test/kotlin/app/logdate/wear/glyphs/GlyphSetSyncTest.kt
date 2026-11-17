package app.logdate.wear.glyphs

import app.logdate.ui.glyphs.LogDateGlyph
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that the white glyph copies the watch draws in notifications and complications match the
 * shapes of the shared glyphs in `client/ui`. The watch copies only the glyphs it draws as Android
 * drawables. Unit tests run with `app/wear` as the working directory.
 */
class GlyphSetSyncTest {
    private val sharedDirectory = File("../../client/ui/src/commonMain/composeResources/drawable")
    private val watchDirectory = File("src/main/res/drawable")

    @Test
    fun `the watch has no glyph copy without a shared original`() {
        val originals = LogDateGlyph.entries.map { glyph -> "ic_${glyph.fileName}.xml" }.toSet()
        val copies =
            watchDirectory
                .listFiles()
                .orEmpty()
                .map(File::getName)
                .filter { name -> name.startsWith("ic_glyph_") }
                .toSet()

        assertTrue(copies.isNotEmpty(), "No glyph copies in ${watchDirectory.path}")
        assertEquals(emptySet(), copies - originals, "Glyph copies without a shared original")
    }

    @Test
    fun `each copy draws the same shapes as its original`() {
        watchCopies().forEach { (glyph, copyFile) ->
            val original = parse(File(sharedDirectory, "${glyph.fileName}.xml"))
            val copy = parse(copyFile)

            assertEquals(original.viewport, copy.viewport, "${glyph.name} viewport")
            assertEquals(original.paths.map(GlyphPath::geometry), copy.paths.map(GlyphPath::geometry), "${glyph.name} paths")
        }
    }

    @Test
    fun `each copy is white so a notification or complication can tint it`() {
        watchCopies().forEach { (glyph, copyFile) ->
            parse(copyFile).paths.forEach { path ->
                assertEquals(MONOCHROME, path.fillColor, "${glyph.name} fill")
            }
        }
    }

    /** Each shared glyph the watch ships a copy of, with that copy. */
    private fun watchCopies(): Map<LogDateGlyph, File> {
        val copies =
            LogDateGlyph.entries
                .associateWith { glyph -> File(watchDirectory, "ic_${glyph.fileName}.xml") }
                .filterValues(File::isFile)
        assertTrue(copies.isNotEmpty(), "No glyph copies in ${watchDirectory.path}")
        return copies
    }

    private data class Glyph(
        val viewport: Pair<String, String>,
        val paths: List<GlyphPath>,
    )

    private data class GlyphPath(
        val geometry: Pair<String, String>,
        val fillColor: String,
    )

    private fun parse(file: File): Glyph {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val root = builder.parse(file).documentElement
        val paths = root.getElementsByTagName("path")
        return Glyph(
            viewport = root.getAttribute("android:viewportWidth") to root.getAttribute("android:viewportHeight"),
            paths =
                (0 until paths.length).map { index ->
                    val path = paths.item(index) as Element
                    GlyphPath(
                        geometry = path.getAttribute("android:pathData") to path.getAttribute("android:fillType"),
                        fillColor = path.getAttribute("android:fillColor"),
                    )
                },
        )
    }

    private companion object {
        const val MONOCHROME = "#FFFFFFFF"
    }
}
