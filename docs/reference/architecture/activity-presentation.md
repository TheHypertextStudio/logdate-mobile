# Activity presentation

An activity in LogDate's navigation is a user task that has its own visible context and its own
navigation, such as browsing Home, working through Settings, reading a journal, watching a Rewind or
merging two journals. It is not an Android `Activity`, a window or a second navigation controller. The
app has one back stack and one `NavDisplay`, and the navigation layer decides which activity is visible and how its entries are composed. A scene is one way of composing one or more back-stack entries on screen: a single entry, or several side by side as panes. A hinge is the fold of a foldable device, which can separate the window into two usable areas.

The rule that keeps this coherent is a division of ownership. The navigation layer chooses the active
activity. The activity owns its visible chrome and composition. Content components provide content and
callbacks, and can occupy a pane or the whole region. Having an entry on the back stack does not mean
it is visible: a scene composes only the entries it was chosen for, so unrelated content never
survives on screen merely because it sits underneath.

Home stays on the back stack with its state retained while a replacement activity is on top, but its
timeline, destination navigation, search and creation controls are not composed. Back restores the
previous Home destination, scroll position and selection. [Adaptive Home workspace](home-workspace.md)
describes the panels inside Home itself.

## Declaring a presentation

Every route declares how it presents when it is registered, with metadata built by
`ActivityPresentation.asMetadata()` and read back by `NavEntry.activityPresentation()`. A registration
that declares nothing is a `Replacement`.

| Presentation                    | Meaning                                                                                                                         | Registered by                                                                                                                                                      |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Replacement`                   | A complete activity that suspends whatever is beneath it. Never composed beside another entry.                                  | Rewind playback, journal merge, a note opened without a journal                                                                                                    |
| `Home`                          | The Home browsing activity.                                                                                                     | `HomeRoute`                                                                                                                                                        |
| `Collection(context)`           | A list that can accompany related details, named by a context string.                                                           | Journals overview (`journals`), Library overview (`library`)                                                                                                       |
| `Detail(context, childContext)` | A detail that belongs to the collection whose context matches. `childContext` names the context its own children can accompany. | Journal details (`journals`, child `journal:<id>`), a note opened inside a journal (`journal:<id>`), media details (`library`), a day in the timeline (`timeline`) |
| `SettingsRoot`                  | The Settings category list.                                                                                                     | `SettingsRoute`                                                                                                                                                    |
| `SettingsDetail(section)`       | One Settings section, with the category list attached as metadata.                                                              | Each settings route, through `settingsDetailMetadata`                                                                                                              |

Presentation is declared at registration, rather than inferred from route classes in a decorator or
from the previous route, because two entries that happen to be adjacent on the stack are not
necessarily related. A note opened from a journal appears beside that journal only because the note
declares that journal's context.

## Choosing the visible composition

`LogDateNavDisplay` passes two scene strategies to the display, in order: `SettingsActivitySceneStrategy`
and `RelatedContentSceneStrategy`. A strategy returns a scene that composes more than the top entry, or
null, in which case the next strategy is asked and finally the top entry is shown alone.

**Related content.** `RelatedContentSceneStrategy` composes the last two entries side by side only when
the last entry is a `Detail`, the entry before it is a `Collection` or `Detail` whose context (or
`childContext`) equals the detail's context, and the window can hold both panes. The window can when its
height is at least 480dp and, with no separating hinge, its width is at least 848dp. With a vertical
hinge the two sides of the fold together must add up to 848dp, and a horizontal hinge never composes
two panes. All three thresholds are multiplied by the font scale, so a larger text size asks for more
room before a second pane appears. The scene frames the pair with `ActivityRouteFrame`, then places
them with `AdaptiveWorkspaceLayout`: the collection is the browse panel (320dp minimum, 400dp
preferred, 440dp maximum) and the detail is the focus panel (480dp minimum, 720dp preferred, 960dp
maximum). The font scale multiplies every one of those widths except the focus maximum. A detail reached directly, such as from a link, has no matching collection beneath it and
renders as a complete activity of its own, so a direct link never needs an artificial Home entry.

**Settings.** `SettingsActivitySceneStrategy` handles a `SettingsDetail` on top of the stack. It builds
a scene of the Settings root and the selected section, with the category list rendered from the
metadata the section carries. Two panes appear only when each pane fits: at least 280dp for the
categories, 360dp for the section, and 480dp of height, each multiplied by the font scale. Each pane's width is also padded by the 16dp panel gutters (three across a window without a hinge, two on each side of a vertical hinge), and the gutters are not scaled by font scale. At a
vertical hinge, each pane must fit on its own side of the fold; at a horizontal hinge the section uses
the larger half. Selection and Back survive a resize between the two layouts. When the panes do not fit, the section is shown alone, and the root and section are
separate entries that Back moves between. Because the category list is attached as metadata, a direct
link to a Settings section still shows its categories on a wide window without adding synthetic stack
entries or a second view model.

**Framing.** `rememberWorkspaceRouteDecorator` wraps only `Collection` and `Detail` entries in
`ActivityRouteFrame`, which supplies safe insets, the shared search header and a surface canvas.
`LocalActivityHosted` and `LocalWorkspaceHosted` make the frame a pass-through when an enclosing frame or
workspace already provides it, and it is also a pass-through while the workspace is disabled, so a nested entry never gets a second shell. Every other entry (`Replacement`, `Home`, `SettingsRoot`, `SettingsDetail` and any route that declares nothing) is unframed and draws its own chrome.

## Rules for contributors

- Declare the presentation where the route is registered. Do not add a list of route classes to a
  decorator or scene strategy.
- Keep a single back stack. A scene may render retained entries as panes, but entry lifecycle and view
  model ownership stay with the stack. Do not add a nested `NavDisplay` to obtain a second `Scaffold`.
- A scene strategy chooses from declared presentation, current selection and measured bounds. It must
  not decide that two entries are related because one follows the other.
- Hinge, inset and spacing primitives are shared and independent of Home. Using `WorkspacePanel` or
  the measurement helpers does not enrol a route in Home. `HOME_WORKSPACE_V2` changes how Home is
  presented and does not decide which activity owns a route.
- Test a new presentation with `ActivityPresentationTest` for the metadata, the scene strategies for
  selection, and an end-to-end navigation test for Back, resize and restoration.

## Journal merge as a replacement activity

Merging one journal into another is a replacement activity, registered with `Replacement` and view models
scoped to each visit (`ViewModelsPerVisit`). It shows how an activity owns its whole composition. It has two steps and one irreversible confirmation. The merge view model and use case own its data and sync behaviour; the layout only displays them.

- **No shared chrome.** The merge has no app bar, no Home search and no account action. A floating
  tonal Back button is the only interactive overlay, and content scrolls beneath it, with 72dp of top padding in the scrolling layouts, and in the side-by-side layout when it is under 640dp tall (16dp otherwise), keeping content clear of the button. The activity is bounded to 1120dp wide on
  a `surfaceContainer` canvas.
- **Choose a destination.** The picker has its own search field and a grid of journal covers, each
  with counts below and the cover's update date. It keeps its loading, empty, no-results and
  cleared-search states. Choosing a destination opens the review directly.
- **Review.** The visual area holds only the two canonical journal covers and the animation that
  merges them; the covers carry no role labels or badges. Beside or below them, a decision panel has
  the heading "Merge journals?", two tappable journal containers labelled "Move contents from" and
  "Add them to" that reopen the shared picker for their role, a notice that reads "Your notes and media
  stay. The original journal will be removed. This can't be undone.", and the Cancel and "Merge
  journals" actions. A recovered merge keeps its already committed source fixed.
- **Counts.** Item counts live in the two journal containers: the source's count and how many items the
  other journal already has, and the destination's current count and its count when merged. If the
  journals change while the review is open, the updated counts show with a message and the person
  confirms again.
- **Width.** From 960dp wide, or 760dp wide when the window is wider than it is tall, and below 150%
  text, the visual area and the decision panel sit side by side, the decision panel at 46% of the
  width. The visual area stays between 240dp and 560dp tall. On a tall phone (720dp or more tall, at least 312dp times the font scale wide, and below 150% text) the covers take the height above the decision panel. Otherwise, and at large text, the covers stack at
  a readable width and the whole review scrolls, so every action stays reachable. The confirm button
  is at least 56dp tall, and Cancel and Merge share a row only when their measured labels fit.
- **Folds.** A separating hinge places the whole bounded flow on the larger physical side, so one
  decision is never split across the fold.
- **Behaviour.** The Merge and Cancel actions disable while a merge is committing. Journal names wrap in full at large text and carry accessible labels for their action and count. Focus order follows reading order and right-to-left layouts are honoured. A failure or a changed count is announced next to the decision. The picker keeps its search and scroll through Back from the review. A successful merge removes the obsolete source routes and opens the surviving journal.
- **Motion.** The selected cover shares its identity between the picker and the review through the
  navigation's shared transition scope, and the two covers converge into the surviving book on
  confirmation. Every transition runs instantly when the system asks for reduced motion, and reopening
  an operation that already completed navigates directly to the surviving journal.
