# Campfire Streaks Design

## Goal

A streak in LogDate should feel like something you are building, not something you can lose. The journaling streak becomes a campfire: every day you add something, you put another log on the fire. Miss a day and the fire burns down to embers, still warm, waiting for you. Only two quiet days in a row let it go out, and even then the app remembers how long your best fire burned and how many days you have journaled in total.

This document covers the first slice of that direction: the forgiving streak model and the campfire visual on the surfaces that already show a streak. Later slices, each with their own spec, cover the memory filmstrip and year-in-pixels calendar, milestone Rewinds, and low-effort ways to add an entry each day.

## Problem

### The streak punishes the people it should encourage

The current streak is a single consecutive-day count. One missed day drops it to zero, and the app says so: the Welcome Back screen shows "Your streak has been reset." A home for memories must welcome people returning after a gap, consistent with the [product purpose](../../product-info/product-positioning.md). Telling them what they lost is the worst possible greeting.

### The number goes stale

`RefreshStreakUseCase` only runs when four specific ViewModels start: account settings, streak settings, Welcome Back, and onboarding completion. Saving an entry does not refresh it, and neither does opening the app or crossing midnight. `ProfileViewModel` reads the cached value without refreshing, so Profile can show a streak that already ended.

### Three surfaces claim things that are not true

- The streak settings screen says milestones "are celebrated in your Rewinds." `WittyRewindMessageGenerator` has streak messages, but nothing passes it a streak length, so they are unreachable.
- The Wear streak complication resolves `CalculateStreakUseCase` from Koin, but the watch's Koin graph never includes the domain module. The lookup throws, the exception is caught, and the complication shows 0 for everyone.
- Welcome Back shows "Your streak has been reset." unconditionally, whatever the streak actually is.

### Late nights count for the wrong day

The streak uses calendar dates. Someone who writes at 1:30 AM before bed is credited for tomorrow, so the next evening's entry does not extend the streak the way they expect.

## Product contract

- A single missed day never ends a fire. It burns down to embers, and the next entry rekindles it at the size it had.
- Two missed days in a row put the fire out.
- The fire grows with its length: spark, small fire, campfire, bonfire, beacon.
- The longest fire and the total number of days journaled are always visible and never go down.
- A new fire after one went out is marked as rekindled.
- A day ends at the user's explicit day-start hour, or at 4 AM when none is set.
- The fire updates as soon as an entry is saved and when the day rolls over.
- No copy speaks as "we", uses guilt, or says "reset".

## Model

### Streak days

`Instant.toStreakDay(timeZone, dayStartHour)` subtracts `dayStartHour` hours from the local time and takes the date. With the default of 4, an entry at 1:30 AM on March 15 counts for March 14. This matches `DefaultLocalFirstHealthRepository.DEFAULT_DAY_START_HOUR`, so a user who has not configured anything gets the same boundary the health layer assumes.

Sleep-based day boundaries are not used for streaks. Resolving them means a health lookup for every day of history, and the longest fire and lifetime total need all of history.

### The campfire

`CampfireCalculator.calculate(loggedDays, today)` is a pure function from the set of streak days that have at least one entry to a `CampfireState`.

A fire is a run of logged days where each logged day is at most two calendar days after the previous one. That is the whole forgiveness rule: one empty day between two logged days leaves them in the same fire.

The phase comes from the most recent logged day, `L`:

| Last logged day | Phase | Meaning |
|---|---|---|
| none | `UNLIT` | Nothing has ever been logged |
| today | `BURNING` | `loggedToday = true` |
| yesterday | `BURNING` | `loggedToday = false`; today is still open |
| two days ago | `EMBERS` | Yesterday was missed; logging today rekindles |
| three or more days ago | `OUT` | Two days in a row were missed |

`runDays` counts logged days in the current fire, not calendar days, so a fire with entries every other day grows at half speed. `size` is derived from `runDays`: 1–2 spark, 3–6 small, 7–29 campfire, 30–99 bonfire, 100+ beacon. `EMBERS` has the same size, so rekindling restores it. `OUT` and `UNLIT` have no size and a `runDays` of 0.

`longestRunDays` is the largest fire across all history. `totalDaysJournaled` is the number of distinct logged days. `isRekindled` is true when the current fire began after an earlier fire.

### Observing it

`ObserveCampfireUseCase` combines three inputs:

- `JournalNotesRepository.observeEntryTimestamps()`, which reads only the `created` column from the text, image, audio, and video note tables;
- the streak-enabled preference; and
- a ticker that emits the current streak day, then waits until the next day-start boundary.

It maps every timestamp to its streak day and runs the calculator. Because every input is a flow, the fire updates when an entry is saved and when the day rolls over, without any screen having to ask for a refresh.

It emits `null` when streak tracking is off, and also when reading the notes fails. Failures are logged with Napier.

## Surfaces

The campfire is gated behind `FeatureFlag.CAMPFIRE_STREAKS`. It is on by default now that every surface renders it. The fixes for false claims never depended on the flag.

- **Campfire illustration.** A Canvas composable draws the fire as a sticker: flat fills, a chunky dark outline, and a white die-cut border, with no gradients or glow. Two crossed logs sit under a two-layer flame whose height follows the fire's size. Embers are three coals on the logs with a spark rising off them. A fire that went out is grey logs and a curl of smoke. The flame sways unless the system asks for reduced motion.
- **Timeline chip.** A small campfire and the day count sit in the timeline's top-bar actions next to the sync indicator. Tapping it opens the streak screen.
- **Streak screen.** A large campfire, a headline and line of copy for the current phase, the three stats (this fire, longest fire, days journaled), the rule in one sentence, and the existing tracking toggle.
- **Profile.** The "Current streak" stat becomes a small campfire with this fire and days journaled.
- **Settings overview.** The streak badge shows the current fire's day count.
- **Onboarding completion.** The hard-coded "1" becomes a small lit campfire: "Your fire is lit."
- **Wear complication.** Shows the current fire's day count with a flame or ember icon.

### Copy

| State | Headline | Supporting line |
|---|---|---|
| Unlit | Light your fire | Add anything today — words, a photo, a sound. |
| Burning, logged today | Your fire is burning | N days on this fire |
| Burning, not yet today | Your fire is waiting for today | Add an entry today to make it a N-day fire. |
| Embers | Down to embers | Add something today to rekindle your N-day fire. |
| Out | Your fire went out | Your longest fire lasted N days. Light a new one anytime. |

The rule, stated once on the streak screen: "Miss a day and your fire burns down to embers. It only goes out after two quiet days in a row."

## Retiring the old streak

Once the flag is on for everyone, `CalculateStreakUseCase`, `RefreshStreakUseCase`, `ObserveStreakUseCase`, `StreakData`, the `streak_cached_value` key, and the flag-off UI paths are deleted.

## Testing

- `CampfireCalculatorTest` covers every phase, the one-day and two-day gap rules, alternating days, longest fire, lifetime total, rekindling, every size boundary, month and leap-year boundaries, and history longer than a year.
- `StreakDayTest` covers the 4 AM boundary, a custom start hour, a fixed-offset time zone, and a daylight-saving transition.
- `ObserveCampfireUseCaseTest` covers the disabled state, a newly saved entry, day rollover through embers to out, and a repository failure.
- Screenshot scenes render the streak screen in every phase, the timeline chip, and the onboarding campfire.
