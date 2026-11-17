# Navigation motion

How screens and surfaces move into each other in LogDate. Read this before adding a shared-element transition, changing a
route's enter or exit animation, or giving a screen a new origin for an existing transition.

The guiding rule is that a motion should show where something came from. When a card, tile or button has a stable place on
screen and the thing it opens is that same object enlarged, the object itself morphs from the source to the destination, and
the screen change around it stays still. Where nothing on screen maps onto the destination, the screen change is an ordinary
forward or back movement. A screen never runs both at once: a slide layered over a morph makes the morph read as noise.

## One shared scope for the whole navigation display

`LogDateNavDisplay` wraps the `NavDisplay` in one `SharedTransitionLayout` and publishes it as `LocalSharedTransitionScope`.
An entry decorator in the same file republishes each entry's animated content scope as `LocalNavAnimatedVisibilityScope`. A shared-bounds or shared-element modifier needs both: the
first lets two entries share an element, and the second says when each entry is entering or leaving.

Feature code reads both locals and attaches the modifier only when both are present. Previews, screenshot tests and the
desktop editor window provide no navigation scope, so a source or destination that is composed without one simply does not
animate. The desktop editor window (`EditorWindow`) provides its own `SharedTransitionLayout` for editor-internal motion.

## Route-level transitions

Only the entry editor declares route-level transitions (`editorRouteTransitionMetadata`, set through `NavDisplay`'s
transition metadata). Every other route uses the display's own transitions and the scene strategies that decide how a route
is presented. The editor's transitions encode the rule above:

- Opening the editor from the Home route enters with no animation of its own, and the outgoing Home scene stays composed
  until the transition finishes (`EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished`). Keeping
  the source alive is what lets the shared bounds animate from it.
- Returning to Home fades the editor out over the Home scene (`EnterTransition.None togetherWith fadeOut()`).
- Opening or leaving the editor from any other route slides horizontally, from the right when going forward and back to the
  right when popping. Predictive back uses the same transitions as a normal pop.

A route that wants a morph from one origin and a slide from every other should follow this pattern, keyed on the class of
the route on the other side of the transition.

## Shared-bounds keys

A key must be identical at the source and the destination and unique per object, or two items would try to share one
element. Every key below contains the identity of the thing being opened.

| Key                             | Source                                                                                   | Destination                                            | Notes                                                                                                                                                   |
| ------------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `entry-creation:<sourceId>`     | A create action: the Home creation control, the workspace create action and pull gesture | The entry editor                                       | 350 ms with `FastOutSlowInEasing`; corners flatten from the source's shape (round or large) to square on the route's clock; a snap under reduced motion |
| `library-media-<mediaId>`       | A Library photo or video tile                                                            | The media detail view                                  | 400 ms with `FastOutSlowInEasing`; a 4 dp clip during the transition; audio tiles do not take part                                                      |
| `journal-container-<journalId>` | A journal cover                                                                          | The journal detail scaffold                            | A shared element on both ends                                                                                                                           |
| `note-viewer-<noteId>`          | A note card in journal detail, and a memory preview card in the location timeline        | The note viewer for text, image, video and audio notes | `RemeasureToBounds`; attached to the card surface only, not to timestamps or membership badges                                                          |

The `TransitionKeys` object in `:client:ui` builds the library, journal and note keys, and `entryCreationKey` builds the
entry-creation key. Build keys with those functions; do not spell them by hand.

Entry creation gives every create button a lease, `EntryCreationSource`, that owns one pending visit. A source launches
once, becomes available again only after its screen is visible and settled, and then takes a fresh id. That keeps the
variants of a control, such as the header and rail forms of the same button, from sharing one key, and keeps a quick
double tap from starting two morphs. `EntryCreationLauncher.withSource` records the source for the synchronous moment the
route is pushed, and the route carries the id and shape to the editor. A destination that is already visible by the time
the editor appears reads the same progress through `rememberEntryCreationVisibilityProgress`.

## Motion inside the editor

The editor has its own motion that runs inside its screen, on the editor's inner `SharedTransitionLayout` when the editor is
shown without a navigation scope, and on the navigation scope otherwise.

- **Block surfaces.** The add-block controls (picker tiles and footer buttons) and the blocks they create share
  `block_surface_<blockId>`. Each control pre-generates the block id, so the control and the new block already agree on a key.
  A block expanding to focus is a shared-element morph on a spring with no bounce (damping ratio `NoBouncy`, stiffness 300,
  `blockFocusSpring`), and its corner radius shrinks in step with the focus progress. With reduced motion the progress snaps.
- **Predictive back.** `MainEditorContent` drives its display states (empty picker, expanded block, scrolling list) from a
  `SeekableTransitionState`, so the predictive back gesture scrubs the morph while the finger moves and then either commits
  or cancels it. Plain route exit is left to navigation. With reduced motion the scrubbed progress is held at zero and the
  state snaps.
- **Journal selector.** The journal picker in the editor is a trigger card that expands into a list card. The two cards
  share the surface key `journal_selector_surface` as shared bounds, so the surface morphs in size, shape and color. When
  exactly one journal is selected, its title is a shared element (`journal_title_<journalId>`) between the trigger and its row
  in the list. The list expands upward from the trigger's bottom edge over 400 ms with a fade, and shrinks back over 350 ms;
  the inner content crossfades. The selected row's checkmark scales and fades in and out. During predictive back the list is
  clipped from the top while its bottom edge stays anchored, and its height is capped by the window height so it fits compact
  and landscape screens. The easing is the Material emphasized decelerate curve for entering and emphasized accelerate for
  leaving. The shape is shared by both cards so their outlines stay continuous mid-animation.
- **Audio blocks.** The waveform and the play and pause button are shared elements between the collapsed and expanded
  layouts; see [Audio presentation](./audio-presentation.md).

## Phones and wider layouts

On a compact phone a detail route is a full-screen page, so a forward and back movement is correct, and a shared morph
replaces it where a stable origin exists. On wider layouts `RelatedContentSceneStrategy` places a detail beside its
collection when the collection's presentation context matches the detail's, so opening the detail activates a pane
rather than pushing a page. A full-width slide on such a route would show a page push where the screen only gained a
pane, so a route that can appear in a pane should rely on a morph or on the display's own transitions, not on a
horizontal slide.

## Reduced motion and other platforms

Motion that is purely decorative or that depends on bounds interpolation consults `rememberSystemReduceMotion`. Entry
creation snaps instead of animating, and editor back gestures stop scrubbing. A new transition has to do the same.

- **Android** supports predictive back through Navigation 3. A route whose enter is a morph has to give its pop and
  predictive pop the same treatment as its forward transition, as the editor does.
- **iOS** adds an edge-swipe back gesture to the navigation display (`iosEdgeSwipeBack`) in place of system predictive back.
- **Desktop** has no system predictive back; its editor opens in a separate window with only editor-internal motion.
- **Wear** is driven by state: surfaces animate properties such as alpha, scale and color rather than entering and leaving.

## Where the shell moves independently

The navigation rail and bottom navigation bar animate in and out themselves, by sliding and fading in
the direction of their edge. This motion is separate from route transitions, so when a route override changes how a screen
enters, check that it does not fight the shell as the bars appear or disappear.
