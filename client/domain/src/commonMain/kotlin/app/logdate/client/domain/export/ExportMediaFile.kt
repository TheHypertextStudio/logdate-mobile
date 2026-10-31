package app.logdate.client.domain.export

import kotlinx.serialization.Serializable

/**
 * Represents a media file to be included in the export.
 */
@Serializable
data class ExportMediaFile(
    val exportPath: String,
    val sourceUri: String,
)
