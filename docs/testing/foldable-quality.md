# Foldable Quality

This guide describes what LogDate must do on a foldable phone, how the code supports it, and how to
verify it. It follows Android's
[adaptive app quality guidance for foldables](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/experiences/foldables).
Read it before changing a screen that shows long-form content, plays media, takes attachments, or
opens in its own window.

## Postures and the hinge

A foldable reports a posture, which is how far it is open and where the fold runs. LogDate models
three, in `FoldablePosture`:

| Posture    | What it is                          | Where the hinge runs                                        |
| ---------- | ----------------------------------- | ----------------------------------------------------------- |
| `Standard` | Flat, fully open, or not a foldable | No separating hinge                                         |
| `Book`     | Half open like a book               | Vertical, splitting the window into a left and right pane   |
| `Tabletop` | Half open like a laptop             | Horizontal, splitting the window into a top and bottom pane |

`rememberFoldableLayoutInfo()` (`client/ui/.../foldable/FoldableSupport.kt`) returns a
`FoldableLayoutInfo`: the posture plus, when there is a fold, a `FoldableHingeInfo` with its
bounds, orientation, state, whether it separates the window, and whether it hides pixels. On
Android it reads AndroidX Window. Desktop and iOS return a non-foldable value, and a test can
override it with `LocalFoldableLayoutInfoOverride`.

A separating hinge is space the screen does not have. Primary content, touch targets, sheet
handles, snackbars, and navigation never sit on it, and a single piece of content never straddles
it. The shared primitives in `client/ui/.../adaptive/` do that work, so a screen asks for a layout
instead of computing hinge geometry:

| Primitive                | Behavior                                                                                                                                                                       |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `AdaptivePaneLayout`     | Splits list and detail panes around a vertical or horizontal separating hinge                                                                                                  |
| `FoldableBookLayout`     | Two panes either side of a vertical hinge. Put navigation, metadata, filters, toggles, or queues in one pane and the primary content in the other.                             |
| `FoldableTabletopLayout` | Two panes above and below a horizontal hinge. Keep the content, such as video, a camera preview, or a story, above the hinge, and put controls, metadata, and chrome below it. |
| `HingeAwareOverlay`      | Pins one blocking overlay (the lock screen, an in-app update banner, a postcard viewer) to a single physical pane, so it never spans the fold.                                 |

Each split primitive takes a `standardContent` slot that renders when there is no separating
hinge, so a flat or non-foldable device sees the ordinary layout. A split is also skipped when a
pane would be too small to use. The Home scene switches to its two-pane form under a vertical
hinge; the choice is made from the hinge geometry in `ActivitySceneSelection.kt`
(`ActivitySceneGeometry`).

Some screens deliberately stay single-pane, because a forced split would make them worse:

- The location timeline in Tabletop keeps its scroll-collapsing map column. A 50/50 horizontal
  split would freeze the map and break the collapse interaction. In Book it still splits the map
  and list across the vertical hinge.
- The People directory is a flat navigation list whose detail is a separate route, so it stays a
  readable, width-constrained pane.
- The People inbox is a short, self-contained review list.
- The cloud account steps are each a single centered card, and the onboarding flows already cover
  their postures.

## What every screen has to do

- **Postures.** A screen renders without clipped content, unreachable controls, or hinge-obscured
  UI in Standard, Book, and Tabletop. In Book, long text and reading-heavy flows get a readable
  layout: single-side reading, a reading and detail pair, or hinge-aware gutters. In Tabletop,
  media playback, recording, review controls, and onboarding actions move to the reachable half.
- **State continuity.** Changing posture keeps the task state, scroll position, selections, drafts,
  playback state, and focused input. A posture change and an activity recreation must leave an
  editor draft intact.
- **Camera.** LogDate does not show the front and back cameras at the same time across a
  foldable's two screens, so that part of Android's guidance does not apply. The in-app preview is sized, oriented, and cropped correctly folded and unfolded, and
  portrait and landscape rotation produce no stretched, mirrored, blank, or stale frames.
- **Picture-in-Picture.** PiP applies to video only (see the
  [media quality checklist](../reference/standards/media-quality-checklist.md#camera-microphone-and-output-routes)),
  and on Android 12 and later the shared video player enters it automatically while video plays.
  Entering and leaving it works folded and unfolded, in portrait, landscape, and multi-window; the
  PiP controls are accessible and stay in step with playback; and resizing the window while in PiP
  does not break playback or the return to the app.
- **Attachments and notification entry.** A shared item, shortcut, widget, playback notification,
  Rewind notification, or deep link opens its target route in any posture and orientation and in
  multi-window, without duplicating content or dropping the route.
- **Multiple instances.** The editor opens in separate windows. `EditorActivity` is resizable and
  launched with `FLAG_ACTIVITY_NEW_DOCUMENT` and `FLAG_ACTIVITY_MULTIPLE_TASK`, so each window is
  its own task with its own route arguments, attachments, and draft, with no overwritten extras or
  wrong Recents entries.

## How to verify

Posture is simulated in instrumented tests rather than needing a folding device. The helper
`FoldablePostureTestSupport` (`app/android-main/src/androidTest/.../e2e/`) wraps AndroidX Window's
`WindowLayoutInfoPublisherRule`, which replaces the app's window-layout stream so a test can publish
a half-open Book or Tabletop fold or a flat window against the activity under test. A suite wraps
that rule together with its Koin and Compose rules.

| Class                                           | What it checks                                                                                                                                                                                                               |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FoldablePostureLayoutE2ETest`                  | The real `MainActivity` day-reading screen in Book, Flat, and Tabletop, and the Book-to-Flat change: the selected content stays clear of the hinge and Home chrome does not return                                           |
| `FoldableStateContinuityE2ETest`                | Editor text survives a posture publish, an unfold and refold, and a posture publish followed by recreation                                                                                                                   |
| `FoldableMultiInstanceEditorE2ETest`            | The primary editor keeps its draft across a posture publish, and a second editor window is its own task                                                                                                                      |
| `NotificationAttachmentEntryRestorationE2ETest` | Each entry route (new-entry draft, day deep link, incoming text share, audio-playback note, location timeline, event detail, Rewind detail, memory recall day, export and restore) survives a posture publish and recreation |
| `VideoPiPEntryExitE2ETest`                      | Tapping the PiP control moves the activity into PiP and back                                                                                                                                                                 |
| `MultiWindowEditorE2ETest`                      | Editor intent flags and split-screen support                                                                                                                                                                                 |

Run a class on the managed-device groups described in [Development](../DEVELOPMENT.md): `smokeDevices`
for ordinary changes, and `deviceMatrix` (which includes the Pixel 9 Pro Fold profile) when adaptive
behavior changes.

```bash
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.FoldableStateContinuityE2ETest \
  --console=plain
```

Layout correctness in Book and Tabletop is also covered by the screenshot tests in
`app/android-main/src/screenshotTest/.../audit/adaptive/`, which render each hinge-aware screen
with a synthetic fold. See [Screenshot tests](screenshot-tests.md).

Two details of the PiP suite are easy to break. Entering PiP detaches the activity's Compose
hierarchy from instrumentation, and it does not come back after leaving PiP, so every Compose
assertion in `VideoPiPEntryExitE2ETest` must run before the PiP tap and everything after it is
asserted against the activity. Whether the player renders correctly afterwards is left to the
screenshot tests.

### What an emulator cannot show

An emulator publishes a fold; it is not a real hinge. Evidence from one is enough for behavior that
does not depend on the hardware: state continuity, multi-instance launch, and attachment or
notification entry restoration, which should pass on a phone and a tablet profile. These need a
person with a physical foldable, and a screenshot or recording of the result:

- the real fold and unfold animation and hinge occlusion;
- flex mode and the hand-off between the cover screen and the inner screen;
- Picture-in-Picture while the device folds or unfolds;
- resizing a window with the system drag handle in multi-window.

Screenshot-only or emulator-only coverage of these is not proof that they work on a device.

Tests never run on a physical Android device; the hardware checks above are performed by a person with a foldable.
