# Keyboard, Pointer, Drag and Drop

Status: partial.

- **Built:** the shared shortcut catalog (Compose handlers, Android's Meta+/ helper, and the desktop
  menu bar), right-click menus at the pointer, tooltips on the workspace account button, dropping
  text and media from other apps into the editor, dragging photos and videos out as real files, and
  writing in the editor with a pen.
- **Planned:** a new-window shortcut.

## What it is

Android runs on tablets with keyboards, Chromebooks, and desktop-windowing setups with a mouse. On
those devices people expect the same input conventions as any desktop app:

- **Keyboard shortcuts**, discoverable through the system shortcut helper (Meta+/ on Android, the
  menu bar on desktop).
- **Right-click** (secondary click) for the same menu a long-press opens.
- **Hover feedback and tooltips** on icon-only buttons.
- **Drag and drop between apps**: dragging a photo from Files into an entry, or a LogDate photo into
  a chat.
- **Stylus handwriting** in text fields.

## What the person sees

### Keyboard shortcuts

| Shortcut         | Action                                                                   | Where    |
| ---------------- | ------------------------------------------------------------------------ | -------- |
| Ctrl+N           | New entry                                                                | Anywhere |
| Ctrl+Shift+R     | Record a voice entry                                                     | Anywhere |
| Ctrl+F           | Search                                                                   | Anywhere |
| Ctrl+,           | Settings                                                                 | Anywhere |
| Ctrl+S           | Save entry                                                               | Editor   |
| Esc              | Close the editor                                                         | Editor   |
| Ctrl+1 to Ctrl+5 | Open the first to fifth Home tab, in the order the navigation shows them | Home     |

Planned: Ctrl+Shift+N for a new editor window.

On macOS desktop builds, Cmd replaces Ctrl. Pressing Meta+/ on Android lists all of these under
LogDate, here on a Pixel Tablet running Android 17 with a USB keyboard:

![Android's Keyboard shortcuts sheet with LogDate selected in its category list, showing the LogDate group (New entry, Record a voice entry, Search, Settings) and the Home group (Ctrl+1 to Ctrl+3 visible)](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/shortcut-helper--tablet-api37.png)

Android 17 gives the focused app its own category, with the app's icon, next to System and
Multitasking. It lists an app's shortcuts only for keys that the attached keyboard has.

### Pointer

- Right-clicking a journal cover, an entry in a day, a media thumbnail, or a postcard opens its menu
  at the pointer.
- Long-pressing a journal cover, an entry in a day, or a postcard opens the same menu without also
  opening the item. Long-pressing a media thumbnail starts a drag instead (see below), so its menu is
  on right-click.
- An entry's only action is "Open in new window", so it has no menu on devices that cannot open
  another LogDate window.
- Hovering an icon-only button shows a tooltip naming its action: the account button, the editor's
  Back, Drafts, Save, and More options buttons, and the Places sheet's expand and full-view buttons.
  A long press shows the same tooltip on touch. Screen readers read the same names.

A mouse resting on the account button in a desktop window's title bar:

![A LogDate window on the Android desktop with a tooltip reading Account, backup and settings under the account button in the title bar](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/account-tooltip--tablet-api37.png)

A long-press on a journal in the tablet's Journals carousel, on Android 17:

![The Journals screen on a tablet with a menu over the Summer in Lisbon cover offering Open journal and New entry in a new window](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/journal-context-menu--tablet-api37.png)

And on an entry in a day, opened beside the timeline:

![A day opened beside the timeline, with an Open in new window menu under its entry](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/entry-context-menu--tablet-api37.png)

### Drag and drop

- Dropping text, photos, videos, or audio onto the editor adds them to the entry. A highlighted drop
  area shows while dragging over it.
- Pasting adds a copied photo, video, or audio file to the entry on Android, whether with a
  keyboard (Ctrl+V) or from the text menu that holding a finger on the text opens. Any copied text
  is pasted into the text as usual. When the clipboard holds media, the text menu shows Paste, or
  Paste as attachment when it also holds text that the menu's own Paste inserts, or when the file
  was copied as a link that does not name its type.
- When a dropped, pasted, or keyboard file cannot be added, the editor says so: a file type an entry
  cannot hold, or a file that could not be read.
- GIFs, stickers, and other images sent from the on-screen keyboard are added to the entry as
  files, the same as a pasted photo, rather than as links.

A photo copied in another app and pasted from the text menu on a phone:

| The text menu                                                                                                                                                                           | Once pasted                                                                                                                                                                    |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ![A new entry with the text menu open over its empty text field, showing Paste](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/paste-menu--phone-api37.png) | ![The entry with the pasted photo of a dog wrapped in a blanket](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/paste-menu-photo--phone-api37.png) |

Gboard suggests a copied image only to a field that accepts images. Tapping its suggestion sends
the photo the same way it sends a GIF or sticker. The photo opens in the entry and the keyboard
closes, so the photo and its caption are in view; tapping the caption brings the keyboard back:

| The keyboard's suggestion                                                                                                                                                                                | Once sent                                                                                                                                                          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ![A new entry with the keyboard open, suggesting the copied image above its keys](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/keyboard-image-suggestion--phone-api37.png) | ![The entry with the photo the keyboard sent](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/keyboard-image-inserted--phone-api37.png) |

A GIF picked from Gboard's GIF panel arrives the same way, as a file in the entry:

| The keyboard's GIF panel                                                                                                                                             | Once sent                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ![A new entry with Gboard's GIF panel open below it](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/keyboard-gif-panel--phone-api37.png) | ![The entry with the GIF the keyboard sent, above the still-open GIF panel](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/keyboard-gif-inserted--phone-api37.png) |

The same photo pasted with Ctrl+V on a tablet with a USB keyboard:

![A new entry in the editor with the pasted photo of a dog wrapped in a blanket](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/paste-photo--tablet-api37.png)

- Dragging a photo or video out of LogDate's Library sends the actual file, not a path string, to
  another app or to another LogDate window. A long press picks it up.

A photo dragged from the Library into a new entry in a second LogDate window on the Android 17
desktop:

| While dragging                                                                                                                                                                                                                                       | Once dropped                                                                                                                                                                  |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ![The Library on the left with its photo being dragged over a new entry in an editor window on the right, which is outlined as a drop target](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/drag-out--tablet-api37.png) | ![The editor window with the photo dropped into the new entry](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/drag-out-dropped--tablet-api37.png) |

A photo dragged from the Files app into a new entry, with the two side by side on the Android 17
desktop. While it is over the editor, the editor is outlined in the theme's primary color:

| While dragging                                                                                                                                                                                                                                                            | Once dropped                                                                                                                                                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ![The Files app on the left with a photo being dragged, its name label following the pointer, and the LogDate editor on the right outlined as a drop target](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/cross-app-drag--tablet-api37.png) | ![The editor with the dropped photo in the entry, still filed to Summer in Lisbon](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/cross-app-drop--tablet-api37.png) |

### Stylus

On Android 14 and later, writing with a pen over an entry's text turns the handwriting into typed
text, through the keyboard's handwriting mode (Gboard on most devices). The keyboard draws the ink
while the pen is down, then types what it read where the pen wrote, and shows its handwriting toolbar
instead of the on-screen keyboard.

"hello" written with a pen in a new entry on a Pixel Tablet running Android 17, while the pen is still
down:

![A new entry with "hello" drawn in thin handwriting ink over the empty text area, and Gboard's handwriting toolbar at the left edge](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/stylus-handwriting--tablet-api37.png)

And once the pen lifts:

![The same entry with "hello" typed as text at the start of the entry](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/stylus-handwriting-typed--tablet-api37.png)

A person can also start writing on two controls that open a text field rather than being one:

- **The entry page's Write choice.** Writing on it starts a text entry as soon as the pen touches
  it, and the handwriting is typed into that entry.
- **Home's search pill.** Writing on it opens search with the handwriting as the query.

| While writing                                                                                                                                                                                                                                           | Once typed                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ![Writing "hello" starting on the Write choice: a text entry opened as soon as the pen touched it, and the ink stays over the choice](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/stylus-write-choice--tablet-api37.png) | ![A new text entry with "hello" typed in it](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/stylus-write-choice-typed--tablet-api37.png)               |
| ![Search, opened as the pen touched Home's search bar, with "hello" handwritten over its field](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/stylus-search--tablet-api37.png)                                             | ![Search with "hello" typed as the query and the entry it finds](../../../screenshots/android-os-integration/keyboard-pointer-drag-and-drop/stylus-search-typed--tablet-api37.png) |

Menus and tooltips draw in popup windows, which preview screenshots cannot render, so their
screenshots are real captures from Android.

## How it works

### One shortcut catalog

`AppKeyboardShortcut` in `client/ui/src/commonMain/kotlin/app/logdate/ui/keyboard/AppKeyboardShortcuts.kt`
lists each action with its key chord, label, and scope (`App` or `Editor`). A chord's "primary"
modifier matches either Ctrl or Command, so one catalog works on every platform. Three places read
it, so a shortcut is defined once:

1. **The Compose key handler.** It replaces the single Ctrl+F handler in
   `app/compose-main/src/commonMain/kotlin/app/logdate/navigation/LogDateNavDisplay.kt` with a lookup
   in the catalog.
2. **`onProvideKeyboardShortcuts`** in `MainActivity` and `EditorActivity`. Android calls it when the
   person opens the shortcut helper, and `appKeyboardShortcutGroups` (client/ui androidMain) turns
   the catalog into `KeyboardShortcutGroup`s named "LogDate" and "Entry editor".
3. **The editor**, whose `EntryEditorContent` handles the `Editor` scope: Ctrl+S saves when the
   Save button would, and Esc behaves like the back button.
4. **Home**, whose `HomeScreen` handles the `Home` scope. Each tab shortcut carries a `homeTabIndex`,
   counted in the visible tabs, so Ctrl+4 still opens the fourth tab shown when Library is hidden.

Desktop builds get the `App` shortcuts through the same Compose handler, and the main window's menu
bar lists them: New entry and Record a voice entry under File, Search and Settings under Go.
`ShortcutItems` (app/compose-main desktopMain) labels each item and binds its accelerator from the
catalog, with Command as the primary modifier on macOS and Ctrl elsewhere. A menu item does not open
a screen itself; it sends the shortcut through `AppShortcutRequests` to `LogDateNavDisplay`, which
opens it with the same code as the keys (`AppKeyboardShortcut.appRoute`), so a menu item and its keys
cannot disagree, and both do nothing while LogDate is locked.

The editor's shortcuts are scoped to the editor, so they never fight with text editing keys.

### Right-click

`ContextMenuArea` is an expect/actual in `client/ui`. Android, desktop, and (until its native bridge
lands) iOS all use `MaterialContextMenuArea`, which opens on long-press and on a secondary click. A
right-click menu opens at the pointer: `contextMenuOffset` converts the click position into the
dropdown's offset from the item's bottom-left corner.

Long-press needs care because the content inside is usually clickable. Compose delivers pointer
events to the innermost element first in the normal (Main) pass, and a `clickable` card consumes the
press there, so a long-press detector on the wrapper in that pass never fires. The wrapper instead
watches the Initial pass, which runs outermost first, and consumes nothing until the press has lasted
the long-press timeout. Then it opens the menu and consumes the finger lifting, so the card does not
also count the press as a tap. If the finger moves past the touch slop after the menu opens, the menu
closes so a drag can take over. Content that uses long-press itself passes `openOnLongPress = false`.

Journal covers offer "Open journal", plus "New entry in a new window" where the device supports more
than one window, in both the journal list and the tablet carousel.

`IconTooltip` (client/ui) names icon-only controls on hover and long-press. The workspace account
button, the editor toolbar (`NoteEditorToolbar`), the Places supporting sheet, and the recorder
bubble use it. Wrap any new icon-only button in it, with the same label as its content description.

### Drag and drop

- **Into the editor.** `entryDropTarget` (editor module) accepts `text/*`, `image/*`, `video/*`, and
  `audio/*`; `routeDroppedItems` splits a drop into media and text and ignores anything else. Content
  URIs from other apps need `requestDragAndDropPermissions`, and that permission ends when released,
  so each file is copied into LogDate's private store through `ManagedMediaImporter` (the same path
  shared files use) before the permission is released. While an acceptable drag hovers, the editor
  draws a primary-color outline.
- **Pasting media.** `entryPasteTarget` (editor module) handles both ways of pasting. For the
  paste keys, it watches key events; it does not consume them, so the text field still pastes any
  text. For the text menu, it adds an item with `appendTextContextMenuComponents`, which every text
  field inside the modifier includes in its long-press toolbar and right-click menu. The item is
  chosen when the menu opens, from the clipboard's description alone: reading the description,
  unlike reading the clip, does not show Android's "pasted from your clipboard" notice.
  `mediaPasteItem` decides between no item, Paste, and Paste as attachment (when there is also text
  for the field's own Paste, or when the clip is a `text/uri-list` link whose file type only reading
  the clip would reveal). Either way, the clip goes through the same `routeDroppedItems` and import
  as a drop (`importIntoEntry`), and the copies are attached. A menu Paste that finds no media
  reports it; Ctrl+V stays quiet, because the field may have pasted text.
- **Import results.** `importIntoEntry` returns an `EntryMediaImport`: the copies in LogDate's store,
  and a `MediaImportProblem` for each file that failed, `Unsupported` for a type an entry cannot
  hold and `Unreadable` for anything else. The editor attaches the copies and shows a snackbar for
  the first problem. Each copy's block type comes from its stored file extension
  (`attachmentBlockType`), which covers every photo, video, and audio format the store writes.
- **Keyboard GIFs and stickers.** A keyboard sends a file only to a field whose `EditorInfo` lists
  that content type, through `InputConnection.commitContent`. Compose's `Modifier.contentReceiver`
  does this, but only for the state-based `BasicTextField` (`TextFieldState`); the editor's text
  block stays on the value-based field because its inline Markdown styling is a
  `VisualTransformation`, which the state-based field cannot apply (its `OutputTransformation`
  changes text, not styles, as of Compose foundation 1.11). Instead, `EntryKeyboardContentReceiver`
  wraps the editor in `InterceptPlatformTextInput`. When any text field inside starts a keyboard
  session, the interceptor adds `keyboardContentMimeTypes` to the `EditorInfo` with
  `EditorInfoCompat.setContentMimeTypes` and wraps the `InputConnection` with
  `InputConnectionCompat.createWrapper`. A committed file is accepted only if
  `acceptsKeyboardContent` matches its type; the keyboard's read grant is requested, the file is
  copied with `importIntoEntry`, and the grant is released once the copy finishes.
- **Out of LogDate.** `mediaDragSource` in `client/ui` sends a `ClipData` with a content URI from the
  app's FileProvider (`${applicationId}.provider`, which covers the private media store) and the
  file's MIME type. Stored media is named by `logdate-media://` references, so it first resolves the
  reference to its file with `MediaFileResolver`. It sets
  `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`, so the receiving app may read that one file.
  Library thumbnails use it. Compose starts the drag on a long press, so the modifier sits inside
  the thumbnail's `clickable`: outside it, the click takes the press first and the drag never
  starts.

### Stylus

LogDate has no handwriting code of its own. Since Compose foundation 1.7, every Compose text field
on Android 14 and later starts the platform's stylus handwriting when a pen moves over it, or within
40dp above or below and 10dp to either side. Compose asks the keyboard to start handwriting
(`InputMethodManager.startStylusHandwriting`), and the keyboard draws the ink and sends the text it
recognizes through the normal input connection, so the editor sees ordinary typing. The editor's text
block (`TextBlockContent.kt`) gets this for free.

Handwriting needs three things on the device: a pen (or an emulator touch screen that reports one),
a keyboard that supports handwriting, and the system setting "Use stylus to write in text fields",
which is on by default on devices that ship with a pen. Password fields never accept handwriting.

Some controls only open a text field: the entry page's Write choice and Home's search pill.
Writing on those uses Android's handwriting delegation. `handwritingDelegator` (client/ui) marks the
control; when a pen starts writing on it, Android asks the keyboard to get ready for handwriting and
calls the control's callback, which opens and focuses a text field marked `handwritingDelegate`. That
field accepts the handwriting, and the keyboard types it there. On Android these are Compose's
`handwritingDetector` and `handwritingHandler`; desktop and iOS builds have no stylus handwriting, so
they do nothing there. The editor's text block and the expanded search field are the two delegates.
A field must be focused as soon as it appears, or the keyboard drops the handwriting.

Postcards have their own pressure-aware ink in `InkCapture.kt`; that is drawing, not text input.

## Flag and rollout

None. These are input fixes that apply wherever the hardware exists.

## Privacy

Dragging out shares only the file the person dragged, with read-only access granted for that drag.

## How to test

- **Unit tests:** the catalog has no duplicate chords per scope, and key events map to the right
  actions.
- **Compose UI tests:** `ContextMenuAreaTest` (client/ui desktopTest) checks that long-press over a
  clickable card opens the menu without clicking, a tap still clicks, right-click opens the menu, and
  content without items or with `openOnLongPress = false` keeps the press. `NoteEditorToolbarTest`
  (editor desktopTest) checks that hovering Save shows its tooltip and that screen readers hear the
  toolbar buttons by name.
- **Import tests:** MIME routing, partial-failure rollback, and a specific error for unreadable URIs.
- **Stylus:** `OsSurfaceScreenshotsTest#stylusHandwriting` writes "hello" with pen events and fails
  if the keyboard does not type it into the entry, and `stylusWritingOnControls` does the same on the
  Write choice and the search pill (see Updating screenshots).
- **Paste and keyboard content:** `EntryDropTest` covers which Paste item the text menu offers and
  which types keyboards may send. `OsSurfaceScreenshotsTest#pastePhotoFromTextMenu` long-presses a
  new entry's text, taps Paste, and captures both steps; `#keyboardInsertsImage` taps Gboard's
  suggestion for the copied photo and fails if the photo is not added to the entry;
  `#keyboardGif` opens Gboard's GIF panel, picks the first GIF, and waits for it in the entry (it
  needs the emulator's network, which Gboard uses to load GIFs); `#pastePhotoIntoEntry` pastes with
  Ctrl+V from a virtual USB keyboard.
- **Cross-app drag:** `OsSurfaceScreenshotsTest#crossAppDrag` drags a photo from Files into a new
  entry on the Android desktop and waits for it in the entry, and `#dragPhotoBetweenWindows` drags
  a Library photo into an entry in a second window.
- **Manual check:** on an API 37 tablet emulator with a hardware keyboard and mouse, press Meta+/.

## Updating screenshots

- Desktop menus, menus, tooltips, and drop states are Lane 1 references.
- The shortcut helper and stylus input are Lane 2 captures, recorded with `./run screenshots:os`. The
  cross-app drag and the account tooltip are recorded with `./run screenshots:desktop`, on the
  desktop windowing emulator, where windows sit side by side and a mouse pointer can hover; the
  tooltip capture rests the pointer with injected mouse hover events. They land in
  `docs/screenshots/android-os-integration/keyboard-pointer-drag-and-drop/`.
- The drag tests place the two windows with `am task resize` and drag with injected touch events
  (`OsSurfaceCapture.drag`): a press held past the long-press timeout, a steady move, and a pause
  over the target for the capture. `input draganddrop` does not hold long enough for Compose's
  drag source. In Files a first long press only selects a file; a drag starts from a file that is
  already selected. The Files app on the emulator does not accept files dropped from other apps,
  so the drag out of LogDate is shown into a second LogDate window, which receives the same shared
  file URI another app would.
- The shortcut helper capture plugs in a USB keyboard through Android's `uinput` shell tool
  (`HardwareKeyboard` in the instrumented tests). Key presses that UiAutomator injects come from
  Android's virtual keyboard, which has no physical keys, and Android 17's helper then drops every app
  shortcut. The test also switches the on-screen keyboards off while it captures, because the
  emulator's Gboard stays up next to a physical keyboard.
- The stylus captures write with `Stylus` in the instrumented tests. It sends pen motion events from
  one of the emulator's touch screens, which also reports itself as a stylus, at a pen's sampling
  rate (about 120 events a second). Speed matters: strokes sent slowly, for example one
  `adb shell input` command per point, reach the keyboard as separate words, and it reads them as
  something else. The tests turn on the handwriting setting and close Gboard's first-use stylus
  tutorial when it appears. A fresh managed device's Gboard has no handwriting model yet: it
  downloads one the first time a pen writes in a text field and ignores the pen until then. So the
  tests write one short stroke in a field, wait up to three minutes for the model (its pack appears
  in `dumpsys input_method`), clear the field, and put the on-screen keyboard away before writing.
