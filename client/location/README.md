# `:client:location`

**Location capture, current-position lookup, and place suggestions**

## Overview

This module gets positions from the platform and records them. It answers "where is the device
now?" for features such as tagging an entry, and it runs the background recording that feeds the
Places history. It does not decide what those positions mean. Turning recorded samples into visits,
journeys and gaps happens in `:client:domain`; that conversion is explained in
[How LogDate builds a day from location samples](../../docs/explanation/location-history.md).

## Architecture

```
commonMain
├── ClientLocationProvider, LocationFix     one-off and observed positions
├── DeviceLocationTracker                   start/stop streaming for a platform
├── history/LocationTracker                 writes samples to the history repository
├── settings/                               LocationTrackingSettings and its repository
├── tracking/                               recording controls, capture status, scheduling rules
└── places/                                 reverse geocoding and nearby-place suggestions
androidMain    Play Services providers, WorkManager and foreground-service recording
iosMain        CoreLocation providers and place lookups
jvmMain        desktop provider that uses a user-chosen default location
```

## Positions

`ClientLocationProvider` answers current-position questions. `getCurrentFix()` returns a
`LocationFix`: the position, the time the position was true (which can be older than the moment it
is read), and the accuracy, speed and bearing the platform reported. History needs the accuracy and
the time to tell a real move from measurement noise, so providers that know only a position report
it as taken now with unknown accuracy.

Each platform supplies its own provider:

- **Android** uses the Fused Location Provider from Google Play Services.
- **iOS** uses `CLLocationManager`. `IosLocationProvider` makes one-shot requests and reuses a
  recent fix for a couple of minutes; `IosDeviceLocationTracker` handles continuous updates.
- **Desktop** has no operating-system location source. `DesktopLocationProvider` never invents
  coordinates: it returns the default location the user configured in settings, and fails with an
  explicit unsupported error when none is set.

## Recording

Background recording is off by default. `LocationTrackingSettings` holds the choices:

| Setting                         | Default   | Meaning                                                                  |
| ------------------------------- | --------- | ------------------------------------------------------------------------ |
| `backgroundTrackingEnabled`     | `false`   | The master switch for background recording                               |
| `captureMode`                   | `PASSIVE` | `PASSIVE` or `ACTIVE`, described below                                   |
| `minimumPersistIntervalMinutes` | 30        | Spacing of periodic samples                                              |
| `autoTrackForJournalEntries`    | `true`    | Capture a position when an entry is created                              |
| `autoTrackForTimelineReview`    | `true`    | Capture a position while reviewing the timeline                          |
| `serverAssistEnabled`           | `false`   | Optional server nudges for the optimized background path                 |
| `defaultLocation`               | none      | Fallback position for devices without operating-system location services |

On Android, `LocationTrackingManager` watches these settings and the location permission, and
starts or stops the right pieces. Changes are debounced by one second, and a missing permission
counts as tracking off without changing the saved preference. When the app resumes it reconciles
again, which recovers a foreground-service start that was refused while the app was in the
background.

- **Periodic samples.** In both modes, a WorkManager job records the current fix every
  `minimumPersistIntervalMinutes`. The job asks the platform for a balanced-power fix, so each run
  is an active request. WorkManager does not run periodic work more often than every 15 minutes, so
  shorter settings are raised to 15. The job gives a position 30 seconds to arrive and asks
  WorkManager to retry when it fails.
- **`PASSIVE` mode** runs only the periodic job.
- **`ACTIVE` mode** also registers passive updates, which record a position whenever another app
  has asked the system for one, and runs
  `ActivityAwareLocationService`, a foreground service with a visible notification. The service uses
  the activity recognition transition API to pick a request profile for what the person is doing:

  | Profile      | Used when                         | Priority       | Interval   | Minimum distance |
  | ------------ | --------------------------------- | -------------- | ---------- | ---------------- |
  | `STILL`      | Still                             | Balanced power | 3 minutes  | none             |
  | `ON_FOOT`    | Walking, running, or unrecognized | High accuracy  | 30 seconds | 20 m             |
  | `IN_VEHICLE` | Driving or cycling                | High accuracy  | 10 seconds | 50 m             |

  The service starts in `ON_FOOT` until the first transition arrives. These are requested
  intervals. The system decides when fixes are actually delivered, so the real cadence is coarser,
  and an emulator says nothing about battery cost or vendor background restrictions. While the
  stream is delivering fixes, the periodic job skips its sample, because a balanced-power fix
  between high-accuracy fixes would add a coarser position to the same moment. A stream that is
  subscribed but has delivered nothing within the interval counts as not covering the period.

- **Activity evidence.** `ActivityTransitionReceiver` stores each transition as evidence even when
  no service instance is running. Reconstruction uses it to label samples that have no activity of
  their own.
- **Pause and resume.** The service notification offers pause and resume. Both change the same
  `backgroundTrackingEnabled` switch that settings uses (`LocationRecordingControls`), so the
  notification and the settings screen cannot disagree, and a paused state leaves its own
  notification that reopens the history.
- **Restart.** `LocationTrackingBootReceiver` restores tracking after a reboot or an app update.
- **Capture status.** `LocationCaptureStatus` (`Stopped`, `Starting`, `Running`, `Failed`) reports
  whether the location subscription was accepted. It is not a guarantee that fixes are arriving and
  it does not say the history is complete.

`DetailedLocationTrackingService` polls at a fixed rate and is deprecated; use
`ActivityAwareLocationService`.

## Writing samples to history

`LocationTracker` is the write side. `StandardLocationTracker` combines a `ClientLocationProvider`
with the `LocationHistoryRepository`: it stores a sample with its accuracy, speed, bearing, source,
and whether it was a mock location, under the canonical owner id. Each capture path (periodic job,
active stream, passive updates, entry creation) writes through it, and reconstruction reads the
same samples back.

When `LogCurrentLocationUseCase` in `:client:domain`, the one-off capture that features request,
fails to write a sample, `LocationRetryWorker` retries it with exponential backoff: 2 seconds,
doubling each attempt, capped at 5 minutes, for at most 5 attempts. The queue is held in memory. A
retry that is still waiting when the process ends is lost, and a sample that fails its fifth
attempt is dropped.

## Places

- `ReverseGeocodingProvider` turns coordinates into a street address. Android uses the platform
  `Geocoder`, using the non-blocking callback on Android 13 and later; iOS uses its own provider.
- `ExternalPlacesProvider` suggests named places near a position. Android uses the Google Places
  SDK within 250 meters, reading its key from the `com.google.android.geo.API_KEY` manifest
  metadata and disabling lookups when it is missing. Platforms without a places API use
  `UnavailableExternalPlacesProvider`, which returns no suggestions.

## Dependencies

- `:shared:model`, `:client:database`, `:client:logdate-datastore`, `:client:permissions`,
  `:client:repository` and `:client:device`.
- Android: Play Services location, maps and Places, WorkManager, and `:client:notifications` for
  the recording notification.

## Testing

```bash
./gradlew :client:location:jvmTest :client:location:testAndroidHostTest
```

Android instrumentation for the history screens runs on a Gradle Managed Device, with
`:app:android-main:smokeDevicesGroupDebugAndroidTest` and
`-Plogdate.androidTestClass=app.logdate.client.e2e.HumanLocationHistoryE2ETest` (or
`HumanLocationHistoryConnectedE2ETest` for the version that talks to a server). Checking encrypted
history across two installations needs a live server shared by two runs; the procedure is in the
[server-client end-to-end module README](../../integration/server-client-e2e/README.md).

The JVM tests cover the desktop provider, the history tracker's handling of evidence and
cancellation, the settings repository, recording controls and the capture-status tracker.
