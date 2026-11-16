# Semantic Timeline

## Vision

The Semantic Timeline replaces the current modality-based timeline with a **narrative-first, moment-based layout**. When someone looks at the timeline, they should see a stream of consciousness and memories grouped by day, organized by narrative — not a categorized inventory of captures.

## Core Concept: Moments

A **Moment** is a semantically coherent experience — "coffee with Ava", "afternoon at the park", "that quiet evening at home". Moments are the structural unit of the timeline, replacing the current content-type sections ("Captured", "Noted", "Said out loud").

### Notes are not memories

A single text note written at 10pm might describe 5 different moments from throughout the day. Conversely, 3 photos taken at the same cafe within 10 minutes are a single moment. The timeline must parse content semantically, not just group by timestamp or input modality.

### Moment inference

Moments are inferred by `InferMomentsUseCase` using two strategies:

1. **AI-inferred** (primary): An AI model analyzes all notes for a day and identifies semantic moment boundaries, producing contextual labels and time estimates. This handles the complex case of journal dumps that describe multiple events. Audio notes carry the ambient sounds the on-device tagger detected (for example birdsong) so the model can mention them.
2. **Heuristic fallback**: Notes are grouped by time-of-day bucket (before noon, noon to 6 PM, and evening) crossed with the saved place they were captured near (within roughly 500 meters). A group is labeled only when it has a place.

The heuristic runs whenever the AI result is unavailable, errors, returns no moments, or when the day's size exceeds the model's input limit (100,000 characters). That size is the notes' text, captions and place names plus a fixed 256 characters per note, so audio and handwriting notes count too. A day with no entries has no moments.

## Day structure

Each day is two items in the timeline list: a sticky header and the day's content. The content item is keyed by the day's date and the header by the date with a `header-` prefix.

- **One date, one place.** The header shows the date once, pinned while any of that day's content is on screen. Tapping it opens the day. Nothing else in the day repeats the date.
- **Summary is subordinate.** The generated summary sits above the moments in body text, at most three lines, and opens the day when tapped. The user's own content carries the visual weight.
- **One structure at every width.** The list is a reading column no wider than 720dp. The width available to the timeline picks a layout mode (compact below 620dp, medium from 620dp, expanded from 920dp), and the mode changes only how many moments share a row. In compact mode every moment takes a full row. In the other modes a hero moment still takes a full row and the rest flow side by side, each at least 260dp (medium) or 300dp (expanded) wide.
- **Today is abbreviated.** Today shows its first two entries with a control to show the rest. Earlier days show everything.
- **Accent color follows content.** A day's accent comes from what it held, so a run of days reads as varied: photos or video make it primary, otherwise a voice recording makes it secondary, otherwise two or more places make it tertiary, and anything else is primary on neutral surfaces. This is the only role of the media-led, voice-led, place-led and story-led layout values.

### Flowing moments (default)

Normal days use a **flowing layout** where moments are inline sections divided by contextual labels. The first or most significant moment gets hero treatment (larger media, more emphasis). Content flows naturally without hard card boundaries.

### Stacked moment cards (big days)

Birthdays and high-activity days (5 or more moments, or 10 or more notes) use **stacked card surfaces** where each moment is a distinct card with rounded borders, providing maximum visual impact and separation.

### Minimal days

A day with a single text note renders cleanly with just the contextual label and content — no unnecessary structure.

### Every day has a moment

`createSemanticTimelineDayUiState` always returns at least one moment for a day that has notes or places. When inference supplies none (it returns nothing for a day with no entries, which includes a day that only has places), the builder makes one unlabeled hero moment from the day's notes and places. The list therefore has one way to render a day. Screenshot tests build their state through this same function so they depict the path users see.

## Contextual Labels

Labels come from context, never from content type:

| Priority | Source                         | Example                 |
| -------- | ------------------------------ | ----------------------- |
| 1        | Place name                     | "At Blue Bottle Coffee" |
| 2        | Activity (AI-inferred)         | "Morning run"           |
| 3        | Time of day (AI-inferred only) | "That evening"          |

The heuristic never labels a moment with a bare time of day, because that adds nothing the position in the day does not already say. A moment with no place and no AI label has no label. Labels such as "Captured" or "Said out loud" describe the input modality, not the experience, so none is used. For the same reason a day carries no recap strip or metadata chips, and its date appears once, in the sticky header.

## Embedded Metadata

No floating metadata chips or recap strips. Instead:

- **Place names** are part of the moment's contextual label
- **People** are referenced inline within the moment content ("with Ava")
- **Media counts** are implicit from the visible media grid
- Relevant stats for big days are embedded in card surfaces

## Voice moments

A recording appears as a `MomentAudioCard` wherever a moment does: the timeline, a day's detail and a location history item. One component presents every recording, so the transcript, the waveform and playback behave the same everywhere.

- **The transcript is the content.** A recording is read before it is played. The collapsed card shows the transcript's first sentence (two when the first is short) with the waveform beneath as the play control and progress readout. The expanded card shows the full transcript. While a transcript is pending the card says it is transcribing.
- **Presentation escalates through one state machine.** `AudioExpansionController` moves a recording between four states: collapsed, spatially expanded (in place in the list), elevated (a floating preview on long press) and immersive (full screen). `MomentAudioCard` drives the first two. Playback expands a collapsed card and its end collapses it again (`expandOnPlayback`), so the card never takes the tap that opens the day. The overlay states live in the editor module, which depends on window-level system bar handling that `client/ui` does not have; the note viewer opens `ImmersiveAudioScreen` directly.
- **Every waveform is the recording's own.** Amplitudes come from `AudioContextProcessor`, which loads cached amplitudes or extracts and caches them. Nothing writes amplitudes when a recording is captured or imported, so reading `WaveformStorage` directly returns nothing for a recording nobody has opened. The card paints cached amplitudes first. A recording with no amplitudes yet shows a flat baseline and stays playable. A missing waveform is not an error and logs nothing. A failed extraction is logged once, at the `AudioContextProcessor` boundary, and no transcript text or sample data is ever logged.
- **Color comes from when it was recorded.** The recording's daylight period produces its palette (`PaletteGenerator`), defaulting to midday.
- **Transcription starts without being asked.** A card requests a transcript when it appears, and the timeline requests transcripts for the audio in the visible days and the two days below them. See [Markdown and audio transcripts](markdown-and-audio-transcripts.md) for how a transcript is produced and stored.

## Maintainer notes

- **Map visible items to days by key.** `TimelineList` resolves which days are on screen from each item's key (the day's date), not from its list position. Sticky headers mean a day does not occupy one predictable slot. The result feeds transcription prefetch, and position arithmetic would resolve to the wrong day's notes without any visible failure.
- **Do not inject Koin inside an audio card.** The card obtains `AudioContextProcessor` from `LocalAudioContextProcessor`, which `AudioPlaybackProvider` supplies. Previews and screenshot tests have no Koin graph, and a failed injection removes the whole surrounding section. With no processor available the card still draws and plays, without a waveform.
- **Keep the moment builder total.** Because `InferMomentsUseCase` returns nothing for a day with no entries, the day builder must supply a moment itself.
- **Keep the visible-audio rule in one place.** `TimelineTranscriptionPlanner` decides which visible recordings are requested automatically for the Timeline route. `HomeViewModel` applies the same rule to the Home workspace through its own private function, so a change to the rule must be made in both.
- **Keep the sticky header tight.** The list has no uniform gap between items, so a header sits against the content it labels; separation between days is bottom padding on the day item.

## Graceful Degradation

When AI is unavailable (offline, no credentials, error):

- No narrative summary is displayed
- Moments fall back to the time-of-day and place grouping, labeled only where a place gives a label
- Content still flows — media grids, text snippets, and audio notes render normally
- The experience is honest: no fake narrative, just organized content
