# The watch companion

LogDate runs on Wear OS watches as its own app, `:app:wear`, alongside the phone app. This page
explains what the watch app is for, the rules it is held to, and the product decisions behind it:
why nobody signs in on a watch, how the recorder tells a hold from a scroll, when a reminder may
ring and on which device, what removing a watch does, and which parts of the phone app exist only
to serve the watch.

Three neighbors cover the mechanics this page leaves out. The
[watch and phone sync protocol](../reference/wear/sync-protocol.md) lists the paths, payloads,
timings and limits of the link between the two devices. The
[device sessions API](../../server/docs/device-sessions-api.md) describes the server contract a
linked watch uses. The [`:app:wear` README](../../app/wear/README.md) maps the module, its build
commands and its tests, and [Audio recording on Wear OS](../feature-design/wear-audio-recording.md)
describes the recorder and its file handling in detail.

## What the watch is for

A watch is the device a person has on them when a moment happens and the phone is in a pocket, a
bag or another room. The watch app exists to make three things possible from the wrist: recording
a thought by holding a button, marking a moment to come back to later, and replaying recent
memories without the phone.

The watch works alone, and phone screens set it up and manage it. When the phone is nearby the two
behave like one product: a recording on the watch shows on the phone while it runs, a mark made on
the watch appears in the phone's timeline with the photos taken around it, and audio plays on
whichever device the person chooses.

Two terms recur below. A **moment** is something a person captured: a voice note, a written note
or a photo. A **mark** is a timestamp saved from the watch with no content yet, which the person
fills in later on the phone with a caption or photos.

## The product contract

Seven rules decide what the watch app may and may not do. A rule that a class enforces links to
that class.

- **No sign-in on the watch.** Signing in is a step between a person and the recorder, and the
  phone already holds the account. The phone sets the watch up instead, without asking the person
  anything on the watch. Someone with a LogDate cloud account gets a watch that backs up on its
  own; someone without one gets a watch that syncs with the phone.
- **Capture is never blocked.** Recording, marking and writing work before setup, without the
  phone and without a network. Whatever was captured before setup joins the account when setup
  finishes. A setup wall in front of the recorder would lose the moment that made the
  person raise their wrist. The one deliberate exception is a watch that is being removed, which
  takes no new captures while it erases itself (see [Removing a watch](#removing-a-watch)). A
  capture can still fail for an ordinary reason, such as a full disk, and then the watch says so.
- **The watch works without the phone.** A bounded copy of the recent library, called the library
  window, lives on the watch, so browsing and replaying recent memories works offline.
- **Status shows only problems.** While sync is healthy the watch says nothing about it. When
  something is stuck, a card in Settings names what failed and what to do. Saying nothing is
  better than saying something untrue. [`WatchStatus`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/settings/WatchStatus.kt)
  has a `Healthy` state that draws nothing.
- **No dead controls.** A button or setting appears only when the feature behind it works on this
  watch with this phone. A control is an optional handler: when the handler is absent, the control is not
  drawn. [`TodayControls`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/today/TodayPage.kt)
  and [`MomentActions`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/moments/MomentActions.kt)
  are built this way, and the phone hides each status line until the watch reports the value behind
  it.
- **Permissions are asked when they are needed**, never up front.
- **Copy is literal.** Text states what happened or what failed, never speaks as "we", and follows
  the locale for language, numbers and time.

## One switch between two experiences

The redesign is large, and every change lands on the main branch, so the earlier watch app and the
new one coexist in the same build. A single feature flag,
[`FeatureFlag.WATCH_EXPERIENCE_V2`](../../client/datastore/src/commonMain/kotlin/app/logdate/client/datastore/featureflags/FeatureFlag.kt),
selects between them. It is off by default. A Play internal testing build that is published on
purpose for testing turns it on by default on both devices, and a choice made in Labs still wins
over that default; the
[README](../../app/wear/README.md#two-experiences-and-the-switch-between-them) describes the
build-time setting.

The flag belongs to the phone. A person changes it on the phone, the phone publishes it to the
watch, and the watch copies it; the watch never writes it back
([`WatchStateMirror`](../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/WatchStateMirror.kt)).
One owner avoids a pair that disagrees about which protocol to speak.

The switch is reachable in release builds, but hidden, because testing on real hardware needs a
store-signed build and a debug menu would not exist there. On the phone, opening Settings, then
About LogDate, and tapping the version row seven times reveals a Labs entry
([`AdvancedSettingsScreen`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/settings/ui/AdvancedSettingsScreen.kt)).
Labs lists unreleased features, including "Redesigned watch app".

Turning the flag on is necessary but not sufficient. Each device also checks that the other one
advertises the newer protocol and the specific feature a screen depends on, and otherwise keeps
using the earlier protocol. A new watch paired with an old phone, or the reverse, therefore keeps
working. The sync protocol reference describes that negotiation.

### A second switch: activity awareness

Some of what this page describes depends on a second flag, `FeatureFlag.ACTIVITY_AWARENESS`, which
is also off by default. Activity awareness means that the phone and the watch notice when the
person is moving, in a vehicle or asleep, and agree on it between them. It is a separate row in
Labs, it is set on the phone like the first flag, and the watch follows the phone: the phone lists
it in its state document only while it is on, so a phone without it, old or new, turns it off on
the watch.

While activity awareness is off, three things on this page do not happen. Today shows no activity
cards and no Record button at the top of its list. A walk, ride or trip that ended is not a reason
for a reminder. And the rule that nothing rings while the person is moving or in a vehicle has
nothing to act on, because no activity is known. Each place below says so where it applies.

## No sign-in, and what replaces it

The phone starts setup; the watch only answers. The phone and watch run a short key exchange over
the Wear Data Layer, the phone hands over the account's encryption keys and a single-use code, and
the watch redeems that code with the server for a session of its own. The session is scoped to
sync: it cannot manage the account, enroll another device or read anything outside the routes a
watch needs. The recovery phrase is never sent to the watch, because it can recover the account
itself.

Without a cloud account the same exchange runs in a pair-only mode. The devices share settings and
state, and no server session is created.

A watch that is already set up for a different account is refused. The phone posts a notification
that names the watch and tells the person to erase LogDate on it before setting it up with this
phone. Adopting it silently would merge one person's captures into another person's account.

The exchange, its timings and the server's limits are in the
[sync protocol reference](../reference/wear/sync-protocol.md) and the
[device sessions API](../../server/docs/device-sessions-api.md).

## Today and the screens behind it

The app opens on **Today**.

Today is one short list
([`TodayPage`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/today/TodayPage.kt)). The date
sits in the curved time text at the top. Below it are the campfire (the streak indicator, when
streaks are on), a summary line such as "3 moments · 2 places · 4:12 voice", led by the emoji of
the day's latest mood check-in when there is one, and the latest moment. A Moments button opens
the list of recent moments, a button group holds Mark and +, and Record is the edge button at the
bottom of the screen.

Two kinds of card can appear between the latest moment and the Moments button
([`TodayUiState`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/today/TodayUiState.kt)).

- **A phone card**, when setup is unfinished. If the paired Android phone does not have LogDate,
  the card opens LogDate's store listing on that phone. If the phone has LogDate but this watch
  has no account link with it, or the watch's own backup is waiting for the phone to link it, the
  card opens the Watch page in the phone's settings. This is how the watch asks for setup without
  a setup screen: the card sits in the list, capture works around it, and tapping it continues on
  the phone. The card clears only for an account link, so it also stays on a watch that is paired
  with a phone that has no cloud account.
- **An activity card**, only while [activity awareness](#a-second-switch-activity-awareness) is
  on. After a walk, ride or trip that lasted long enough to count, Today offers to record a moment
  about it, with a "Not now" button beneath. The offer shows once per activity and goes away when
  the person records, dismisses it, starts another activity, or two hours pass. After a night's
  sleep the same slot holds a morning start, until the first capture of the day or two hours
  later. Tapping either card starts a recording. While the person is moving, a Record button also
  leads the list, so voice capture comes before anything else on screen
  ([`ActivityPresenter`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/activity/ActivityPresenter.kt)).

The summary follows fixed rules, shared with the Today tile and the phone's preview of it
([`TodaySummaryCalculator`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/today/TodaySummary.kt)).
A mood check-in describes the day rather than being a moment of it, so it is not counted as a
moment. Two moments are at the same place when they name the same saved place or were captured
within 150 meters of each other. Voice time is the length of every recording added up.

Today covers the calendar day, from local midnight, and rolls over when the clock, date or time
zone changes. The campfire counts streak days instead, which start at the hour the person chose on
the phone, or 4 AM until the phone has shared it. The watch computes its campfire from the notes
it has. Since it holds only a bounded window of the library, a long run can read shorter on the
watch than on the phone.

Navigation is a back stack of routes
([`WearRoute`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/navigation/WearRoute.kt)), and
swiping right dismisses the top screen:

| Route          | Screen                                                                                                                | Reached from           |
| -------------- | --------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| `Home`         | Today                                                                                                                 | App launch             |
| `Flow`         | Voice and text moments from the last 7 days                                                                           | Today's Moments button |
| `Memories`     | On this day and this week's Rewind                                                                                    | Flow                   |
| `Archive`      | Moments older than 7 days that are on the watch, starred first                                                        | Flow's Earlier button  |
| `Settings`     | Status card, Haptics, Places, Reminders, More on your phone (shown when the phone advertises `phone.settingsLink.v1`) | Flow                   |
| `Moment(id)`   | One moment: player, transcript, place, actions                                                                        | Any list               |
| `CaptureSheet` | Write, Mood, Phone camera                                                                                             | Today's +              |
| `PhoneCamera`  | The phone's camera, driven from the watch                                                                             | The capture sheet      |

An edge button reaches its full size only at the end of a scrolling list, so Today is a short list
of its own and Flow, the moment list, is a second route opened by a button. While a recording runs, the recorder's own items take the place of
the summary on Today.

A launch from a tile, a complication, a notification or the phone always opens the requested
screen above Today, so going back lands on Today
([`toV2BackStack`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/navigation/WearLaunchRoutes.kt)).

## The recorder gesture

Recording is one gesture on one button, because a watch is used with one finger and often without
looking.

- **Hold** Record to talk; lifting the finger saves.
- **Slide up** 56 dp while holding to lock the recording, so it continues after the finger lifts.
  A haptic confirms the lock, and Stop, Pause and Discard appear.
- **Tap** to start a recording that is locked from the beginning.

The hard part is telling a hold from a scroll, since Record sits at the end of a scrolling list.
The gesture only watches until the system long-press timeout passes. If the list takes the touch,
or the finger travels past the touch slop, the gesture steps aside and the list scrolls. After the
timeout the gesture owns every touch event, so nothing scrolls, and a finger that drifts sideways
off the button does not end the recording. Using the system timeout, instead of a fixed number,
means the gesture respects the accessibility setting for touch-and-hold delay.

The recorder starts the moment a finger touches Record, before the gesture has decided anything,
so the first word is never lost. If the touch turns out to be a scroll, that recording is
discarded without a trace.

For someone using a screen reader, Record is a single control with one action, "Start recording",
which behaves like a tap.

After a save, Undo is available for 5 seconds and a row of five feelings for 4 seconds, so a mood
takes one tap. Discard needs two taps within 3 seconds. The details are in
[Audio recording on Wear OS](../feature-design/wear-audio-recording.md).

## Marks

Mark saves the current moment with nothing but its time, time zone and device, and confirms with
a haptic. The mark is written first and enriched afterwards, so a slow sensor can never lose it:
the place (when Places is on) and a single heart-rate reading are added within a few seconds if
they can be read. A mark made from a complication runs in the background, where neither can be
read, so it has only its time and the phone fills in the place later from its own location
history.

A mark is its own synced record, not a kind of note. Older phones decode notes with a closed list
of types; an unknown note type would be dropped without acknowledgement and the watch would resend
it forever. A separate record that older clients never request avoids that.

A mark counts toward the campfire, and toward "has anything been logged today", only once it is
filled in. An open mark is a promise to come back, not content. Filling a mark in on the phone,
with a caption or an attached photo, creates the note that the streak counts. Because a mark is
not a note, it does not appear in the watch library either.

## Permissions at the point of use

Each permission is requested the first time something needs it
([`WatchPermission`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/permissions/WatchPermission.kt)):

| Permission                          | Asked                                   | How often                             |
| ----------------------------------- | --------------------------------------- | ------------------------------------- |
| Microphone                          | At the first recording                  | Each time recording starts without it |
| Notifications                       | At the first locked recording           | Once                                  |
| Heart rate                          | At the first mark                       | Once                                  |
| Location                            | When Places is turned on in Settings    | When turned on                        |
| Notifications and physical activity | When Reminders is turned on in Settings | When turned on                        |

The split between "each time" and "once" follows what the feature needs. Recording cannot work
without the microphone, so the request repeats. The recording's entry on the watch face needs
notifications, but recording works without it, so that request is made once and never nagged. It
is made at the first _locked_ recording because that is the first time the watch face entry and
its Stop control matter.

Granting a permission is not consent to a feature. Reminders stay off until the person turns them
on, whatever the notification permission says.

## What the watch library shows

The phone decides what is on the watch. It publishes a library window made of recent days (7, 14
or 30, defaulting to 14), On this day, this week's Rewind, and starred items, within a budget of
400 notes or 8 MB of note bodies
([`WatchWindowPolicy`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/watch/WatchWindowPolicy.kt)).
The phone owns the window so that one device, the one with the whole library, makes the choice.

The watch then applies its own rule about what to show
([`LibraryWindow`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/library/LibraryWindow.kt)):

- **Voice and text only.** Photos and videos stay on the phone. A watch screen does them no
  justice and their bytes would crowd out recordings. Mood check-ins are not listed as moments.
- **The last 7 days are the list.** Anything older that is on the watch sits behind Earlier, with
  starred moments first.
- **The watch never generates a Rewind.** The Rewind card appears only when the phone has sent
  one. Building a weekly summary needs the whole week's library, which the watch does not have.
- **Opening a moment does not start playback.** The person may be somewhere quiet.

Audio for moments the watch did not record is stored through one boundary,
[`WatchMediaCache`](../../app/wear/src/main/kotlin/app/logdate/wear/playback/WatchMediaCache.kt),
which refuses to store audio that would leave less than 200 MB free. The floor exists so that
playback can never take the space a new recording needs; capture always wins over the library.
Background downloads follow a policy the person picks on the phone, Wi-Fi or charging by default,
and tapping Play always works regardless of the policy.

## Playback and where audio plays

A voice note's screen has a player and, one swipe up, its transcript, place and actions. Turning
the crown scrubs in 5-second steps with a tick at each step. Speed cycles through 1×, 1.5× and 2×,
and Skip silence is a switch. Speed is a standard command of the media player. Skip silence is a
player setting with no standard command, so it is the one custom command the shared playback
service accepts
([`PlaybackSessionCommands`](../../client/media/src/androidMain/kotlin/app/logdate/client/media/audio/PlaybackSessionCommands.kt)).

Play on offers the outputs the system knows about. It opens the system output switcher, or
Bluetooth settings on a watch without one, and is not drawn when neither exists. The app never
forces audio to the watch speaker itself: the platform treats the built-in speaker as unsuitable
unless the person explicitly chose it, and the system picker is where that choice is made.

When a phone that supports it is in reach, the moment can also play on the phone or open there for
editing. A handoff reports success only after the phone confirms it. A command that was merely
sent is not shown as done.

## Reminders

The reminder policy has four parts.

**Off until turned on.** One switch, shared by the phone and the watch, enables reminders. An
update must not silently take away something a person chose, so one group starts with reminders
on: someone who finished onboarding before the update and had all three of the earlier settings
enabled, which were contextual recommendations, ambient prompts and capture nudges
([`LegacyReminderMigration`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/reminders/LegacyReminderMigration.kt)).
Everyone else, including every new installation, starts with reminders off.

**Tied to a moment.** A reminder rings only inside the person's waking hours, and for the first of
these that applies
([`ReminderOrchestrator`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/reminders/ReminderOrchestrator.kt)):

1. a walk, ride or trip that ended within the last 30 minutes;
2. a workout that ended within the last 30 minutes;
3. arriving somewhere new and staying at least 10 minutes;
4. a campfire in embers with nothing logged today and at most 3 hours left before bedtime or the
   end of the streak day, whichever comes first;
5. the evening wind-down, the last 90 minutes before bedtime, when nothing was written today or
   marks are waiting to be filled in;
6. a draft waiting to be finished;
7. a memory worth revisiting.

The last two are the draft-rescue and memory-recall nudges. They fall under the same switch and
the same daily limit as the rest.

The first trigger exists only while
[activity awareness](#a-second-switch-activity-awareness) is on, and so does a rule that sits in
front of the whole list: nothing rings while the person is moving or in a vehicle. With activity
awareness off, no activity is known, so that rule never holds a reminder back and the list starts
at the workout trigger.

Waking hours come from the person's learned sleep times and fall back to 07:30 to 22:30. The
campfire rule counts to bedtime as well as to the end of the streak day because the streak day
ends at 4 AM by default, and "three hours before the day ends" would otherwise always fall while
the person is asleep.

**At most one per streak day, across both devices.** The limit counts streak days, the same days
the campfire counts (see [Today](#today-and-the-screens-behind-it)), so a reminder at 1 AM belongs
to the evening before it. Each device records when it last posted a reminder and publishes that
time to the other, and the later time wins. When the peer advertises `reminders.v1` (see below), a device claims the day
from its peer before posting, so two devices double up only when they cannot reach each other.

**On the device being worn.** The phone asks the watch for one off-body sensor
reading. If the watch is reachable, supports reminders and reports that it is on the wrist in a
reading no more than 2 seconds old, the phone asks the watch to post the reminder, with a Record
action
([`NudgeTargetPolicy`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/reminders/NudgeTargetPolicy.kt)).
Otherwise the phone posts it under the tag `logdate.reminder`, which the watch excludes from
notification bridging, so a reminder never rings on both. A watch that cannot tell whether it is
worn sends reminders to the phone.

**The claim and the hand-off to the watch run only when the other device advertises the protocol
feature `reminders.v1`.** Both the current phone build and the current watch build advertise it,
so the watch claims the day from the phone before posting, and the phone hands due reminders off
to a worn watch when reachable. If either peer is an older build without `reminders.v1`, that
device falls back to applying its own daily limit using the published last-reminder times. The
[sync protocol reference](../reference/wear/sync-protocol.md#features) lists which features each
build advertises.

The watch also has a small set of rules of its own
([`WatchReminderRules`](../../app/wear/src/main/kotlin/app/logdate/wear/reminders/WatchReminderRules.kt)).
It posts a reminder within 30 minutes of a workout ending and, while activity awareness is on,
within 30 minutes of a walk, ride or trip that lasted long enough to count. It also runs an
evening check in the last 2 hours of the waking window, which fires only when the phone is
unreachable and nothing was captured today. With the fallback hours that check runs at 20:30.

The phone does not read workouts. Reading exercise sessions on the phone would need a health data
declaration for the store listing and a new permission, so the workout trigger comes only from a
watch that can report activity.

## Tiles and complications

A tile is a glanceable screen in the watch's carousel; a complication is a slot on a watch face.
Both follow one privacy decision: there is no extra gating on the watch. Tiles and complications
show full previews, because a watch is worn by its owner and a preview that hides its content is
not worth a slot.

With the new experience on, the watch offers:

- **Today tile:** the day's summary with a Record button.
- **On this day tile and complication:** a moment from this date in a past year.
- **Campfire complication:** the streak, also as a ranged value whose color follows the fire.
- **Record complication:** opens the app and starts a locked recording. It has to open the app,
  because a microphone foreground service cannot start from the background.
- **Mark complication:** saves a mark and confirms with a haptic, without opening the app.

Three surfaces are retired: the Entry Count and Mood complications, and the Quick Capture tile,
which the Today tile's Record button replaces.

Retiring a surface follows a policy, because watch faces and the tile carousel refer to a surface
by its class name
([`SurfaceCatalog`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/surfaces/SurfaceComponents.kt)):

- **A class name never changes once shipped.** Renaming one silently removes it from every watch
  face it was placed on.
- **A surface is retired by disabling its component, not by deleting its class.** While the flag
  is on, the retired three are disabled and the new ones enabled; while it is off, the reverse.
- **A surface whose content changes keeps its component.** The Today tile reuses the existing
  summary tile's service, and the campfire's ranged value was added to the existing streak
  complication, so existing placements keep working.
- **Disabling is not reversible for placements.** Turning the flag off enables the retired
  surfaces again but does not put them back where they were. A watch that toggles the flag loses
  those placements.

Surfaces refresh at once after something the person does on the watch, and at most once every 5
minutes for changes that arrive through sync, which is the pace complications are meant to update
at.

## Color and haptics

Both devices use the system's dynamic color where it exists. On a watch that means Wear OS 6 and
newer, so most watches show the fallback: a neutral graphite scheme in which orange appears only
in the campfire's flame, which the campfire draws itself. The phone's fallback is the same family
([`Color.kt`](../../client/theme/src/commonMain/kotlin/app/logdate/ui/theme/Color.kt)). Keeping
every role neutral makes the one warm element on screen mean something.

Haptics are reserved for changes of state: recording started, locked, paused, resumed, saved, a
mark made, a limit approaching, an action rejected. The watch plays the same vocabulary of events
the phone uses. A setting chooses between all haptics and essential ones (recording, marks and
errors only).

## The phone side

The watch needs the phone for setup, for the library, and for everything that involves editing.
The phone remains the editor: it resolves marks, writes captions, suggests photos and attaches
them. Conflict resolution never happens on the watch.

### The Watch page and onboarding

Settings has a Watch page with a preview of the watch's Today tile, controls for what the library
holds and when audio downloads, capture settings, reminders, a guide to tiles and complications,
a connection check, and Remove watch
([`settings/ui/watch/v2`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/settings/ui/watch/v2/)).
The connection check runs a list of checks and offers the fix for each one it can repair, instead
of listing tips. Onboarding has an optional watch step when a Wear OS watch is connected
([`OnboardingWatchStep`](../../client/feature/onboarding/src/commonMain/kotlin/app/logdate/feature/onboarding/ui/OnboardingWatchStep.kt)).
With the redesign on, a dismissible card on Home names the watch and opens the Watch page. It
appears once, when a connected watch has finished verified setup and the onboarding watch step
has not handled it
([`WatchReadyOnPhoneCard`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/settings/ui/watch/v2/WatchReadyOnPhoneCard.kt)).

The phone describes the watch only from what the watch reports. A status line stays hidden until
the watch has sent the value behind it under a feature it advertises, so an older watch, or one
with the switch off, shows only its name and whether it is nearby. Each line has the feature that
makes the watch's number mean something
([`WatchStatusResolver`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/settings/ui/watch/WatchStatusResolver.kt)):

| Feature         | What it unlocks on the Watch page                                              |
| --------------- | ------------------------------------------------------------------------------ |
| `receipts.v1`   | The sync line, and the warning that moments did not send                       |
| `window.v1`     | The line that says how many days of the library the watch holds                |
| `link.cloud.v1` | Whether the watch backs up on its own or through the phone, and "set up again" |

A watch that needs attention shows that problem in place of the ordinary lines.

### Shared preferences

Settings that both devices need are shared preferences: each has a wire name that never changes,
either device may write it, the most recent write wins, and the phone wins a tie
([`SharedPreferenceKey`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/watch/SharedPreferenceKey.kt)).

| Key                          | Default            | Meaning                                          |
| ---------------------------- | ------------------ | ------------------------------------------------ |
| `reminders.enabled`          | off                | The reminders switch                             |
| `capture.places`             | off                | Whether marks and watch captures get a place     |
| `capture.moodAfterRecording` | on                 | Whether the five feelings show after a save      |
| `window.days`                | 14                 | Days of recent moments on the watch: 7, 14 or 30 |
| `window.onThisDay`           | on                 | Whether the library includes On this day         |
| `window.rewind`              | on                 | Whether the library includes this week's Rewind  |
| `window.audioDownloads`      | `wifi_or_charging` | When audio downloads in the background           |

The two keys that involve a person's location or attention default to off. A stored value this
version cannot read, such as one a newer app wrote, reads as the default rather than failing.

### Which notes carry a capture source

A note can record the kind of device it was captured on
([`CaptureDevice`](../../client/repository/src/commonMain/kotlin/app/logdate/client/repository/journals/CaptureDevice.kt)).
The decisions around that field are deliberate and easy to undo by accident:

- **Notes written on the phone carry no capture source.** The note model is shared with the
  desktop and iOS apps, where "phone" would be wrong, and only the watch is ever shown. The phone
  stamps `watch` on notes that arrive from the watch without a source
  ([`WatchNoteIngestor`](../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/WatchNoteIngestor.kt)).
- **The source is encrypted in sync.** It travels as its own encrypted field,
  `sync:note:<id>:capturedOn`, the scheme captions and places already use. Where a moment was
  captured is information about the person, so the server stores only ciphertext.
- **The source is left out of content comparisons.** It never changes after capture, and an older
  server returns none. Comparing it would turn an acknowledged upload into a conflict.
- **Archives store it as an open string.** A device name a newer app wrote must never make an
  older reader reject a notes file, so the archive schema does not restrict the value, and an
  unknown value reads as "unknown".
- **A moment shows the watch glyph only when every note behind it came from the watch.** A mixed
  moment is not a watch moment. The glyph is switched on once, at the navigation root, so leaf
  screens never read the flag
  ([`LocalWatchProvenanceVisible`](../../client/ui/src/commonMain/kotlin/app/logdate/ui/provenance/CapturedOnWatch.kt)).

### Filling in a mark's place

A mark made from the watch face has no place. The phone fills it in from its own location history:
the sample nearest in time within 10 minutes either way, accurate to 100 meters or better, genuine
and not mocked, and only when Places is on
([`EnrichMarkLocationUseCase`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/moments/EnrichMarkLocationUseCase.kt)).
Photos taken within 15 minutes either side of a mark are suggested for attaching.

### Associating the watch with the phone

The phone registers the watch with Android's Companion Device Manager so the system can wake
LogDate when the watch comes into range. The request filters on the watch's Bluetooth name and
asks for a single device, so the system shows one confirmation instead of a device list
([`WatchAssociationRequestFactory`](../../app/compose-main/src/androidMain/kotlin/app/logdate/client/watch/WatchAssociationRequestFactory.kt)).
Presence is treated as a scheduling hint only: the work it wakes still checks the verified link
before publishing anything. The request also sets the watch device profile.

### Removing a watch

Removing a watch must not lose captures, and must not leave an account's keys on a device the
person no longer controls. Those two goals conflict when the watch is out of reach, so removal has
two paths
([`WatchRemovalCoordinator`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/removal/WatchRemovalCoordinator.kt)).

Before either, the phone shows a review screen with the watch's last reported count of unsent
moments from the watch's state document, so the person decides knowing what is at stake. The drain
step below still checks and drains live pending captures when the watch is reachable.

- **The watch is reachable.** The phone asks it to drain: send every pending capture. Removal
  continues only when the watch reports nothing pending. The phone then revokes the watch's server
  session, asks the watch to erase itself, removes the system association and forgets the link. A
  step that fails stops the sequence and is reported by name; removal is never shown as complete
  when it is not.
- **The watch is unreachable**, for example lost. The phone revokes the session first, then queues
  an erase request that the watch acts on when it next connects. The watch also erases itself as
  soon as the server tells it the session was revoked. Captures that never left that watch are
  lost, and the phone says so.

The watch refuses new captures while a removal is in progress, and records the removal outside
its ordinary preferences so that a crash during erasure cannot restore the link.

**Signing out on the phone removes every linked watch**, by the same procedure, before the phone's
own credentials are cleared
([`PasskeyAccountRepository`](../../client/data/src/commonMain/kotlin/app/logdate/client/data/account/PasskeyAccountRepository.kt)).
A watch that kept syncing to an account the phone had left would be a device nobody could manage.
If a watch cannot be removed safely, sign-out fails and asks the person to keep the watch nearby
and try again.
