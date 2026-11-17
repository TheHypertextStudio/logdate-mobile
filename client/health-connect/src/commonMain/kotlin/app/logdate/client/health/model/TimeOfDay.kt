package app.logdate.client.health.model

/**
 * Platform-independent representation of a time of day.
 * Similar to LocalTime but without platform-specific dependencies.
 */
data class TimeOfDay(
    val hour: Int,
    val minute: Int,
    val second: Int = 0,
) {
    init {
        require(hour in 0..23) { "Hour must be between 0 and 23" }
        require(minute in 0..59) { "Minute must be between 0 and 59" }
        require(second in 0..59) { "Second must be between 0 and 59" }
    }

    /**
     * Checks if this time is before another time.
     */
    fun isBefore(other: TimeOfDay): Boolean =
        when {
            hour < other.hour -> true
            hour > other.hour -> false
            minute < other.minute -> true
            minute > other.minute -> false
            else -> second < other.second
        }

    /**
     * Returns a new TimeOfDay that is the specified number of hours earlier.
     */
    fun minusHours(hours: Int): TimeOfDay {
        val newHour = (hour - hours) % 24
        return copy(hour = if (newHour < 0) newHour + 24 else newHour)
    }

    override fun toString(): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}"

    companion object {
        /**
         * Creates a TimeOfDay from hours, minutes, and optional seconds.
         */
        fun of(
            hour: Int,
            minute: Int,
            second: Int = 0,
        ): TimeOfDay = TimeOfDay(hour, minute, second)
    }
}
