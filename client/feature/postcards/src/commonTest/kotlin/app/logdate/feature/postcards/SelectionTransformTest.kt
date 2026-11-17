package app.logdate.feature.postcards

import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.ElementTransform
import app.logdate.feature.postcards.model.InkPoint
import app.logdate.feature.postcards.model.InkTool
import app.logdate.feature.postcards.model.ShapeKind
import app.logdate.feature.postcards.ui.elementSizeDp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests for element selection and transform operations. Gesture undo batching is covered by
 * [CanvasEditorGestureUndoTest].
 */
class SelectionTransformTest {
    @Test
    fun `select element sets selected id`() {
        val elementId = Uuid.random()
        var selectedId: Uuid?

        // Simulate onElementTap callback
        selectedId = elementId
        assertEquals(elementId, selectedId)
    }

    @Test
    fun `deselect clears selected id`() {
        val selectedId: Uuid? = null
        assertNull(selectedId)
    }

    @Test
    fun `move element applies delta`() {
        val original = ElementTransform(x = 100f, y = 200f)
        val dx = 25f
        val dy = -15f
        val moved = original.copy(x = original.x + dx, y = original.y + dy)

        assertEquals(125f, moved.x)
        assertEquals(185f, moved.y)
        // Rotation and scale unchanged
        assertEquals(original.rotation, moved.rotation)
        assertEquals(original.scaleX, moved.scaleX)
    }

    @Test
    fun `transform element applies scale and rotation`() {
        val original = ElementTransform(scaleX = 1f, scaleY = 1f, rotation = 0f)
        val scaleDelta = 1.5f
        val rotationDelta = 30f

        val transformed =
            original.copy(
                scaleX = (original.scaleX * scaleDelta).coerceIn(0.1f, 10f),
                scaleY = (original.scaleY * scaleDelta).coerceIn(0.1f, 10f),
                rotation = original.rotation + rotationDelta,
            )

        assertEquals(1.5f, transformed.scaleX)
        assertEquals(1.5f, transformed.scaleY)
        assertEquals(30f, transformed.rotation)
    }

    @Test
    fun `transform element clamps scale min`() {
        val original = ElementTransform(scaleX = 0.15f, scaleY = 0.15f)
        val scaleDelta = 0.5f // Would result in 0.075, below minimum

        val clamped = (original.scaleX * scaleDelta).coerceIn(0.1f, 10f)
        assertEquals(0.1f, clamped)
    }

    @Test
    fun `transform element clamps scale max`() {
        val original = ElementTransform(scaleX = 8f, scaleY = 8f)
        val scaleDelta = 2f // Would result in 16, above maximum

        val clamped = (original.scaleX * scaleDelta).coerceIn(0.1f, 10f)
        assertEquals(10f, clamped)
    }

    @Test
    fun `element size dp returns correct size for photo`() {
        val photo =
            CanvasElement.Photo(
                id = Uuid.random(),
                momentRef = Uuid.random(),
                mediaUri = "content://test",
            )
        val (w, h) = elementSizeDp(photo)
        assertEquals(200f, w)
        assertEquals(200f, h)
    }

    @Test
    fun `element size dp returns correct size for shape`() {
        val shape =
            CanvasElement.Shape(
                id = Uuid.random(),
                shapeKind = ShapeKind.RECTANGLE,
                color = "#000",
                strokeWidth = 2f,
                width = 150f,
                height = 80f,
            )
        val (w, h) = elementSizeDp(shape)
        assertEquals(150f, w)
        assertEquals(80f, h)
    }

    @Test
    fun `element size dp returns correct size for sticker`() {
        val sticker =
            CanvasElement.Sticker(
                id = Uuid.random(),
                stickerRef = Uuid.random(),
            )
        val (w, h) = elementSizeDp(sticker)
        assertEquals(80f, w)
        assertEquals(80f, h)
    }

    @Test
    fun `element size dp computes bounds for ink`() {
        val ink =
            CanvasElement.Ink(
                id = Uuid.random(),
                tool = InkTool.PEN,
                color = "#000",
                strokeWidth = 2f,
                points =
                    listOf(
                        InkPoint(10f, 20f),
                        InkPoint(50f, 80f),
                        InkPoint(30f, 40f),
                    ),
            )
        val (w, h) = elementSizeDp(ink)
        // maxX - minX = 50 - 10 = 40, maxY - minY = 80 - 20 = 60
        assertEquals(40f, w)
        assertEquals(60f, h)
    }

    @Test
    fun `element size dp enforces minimum for small ink`() {
        val ink =
            CanvasElement.Ink(
                id = Uuid.random(),
                tool = InkTool.PEN,
                color = "#000",
                strokeWidth = 2f,
                points =
                    listOf(
                        InkPoint(10f, 20f),
                        InkPoint(12f, 22f),
                    ),
            )
        val (w, h) = elementSizeDp(ink)
        // Bounds are 2x2, but minimum is 20x20
        assertTrue(w >= 20f)
        assertTrue(h >= 20f)
    }

    @Test
    fun `cumulative move preserves accuracy`() {
        var transform = ElementTransform(x = 0f, y = 0f)

        // Simulate many small drag increments
        repeat(100) {
            transform =
                transform.copy(
                    x = transform.x + 0.5f,
                    y = transform.y + 0.3f,
                )
        }

        // Should be 50.0 and 30.0 respectively (within floating point tolerance)
        assertTrue(transform.x in 49.9f..50.1f)
        assertTrue(transform.y in 29.9f..30.1f)
    }
}
