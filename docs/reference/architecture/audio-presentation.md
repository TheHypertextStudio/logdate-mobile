# Audio presentation

How a recorded voice memory looks and behaves wherever LogDate shows one: the timeline, a day's detail, the editor and the
full-screen player. Read this before changing an audio card, the waveform, or the way a recording expands.

A recording is treated as an object with its own identity, not as a row in a media player. Three ideas follow from that.
The words lead, because a recording the reader has to play before learning anything about it is a poor memory. Each
recording looks like itself, because its waveform is drawn from its own audio and its colors come from when it was made.
And the design is restrained: expanding a recording changes the size of its container and lets the content reflow. It does
not scale content, because scaling distorts text and waveforms, and it adds no decoration such as pulsing rings.

## Where the data for a card comes from

Every audio surface draws the recording's own amplitudes. `AudioContextProcessor` (in `:client:ui`) produces an
`AudioContext` for a recording URI: the amplitudes, the detected segments, the daylight period, and the palette.

- **Amplitudes.** An `AmplitudeExtractor` decodes the audio and returns normalized values between 0 and 1, 300 samples by
  default, as RMS levels. The Android extractor emits partial snapshots while it decodes, so a long waveform fills in from
  the left; other platforms emit the finished list once. The result is cached by `WaveformStorage`, which keeps the packed
  floats (about 1.2 KB per recording) in a cache directory rather than in the database.
- **Extraction is lazy.** Nothing is computed when a recording is saved. The first card that shows a recording asks the
  processor, which loads the cache or decodes the file and then writes the cache. The first emission carries the palette
  and any cached amplitudes so the card paints immediately. Reading `WaveformStorage` directly gives an empty waveform for a
  recording that has not been shown yet, so cards always go through the processor.
- **Segments.** `SegmentDetector` derives points of interest from the amplitudes each time they are loaded; they are not
  stored. A segment is a speech onset (a rise above twice the silence threshold after silence), a significant pause (silence
  of at least 500 ms, marked at its midpoint) or a volume peak (a local maximum above 0.7). Silence is any level below 0.1.
- **The processor reaches the card through `LocalAudioContextProcessor`**, which `AudioPlaybackProvider` supplies. Cards read
  it from the composition rather than from Koin so that previews and screenshot tests, which have no Koin graph, still
  compose. Without a provider the waveform stays a flat line.

## Color comes from the time of day

`PaletteGenerator.generate(DaylightPeriod)` returns an `AudioPalette`: the two ends of the waveform gradient, the fill for
the played portion, an accent for the playhead and controls, a background for the full-screen player, and a legible content
color for text drawn on that background. There are seven palettes, one per `DaylightPeriod`: dawn, morning, midday,
afternoon, golden hour, evening and night. Their moods follow the light: warm pinks and ambers in the morning, a clear blue
at midday, deep orange in the golden hour, purple in the evening, and muted blues at night. Location type and the content of
the audio do not affect the palette.

`DaylightClassifier` (in `:client:awareness`) chooses the period. With coordinates it uses that day's sunrise, sunset and
solar noon, so a winter morning that is still dark at 8 a.m. is not colored as daytime:

| Period      | When                                          |
| ----------- | --------------------------------------------- |
| Dawn        | Thirty minutes either side of sunrise         |
| Morning     | After dawn, until two hours before solar noon |
| Midday      | Two hours either side of solar noon           |
| Afternoon   | After midday, until one hour before sunset    |
| Golden hour | The last hour before sunset                   |
| Evening     | Sunset until two hours after                  |
| Night       | Everything else                               |

Without coordinates it falls back to fixed clock hours: 5 to 6 dawn, 7 to 10 morning, 11 to 13 midday, 14 to 16 afternoon,
17 to 18 golden hour, 19 to 21 evening, and night otherwise. `MomentAudioCard` uses the period its caller supplies and treats
a missing period as midday.

## The four presentation states

`AudioExpansionState` names four ways to present one recording, in two groups, and `AudioExpansionController` is the state
machine that moves between them.

- **Inline** states sit in the flow of the timeline or editor: `COLLAPSED` (the transcript excerpt with a compact waveform,
  a play button and the duration) and `SPATIAL_EXPANDED` (the full transcript, a taller waveform with segment markers, the
  controls, the duration and the time of day). They toggle in both directions.
- **Overlay** states sit above the content: `ELEVATED` (a floating card on a dimmed scrim) and `IMMERSIVE` (the full-screen
  player).

The controller remembers which inline state was showing, so dismissing an overlay returns there. With `expandOnPlayback`
(the default) a collapsed card expands when playback starts and collapses again when playback finishes. A long press moves
an inline state to `ELEVATED`, releasing or dismissing returns to the inline state, and the explicit full-screen action
moves any state to `IMMERSIVE`.

Where each state is rendered:

- **`MomentAudioCard`** is the card used in the timeline, a day's detail and location history. It renders
  `COLLAPSED` and `SPATIAL_EXPANDED`, driven only by playback through `expandOnPlayback`. The card body is deliberately not
  tappable, because the card sits inside a day that opens when tapped; a card that took that tap would leave a day whose only
  content is a recording with no way to open it.
- **`ImmersiveAudioScreen`** is the full-screen player, shown by the audio note viewer. Its background is a gradient built from
  the palette. Controls auto-hide after four seconds of playback, a tap on the background toggles them, and starting to
  scrub shows them again. It rearranges itself for tabletop and book postures through the foldable layouts.
- **The editor's completed audio block** (`AudioBlockContent`) is its own component with a collapsed and an expanded layout
  rather than the expansion controller, because it also carries the caption, a delete action and a timed transcript that seeks the audio.

### Shared elements between states

Where a state change happens inside one composition, the waveform and the play and pause button are shared elements, so
they move between layouts instead of fading. In `AudioBlockContent` their keys are `waveform_<blockId>` and
`play_pause_button_<blockId>`, shared across an `AnimatedContent` that swaps the collapsed and expanded layouts. The block
uses the app-wide `SharedTransitionScope` when one is provided through `LocalSharedTransitionScope`, and creates its own
`SharedTransitionLayout` otherwise. A transition between inline and overlay states has to share a scope that encloses both,
which is why the scope is hoisted to the root rather than created inside a card. See
[Navigation motion](./navigation-motion.md) for the app-wide scope.

## The waveform and scrubbing

`BezierAudioWaveform` draws the amplitudes as a smoothed filled path with a vertical gradient from the palette. The played
portion is drawn at full strength and the rest at 30 percent alpha, with the playhead as a line with a soft glow. Segment
markers are small dots. With no amplitudes it draws a flat line.

Given an `onSeek` callback, a tap on the waveform jumps straight to that position and a horizontal drag seeks continuously.
During a drag the waveform calls `onCrossSegment` whenever the drag is within 100 ms of a segment. It does not snap the
position to segments.

`HapticController` defines five scrubbing moments (`onDragStart`, `onDragging`, `onCrossSegment`, `onSnapToSegment`,
`onDragEnd`) and `AndroidHapticController` implements them with vibration primitives where the device supports them: a tick
on drag start, a faint throttled tick while dragging (at most every 50 ms), a medium tick across a segment, a stronger tick
or click on a snap, and a quick fall on release, with plain vibration or none on older Android versions. Haptic strength has to be
judged on a physical device.

## Playback

On Android, `AudioPlaybackService` hosts a Media3 player in a media session service, and `AndroidAudioPlaybackManager`
controls it through a `MediaController`. The player requests audio focus, so playback ducks or pauses for other apps' audio and calls, and it
pauses when headphones disconnect; Media3 also keeps player state consistent across Android versions. Common code
depends only on the `AudioPlaybackManager` interface. Cards read the
shared playback state from `LocalAudioPlaybackState`, so only one recording plays at a time and every card for the playing
recording shows the same progress.
