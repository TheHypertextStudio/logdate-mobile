# Activity awareness

Activity awareness lets the phone and the watch notice what someone is doing, so LogDate can react
while it happens and offer something useful when it ends. A reminder that comes due while someone is
walking, riding or in a car is not sent then, and the end of the activity can itself be the day's
reminder. When a walk, ride or trip ends, Home and the
watch's Today screen offer to add a moment from it. When the watch sees someone wake up, the day
starts with a short prompt instead of an empty screen. Moments captured during an activity record
which one, so the timeline can show it later.

The feature is on Android and Wear OS. It is controlled by the `ACTIVITY_AWARENESS` feature flag (key
`activity_awareness_enabled`), which is off by default and appears as the "Activity awareness" row in
Labs, the Settings page for unreleased features, on the phone. The watch follows the phone: the phone lists the flag in its state document only
while it is on, and a watch that sees the flag missing turns its own copy off. With the flag off, no
activity is tracked or shown, a workout that ends on the watch ends in the workout reminder, and
moments are not stamped.

Reminders are off until the person turns them on. At most one comes each streak day (the day as the
person's day-start hour defines it) across both devices, it arrives on the device being worn, and it
never comes outside the waking window (the waking hours set for reminders). [The watch companion page](../explanation/wear-companion.md) describes those rules
and the watch screens this feature adds to.

## What the person sees

| Activity                                                             | While it happens                                                                                              | When it ends                                                                                    |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Moving: walking, running, cycling, or any exercise the watch records | No reminder is sent. Today on the watch puts a "Record a voice note" button first while the recorder is idle. | Home and Today offer to add a moment. The end can also be the day's one reminder.               |
| In a vehicle: a car, bus or train                                    | No reminder is sent. Nothing else changes.                                                                    | Home and Today offer to add a moment from the trip. The end can also be the day's one reminder. |
| Asleep, which only the watch can tell                                | Nothing changes.                                                                                              | Home and Today show a morning start card. Waking up is not a reason for a reminder.             |
| Settling somewhere new after travelling                              | Nothing changes.                                                                                              | The "arrived somewhere new" reminder.                                                           |

An offer from an activity appears once. It goes away when the person adds a moment, dismisses it,
starts another activity, or two hours pass after the activity ended. The morning start shows from
waking until the first moment is saved or two hours later.

The wording is deliberately plain. The phone and the watch use these strings:

| Surface            | Moving                                                                             | Trip                                                                             | Morning start                                                        |
| ------------------ | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Home card          | "You were moving for 40 minutes. How did it go?" with "Add a moment" and "Dismiss" | "Your trip took 25 minutes. Where did you go?" with "Add a moment" and "Dismiss" | "How is your day starting?" with "Record a voice note" and "Dismiss" |
| Today on the watch | "Record a moment", "From the last 40 minutes", and "Not now"                       | "Record a moment", "From your 25-minute trip", and "Not now"                     | "Good morning", "Start the day with a voice note", and "Not now"     |
| Reminder           | "Activity finished", "Record how it went"                                          | "Trip finished", "Record where you went"                                         | None                                                                 |

Durations are whole minutes. Reminder text names neither the kind of activity nor its length.

## Activities and how they are detected

An activity is a stretch of time during which one device's sensors report the same kind of
movement. There are three kinds, in `ActivityKind`:

| Kind         | Must last at least | Counts as ended after its signal has been gone for | Holds reminders |
| ------------ | ------------------ | -------------------------------------------------- | --------------- |
| `MOVING`     | 10 minutes         | 3 minutes                                          | Yes             |
| `IN_VEHICLE` | 8 minutes          | 4 minutes                                          | Yes             |
| `SLEEP`      | 3 hours            | 20 minutes                                         | No              |

The first column is the kind. The second is its minimum duration: an activity shorter than that is
forgotten and never produces an offer or a reminder, so a few steps do not count as a walk. The third
is its end grace: sensors report brief gaps, such as a red light during a drive, and the grace period
keeps a gap from ending the activity. If the signal comes back within the grace period, the activity
continues as if nothing happened.

Walking, running and cycling share one kind, `MOVING`, because they get the same reactions. The
vehicle kind is `IN_VEHICLE` rather than "driving" because the sensors cannot tell a driver from a
passenger, and holding reminders is right for both. Settling somewhere after travel is handled by
`NovelPlaceArrivalDetector` and has no kind of its own.

An `ActivityLog` is one device's record: the activity under way and the last one that ended and
counted. It is a pure function of the transitions it is given, which keeps every rule above testable
without a clock. The log counts an activity from its first signal, so reminders wait at once, and
ends it where its signal stopped, once the end grace has passed. A signal for a different kind ends
the open activity where it was left and starts the new one.

`ActivityTracker` wraps the log with storage and a timer. It saves the log after every change under
the key `activity.log`, because the processes that feed it are short-lived: a broadcast receiver on
the phone and a Health Services callback on the watch each start a process that picks up where the
last one stopped. Its timer ends an activity on time once the grace period passes.

### Where the transitions come from

On the phone, `PhoneActivityAwareness` asks Play services Activity Recognition for enter and exit
transitions for walking, running, cycling and being in a vehicle. It registers while the flag is on
and the `ACTIVITY_RECOGNITION` permission is granted, rechecks the permission each time the app
resumes, and unregisters otherwise. Play services replaces a request only with another one that uses
the same `PendingIntent`, so this request has a `PendingIntent` and receiver of its own. Sharing the
location tracker's would have replaced its request for the same transitions. A transition carries
its time on the elapsed-realtime clock, so the receiver places it on the wall clock by how long ago
it happened.

On the watch, Health Services reports the person's activity state to `WorkoutEndListenerService`.
Exercise becomes `MOVING` and asleep becomes `SLEEP`; the passive state ends whichever of those was
open, and an unknown reading changes nothing. The watch cannot detect vehicles. The listener is
registered while reminders are on, on a watch whose Health Services reports at least the exercise and
passive states, and it is restored after a restart by the same worker that restores reminders.

## Agreeing across the phone and the watch

Each device publishes its own log in the state document it already sends its peer: `PhoneState` and
`WatchState` (the small status record the two devices send each other) each carry an optional `activity`, an `ActivitySnapshot` holding the open activity's kind
and start, and the last ended activity's kind, start and end, as epoch milliseconds. Kinds travel as
names, so a kind a newer device adds reads as no activity instead of making the document unreadable.

Each tracker then combines its own log with its peer's and picks the agreed activity, the one both devices act on,
in this order:

1. `IN_VEHICLE` from the phone, whenever the phone reports it.
2. `SLEEP` from the watch, while the watch is worn.
3. `MOVING` from either device.

The order follows what each device can see. The phone knows vehicles best, only the watch knows
sleep, and the watch reports exercise but not an ordinary walk, so a walk the phone sees has to
count. The watch counts as worn unless its presence says it is off the wrist; the watch's own tracker
always treats itself as worn, because Health Services reports sleep and exercise only while it is.
The most recently ended activity from either log is the last one that ended. A device whose peer is out
of reach uses its own log alone, and a peer's activity under way that began more than 12 hours ago is
ignored, which covers a watch whose battery died mid-walk. While the flag is off the agreed activity
is empty.

## Reminders

`ReminderOrchestrator` reads the agreed activity through `ReminderSignals.activity`.

- While the current activity holds reminders, `decide` returns `Skip(ACTIVITY_UNDER_WAY)`. The check
  comes after the waking-window and one-a-day checks. A skip is not a deferral: nothing remembers
  that a reminder was held. The reminder goes out only if a later evaluation finds it due.
- `MOVING_ENDED` ("Activity finished") and `TRIP_ENDED` ("Trip finished") are the first two
  triggers, ahead of the workout trigger and the rest. A walk, ride, workout or trip that ended
  within the last 30 minutes (`ACTIVITY_RECENCY`) can be the day's reminder. Waking up cannot.
- The phone evaluates reminders when an open mark (a marked moment not yet filled in) or the last known
  location changes. The end of an
  activity is not an evaluation trigger of its own on the phone.
- On the watch, with the flag on, a workout that lasted at least the `MOVING` minimum ends in the
  activity reminder instead of the workout reminder, and the watch's evening check waits while the
  person is moving or in a vehicle. With the flag off, a workout end gives the workout reminder.

A trigger travels between the phone and the watch, and an older watch cannot decode an enum value it
has never seen. The watch therefore advertises the protocol feature `WearFeatures.ACTIVITY_REMINDERS`
(`reminders.activity.v1`), and the phone hands `MOVING_ENDED` and `TRIP_ENDED` only to a watch that
lists it. Otherwise the phone shows those reminders itself. The
[sync protocol reference](../reference/wear/sync-protocol.md#features) describes feature
negotiation.

## Offers and the morning start

`ActivityPresenter` turns the agreed activity into one `ActivityUiState`, so the phone and the watch
show the same thing. The state holds three things: whether voice capture comes first, an optional
suggestion with the activity and its length in whole minutes, and an optional morning start.

- Voice comes first while the agreed current activity is `MOVING`. Only Today on the watch acts on
  it, and only while the recorder is idle.
- A suggestion exists for a `MOVING` or `IN_VEHICLE` activity that ended no more than two hours ago
  (`SHOWN_FOR`), lasted its kind's minimum, was not followed by a moment saved since it ended, and has
  not been dismissed or acted on. Nothing is offered while another activity is under way.
- The morning start exists in the same way for a `SLEEP` activity.
- The state re-evaluates every minute while it is observed, so an offer expires on time.

On the phone the state feeds `GetHomeRecommendationUseCase`. An activity suggestion or morning start
takes priority over every other Home recommendation, becomes a `TimelineSuggestionBlock`, and uses
the card styling the timeline already has, inside the timeline panel. It appears only while
contextual recommendations are enabled in settings. "Add a moment" opens the editor and "Record a
voice note" opens the editor in voice mode. On the watch, the suggestion is a card after Today's
summary, and tapping it starts a recording.

### What is remembered about offers

`SuggestionOutcomes` records, for each offer, whether it was shown, acted on or dismissed, keyed by
the kind of offer and the activity's kind and start time. A showing is recorded the first time an
offer appears, and an offer with an acted-on or dismissed outcome never shows again. That is how each
activity gets one offer. The record is stored on the device as JSON under `activity.suggestions`,
keeps 30 days and is never synced. The record also marks which activity a capture follows, as the
next section explains.

## What a moment records

Notes and marked moments captured while the flag is on record what the person was doing, as
`capturedDuring`. The value is a `CaptureActivity`, either `MOVING` or `IN_VEHICLE`. Sleep is never a
recorded value: a sleeping watch contributes no activity, so only an offer acted on in the last 30
minutes can supply one.

**Stamping.** `CurrentCaptureActivity` supplies the value through the `CaptureActivitySource`
interface, which each capture site takes with `CaptureActivitySource.None` as the default. It
returns the activity under way or, when none is, the activity whose offer the person acted on in the
last 30 minutes. That is how a moment started from an offer records the walk it follows without any
route carrying it. A lookup that takes longer than two seconds records nothing, so a capture never
waits on it. The stamping sites are:

- the watch's audio recordings, quick text notes, mood check-ins and marks (`AudioMomentSaver`,
  `QuickTextHandler`, `MoodCheckInViewModel`, `QuickNoteWriter` and `MarkMomentUseCase`). A mark is
  stamped only if it is saved within two minutes of being made;
- every new phone note, through `AddNoteUseCase`, unless the note already names an activity or is
  written about another time, such as a visit or a mark. When a mark is filled in, its notes take the
  mark's value if it has one.

Recovering an interrupted watch recording does not stamp it, because the activity at recovery time
is not the activity at capture time.

**Wire values.** `CaptureActivity.wireName` is `"moving"` or `"in_vehicle"`. It is what the Room
column `captured_during` stores on the four note tables and `capturedDuring` stores on marked
moments, what the encrypted sync field carries, what the watch's note JSON and data export archives
contain, and what the server stores without reading. The personal-space records use the lexicon's
spelling instead: the `capturedDuring` field on `app.logdate.journal.entry` and
`app.logdate.journal.markedMoment` has the known values `moving` and `inVehicle`, and the mappers
convert between the two. A new activity is a new value, and a value never changes once shipped.

**Reading an unknown value.** Every reader turns a value it does not recognise into null: the
database mappers, the sync decoder, the watch's JSON serializer and archive restore. An older app
therefore keeps working with moments from a newer one. Notes captured before the column existed stay
null, because their activity was never recorded.

**Storage details.** The column is nullable, and `MIGRATION_54_55` adds it without rebuilding any
table. In a note's JSON the field is left out while it is null, because the fingerprints that decide
whether the watch's window of notes must be sent again hash the full JSON, and a new null field
would otherwise change every note's fingerprint once. Sync encrypts the value with the same helpers as `capturedOn`, under
the field id `sync:note:<note id>:capturedDuring`. When two copies of a note conflict, the copy that
has a value wins.

**Display.** Home moments, the day detail and journal entries show "While moving" or "In a vehicle"
after the moment's time, where the watch glyph appears. A Home moment shows it when every note
behind it records the same activity. The label shows only while the flag is on, through
`LocalActivityContextVisible`, which the navigation root provides so that leaf composables never read
feature flags. Rewind does not show it, because its panels come from stored Rewind content rather
than from the notes.

## Permissions and privacy

Activity awareness needs the `ACTIVITY_RECOGNITION` permission on both devices. It is declared in the
manifests of the `client/location` module, which the phone merges, and the watch app. The watch
requests it with the other reminder permissions. On the phone, turning the flag on in Labs requests
it, and while it is missing the Labs row is followed by an "Allow physical activity" row that asks
again. Without the permission the phone has no activity to track.

The Labs row says what the feature does in one line: "Reminders wait while you’re on the move." Raw
activity transitions are read on the device. An activity leaves a device in two ways, both encrypted or
between the person's own devices: the log in the state document the phone and watch exchange, and the
`capturedDuring` value on moments, encrypted like the rest of a moment. Suggestion outcomes never
leave the device.
