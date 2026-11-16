# How LogDate builds a day from location samples

Places shows a person's day as a sequence of **visits** (stretches spent in one place), **journeys**
(the travel between visits) and **gaps** (stretches where nothing was recorded). A phone never
reports any of those. It reports **samples**: a position, a time and a claim about how precise the
position is. This page explains how samples become a day, why the thresholds in that conversion
have the values they have, how a person's corrections are layered on top, and how the result is
stored and synced. The code lives in `client/domain/.../location/history`; recording is described
in the [location module README](../../client/location/README.md), and the encrypted sync API is in
the [location history API](../../server/docs/location-history-api.md).

## The day is computed, not stored

LogDate keeps the evidence and recomputes the day from it every time the day is read. A visit is
never saved as a row. Three kinds of record are saved instead:

- **Observations**: the samples. Each has an id, an owner, the recording device, a timestamp, a
  latitude and longitude, and optional accuracy, speed, bearing, a recorded activity, the time zone
  the device was in, and the capture source.
- **Activity evidence**: transitions such as "entered walking" reported by the operating system.
  They are stored separately from samples because they arrive independently of the sampling rate.
  A sample with no activity of its own borrows the most recent transition from the same device when
  that transition is no more than ten minutes old, is the only one at its timestamp and was an
  "enter" event.
- **Corrections, places, manual visits and memory links**: what the person changed or added. They
  are described under [Corrections](#corrections-and-additions).

Recomputing keeps edits safe. Better reconstruction rules can improve every past day without
touching anything the person wrote, and a correction stays attached to the evidence it was made
about instead of to a result that might change shape.

## From samples to visits and journeys

Reconstruction works on one recording device at a time. Samples with invalid coordinates are
dropped, and samples with the same owner, device and id are counted once. The steps below then run
in order. Every threshold is a field of `HistoryReconstructionParameters`; the table at the end of
this section lists the defaults.

**1. Decide which samples are precise.** Phones report positions with error. A Wi-Fi fix indoors
can land 50 to 150 meters from the phone, and GPS reports a walking-pace speed for a phone lying on
a desk. Only a sample whose reported accuracy is 100 meters or better may decide where something
happened. Samples that are coarser still count as evidence that the phone was recording, and they
are attached to the visit or journey that was in progress, but they never move a visit. A sample
with no accuracy at all is normally treated as coarse. If most of a stretch has no accuracy,
which is true of old recordings made before accuracy was kept, those samples are the best evidence
available and are treated as precise. A precise-looking sample that jumps far away and straight
back is a **spike** and is demoted too. A spike is at least 150 meters (or twice its own accuracy)
from both neighbours while the neighbours are within a stay radius of each other, or it implies a
speed above 70 meters per second both on the way out and on the way back. A sample is only judged
this way when both neighbours are no more than ten minutes away.

**2. Cut at gaps.** If more than ten minutes pass between two samples, the history shows a gap
rather than guessing what happened. There is one exception. Phones that report a fix only after
moving a few meters go quiet exactly when someone sits still, so a silence of up to thirty minutes
that resumes at the same spot continues the visit instead of splitting it. Resuming at the same spot
means both neighbouring samples are placeable (accurate to 100 meters or better, or with no accuracy
reported) and no more than 150 meters apart.

**3. Find stays.** A stay starts at a sample and grows while later samples land within 100 meters
of its centre. A sample's own reported accuracy widens that radius by at most 50 meters. The centre
is an average that weights each sample by the inverse square of its accuracy, so precise fixes
count for more. Samples that wander off and come back within five minutes are kept as drift, unless
two or more of them were beyond 250 meters or the recorded activity says the person was riding.
Once a candidate has lasted five minutes, later samples must also fit the centre it had at that
moment, so a centre that creeps along with a slow walk ends the stay instead of following it. A
candidate becomes a visit only if it lasts at least five minutes, its first third and last third
are no more than 100 meters apart when it has at least six samples (a slow walk keeps moving a
"stay" along with the walker), and the recorded activity is not mostly a vehicle, bus, train or
cycling. Sitting in traffic is not a visit when the recorded activity is mostly riding.

**4. Join stays at the same spot.** Two stays whose centres are within 100 meters become one visit
when every sample between them stayed within 250 meters of the first. A trip to the corner shop
does not split an afternoon at home.

**5. Build journeys.** Whatever lies between stays becomes a journey, and a journey is split where
the recorded way of travelling changes, such as walking to a car. Samples before the first stay or
after the last one join it when they never left its neighbourhood, so a visit's edges are not
trimmed by a few early or late fixes. A single stray fix with no sign of movement is kept as
evidence of the stay and is not turned into a trip. Two visits more than 100 meters apart with
nothing recorded between them are joined by a connecting journey of unknown mode, because the
person must have travelled between them.

**6. Name items after evidence.** A visit or journey takes its id from its first precise sample,
in the form `owner:device:sample`. The same recordings therefore always produce the same ids, and
corrections that point at a sample keep finding their item.

| Parameter                         | Default    | What it controls                                                 |
| --------------------------------- | ---------- | ---------------------------------------------------------------- |
| `maximumGap`                      | 10 minutes | Silence longer than this is shown as a gap                       |
| `maximumQuietStay`                | 30 minutes | Longest silence that can still continue a visit at the same spot |
| `minimumStay`                     | 5 minutes  | How long someone must stay for a visit                           |
| `stayRadiusMeters`                | 100 m      | Distance from a visit's centre that still belongs to it          |
| `accuracyAllowanceMeters`         | 50 m       | Most that a sample's own accuracy can widen the radius           |
| `maximumPreciseAccuracyMeters`    | 100 m      | Coarsest accuracy allowed to place a visit                       |
| `excursionTolerance`              | 5 minutes  | Time away that is drift rather than a trip                       |
| `neighborhoodMeters`              | 250 m      | How far "nearby" extends when joining stays and judging drift    |
| `implausibleSpeedMetersPerSecond` | 70 m/s     | Speed implied both ways that marks a measurement error           |

Travel mode comes from a `TravelModeInferenceProvider`, which takes the samples of a journey and
returns candidate modes with a confidence and a provenance string. The provider in use reads the
activity recorded with the samples and picks the most common one other than still or unknown. It
never calls a journey driving or transit from speed or route alone, so movement with no recorded
activity stays unknown. Replacing the provider changes how journeys are labelled without changing
which visits exist.

## Reading a day

A day is read as a window of its own dates plus 24 hours of context on each side, in the time zone
of the device that is browsing. Observations keep the zone they were captured in, but the day
boundaries follow the browsing device, and times are shown in that device's zone. Records captured
without a zone use the same zone.

**One device per day.** If several devices recorded the same day, their routes are not
interleaved. Each day uses a single recording device: the one the person chose, otherwise the
current device, otherwise the first device by id. A range of days, such as a place's history over
90 days, takes each day from its own chosen device.

**Continuity across midnight.** A stay that began yesterday must still be one visit today, so the
window is extended backward. `HistoryBoundaryReader` pages through earlier observations 256 at a
time and stops where reconstruction could no longer join older samples to the window, which is a
recording gap, or precise samples showing the person somewhere else for longer than drift. Stopping
at a real boundary matters more than a fixed page budget, because truncating a component would drop
the samples that corrections are keyed to. The cost is that a very long uninterrupted recording can
take many pages to read. Reconstruction runs on a background dispatcher, and the paged-in boundary
is reused until the raw samples, the stored records or the activity evidence change, so edits to
notes or places do not repeat it.

**Partial failures.** Notes, saved places and activity evidence are read as optional sections. If
one fails, the day shows what was loaded and the snapshot reports which section failed, so the UI
can say so instead of showing an empty day.

## Corrections and additions

The person can rename a place, change a visit's place or a journey's activity, move an arrival or
departure, add a visit that was never recorded, merge two places, connect a memory to a visit, and
delete. None of these rewrite samples.

- A **correction** is an immutable record with a target evidence id, a field (`LABEL`, `PLACE`,
  `ACTIVITY`, `START`, `END` or `DELETE`) and a value. A new correction for the same target and
  field lists the ids it supersedes. If two devices make different corrections to the same field
  before seeing each other's, both stay unsuperseded, the day reports a conflict, and the item keeps
  its reconstructed value until the person picks one. A new correction supersedes every earlier one
  for that target and field.
- Arrival and departure edits apply only when aimed at an item's first sample. Once later samples
  have joined an item to others, an edit aimed at an inner sample described a smaller visit and
  would cut the joined one short.
- A visit gets a **place** when exactly one saved place lies within 75 meters of its centre.
  Merging places writes a place-redirect correction, re-points existing corrections and manual
  visits to the surviving place, and deletes the merged one, so every visit stays individually
  visible.
- A **memory** (a journal note) is shown under a visit when the person linked it explicitly, or
  when its creation time falls within the visit and its location is within 75 meters of the visit's
  centre. An implicit match counts only if exactly one visit qualifies. An explicit link never
  rewrites the note's own time or location. Adding a memory from a visit opens the editor with that
  visit's place and time, and a draft keeps that context until it is saved.
- A **manual visit** is a person-made visit at a saved place with a start and an end. It is
  shown like a reconstructed one.
- **Deleting a visit** writes a `DELETE` correction for each piece of evidence in it and replaces
  each underlying record with a tombstone, removing the raw sample from the local table in the same
  transaction. A tombstone can never be overwritten by a live record, so a delayed upload from
  another device cannot bring a deleted sample back. Deleting history never deletes journal
  notes.

## What the person sees

The feature module `client/feature/location-timeline` presents the result in two tabs. **Your day**
lists the visits, journeys and gaps of one date in order, with a map and an optional replay of the
day. **Your places** lists places built from recorded visits and from memories that carry a
location. Its collection covers the last 30 or 90 days up to the selected date, can be searched by
name, and shows a place's visits and memories separately, so a place with memories but no recorded
visit in the range says so.

Browsing needs no current location access, because everything shown comes from stored evidence.
The screen explains why a day may be incomplete instead of showing an empty list: recording is off
or paused, location access is missing, capture was interrupted (with an action to resume it), the
day failed to load or a change failed to save (both with a retry), an optional section such as
notes or places could not load, or two devices disagree about a correction. A day with nothing
recorded and a places search with no matches have their own messages.

Each visit or journey offers the edits described above: change the place, activity or time, add a
memory, link an existing memory, and delete. A person can also add a visit that was never recorded.
Deleting asks for confirmation and says that journal memories remain.

## Storage and sync

Every record above is stored locally under the owner's id and the server origin, so signing in to a
different server never mixes histories. Records are uploaded in batches as `LDSE2` encrypted
payloads. The server sees a coarse record type, ids, versions and tombstones, and nothing inside a
payload: coordinates, place names, times, activity details, corrections and memory links all sit
inside the ciphertext. Downloads are paged by a committed cursor, and a page and its cursor are
applied in one local transaction, so an interrupted download resumes where it stopped. The
[private storage explanation](private-storage.md) and [encryption explanation](encryption.md)
describe the sealed envelope; the [location history API](../../server/docs/location-history-api.md)
lists the routes, limits and conflict rules.

The `HUMAN_LOCATION_HISTORY` feature flag (`human_location_history_enabled`, on by default) gates
history browsing and encrypted sync. Recording in the background stays off until the person turns
it on, whatever the flag says.
