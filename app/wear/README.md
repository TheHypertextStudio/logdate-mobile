# `:app:wear` — Wear OS App

LogDate captures thoughts, voice recordings, moods, and marked moments on your wrist. The phone
companion manages setup, the shared library, reminders, and device access.

**Min SDK**: 34 (Wear OS 5+) | **Target SDK**: 37 | **Compile SDK**: 37

[The watch companion](../../docs/explanation/wear-companion.md) explains what the watch is for and
why it behaves as it does.

## Release and compatibility

The phone and watch use the same application ID (`studio.hypertext.logdate`, or
`studio.hypertext.logdate.debug` for debug) and must have matching signing certificates for the Wear
Data Layer. Matching identity is necessary, but does not establish pairing or a verified LogDate link.
A Play phone build and a locally upload-key-signed watch build do not have matching app-signing keys.
Install both from the corresponding Play internal test tracks for hardware dogfood.

The redesign is gated by `FeatureFlag.WATCH_EXPERIENCE_V2`, default OFF in ordinary builds. A
deliberately dispatched Play internal build defaults it ON on both phone and watch for dogfood;
an explicit Labs choice still wins. Protocol negotiation also requires the peer's version and
advertised feature; an incompatible or older peer retains the legacy path. The
[dogfood checklist](../../docs/testing/wear-dogfood-checklist.md) covers paired scenarios and
hardware testing.

Mobile distribution is **internal only**: phone `internal`, watch `wear:internal`. Release identity,
versioning, and signing come from `app.logdate.android-release` in `build-logic`. See
[Google Play Publishing](../../docs/reference/google-play-publishing.md#wear-os). Batch a verified
implementation into one publication rather than creating a new Play version for each local commit.

## Installing on a physical watch

The Wear Data Layer is the system channel that carries data between a phone app and a watch app.
It connects the two only when they have the same application ID and are signed with the same
certificate. A watch build that does not match the phone build installs and runs, but the two
cannot exchange anything. Getting LogDate onto a watch is therefore about installing a matched
pair, not about the watch alone.

Two pairs match:

| Phone build                            | Watch build                                 | Application ID                   | Signed with                   |
| -------------------------------------- | ------------------------------------------- | -------------------------------- | ----------------------------- |
| Play internal testing track `internal` | Play internal testing track `wear:internal` | `studio.hypertext.logdate`       | The Play app-signing key      |
| Debug build from a checkout            | Debug build from the same machine           | `studio.hypertext.logdate.debug` | That machine's debug keystore |

Mixed pairs do not match. A phone installed from Play and a watch built locally have different
signing certificates even when the local build uses the release upload key, because Play re-signs
what it distributes. A debug phone and a release watch differ in application ID as well.

For testing on real hardware, install both from Play: LogDate on the phone from the internal
testing track, then LogDate on the watch from the Play Store on the watch. The
[dogfood checklist](../../docs/testing/wear-dogfood-checklist.md) walks through it, and
[Google Play Publishing](../../docs/reference/google-play-publishing.md#wear-os) describes how the
watch build reaches that track.

A debug pair uses a separate application ID, so it installs beside a Play install and does not
replace its data. Prefer an emulator pair for development; see
[Build and emulator validation](#build-and-emulator-validation). A physical device holds real
recordings, and the commands that are convenient on an emulator (uninstalling, clearing app data)
destroy them. Never uninstall LogDate or clear its data on a device to fix a setup problem.

### Installing a debug pair by hand

These steps are for a person installing their own debug builds on their own devices. Automated
tooling in this repository must not run them against physical hardware; see
[Build and emulator validation](#build-and-emulator-validation).

1. Pair the watch with the phone through the system's own watch setup, if it is not already. The
   Wear Data Layer only connects a watch to the phone it is paired with.
2. Turn on developer options and ADB debugging on both devices, and connect both to the same
   computer. A watch usually connects over Wi-Fi debugging.
3. List the attached devices and note the serial of each:

   ```bash
   adb devices
   ```

4. Install the debug phone app on the phone and the debug watch app on the watch, from the same
   checkout on the same computer, so both are signed with that computer's debug keystore.
   `ANDROID_SERIAL` limits a Gradle install to one device:

   ```bash
   ANDROID_SERIAL=<phone serial> ./gradlew :app:android-main:installDebug
   ANDROID_SERIAL=<watch serial> ./gradlew :app:wear:installDebug
   ```

5. Open LogDate on both. The debug applications are `studio.hypertext.logdate.debug` on each
   device, beside any Play install.

Name the device every time. `./run run:wear` and `./run run:android` run the same install tasks
without a serial, and with a phone and a watch both attached an install task targets every
attached device.

`./run build:wear:release` is not a way to install on a watch. It builds the release bundle that
is uploaded to Play; the build Play then distributes is the one that matches a Play phone install.

An older watch install under the retired application ID `app.logdate.wear` is a different app. It
does not update in place and never synced to the phone, so recordings on it exist nowhere else.

## Two experiences and the switch between them

The module contains two watch apps. The **current experience** is the one that opens by default:
a recorder home screen with separate timeline, voice memories, mood and settings screens, under
[`presentation/`](src/main/kotlin/app/logdate/wear/presentation/). The **new experience** is the
redesign, under [`v2/`](src/main/kotlin/app/logdate/wear/v2/). Both are compiled into every build,
because every change lands on the main branch and an unfinished redesign has to stay invisible.

`FeatureFlag.WATCH_EXPERIENCE_V2` chooses between them. Its stored key is
`watch_experience_v2_enabled` and it is off by default
([`FeatureFlag.kt`](../../client/datastore/src/commonMain/kotlin/app/logdate/client/datastore/featureflags/FeatureFlag.kt)).

**Choosing the UI.** [`MainActivity`](src/main/kotlin/app/logdate/wear/presentation/MainActivity.kt)
asks [`WearExperienceGate`](src/main/kotlin/app/logdate/wear/launch/WearExperienceGate.kt) once per
activity, behind the startup screen, and
[`MainActivityContent`](src/main/kotlin/app/logdate/wear/presentation/MainActivityContent.kt)
shows `WearApp` (current) or `WearAppV2` (new). Three rules keep the choice stable:

- The choice is saved with the activity's state, and a recreated activity uses the saved choice.
  A configuration change therefore never swaps the app under the person's finger.
- A flag read that fails, or takes longer than 2 seconds, opens the current experience, so the
  startup screen always ends.
- Changing the flag takes effect the next time the activity is created.

**Following the flag elsewhere.** The gate decides the UI only. Background code reads the flag
itself, because it runs without the activity: the mark receiver and its worker, the recording's
watch face entry (through [`FeatureSwitch`](src/main/kotlin/app/logdate/wear/launch/FeatureSwitch.kt),
for code that cannot suspend), the Today tile, tile and complication enablement, reminders, and the
newer sync protocol. Anything added outside `v2/` that belongs to the new experience has to check
the flag the same way.

**Where the flag is set.** The flag is phone-owned. The watch copies the phone's value from the
phone's state document and never writes it back
([`WatchStateMirror`](src/main/kotlin/app/logdate/wear/sync/v2/WatchStateMirror.kt)). There is no
switch on the watch.

**How to reach Labs.** On the phone, open Settings, then About LogDate, and tap the version row
seven times. Developer tools and Labs appear under it. Open Labs and turn on "Redesigned watch
app". The watch picks the change up from the phone and shows the new experience the next time its
activity is created. Labs exists in release builds on purpose, so the redesign can be tried on a
store-signed pair.

**The build-time default.** Passing `-Plogdate.watchExperienceDefault=true` to Gradle writes the
manifest value `app.logdate.watchExperienceDefault`, which the datastore module reads as the
flag's default for that build. The Play internal publishing workflow passes it for both the phone
and the watch. It changes only the default: a value someone set in Labs still wins.

**A second flag: activity awareness.** `FeatureFlag.ACTIVITY_AWARENESS` (stored key
`activity_awareness_enabled`, off by default) is separate from the switch between the two
experiences. It is also set in Labs on the phone, and `WatchStateMirror` copies it to the watch:
the phone lists it in its state document only while it is on, so its absence turns it off on the
watch. With it off, the watch tracks no activity. Today then shows no activity cards and no Record
button at the top of its list, a walk, ride or trip that ended is not a reason for a reminder, and
no reminder is held back because the person is moving. The checks are in
[`WearV2Module`](src/main/kotlin/app/logdate/wear/di/WearV2Module.kt) and
[`WearSyncV2Module`](src/main/kotlin/app/logdate/wear/di/WearSyncV2Module.kt). Turning on the new
experience does not turn this on.

## Companion behavior

With the redesign enabled, Today leads into Flow, retained memories, Archive, and moment details.
Capture supports tap-to-record, hold-to-talk, pause/resume, explicit discard, and Undo. Shared
preferences govern capture places, post-recording mood, reminders, and library sources. Permission
and preference checks still apply when a background operation executes.

The phone's Watch page uses shared settings components and includes a populated Today preview,
library/capture controls, Tiles and complications instructions, connection diagnostics, and removal.
Optional watch onboarding persists a per-device handled marker. Installation and repair actions
remain bound to the selected watch rather than silently targeting another node.

A linked watch can create encrypted captures through the phone or directly through its restricted
cloud session. The phone remains the editor: it resolves marked moments, captions, photo suggestions,
and attachments. Accepted captures survive offline retries; sync receipts distinguish local storage
from delivery and cloud backup. The watch's cloud profile cannot enroll another device.

Recording continuity exposes the live watch recording on the phone with Stop and Open actions.
Playback handoffs use acknowledged requests: an attempted command must not appear completed merely
because a button was tapped or the transport accepted bytes.

Removal drains pending captures first, revokes the linked cloud session, and clears watch data.
An offline watch retains a deferred erase request; a failed step remains actionable and must not be
shown as complete. Sign-out follows the same protection before clearing phone credentials.

## UI structure of the new experience

[`WearAppV2`](src/main/kotlin/app/logdate/wear/v2/WearAppV2.kt) is an `AppScaffold` around a
Navigation 3 `NavDisplay`. The back stack is a list of
[`WearRoute`](src/main/kotlin/app/logdate/wear/v2/navigation/WearRoute.kt) values saved with the
activity, and swiping right dismisses the top screen. Every route is serializable and names a
moment by its note ID as a string, because the back stack is restored after process death.

| Route          | Screen                                                                                                                                                                                     | Package                     | Opened from            |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------- | ---------------------- |
| `Home`         | Today: date, campfire, summary line, latest moment, a phone card while setup is unfinished, activity cards while activity awareness is on, Moments, Mark, +, and Record as the edge button | `v2/today`, `v2/recorder`   | Launch                 |
| `Flow`         | Voice and text moments of the last 7 days, with Memories, Settings and Earlier                                                                                                             | `v2/flow`                   | Today's Moments button |
| `Memories`     | On this day and this week's Rewind                                                                                                                                                         | `v2/memories`               | Flow                   |
| `Archive`      | Older moments on the watch, starred first, then by day                                                                                                                                     | `v2/archive`                | Flow's Earlier         |
| `Settings`     | Status card, Haptics, Places, Reminders, More on your phone (only when the phone advertises `phone.settingsLink.v1`, which no phone build does)                                            | `v2/settings`               | Flow                   |
| `Moment(id)`   | Player with crown scrubbing; transcript, place and actions one swipe up                                                                                                                    | `v2/moments`, `v2/playback` | Any list               |
| `CaptureSheet` | Write, Mood, Phone camera                                                                                                                                                                  | `v2/capture`                | Today's +              |
| `PhoneCamera`  | The remote camera screen, reused from `presentation/camera`                                                                                                                                | `presentation/camera`       | The capture sheet      |
| `PlayOn`       | Declared, with no screen. Play on opens the system output picker directly                                                                                                                  | `v2/playon`                 | —                      |

Today and Flow are separate routes. Today is a short list of its own so that Record can be its
edge button, and Flow is opened with a button.

Today's optional items come from
[`TodayUiState`](src/main/kotlin/app/logdate/wear/v2/today/TodayUiState.kt). `PhoneCard.GetApp`
opens LogDate's store listing on a paired Android phone that lacks the app. `PhoneCard.SetUp`
opens the Watch page in the phone's settings, and shows whenever a phone with LogDate is connected
and this watch does not hold an account link with it. The activity items are a suggestion after a
walk, ride or trip, a morning start after a night's sleep, and a Record button at the top of the
list while the person is moving; all three are empty while activity awareness is off.

The recorder ([`RecorderViewModel`](src/main/kotlin/app/logdate/wear/v2/recorder/RecorderViewModel.kt))
and Mark are resolved in `WearAppV2`, outside every route, so a locked recording outlives the
screen that started it. Each other screen's view model lives as long as its back stack entry.
Controls are optional handlers
([`TodayControls`](src/main/kotlin/app/logdate/wear/v2/today/TodayPage.kt),
[`MomentActions`](src/main/kotlin/app/logdate/wear/v2/moments/MomentActions.kt)): a control with no
handler is not drawn, which is how the app avoids showing a button that does nothing.

### Launch requests

Tiles, complications, notifications and the phone open the app with a route name in the intent
extra `tile_route` (and `note_id` for a single moment), or with a
`studio.hypertext.logdate://watch/...` link.
[`WearLaunchRequest`](src/main/kotlin/app/logdate/wear/launch/WearLaunchRequest.kt) turns every
name any shipped build ever sent into one typed request, because a complication placed by an old
build keeps sending the old name for as long as it stays on a watch face.

| Route name                             | Request        | Current experience opens    | New experience opens                 |
| -------------------------------------- | -------------- | --------------------------- | ------------------------------------ |
| `quick_record`, `voice_note`, `record` | `Record`       | Home, which is the recorder | Today, and starts a locked recording |
| `memories`                             | `Memories`     | Voice memories              | Memories                             |
| `on_this_day`                          | `OnThisDay`    | Home                        | Memories                             |
| `mood`                                 | `MoodCheckIn`  | Mood check-in               | Today                                |
| `quick_text`                           | `QuickText`    | Quick text                  | Today                                |
| `timeline`                             | `Timeline`     | Timeline                    | Flow                                 |
| `remote_camera`                        | `RemoteCamera` | Remote camera               | Today                                |
| `settings`                             | `Settings`     | Settings                    | Settings                             |
| `mark`                                 | `Mark`         | Home                        | Today                                |
| `moment` + `note_id`                   | `Moment`       | Home                        | That moment                          |
| `rewind`, `health`                     | `Home`         | Home                        | Today                                |

The two mappings are
[`toV1Route`](src/main/kotlin/app/logdate/wear/presentation/navigation/WearLaunchRoutes.kt) and
[`toV2BackStack`](src/main/kotlin/app/logdate/wear/v2/navigation/WearLaunchRoutes.kt). The new
experience always places the requested screen above Today, so going back lands on Today. A Record
launch starts recording the same way a tap on Record does, and leaves a recording or a save that
is already in progress alone.

### The main activity's name is fixed

The activity is `app.logdate.wear.presentation.MainActivity`, and that fully qualified name cannot
change, even though the class sits in the package that holds the current experience. It is written
as a string, not a class reference, in four places that the compiler does not check:

- [`MoodComplicationService`](src/main/kotlin/app/logdate/wear/complication/MoodComplicationService.kt)
- [`QuickCaptureComplicationService`](src/main/kotlin/app/logdate/wear/complication/QuickCaptureComplicationService.kt)
- [`benchmark/wear-macro`](../../benchmark/wear-macro/src/main/kotlin/app/logdate/benchmark/wear/WearBenchmarkConfig.kt)
- [`benchmark/wear-baselineprofile`](../../benchmark/wear-baselineprofile/src/main/kotlin/app/logdate/benchmark/wear/WearBenchmarkConfig.kt)

The `./run run:wear` command names it too, and it is the launcher component in the manifest.
Tiles, the recording's watch face entry and launch intents refer to the class itself. Moving or
renaming the class means changing all of these in the same commit; a string that is missed fails
only when a person taps a complication.

## Tiles, complications and other entry points

| Component                                      | Kind         | Current experience                         | New experience                           |
| ---------------------------------------------- | ------------ | ------------------------------------------ | ---------------------------------------- |
| `tile.TodaySummaryTileService`                 | Tile         | Summary: entry count, mood, latest entry   | Today tile with a Record button          |
| `tile.QuickCaptureTileService`                 | Tile         | Four capture buttons                       | Disabled                                 |
| `tile.OnThisDayTileService`                    | Tile         | Disabled                                   | On this day                              |
| `complication.StreakComplicationService`       | Complication | Campfire or streak, also as a ranged value | The same                                 |
| `complication.QuickCaptureComplicationService` | Complication | Opens Home                                 | Opens the app and starts a recording     |
| `complication.EntryCountComplicationService`   | Complication | Today's entry count                        | Disabled                                 |
| `complication.MoodComplicationService`         | Complication | Today's mood                               | Disabled                                 |
| `complication.OnThisDayComplicationService`    | Complication | Disabled                                   | On this day, long text only              |
| `complication.MarkComplicationService`         | Complication | Disabled                                   | Marks the moment without opening the app |
| `v2.mark.MarkMomentReceiver`                   | Receiver     | Does nothing                               | Saves a mark                             |

[`SurfaceComponents`](src/main/kotlin/app/logdate/wear/v2/surfaces/SurfaceComponents.kt) enables
and disables components through the package manager as the flag changes. The three surfaces that
exist only in the new experience are declared `android:enabled="false"` in the manifest, so they
are absent until the flag turns them on. Four rules apply to every surface:

- **A surface's class name never changes once shipped.** Watch faces and the tile carousel store
  the name. A renamed class disappears from every place it was put.
- **Retire a surface by disabling its component.** Do not delete the class while the flag exists.
- **Change a surface's content in place.** The Today tile is the existing summary tile's service
  with a new layout, so placements made before the redesign keep working.
- **Disabling loses placements.** Turning the flag off enables a retired surface again but does not
  return it to the watch face or carousel it was on.

[`SurfaceUpdater`](src/main/kotlin/app/logdate/wear/v2/surfaces/SurfaceUpdater.kt) redraws the new
experience's surfaces at once after a capture, edit or deletion made on the watch and when the day
changes, and at most once every 5 minutes for changes that arrive through sync. The updater does
nothing until the surface coordinator has read the flag, so when a Data Layer event is what starts
the process, the first sync-driven refresh can arrive too early and is skipped. The system's own
update periods then apply (the campfire complication every 6 hours, the On this day complication
every hour), and the next capture refreshes every surface.

## Reminders

Reminders are explicitly optional. Notification permission alone does not enable them. Established
users with effective legacy nudges retain their choice through migration; a new installation stays
OFF until chosen. The phone publishes learned waking hours for offline watch scheduling, with safe
fallback when sleep access is unavailable. Each device posts at most one reminder per streak day.

Claiming the day's reminder from the other device, and posting on the watch when it is the device
being worn, both require the other device to list the protocol feature `reminders.v1`, and neither
`phoneWatchFeatures` nor `watchSyncFeatures` lists it. The phone therefore posts its own reminders,
the watch posts only the ones its own rules produce, and the phone's reminders are kept off the
watch by excluding their tag from notification bridging. The walk, ride and trip triggers also need activity
awareness; see [the flag section](#two-experiences-and-the-switch-between-them).
[Reminders](../../docs/explanation/wear-companion.md#reminders) in the explanation has the rules.

Watch reminders post on the `MOMENT_REMINDERS` notification channel (id `moment_reminders`,
notification ID 4005) under the tag `logdate.reminder`. The watch applies its bridging exclusion for
that tag at every application start, whether or not the new experience is on, so a reminder the
phone posts for itself never also rings on the watch.

The watch manifest is not gated by the flag. It always declares `ACTIVITY_RECOGNITION`,
`RECEIVE_BOOT_COMPLETED`, `BODY_SENSORS` (up to API 35) and `health.READ_HEART_RATE`, so the store
listing for the watch shows them whether or not the new experience is on. The app asks for each at
the moment a feature needs it: activity recognition when Reminders is turned on, heart rate at the
first mark.

The phone does not collect workouts through Health Connect. Reading exercise sessions there needs
the `android.permission.health.READ_EXERCISE` permission and a Health Connect declaration for the
store listing, and the phone manifest declares only `READ_SLEEP`. Sleep access does not authorize
reading workouts, so enabling reminders or granting sleep access never starts a workout read. The
phone's Reminders settings say that phone workout reminders are not included and point to activity
access on a compatible watch, and `PhoneReminderSignalsModule` supplies no workout reader to the
phone's reminder snapshot. Compatible watches have a separate passive workout trigger, subject to
capability, consent and permissions, and the phone's settings text is not evidence that a given
watch supports it.

The workout trigger is a Health Services passive listener,
[`WorkoutEndListenerService`](src/main/kotlin/app/logdate/wear/reminders/WorkoutEndListenerService.kt).
A registration can be lost when the watch restarts or the app updates, so
[`ReminderRestoreReceiver`](src/main/kotlin/app/logdate/wear/reminders/ReminderRestore.kt) restores
it after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` while reminders are on. That is why the watch
requests `RECEIVE_BOOT_COMPLETED`. The receiver only hands the work to WorkManager, because Health
Services can take longer to accept a registration than a broadcast receiver is allowed to run.
A watch whose Health Services does not report the exercise and passive activity states
declines the registration, so no workout reminder fires there. The
evening check does not depend on it and still runs.

Alarms survive app updates, so
[`LegacyPromptAlarms`](src/main/kotlin/app/logdate/wear/reminders/LegacyPromptAlarms.kt) cancels
the two repeating morning and evening prompt alarms that earlier builds scheduled, deletes their
notification channel and removes their preferences file at every app start. Each step is a no-op
once it has run.

## Sync with the phone

The watch speaks two protocols over the Wear Data Layer, and picks one per peer.

The **earlier protocol** sends each note as a data item and, for a voice note, its audio over a
channel. The watch keeps the note pending until the phone acknowledges that it stored the note and
its audio. This protocol is used whenever the flag is off or the phone does not advertise the newer
one, so every pairing of old and new builds keeps working.

The **newer protocol** is active only when the flag is on, the phone advertises the capability
`logdate_phone_sync_v2`, and the phone lists the specific feature in use
([`WearProtocolGate`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/WearProtocolGate.kt)).
It adds setup without sign-in, receipts that distinguish "on the phone" from "backed up", a library
window the phone publishes to the watch, audio streamed by byte range, marks, shared settings, live
recording and playback state, and removal. It lives under
[`sync/v2/`](src/main/kotlin/app/logdate/wear/sync/v2/).

The paths, payloads, timings and limits of both are in the
[watch and phone sync protocol](../../docs/reference/wear/sync-protocol.md) reference. The server
contract a linked watch uses is in the
[device sessions API](../../server/docs/device-sessions-api.md).

## Location on the watch

The watch reads a location only in the foreground, at the moment something is captured, and only
when Places is on and location access is granted
([`WearLocationCaptureCoordinator`](src/main/kotlin/app/logdate/wear/location/WearLocationCaptureCoordinator.kt)).
It never tracks location in the background. A mark made from a complication runs in the
background, so it has no place, and the phone fills one in afterwards.

The watch depends on the shared `:client:location` module, whose manifest is written for the
phone. The watch manifest removes the two background location permissions and
`LocationTrackingBootReceiver`. It does not remove the module's other phone components, which
therefore still merge into the watch app:

- `DetailedLocationTrackingService`
- `ActivityAwareLocationService`
- `OptimizedBackgroundLocationReceiver`
- `ActivityTransitionReceiver`
- `LocationRecordingActionReceiver`

The watch app's own code never names these components. The shared module's dependency graph is
included on the watch, though, so the build does not prevent them from being reached, and a
component that should not exist on a watch is registered on it. The only check is
[`WearReminderManifestTest`](src/androidTest/kotlin/app/logdate/wear/e2e/WearReminderManifestTest.kt),
which runs on an emulator against the installed app. It covers the boot receiver and the
background location permission. Nothing checks the two location services or the three receivers
listed above.

## Architecture

- `di/`: platform dependencies, protocol, window publication, reminders, continuity, and removal.
- `launch/`: the experience gate, typed launch requests, and startup.
- `recording/`: shared recording-service controller, audio persistence/recovery, and Undo.
- `capture/`: shared preference checks and the capture/write guard used during secure erasure.
- `sync/`: the earlier Data Layer protocol, and the transport helpers both protocols share.
- `sync/v2/`: verified linking, receipts, library membership, media delivery, shared state, and removal.
- `v2/`: responsive watch destinations, moment actions, recorder, playback, and live continuity.
- `presentation/`: the main activity and the current experience's screens. The remote camera
  screen here is also used by the new experience.
- `playback/`: the current experience's voice note player, and the audio cache and resolvers both
  experiences use.
- `reminders/`, `health/`, `location/`: reminder triggers and delivery, heart rate and workout
  sensing, and foreground place capture.
- `tile/`, `complication/`: system entry points and compact summaries.

The watch uses the shared Room schema, migrations, SQLCipher storage, and note repositories. Cloud
credentials and identity/media keys stay in protected device storage. The bounded library window is
phone-owned; watch-owned captures are distinct from evictable phone library copies. Persisted window
reasons supply On this day, the previous completed week's Rewind, and actual starred sources without
requiring the watch to generate Rewinds.

## Build and emulator validation

```bash
# Build only; no connected-device selection
./gradlew :app:wear:assembleDebug

# Host behavior checks
./gradlew :app:wear:testDebugUnitTest :app:wear:ktlintCheck :app:wear:detekt

# Emulator-only instrumentation
./gradlew :app:wear:wearSmallRoundApi34DebugAndroidTest

# Compare existing visual references
./gradlew :app:wear:validateDebugScreenshotTest
```

For manual paired acceptance, first verify both explicit `adb` serials begin with `emulator-`.
Use Android Studio Device Manager's Pair Wearable flow and verify the actual peer in LogDate's
connection diagnostics. An AVD named “Paired Watch” or a saved Android Studio association does not
prove that the running phone and watch are paired. Preserve retained AVD data and credentials.

Repository policy forbids automated installs, instrumentation, and other device commands against
physical hardware. Hardware acceptance is performed by the user using Play internal builds. Do not
uninstall or clear LogDate data to troubleshoot setup. Controlled fixture instrumentation can replace
link/session state; run it on managed devices, not the retained pair used for end-to-end acceptance.

Visual tests cover small/large round screens; the consolidated phone/watch matrix also covers
populated states, large text, RTL, dark/dynamic colors, and reduced motion. Static rendering does not
prove rotary/gesture input, real audio, background behavior, Bluetooth, battery, or paired delivery.
See [Screenshot tests](../../docs/testing/screenshot-tests.md).

## Further reading

- [The watch companion](../../docs/explanation/wear-companion.md): what the watch is for, the
  product contract, and the decisions behind it
- [Watch and phone sync protocol](../../docs/reference/wear/sync-protocol.md)
- [Device sessions API](../../server/docs/device-sessions-api.md)
- [Audio recording feature](../../docs/feature-design/wear-audio-recording.md)
- [Testing strategy](../../docs/testing/introduction.md)
- [Internal hardware dogfood and rollout](../../docs/testing/wear-dogfood-checklist.md)
