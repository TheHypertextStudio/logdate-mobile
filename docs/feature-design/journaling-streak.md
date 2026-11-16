# Journaling streak

The journaling streak is drawn as a campfire. Every day someone adds an entry puts another log on
the fire. A single quiet day lets it burn down to embers, which are still warm, and the next entry
rekindles the fire at the size it had. Only two quiet days in a row put it out, and even then the
app keeps the length of the longest fire and the total number of days journaled, because neither
ever goes down.

The aim is that a streak feels like something being built rather than something that can be lost.
People return to a journal after gaps, so the app never greets a gap with what was lost.

The campfire is controlled by the `CAMPFIRE_STREAKS` feature flag (key `campfire_streaks_enabled`),
which is on by default. With the flag off, each surface falls back to the older counter of
consecutive calendar days. Whether streaks are tracked at all is a separate setting on the streak
screen. When tracking is off there is no fire to show.

## How a day is counted

A streak day is not a calendar day. It starts at the person's day-start hour and runs until the same
hour the next morning, so an entry written at 1:30 AM before bed counts for the day the person is
still living. The hour is the one chosen in the day-boundary setting, or 4 AM
(`DEFAULT_STREAK_DAY_START_HOUR`) when none is chosen. 4 AM is also the default the health layer
uses, so someone who has configured nothing gets the same boundary everywhere.

`Instant.toStreakDay(timeZone, dayStartHour)` subtracts the hour from the local time and takes the
date. `nextStreakDayStart` gives the instant the following streak day begins.

A streak day always starts at a fixed hour. A sleep-based boundary would need a health lookup for
every day of history, and the longest fire and lifetime total read all of history.

An entry is a text, image, audio or video note. A day counts once it has at least one, whatever the
kind. Only each note's creation time is read, so the calculation does not load notes.

## The fire

`CampfireCalculator.calculate(loggedDays, today)` is a pure function from the set of streak days that
have at least one entry to a `CampfireState`. Days after today are ignored.

**A fire is a run of logged days in which each is at most two calendar days after the one before.**
That is the whole forgiveness rule: one empty day between two logged days leaves them in the same
fire, and two empty days in a row end it.

The phase comes from how long ago the last logged day was:

| Last logged day        | Phase     | Meaning                                                 |
| ---------------------- | --------- | ------------------------------------------------------- |
| None                   | `UNLIT`   | Nothing has ever been logged.                           |
| Today                  | `BURNING` | `loggedToday` is true.                                  |
| Yesterday              | `BURNING` | `loggedToday` is false and today is still open.         |
| Two days ago           | `EMBERS`  | Yesterday was missed. Logging today rekindles the fire. |
| Three or more days ago | `OUT`     | Two days in a row were missed.                          |

The other fields of `CampfireState`:

- `runDays` counts the logged days in the current fire, not calendar days, so a fire with an entry
  every other day grows at half speed. It is 0 when the fire is `UNLIT` or `OUT`.
- `size` comes from `runDays`: 1 to 2 is a spark, 3 to 6 a small fire, 7 to 29 a campfire, 30 to 99 a
  bonfire, and 100 or more a beacon. `EMBERS` keeps the size, which is why rekindling restores it.
  `UNLIT` and `OUT` have no size.
- `longestRunDays` is the largest fire across all history, including the current one.
- `totalDaysJournaled` is the number of distinct logged days.
- `isRekindled` is true when the current fire is alive and began after an earlier fire went out.

## Keeping it current

`ObserveCampfireUseCase` combines three live inputs, so no screen has to ask for a refresh:

- the creation time of every entry, from `JournalNotesRepository.observeEntryTimestamps()`;
- the streak-enabled setting; and
- a ticker that emits the current streak day, then waits until the next day-start boundary.

The ticker rereads the time zone and day-start hour at least every 15 minutes, so traveling or
changing the day-start hour moves the boundary within minutes instead of after the day that was
already scheduled. A new entry updates the fire immediately, and the fire changes again when a streak
day ends.

The use case emits `null` when tracking is off. If reading entries fails, it logs the failure with
Napier, emits `null` so surfaces show no fire rather than a wrong one, and reads again after 30
seconds.

## Where it appears

`CampfireViewModel` turns the domain state into a `CampfirePresentation`, a UI-only copy that
`client/ui` draws without depending on the domain layer. It publishes nothing while the flag is
still being read, so a screen never flashes the older counter before switching.

- **Timeline.** A small campfire with the day count sits in the timeline's top-bar status actions.
  Tapping it opens the streak screen. In the Home workspace, "Journaling streak" is also an item in
  the account menu.
- **Streak screen.** A large campfire, a headline and a line of text for the current phase, three
  stats (this fire, longest fire, days journaled), the rule in one sentence, and the tracking
  switch. A "Rekindled" label marks a comeback until the new fire reaches seven days.
- **Profile.** The streak stat is a small campfire with this fire and days journaled.
- **Settings overview.** The streak badge shows the day count of the current fire while there is one.
- **Onboarding completion.** The final screen shows a small lit campfire.
- **Wear OS.** The watch computes its own fire from the notes it holds and shows it on Today and in
  the streak complication, following only the flag because the tracking setting belongs to the
  phone. [The watch companion page](../explanation/wear-companion.md) covers how its day start and
  note window differ from the phone's.

The campfire illustration is drawn on a canvas as a sticker: flat fills, a chunky dark outline and a
white die-cut border, with no gradients or glow. Two crossed logs sit under a two-layer flame whose
height follows the fire's size. Embers are three coals on the logs with a spark rising off them. A
fire that went out is grey logs and a curl of smoke. The flame sways unless the system asks for
reduced motion.

### Text

| State                  | Headline                       | Supporting line                                           |
| ---------------------- | ------------------------------ | --------------------------------------------------------- |
| Unlit                  | Light your fire                | Add anything today — words, a photo, a sound.             |
| Burning, logged today  | Your fire is burning           | N days on this fire                                       |
| Burning, not yet today | Your fire is waiting for today | Add an entry today to make it a N-day fire.               |
| Embers                 | Down to embers                 | Add something today to rekindle your N-day fire.          |
| Out                    | Your fire went out             | Your longest fire lasted N days. Light a new one anytime. |

The streak screen states the rule once: "Miss a day and your fire burns down to embers. It only goes
out after two quiet days in a row."

Streak text never speaks as "we", never uses guilt, and never says the streak was reset. The
strings live in `strings_campfire.xml` in `client/ui`, and `CampfireCopy.kt` chooses between them.
