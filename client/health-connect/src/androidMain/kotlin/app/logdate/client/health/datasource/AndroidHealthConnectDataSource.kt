package app.logdate.client.health.datasource

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.logdate.client.health.HealthDataAvailability
import app.logdate.client.health.SleepSessionConstants
import app.logdate.client.health.model.SleepSession
import app.logdate.client.health.model.SleepStage
import app.logdate.client.health.model.SleepStageType
import app.logdate.client.health.model.TimeOfDay
import io.github.aakira.napier.Napier
import kotlinx.datetime.TimeZone
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Android implementation of [RemoteHealthDataSource] using Health Connect API.
 */
class AndroidHealthConnectDataSource(
    private val context: Context,
) : RemoteHealthDataSource {
    init {
        warnIfManifestRequirementsMissing()
    }

    private val healthConnectClient by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Napier.e("Failed to create HealthConnectClient", e)
            null
        }
    }

    private val sleepPermissions =
        setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )

    override suspend fun getAvailability(): HealthDataAvailability =
        try {
            val availability = HealthConnectClient.getSdkStatus(context)
            Napier.i("Health Connect SDK status: ${sdkStatusLabel(availability)} ($availability)")
            when (availability) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    HealthDataAvailability.AVAILABLE
                }

                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    HealthDataAvailability.PROVIDER_UPDATE_REQUIRED
                }

                else -> {
                    HealthDataAvailability.NOT_AVAILABLE
                }
            }
        } catch (e: Exception) {
            Napier.e("Error checking Health Connect availability", e)
            HealthDataAvailability.NOT_AVAILABLE
        }

    override suspend fun isAvailable(): Boolean = getAvailability() == HealthDataAvailability.AVAILABLE

    override suspend fun hasSleepPermissions(): Boolean {
        val client = healthConnectClient ?: return false

        return try {
            val grantedPermissions = client.permissionController.getGrantedPermissions()
            grantedPermissions.containsAll(sleepPermissions)
        } catch (e: Exception) {
            Napier.e("Error checking sleep permissions", e)
            false
        }
    }

    override suspend fun requestSleepPermissions(): Boolean {
        // This would normally launch the permission request flow
        // which requires an Activity context and result handling.
        // In a real implementation, this would be handled by a UI component.
        Napier.w("Permission request flow should be handled by a UI component")
        return false
    }

    override suspend fun getSleepSessions(
        start: Instant,
        end: Instant,
    ): List<SleepSession> {
        val client = healthConnectClient ?: return emptyList()

        return try {
            val timeRangeFilter =
                TimeRangeFilter.between(
                    start.toJavaTimeInstant(),
                    end.toJavaTimeInstant(),
                )

            val request =
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = timeRangeFilter,
                )

            val response = client.readRecords(request)

            response.records.map { record ->
                SleepSession(
                    id = record.metadata.id,
                    startTime = record.startTime.toKotlinxInstant(),
                    endTime = record.endTime.toKotlinxInstant(),
                    sourceAppName = record.metadata.dataOrigin.packageName,
                    stages =
                        record.stages.map { stage ->
                            SleepStage(
                                type = SleepStageType.UNKNOWN,
                                startTime = stage.startTime.toKotlinxInstant(),
                                endTime = stage.endTime.toKotlinxInstant(),
                            )
                        },
                )
            }
        } catch (e: Exception) {
            Napier.e("Error reading sleep sessions", e)
            emptyList()
        }
    }

    override suspend fun getAverageWakeUpTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? {
        if (!isAvailable() || !hasSleepPermissions()) {
            return null
        }

        try {
            // Calculate the start and end times for the query
            val endTime = Clock.System.now()
            val startTime =
                Instant.fromEpochMilliseconds(
                    endTime.toEpochMilliseconds() - (days * 24 * 60 * 60 * 1000L),
                )

            // Get sleep sessions for the period
            val sleepSessions = getSleepSessions(startTime, endTime)

            if (sleepSessions.isEmpty()) {
                return null
            }

            val wakeUpTimes =
                sleepSessions.map { session ->
                    val zoneId = ZoneId.of(timeZone.id)
                    val dateTime =
                        session.endTime
                            .toJavaTimeInstant()
                            .atZone(zoneId)
                            .toLocalTime()

                    // Convert to hour and minute
                    dateTime.hour to dateTime.minute
                }

            // Calculate average wake-up time
            val totalMinutes =
                wakeUpTimes.sumOf { (hour, minute) ->
                    hour * 60 + minute
                }
            val avgMinutes = totalMinutes / wakeUpTimes.size

            val avgHour = avgMinutes / 60
            val avgMinute = avgMinutes % 60

            return TimeOfDay(avgHour, avgMinute)
        } catch (e: Exception) {
            Napier.e("Error calculating average wake-up time", e)
            return null
        }
    }

    override suspend fun getAverageSleepTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? {
        if (!isAvailable() || !hasSleepPermissions()) {
            return null
        }

        try {
            // Calculate the start and end times for the query
            val endTime = Clock.System.now()
            val startTime =
                Instant.fromEpochMilliseconds(
                    endTime.toEpochMilliseconds() - (days * 24 * 60 * 60 * 1000L),
                )

            // Get sleep sessions for the period
            val sleepSessions = getSleepSessions(startTime, endTime)

            if (sleepSessions.isEmpty()) {
                return null
            }

            val sleepTimes =
                sleepSessions.map { session ->
                    val zoneId = ZoneId.of(timeZone.id)
                    val dateTime =
                        session.startTime
                            .toJavaTimeInstant()
                            .atZone(zoneId)
                            .toLocalTime()

                    // Convert to hour and minute
                    dateTime.hour to dateTime.minute
                }

            // Calculate average sleep time
            val totalMinutes =
                sleepTimes.sumOf { (hour, minute) ->
                    hour * 60 + minute
                }
            val avgMinutes = totalMinutes / sleepTimes.size

            val avgHour = avgMinutes / 60
            val avgMinute = avgMinutes % 60

            return TimeOfDay(avgHour, avgMinute)
        } catch (e: Exception) {
            Napier.e("Error calculating average sleep time", e)
            return null
        }
    }

    /**
     * Logs errors for each Health Connect manifest requirement that is missing.
     *
     * Health Connect will not list the app in its "App permissions" settings screen unless all
     * three entries are present. Missing any one of them silently breaks the integration — the
     * runtime permission API still works, but users can never find or manage the app from within
     * Health Connect. This check fires once at startup so the problem is immediately visible in
     * logs rather than discovered via a user report.
     *
     * See docs/health-connect-refactoring.md § "Android Manifest Requirements" for the XML
     * snippets that resolve each error.
     */
    private fun warnIfManifestRequirementsMissing() {
        val appInfo =
            try {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA,
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Napier.e("Health Connect: could not read ApplicationInfo to verify manifest requirements", e)
                return
            }

        if (appInfo.metaData?.getString("health_connect.privacy_policy_url") == null) {
            Napier.e(
                "Health Connect: <meta-data android:name=\"health_connect.privacy_policy_url\"> " +
                    "is missing from AndroidManifest.xml. The app will NOT appear in Health Connect's " +
                    "App permissions settings screen. " +
                    "See docs/health-connect-refactoring.md for the required snippet.",
            )
        }

        val stringId =
            context.resources.getIdentifier(
                "health_permissions",
                "string",
                context.packageName,
            )
        if (stringId == 0) {
            Napier.e(
                "Health Connect: <string name=\"health_permissions\"> is missing from strings.xml. " +
                    "The permission request dialog will show a blank label. " +
                    "See docs/health-connect-refactoring.md for details.",
            )
        }

        // Android 13 and earlier: HC permission dialog → rationale screen
        val rationaleIntent =
            Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
                .setPackage(context.packageName)
        if (context.packageManager.resolveActivity(rationaleIntent, 0) == null) {
            Napier.e(
                "Health Connect: No activity responds to ACTION_SHOW_PERMISSIONS_RATIONALE. " +
                    "The privacy policy link in the HC permission dialog will do nothing. " +
                    "Add an <activity-alias> in AndroidManifest.xml. " +
                    "See docs/health-connect-refactoring.md for the required snippet.",
            )
        }

        // Android 14+: HC settings → manage app permissions (also controls HC app-list visibility)
        val viewUsageIntent =
            Intent("android.intent.action.VIEW_PERMISSION_USAGE")
                .addCategory("android.intent.category.HEALTH_PERMISSIONS")
                .setPackage(context.packageName)
        if (context.packageManager.resolveActivity(viewUsageIntent, 0) == null) {
            Napier.e(
                "Health Connect: No activity responds to VIEW_PERMISSION_USAGE + HEALTH_PERMISSIONS. " +
                    "LogDate will NOT appear in Health Connect's App permissions list on Android 14+. " +
                    "Add a <activity-alias> with action VIEW_PERMISSION_USAGE and " +
                    "category HEALTH_PERMISSIONS in AndroidManifest.xml. " +
                    "See docs/health-connect-refactoring.md for the required snippet.",
            )
        }
    }

    private fun mapSleepStageType(stage: String): SleepStageType =
        when (stage) {
            "${SleepSessionConstants.STAGE_TYPE_AWAKE}" -> SleepStageType.AWAKE
            "${SleepSessionConstants.STAGE_TYPE_DEEP}" -> SleepStageType.DEEP
            "${SleepSessionConstants.STAGE_TYPE_LIGHT}" -> SleepStageType.LIGHT
            "${SleepSessionConstants.STAGE_TYPE_REM}" -> SleepStageType.REM
            else -> SleepStageType.UNKNOWN
        }
}

private fun Instant.toJavaTimeInstant(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

private fun java.time.Instant.toKotlinxInstant(): Instant = Instant.fromEpochSeconds(epochSecond, nano.toLong())

private fun sdkStatusLabel(status: Int): String =
    when (status) {
        HealthConnectClient.SDK_AVAILABLE -> {
            "SDK_AVAILABLE"
        }

        HealthConnectClient.SDK_UNAVAILABLE -> {
            "SDK_UNAVAILABLE"
        }

        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
            "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED"
        }

        else -> {
            "UNKNOWN"
        }
    }
