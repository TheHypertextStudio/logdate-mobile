package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.logdate.client.domain.storage.StoredItem
import app.logdate.client.domain.storage.StoredKind
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.skia.Image as SkiaImage

/** A library to clean up: varied sizes, real pictures and real words. */
internal object StorageCleanupFixtures {
    private const val KB = 1024L
    private const val MB = 1024L * KB

    private fun megabytes(amount: Double): Long = (amount * MB).toLong()

    private val days =
        listOf(
            "2026-10-31T19:12:00Z",
            "2026-10-18T08:40:00Z",
            "2026-09-27T16:05:00Z",
            "2026-09-02T21:30:00Z",
            "2026-08-14T11:20:00Z",
            "2026-07-04T18:45:00Z",
            "2026-06-21T07:15:00Z",
            "2026-05-09T13:00:00Z",
        ).map(Instant::parse)

    private fun day(index: Int) = days[index % days.size]

    val photos: List<StoredItem> =
        listOf(14.0, 12.0, 11.0, 9.8, 9.1, 8.4, 7.7, 6.9, 6.2, 5.5, 4.8, 4.1, 3.6, 3.2, 2.9, 2.4)
            .mapIndexed { index, size ->
                StoredItem(Uuid.random(), StoredKind.Photos, megabytes(size), day(index), mediaRef = "picture-$index")
            }

    val videos: List<StoredItem> =
        listOf(412.0 to 204_000L, 268.0 to 131_000L, 190.0 to 95_000L, 122.0 to 61_000L, 77.0 to 38_000L, 41.0 to 20_000L, 18.0 to 9_000L)
            .mapIndexed { index, (size, length) ->
                StoredItem(
                    Uuid.random(),
                    StoredKind.Videos,
                    megabytes(size),
                    day(index),
                    mediaRef = "picture-${index + 5}",
                    durationMs = length,
                )
            } +
            // One whose file is on another device: no picture and no size.
            StoredItem(Uuid.random(), StoredKind.Videos, sizeBytes = null, createdAt = day(7), mediaRef = "elsewhere", durationMs = 12_000L)

    val recordings: List<StoredItem> =
        listOf(
            Triple("Okay so we finally made it up to the lookout and", megabytes(36.0), 2_292_000L),
            Triple("Voice memo for Dad’s birthday, take two", megabytes(23.0), 1_480_000L),
            Triple("I keep thinking about what Maya said at dinner", megabytes(11.0), 725_000L),
            Triple(null, megabytes(8.1), 511_000L),
            Triple("Grocery run, then the long way home along the canal", megabytes(4.8), 302_000L),
            Triple("Idea for the garden: move the tomatoes", megabytes(2.6), 167_000L),
            Triple("Rain on the tent, first night", megabytes(1.9), 118_000L),
            Triple("Checking the mic before the interview", null, 46_000L),
        ).mapIndexed { index, (said, size, length) ->
            StoredItem(Uuid.random(), StoredKind.Recordings, size, day(index), mediaRef = "memo-$index", text = said, durationMs = length)
        }

    val entries: List<StoredItem> =
        listOf(
            "The house was quiet when I got back, and for once I didn’t mind" to 18 * KB,
            "Notes from the long weekend at the lake" to 12 * KB,
            "Things I want to remember about this autumn" to 9_830L,
            "Maya’s speech, written down before I forget it" to 7_372L,
            "First day at the new studio" to 5_222L,
            "A list of small good things" to 3_379L,
            "Tea, then the garden" to 1_843L,
            "Call Dad back" to 640L,
        ).mapIndexed { index, (words, size) ->
            StoredItem(Uuid.random(), StoredKind.WrittenEntries, size, day(index), text = words)
        }

    /** Square crops of the photographs the repository already has, so the grid shows real pictures. */
    private val pictures: List<BitmapPainter> by lazy {
        val pug = load(checkNotNull(javaClass.getResourceAsStream("/recovery/photo.jpg")).readBytes())
        // The onboarding sheet holds four photographs; it is read from the module beside this one.
        val sheet =
            File("../onboarding/src/commonMain/composeResources/drawable/onboarding_scene_photo.jpg").takeIf(File::exists)?.let {
                load(it.readBytes())
            }
        val fromSheet =
            sheet
                ?.let {
                    listOf(
                        crop(it, 0.50, 0.50, 0.50),
                        crop(it, 0.00, 0.50, 0.50),
                        crop(it, 0.50, 0.00, 0.50),
                        crop(it, 0.00, 0.00, 0.50),
                        crop(it, 0.60, 0.70, 0.36),
                        crop(it, 0.08, 0.70, 0.30),
                        crop(it, 0.10, 0.24, 0.30),
                        crop(it, 0.74, 0.02, 0.24),
                        crop(it, 0.55, 0.52, 0.26),
                        crop(it, 0.36, 0.52, 0.14),
                    )
                }.orEmpty()
        val fromPug =
            listOf(
                crop(pug, 0.125, 0.0, 0.75),
                crop(pug, 0.36, 0.06, 0.26),
                crop(pug, 0.0, 0.34, 0.30),
                crop(pug, 0.22, 0.44, 0.40),
                crop(pug, 0.72, 0.30, 0.28),
                crop(pug, 0.48, 0.62, 0.30),
            )
        // Alternated so neighbours in the grid come from different photographs.
        (0 until maxOf(fromSheet.size, fromPug.size)).flatMap { listOfNotNull(fromSheet.getOrNull(it), fromPug.getOrNull(it)) }
    }

    fun picture(item: StoredItem): BitmapPainter? {
        val index = item.mediaRef?.substringAfter("picture-", missingDelimiterValue = "")?.toIntOrNull() ?: return null
        return pictures[index % pictures.size]
    }

    private fun load(bytes: ByteArray): ImageBitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()

    /** The square whose left and top edges and side are these shares of the image's width. */
    private fun crop(
        image: ImageBitmap,
        left: Double,
        top: Double,
        side: Double,
    ): BitmapPainter {
        val size = (image.width * side).toInt().coerceAtMost(image.height)
        val x = (image.width * left).toInt().coerceAtMost(image.width - size)
        val y = (image.width * top).toInt().coerceAtMost(image.height - size)
        return BitmapPainter(image, IntOffset(x, y), IntSize(size, size))
    }
}
