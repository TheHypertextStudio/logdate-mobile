package app.logdate.client.feature.widgets

import app.logdate.client.domain.recommendation.RecallMode
import app.logdate.client.domain.recommendation.WidgetContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

enum class PhotoWidgetShape { SYSTEM, SOFT, CIRCLE }

@Serializable
internal enum class WidgetSetupTarget { PINNED_MEMORY, MEMORIES, PHOTO_PROMPT }

/** Contains control choices and identifiers only; journal content stays in its repository. */
@Serializable
internal data class WidgetSetupRequest(
    val widgetId: Int,
    val target: WidgetSetupTarget,
    val values: WidgetSettingValues,
    val version: Int = 1,
    val refreshEventId: String = Uuid.random().toString(),
) {
    fun encode(): String = Json.encodeToString(this)

    fun isValid(): Boolean {
        if (widgetId <= 0 || version != 1 || Uuid.parseOrNull(refreshEventId)?.toString() != refreshEventId) return false
        val strings = values.strings
        return when (target) {
            WidgetSetupTarget.PINNED_MEMORY -> {
                values.flags.isEmpty() &&
                    strings.keys == setOf("chosenNoteId") &&
                    Uuid.parseOrNull(strings.getValue("chosenNoteId")) != null
            }

            WidgetSetupTarget.MEMORIES -> {
                val types = strings["contentTypes"]?.split(',').orEmpty()
                values.flags.isEmpty() &&
                    strings.keys == setOf("recallMode", "contentTypes") &&
                    RecallMode.entries.any { it.name == strings["recallMode"] } &&
                    types.isNotEmpty() &&
                    types.distinct() == types &&
                    types.all { type -> WidgetContentType.entries.any { it.name == type } }
            }

            WidgetSetupTarget.PHOTO_PROMPT -> {
                strings.keys == setOf("photoShape") &&
                    values.flags.keys == setOf("usePhotoPrompt") &&
                    PhotoWidgetShape.entries.any { it.name == strings["photoShape"] }
            }
        }
    }

    companion object {
        fun pinned(
            widgetId: Int,
            noteId: Uuid,
        ) = WidgetSetupRequest(
            widgetId,
            WidgetSetupTarget.PINNED_MEMORY,
            WidgetSettingValues(strings = mapOf("chosenNoteId" to noteId.toString())),
        )

        fun memories(
            widgetId: Int,
            mode: RecallMode,
            types: Set<WidgetContentType>,
        ) = WidgetSetupRequest(
            widgetId,
            WidgetSetupTarget.MEMORIES,
            WidgetSettingValues(
                strings =
                    mapOf(
                        "recallMode" to mode.name,
                        "contentTypes" to types.sortedBy { it.ordinal }.joinToString(",") { it.name },
                    ),
            ),
        )

        fun photoPrompt(
            widgetId: Int,
            shape: PhotoWidgetShape,
        ) = WidgetSetupRequest(
            widgetId,
            WidgetSetupTarget.PHOTO_PROMPT,
            WidgetSettingValues(strings = mapOf("photoShape" to shape.name), flags = mapOf("usePhotoPrompt" to true)),
        )

        fun decodeFor(
            encoded: String?,
            widgetId: Int,
            target: WidgetSetupTarget,
        ): WidgetSetupRequest? {
            if (encoded == null || encoded.length > 4_096) return null
            return runCatching { Json.decodeFromString<WidgetSetupRequest>(encoded) }
                .getOrNull()
                ?.takeIf { it.widgetId == widgetId && it.target == target && it.isValid() }
        }
    }
}
