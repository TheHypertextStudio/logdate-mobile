package app.logdate.client.database.converters

import androidx.room.TypeConverter

/**
 * Stores a list of strings in one text column. An empty list is stored as an empty string rather
 * than null, because the column of a non-null list does not accept null.
 */
class StringListConverter {
    @TypeConverter
    fun fromString(value: String?): List<String> =
        value
            ?.split(SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    @TypeConverter
    fun toString(values: List<String>?): String? =
        values
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.joinToString(SEPARATOR)

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}
