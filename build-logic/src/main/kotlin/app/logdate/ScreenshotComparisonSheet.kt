package app.logdate

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** A source image's size in pixels. */
data class Size(
    val width: Int,
    val height: Int,
)

/** Where a scaled tile sits on the sheet, in pixels. */
data class Placement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** One image on a comparison sheet, with the label printed under it. */
data class SheetTile(
    val label: String,
    val image: File,
)

/**
 * Builds the consolidated comparison sheet that UI review needs: every screenshot of one feature,
 * scaled to the same height, labeled, and laid out on one image. A reviewer compares phone with
 * tablet, light with dark, and left-to-right with right-to-left on one page instead of opening
 * dozens of files.
 */
object ScreenshotComparisonSheet {
    private const val TITLE_HEIGHT = 64
    private const val LABEL_HEIGHT = 28
    private const val MARGIN = 24

    /** Lays [sizes] out left to right, wrapping to a new row when the next tile would pass [maxRowWidth]. */
    fun layout(
        sizes: List<Size>,
        tileHeight: Int,
        maxRowWidth: Int,
        gap: Int,
        labelHeight: Int = LABEL_HEIGHT,
    ): List<Placement> {
        var x = 0
        var y = 0
        return sizes.map { size ->
            val width = (size.width.toLong() * tileHeight / size.height).toInt()
            if (x > 0 && x + width > maxRowWidth) {
                x = 0
                y += tileHeight + labelHeight + gap
            }
            Placement(x, y, width, tileHeight).also { x += width + gap }
        }
    }

    /** Renders [tiles] under [title] into a PNG at [output]. */
    fun render(
        title: String,
        tiles: List<SheetTile>,
        output: File,
        tileHeight: Int = 640,
        maxRowWidth: Int = 4096,
    ) {
        System.setProperty("java.awt.headless", "true")
        val images =
            tiles.map { tile ->
                require(tile.image.isFile) { "Comparison sheet image does not exist: ${tile.image}" }
                ImageIO.read(tile.image) ?: throw IllegalArgumentException("Not a readable image: ${tile.image}")
            }
        val placements = layout(images.map { Size(it.width, it.height) }, tileHeight, maxRowWidth, MARGIN)
        val width = (placements.maxOfOrNull { it.x + it.width } ?: 0) + MARGIN * 2
        val height = (placements.maxOfOrNull { it.y + it.height } ?: 0) + LABEL_HEIGHT + TITLE_HEIGHT + MARGIN * 2

        val sheet = BufferedImage(maxOf(width, 640), height, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.color = Color(0xF4, 0xF4, 0xF6)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.color = Color(0x1C, 0x1B, 0x1F)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        graphics.drawString(title, MARGIN, MARGIN + 32)

        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
        val top = MARGIN + TITLE_HEIGHT
        images.zip(placements).zip(tiles).forEach { (pair, tile) ->
            val (image, placement) = pair
            val x = MARGIN + placement.x
            val y = top + placement.y
            graphics.drawImage(image, x, y, placement.width, placement.height, null)
            graphics.color = Color(0xC4, 0xC4, 0xCC)
            graphics.drawRect(x, y, placement.width, placement.height)
            graphics.color = Color(0x1C, 0x1B, 0x1F)
            graphics.drawString(tile.label.take(placement.width / 8 + 8), x, y + placement.height + 20)
        }
        graphics.dispose()

        output.parentFile.mkdirs()
        ImageIO.write(sheet, "png", output)
    }

    /** Every image a feature's registry entries name, labeled by lane and configuration. */
    fun tilesFor(
        requirements: List<ScreenshotCoverage.Requirement>,
        roots: ScreenshotCoverage.Roots,
    ): List<SheetTile> =
        requirements.flatMap { requirement ->
            when (requirement) {
                is ScreenshotCoverage.Requirement.AndroidPreview -> {
                    val fileClass = "${File(requirement.sourcePath).nameWithoutExtension}Kt"
                    val directory =
                        roots.androidReferences
                            .resolve(requirement.sourcePath.substringBeforeLast('/', missingDelimiterValue = ""))
                            .resolve(fileClass)
                    directory
                        .listFiles { file -> file.extension == "png" && file.name.startsWith("${requirement.function}_") }
                        .orEmpty()
                        .sortedBy { it.name }
                        .map { file ->
                            val configuration =
                                file.name
                                    .removePrefix(
                                        "${requirement.function}_",
                                    ).substringBeforeLast('_')
                                    .substringBeforeLast('_')
                            SheetTile("Android ${requirement.function}: $configuration", file)
                        }
                }

                is ScreenshotCoverage.Requirement.DesktopScene -> {
                    roots.desktopReferences
                        .listFiles { file -> file.extension == "png" && file.name.startsWith("${requirement.sceneId}--") }
                        .orEmpty()
                        .sortedBy { it.name }
                        .map { file ->
                            SheetTile("Desktop ${requirement.sceneId}: ${file.nameWithoutExtension.substringAfter("--")}", file)
                        }
                }

                is ScreenshotCoverage.Requirement.SystemCapture -> {
                    if (requirement.path.endsWith(".png")) {
                        listOf(SheetTile("System ${requirement.path}", roots.repoRoot.resolve(requirement.path)))
                    } else {
                        emptyList()
                    }
                }

                is ScreenshotCoverage.Requirement.GlanceReference -> {
                    listOf(SheetTile("Glance ${requirement.path}", roots.repoRoot.resolve(requirement.path)))
                }
            }
        }
}
