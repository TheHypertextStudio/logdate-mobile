package app.logdate.feature.core.settings.ui

import androidx.compose.ui.graphics.Color
import app.logdate.shared.model.CloudStorageQuota
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.data_settings_quota_category_attachments
import logdate.client.feature.core.generated.resources.data_settings_quota_category_journals
import logdate.client.feature.core.generated.resources.data_settings_quota_category_other
import logdate.client.feature.core.generated.resources.data_settings_quota_category_photos
import logdate.client.feature.core.generated.resources.data_settings_quota_category_profile
import logdate.client.feature.core.generated.resources.data_settings_quota_category_recordings
import logdate.client.feature.core.generated.resources.data_settings_quota_category_videos
import logdate.client.feature.core.generated.resources.data_settings_quota_category_written_entries
import org.jetbrains.compose.resources.StringResource

/**
 * UI state representation of quota usage for display in settings.
 */
data class StorageQuotaUi(
    val totalBytes: Long,
    val usedBytes: Long,
    val usagePercentage: Float,
    val categories: List<StorageCategory> = emptyList(),
    val formattedTotal: String,
    val formattedUsed: String,
    val isOverQuota: Boolean = false,
    // Derived properties for easier use in UI
    val usedGB: Float = usedBytes / (1024f * 1024f * 1024f),
    val totalGB: Float = totalBytes / (1024f * 1024f * 1024f),
)

/**
 * UI state representation of a storage category (e.g., images, text, etc.)
 */
data class StorageCategory(
    val name: StringResource,
    val usedBytes: Long,
    val usagePercentage: Float,
    val color: Color,
    val formattedUsed: String,
    // Derived properties for easier use in UI
    val sizeInMB: Float = usedBytes / (1024f * 1024f),
)

/**
 * Converts domain [CloudStorageQuota] data to UI state for display.
 */
fun CloudStorageQuota.toStorageQuotaUi(): StorageQuotaUi {
    val usagePercentage =
        if (totalBytes > 0) {
            (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    return StorageQuotaUi(
        totalBytes = totalBytes,
        usedBytes = usedBytes,
        usagePercentage = usagePercentage,
        categories =
            categories.map { category ->
                StorageCategory(
                    name = categoryLabel(category.category.name),
                    usedBytes = category.sizeBytes,
                    usagePercentage = if (usedBytes > 0) category.sizeBytes.toFloat() / usedBytes.toFloat() else 0f,
                    color = getCategoryColor(category.category.name),
                    formattedUsed = formatByteSize(category.sizeBytes),
                )
            },
        formattedTotal = formatByteSize(totalBytes),
        formattedUsed = formatByteSize(usedBytes),
        isOverQuota = usedBytes > totalBytes,
    )
}

// This function has been replaced by CloudStorageQuota.toStorageQuotaUi()

/**
 * The quota API names its categories the way the server enumerates them. Those names are not
 * something to put in front of someone reading a storage breakdown.
 */
private fun categoryLabel(categoryName: String): StringResource =
    when (categoryName.uppercase()) {
        "TEXT_NOTES" -> Res.string.data_settings_quota_category_written_entries
        "IMAGE_NOTES" -> Res.string.data_settings_quota_category_photos
        "VOICE_NOTES" -> Res.string.data_settings_quota_category_recordings
        "VIDEO_NOTES" -> Res.string.data_settings_quota_category_videos
        "JOURNAL_DATA" -> Res.string.data_settings_quota_category_journals
        "USER_PROFILE" -> Res.string.data_settings_quota_category_profile
        "ATTACHMENTS" -> Res.string.data_settings_quota_category_attachments
        else -> Res.string.data_settings_quota_category_other
    }

/**
 * Returns a color for the given quota category name.
 */
private fun getCategoryColor(categoryName: String): Color =
    when (categoryName.uppercase()) {
        "IMAGE_NOTES" -> Color(0xFF4CAF50)

        // Green
        "TEXT_NOTES" -> Color(0xFF2196F3)

        // Blue
        "VIDEO_NOTES" -> Color(0xFFF44336)

        // Red
        "VOICE_NOTES" -> Color(0xFFFF9800)

        // Orange
        "JOURNAL_DATA" -> Color(0xFF9C27B0)

        // Purple
        "ATTACHMENTS" -> Color(0xFF607D8B)

        // Blue Gray
        "USER_PROFILE" -> Color(0xFF795548)

        // Brown
        else -> Color(0xFF9E9E9E) // Gray
    }

// CloudStorageQuota?.orDefault() is defined in SettingsUiMappers.kt

/**
 * Maps a CloudStorageQuota to a QuotaUsageUi model for the DataSettingsScreen.
 */
fun CloudStorageQuota.toQuotaUsageUi(): StorageQuotaUi = orDefault().toStorageQuotaUi()

/*
 * Formats byte size to human-readable string (e.g., "1.5 GB").
 */
