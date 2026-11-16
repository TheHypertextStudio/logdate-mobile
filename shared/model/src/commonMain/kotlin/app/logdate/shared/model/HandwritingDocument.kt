package app.logdate.shared.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val HANDWRITING_COLOR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

/** Original ink in page coordinates, independent of the editor and its current viewport. */
@Serializable
data class HandwritingDocument(
    val version: Int = 1,
    val width: Float = 720f,
    val height: Float = 960f,
    val strokes: List<HandwritingStroke> = emptyList(),
) {
    init {
        if (version != 1) throw UnsupportedHandwritingVersionException(version)
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
    }

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(content: String): HandwritingDocument = Json.decodeFromString(serializer(), content)
    }
}

@Serializable
data class HandwritingStroke(
    val id: Uuid,
    val tool: String,
    val color: String,
    val width: Float,
    val points: List<HandwritingPoint>,
    val x: Float = 0f,
    val y: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val zIndex: Int = 0,
) {
    init {
        require(tool in setOf("pen", "highlighter", "eraser")) { "Unsupported handwriting tool" }
        require(HANDWRITING_COLOR.matches(color)) { "Invalid handwriting color" }
        require(width.isFinite() && width > 0f)
        require(listOf(x, y, rotation, scaleX, scaleY).all { it.isFinite() })
        require(scaleX > 0f && scaleY > 0f)
    }
}

@Serializable
data class HandwritingPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
) {
    init {
        require(x.isFinite() && y.isFinite() && pressure.isFinite() && pressure in 0f..1f)
    }
}

class UnsupportedHandwritingVersionException(
    version: Int,
) : IllegalArgumentException("Unsupported handwriting document version: $version")
