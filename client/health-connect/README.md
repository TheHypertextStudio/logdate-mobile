# Health Connect Module

This module gives the rest of the app one way to ask for health data, whichever platform it runs
on. Today that data is sleep: LogDate uses it to decide when a person's "day" starts and ends, so
that an entry written at 1 a.m. after a late night can belong to the day the person was still
living. The module follows a local-first pattern: reads go to a local cache first, and the platform
health API is asked only when the cache has nothing.

## Architecture

- `LocalFirstHealthRepository` is the single interface the app uses. It extends
  `HealthDataRepository`. `DefaultLocalFirstHealthRepository` implements it.
- A `LocalHealthDataSource` caches results. `InMemoryLocalHealthDataSource` is the implementation on
  every platform.
- A `RemoteHealthDataSource` wraps the platform health API:
  - `AndroidHealthConnectDataSource` uses Android Health Connect and reads `SleepSessionRecord`.
  - `IosHealthKitDataSource` uses HealthKit through `IosSleepRepository`. It needs the HealthKit
    entitlement and an `NSHealthShareUsageDescription` entry in the app's `Info.plist`, and answers
    with empty results rather than crashing when either is missing.
  - `JvmUnavailableRemoteHealthDataSource` is used on desktop, where no health API exists. It
    reports not available and returns no data.
  - `UnavailableRemoteHealthDataSource` is a platform-neutral version of the same behavior.
- Models are platform independent: `DayBounds`, `TimeOfDay`, `SleepSession` and `SleepStage`.

The data sources live in the `datasource` package; the `data` package is not used by the app.

## How reads work

Every read follows the same path:

1. Return the cached value if the local data source has one.
2. Otherwise, if the remote source is available and sleep permission is granted, fetch from it,
   store the result in the cache, and return it.
3. Otherwise return an empty list or `null`.

The three reads (sleep sessions, the two averages) log an exception and return an empty result, and
day bounds fall back to the 04:00 default, so a failed read does not crash the UI. The availability and
permission calls (`getHealthDataAvailability`, `hasSleepPermissions`, `requestSleepPermissions`,
`isHealthDataAvailable`) pass exceptions through. `HealthDataAvailability` reports why the provider cannot be used: `AVAILABLE`,
`PROVIDER_UPDATE_REQUIRED` (Health Connect is installed but too old) or `NOT_AVAILABLE`.

`InMemoryLocalHealthDataSource` keeps its data only for the life of the process. Its entries share
one timestamp, the time of the latest write, and the whole cache is treated as empty 12 hours after
that write. `clearCache()` empties it at once.

## Day bounds

`getDayBoundsForDate(date, timeZone, sleepBasedBoundariesEnabled = true)` returns the start and end
of the person's day as instants. It tries three sources in order:

1. **Sleep sessions.** When sleep-based boundaries are on and permission is granted, one query
   covers 18:00 the previous day to 14:00 the next day. Sessions that end before 14:00 on the date
   are wake-up candidates and sessions that start after 14:00 are bedtime candidates. In each group
   the primary session is the longest one of at least three hours, so naps are ignored, or the
   longest session of any length when none reaches three hours. The day starts when the wake-up
   session ends and ends when the bedtime session starts. Each boundary falls back independently.
2. **The person's preferences.** `dayStartHour` and, when set, `dayEndHour`. An end hour at or
   before the start hour means the next calendar day, so equal hours describe a full 24-hour day.
3. **The default.** A day starts at 04:00 and ends at 04:00 the next day
   (`DEFAULT_DAY_START_HOUR`).

The preferences come from `LogdatePreferencesDataSource`, which `:client:domain` binds, because this
module cannot depend on the datastore.

## Dependency injection

- `healthModule` binds the in-memory local source, `LocalFirstHealthRepository` and
  `HealthDataRepository`. It is included on every platform. It needs a
  `LogdatePreferencesDataSource`, a `RemoteHealthDataSource` and a coroutine dispatcher named
  `io-dispatcher`.
- `androidHealthModule`, `iosHealthModule` and `jvmHealthModule` supply the platform's remote
  source and dispatcher.

## Usage

```kotlin
val healthRepository: LocalFirstHealthRepository = get()

val sleepSessions = healthRepository.getSleepSessions(startTime, endTime)
val avgWakeUpTime = healthRepository.getAverageWakeUpTime(TimeZone.currentSystemDefault())
val avgSleepTime = healthRepository.getAverageSleepTime(TimeZone.currentSystemDefault())
val dayBounds = healthRepository.getDayBoundsForDate(date, timeZone)
```

Averages look back 30 days unless `days` is given.

## Android manifest requirements

Health Connect needs three declarations that are separate from the runtime permission request. If
any is missing, the app can still request and use permission, but it does not appear in Health
Connect's **App permissions** screen, so people cannot find or manage what LogDate can read.
`AndroidHealthConnectDataSource` checks all of them when it is created and logs an error naming each
one that is missing, so the problem shows up in logs instead of in a user report.

**1. Privacy policy URL.** Health Connect reads a `health_connect.privacy_policy_url` meta-data entry
to populate its list of registered apps. It is declared in this module's own manifest
(`src/androidMain/AndroidManifest.xml`) inside `<application>`, and Gradle manifest merging carries
it into the app.

**2. A `health_permissions` string resource.** Health Connect looks the label up by that exact name
and shows it in the permission request dialog. It is declared in the app module's
`res/values/strings.xml`. Without it the dialog shows a blank label.

**3. Two activity aliases in the app manifest**, both pointing at `MainActivity`. Health Connect
uses a different intent depending on the Android version:

- Android 13 and earlier send `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` when the person
  taps the privacy policy link in the permission dialog. The alias
  (`HealthConnectPermissionsRationaleActivity`) is exported and carries no permission guard, because
  the dialog sends this intent without holding any special permission.
- Android 14 and later send `android.intent.action.VIEW_PERMISSION_USAGE` with the
  `android.intent.category.HEALTH_PERMISSIONS` category from Health Connect's settings. This alias
  (`ViewHealthPermissionUsageActivity`) is what makes the app appear in the App permissions list on
  those versions. It requires `android.permission.START_VIEW_PERMISSION_USAGE`, a permission only
  the system holds, so no other app can fire the intent.

The `android.permission.health.READ_SLEEP` permission and the `<queries>` entry for the Health
Connect provider package are declared in `client/domain/src/androidMain/AndroidManifest.xml` and
merge into the app manifest.

## Dependencies

- Android Health Connect client (Android only) and HealthKit (iOS only)
- Kotlin Coroutines and Kotlin Datetime
- Koin for dependency injection
