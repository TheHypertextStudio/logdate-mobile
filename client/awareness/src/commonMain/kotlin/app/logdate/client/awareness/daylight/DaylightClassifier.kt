package app.logdate.client.awareness.daylight

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Classifies the time of day into a DaylightPeriod based on sun position.
 *
 * Uses latitude and longitude to calculate accurate sunrise/sunset times,
 * then classifies the recording time into one of seven periods:
 * - DAWN: Around sunrise
 * - MORNING: After dawn until before midday
 * - MIDDAY: Around solar noon
 * - AFTERNOON: After midday until before golden hour
 * - GOLDEN_HOUR: The hour before sunset
 * - EVENING: After sunset until night
 * - NIGHT: Dark hours
 */
class DaylightClassifier {
    /**
     * Classifies the recording time into a DaylightPeriod.
     *
     * @param recordingTime The instant when the recording was made
     * @param latitude Latitude in decimal degrees
     * @param longitude Longitude in decimal degrees
     * @return The classified DaylightPeriod
     */
    fun classify(
        recordingTime: Instant,
        latitude: Double,
        longitude: Double,
    ): DaylightPeriod {
        val localDateTime = recordingTime.toLocalDateTime(TimeZone.currentSystemDefault())
        val date = localDateTime.date
        val time = localDateTime.time

        val sunTimes = SunCalculator.calculate(latitude, longitude, date)

        return classifyTime(time, sunTimes)
    }

    /**
     * Classifies a recording time when location is not available.
     * Uses a default approximation based on standard day hours.
     */
    fun classifyWithoutLocation(recordingTime: Instant): DaylightPeriod {
        val localDateTime = recordingTime.toLocalDateTime(TimeZone.currentSystemDefault())
        val hour = localDateTime.hour

        return when (hour) {
            in 5..6 -> DaylightPeriod.DAWN
            in 7..10 -> DaylightPeriod.MORNING
            in 11..13 -> DaylightPeriod.MIDDAY
            in 14..16 -> DaylightPeriod.AFTERNOON
            in 17..18 -> DaylightPeriod.GOLDEN_HOUR
            in 19..21 -> DaylightPeriod.EVENING
            else -> DaylightPeriod.NIGHT
        }
    }

    private fun classifyTime(
        time: LocalTime,
        sunTimes: SunTimes,
    ): DaylightPeriod {
        val sunriseMinutes = sunTimes.sunrise.toMinuteOfDay()
        val sunsetMinutes = sunTimes.sunset.toMinuteOfDay()
        val noonMinutes = sunTimes.solarNoon.toMinuteOfDay()
        val currentMinutes = time.toMinuteOfDay()

        return when {
            // Dawn: 30 minutes before to 30 minutes after sunrise
            currentMinutes in (sunriseMinutes - 30)..(sunriseMinutes + 30) -> DaylightPeriod.DAWN

            // Morning: After dawn until 2 hours before solar noon
            currentMinutes in (sunriseMinutes + 31) until (noonMinutes - 120) -> DaylightPeriod.MORNING

            // Midday: 2 hours around solar noon
            currentMinutes in (noonMinutes - 120)..(noonMinutes + 120) -> DaylightPeriod.MIDDAY

            // Afternoon: After midday until 1 hour before sunset
            currentMinutes in (noonMinutes + 121) until (sunsetMinutes - 60) -> DaylightPeriod.AFTERNOON

            // Golden hour: Last hour before sunset
            currentMinutes in (sunsetMinutes - 60) until sunsetMinutes -> DaylightPeriod.GOLDEN_HOUR

            // Evening: 2 hours after sunset
            currentMinutes in sunsetMinutes..(sunsetMinutes + 120) -> DaylightPeriod.EVENING

            // Night: Everything else
            else -> DaylightPeriod.NIGHT
        }
    }
}

private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
