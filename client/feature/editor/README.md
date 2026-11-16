# `:client:feature:editor`

**Entry creation and editing functionality**

## Overview

Provides comprehensive entry editing capabilities including rich text, images, and multimedia content with real-time saving and block-based editing architecture. The core feature for creating and modifying journal entries.

## Architecture

```
Editor Feature
├── Block-Based Editor System
├── Rich Text Editing
├── Media Integration
├── Auto-Save System
├── Draft Management
└── Platform-Specific Components
```

## Key Components

### Core Editor

- `NoteEditorScreen.kt` - Main editor interface
- `EntryEditorViewModel.kt` - Editor state management
- `EditorState.kt` - Editor state definitions
- `EntryBlockData.kt` - Block data structures

### Block System

- `TextBlockContent.kt` - Text block editing
- `ImageBlockContent.kt` - Image block handling
- `CameraBlockContent.kt` - Camera integration
- `BlockTypePickerDialog.kt` - Block type selection

### Editor Components

- `AutoSaveHandler.kt` - Automatic saving logic
- `ConfirmEntryExitDialog.kt` - Unsaved changes dialog
- `EmptyEditorContent.kt` - Empty state handling
- `JournalSelectorDropdown.kt` - Journal selection

### Navigation & UI

- `EditorNavRoute.kt` - Navigation definitions
- `NoteEditorSurfaces.kt` - Editor UI surfaces
- `NoteEditorToolbar.kt` - Editor toolbar
- `LiveCameraPreview.kt` - Camera preview (platform-specific)

## Features

### Block-Based Editing

- Modular content blocks (text, images, camera)
- Drag and drop block reordering
- Block-specific editing contexts
- Dynamic block addition/removal

### Rich Text Editing

- Markdown-style formatting
- Inline text styling
- Paragraph and heading support
- List creation and management

### Media Integration

- Camera capture integration
- Image import from gallery
- Image editing and cropping
- Media organization and management

### Auto-Save & Drafts

- **Intelligent Auto-Save**: Debounced saving (2s after typing stops)
- **Periodic Backup Saves**: Every 30 seconds to prevent data loss
- **Robust Error Handling**: Exponential backoff retry logic for failed saves
- **Draft Management**: Automatic draft creation, update, and cleanup
- **Content Change Detection**: Smart detection of meaningful content changes
- **Visual Feedback**: Save indicators to show draft status
- **Offline Support**: Full offline editing with local draft storage
- **Draft Recovery**: Automatic loading of recent drafts on editor restart

## Platform-Specific Features

### Android

- **CameraX Integration**: Advanced camera features
- **File Picker**: System file selection
- **Keyboard Handling**: IME integration
- **Drop and Paste**: Text, photos, videos, and audio dropped from other apps or pasted with Ctrl+V
  or the text menu are added to the entry (`ui/drop/`)
- **Keyboard GIFs and Stickers**: Images the on-screen keyboard sends are attached as files
  (`EntryKeyboardContentReceiver`); see
  [Keyboard, Pointer, Drag and Drop](../../../docs/reference/android/os-integration/keyboard-pointer-drag-and-drop.md)
- **Material Design**: Platform-native styling

### iOS

- **Native Camera**: iOS camera integration
- **Photo Library**: System photo access
- **Keyboard Accessories**: Custom input tools
- **SwiftUI Integration**: Native feel

### Desktop

- **File System Access**: Direct file operations
- **Keyboard Shortcuts**: Power user features
- **Multi-Window**: Multiple editor instances
- **Drag & Drop**: External file integration

## Dependencies

### Core Dependencies

- `:client:domain` - Business logic
- `:client:ui` - Shared UI components
- `:client:media` - Media management
- **Compose Multiplatform**: UI framework

### External Dependencies

- **AndroidX Camera**: Camera functionality (Android)
- **FileKit Compose**: File operations
- **Coil**: Image loading and caching

## Editor Architecture

### State Management

```
Editor ViewModel
    ↓
Editor State
    ↓
┌─────────────┬─────────────┬─────────────┐
│ Block State  │ Auto-Save   │ Navigation  │
└─────────────┴─────────────┴─────────────┘
```

### Auto-Save Flow

```
User Input/Content Change
    ↓
Content Change Detection (with block type prefixes)
    ↓
Debounced Auto-Save (2s delay)
    ↓
Draft Create/Update (with error handling)
    ↓
Local Storage (via LocalEntryDraftStore)
    ↓
Visual Feedback (save indicator)
    ↓
Periodic Backup Save (every 30s)
```

### Block System Flow

```
User Input
    ↓
Block Type Detection
    ↓
Content Processing
    ↓
State Update (triggers auto-save)
    ↓
Draft Storage
```

## Usage Patterns

The editor is a Navigation 3 destination. `EntryEditorRoute` carries what the screen needs to
start, and callers push it onto the back stack (see
[Navigation architecture](../../../docs/navigation-architecture.md)).

### Creating a new entry

```kotlin
backStack.add(EntryEditorRoute(journalIds = listOf(journal.id.toString())))
```

### Editing an existing entry

```kotlin
backStack.add(EntryEditorRoute(entryId = entry.id.toString()))
```

The route also carries a draft to resume (`draftId`), initial text, a photo to start from, the
launch mode (`EditorLaunchMode`), a visit context and the transition identity described below.
Identifiers are strings because saved-state serialization on iOS cannot handle `Uuid`.

## Entry overview and block focus

An entry is presented in two states over the same blocks: an **overview**, where every block sits on
one scroll, and a **focus**, where one block expands to fill the working area. Switching between them
changes geometry and never ownership. `RetainedEditorBlocks` keeps one permanent document, so each
block's surface and its input owner (text field, canvas, recorder) stay attached while focus changes.
Text selection, scroll position and block identity therefore stay with the input and are never
rebuilt in a second presentation tree.

- **One interruptible clock per block.** The surface bounds, safe spacing and corners of a block follow
  a single focus progress value (`RetainedEditorFocusMotion`). A new focus request retargets it from
  wherever it currently is.
- **Predictive Back drives the same clock.** The gesture starts from the position currently displayed.
  Cancelling restores focus; committing returns the block to the overview. A gesture that a new input
  owner has invalidated cannot dismiss the owner that replaced it.
- **Presentation never controls capture.** Starting, recording, processing and recovering a capture
  stay within the selected block's own editor. A change of presentation must not stop a recorder or
  create a second owner. Native camera and recording paths need their real draft-persistence
  callbacks before capture starts.
- **Local coordinates, not window coordinates.** Anchors for returning to the overview and hinge
  bounds are converted into the editor's measured local coordinates, including inside nested
  viewports. After the width changes, returning anchors the focused block in the reflowed overview;
  at the same width it restores the exact scroll offset, and manual scrolling replaces the anchor.
  Layout responds to separating hinges and keeps draft content and overview scroll across resizing.
- **Block corners are `EditorBlockCornerRadius` (12dp).** Blocks do not derive their shape from the
  display corners. A focused block expands into edge-to-edge paper behind the system bars and
  cutouts, while controls keep their safe insets.
- **Drafts report truthfully.** The draft status shows the real draft-persistence state. Leaving
  through Save as draft waits for the complete draft snapshot to persist, and Save entry commits the
  entry.

### Edge pulls in the overview

Scrolling past either end of the overview does two things, both available through visible controls
as well. Pulling down past the top, when earlier entries exist for the day, opens the earlier-entries
view; pulling up past the bottom adds a block of the preferred mode (`EntryStartMode`) when block
creation is allowed. A pull only begins once the list can no longer scroll in that direction, so
ordinary scrolling always finishes first. The finger travel is damped to 60% so the pull feels resistive, and the action arms at
112dp, which separates a deliberate pull from an overscroll; the shared haptics play a detent tick
there, and releasing before that point cancels. The pull visual is capped at 1.4 times the arm
distance. While earlier entries are open, a floating Back to writing action and system Back return to the
same draft and scroll position. The overview
saves its scroll offsets, whether the earlier-entries view is open, and the anchor of the focused
block, so all of them survive recreation.

### Starting an entry

The mode a new entry opens in (text, handwriting, camera, audio or gallery; `EntryStartMode`) is a
user preference, chosen in Settings under Memories. Choosing a different tool inside an entry does not
change the preference. The capture bar that switches modes appears only for an empty input that no
capture owns, so existing content is never silently replaced by a block of another type. A horizontal
swipe on the focused header or the tool strip moves to the neighboring tool, mirrored in
right-to-left layouts; drawing gestures stay reserved for handwriting, and a swipe on the header does
nothing while a recording is active.

An entry opens in the preferred mode only when nothing more specific was asked for. Loading an
existing entry or draft, a photo prompt, incoming text or attachments, a marked moment, a first-entry
flow and an explicit launch mode other than the default or text all take precedence. Earlier entries
of the current day are read from the day's notes and stay separate from the draft being written.

Creation sources on Home pass a per-visit `creationTransitionId` and their real shape
(`creationTransitionShape`) in the route, and the editor draws its bounds as the shared-element
destination. The pull gesture at the start of the timeline previews the New entry source; retreating
cancels, and releasing past the arm point creates exactly one editor visit through
`tryLaunchHomeEditor`. Nothing is created during the preview.

### Presentation flags

`IMMERSIVE_ENTRY_EDITOR` selects the overview and focus presentation; turning it off returns to the
earlier single-list presentation. `HANDWRITING_ENTRIES` controls whether handwriting blocks can be
created. `NoteEditorScreen` offers handwriting creation when either flag is on, and only the
creation controls consult it, so existing handwriting blocks are not hidden by it. Both flags default
on.

## Handwriting

Handwriting is an ordinary entry block whose content is a `HandwritingDocument`, a versioned model in
`shared/model` that stores page dimensions and an ordered list of strokes. A stroke has a stable id, a
tool (`pen`, `highlighter` or `eraser`), a color, a width, a transform and its points, and each point
carries its position and pressure. The original geometry is stored, never a bitmap or recognized
text, so ink can be redrawn at any size and survives every route below unchanged. Strokes are validated
when they are ingested, and an unsupported document version raises
`UnsupportedHandwritingVersionException` instead of being read as something else.

The editor keeps undo history and tool preferences outside the document, which holds only the
authoritative page. Completed strokes update `EntryEditorViewModel` directly, so ink takes part in
autosave, background save, Save as draft and Save entry like any other block. The canvas accepts pen
input by default and scrolls with a finger; the drawing options can let a finger draw. The tools are
pen, highlighter, a whole-stroke eraser, undo and redo, with a choice of colors and widths.

How ink is kept:

- **Local database.** A saved handwriting entry is a row of the text-notes table whose
  `content_format` column is `handwriting`; plain text rows keep the default `text`. It hydrates as
  `JournalNote.Handwriting`. The column is excluded from full-text search and prose inference by the
  search triggers, which only index `text` rows. A stored document that cannot be read keeps its
  identity and complete original payload, appears as an unavailable record and cannot be edited as
  an empty page.
- **Drafts.** The draft model has `SerializableHandwritingBlock`.
- **Sync.** Cloud content uses the `HANDWRITING` type with the document encrypted in its content
  field. Personal-space records use the `handwritingBlock` of the entry and draft lexicons, whose
  `document` is the encoded document as a JSON string. A string keeps fractional coordinates and
  pressure out of DAG-CBOR, which has no floating-point values in these records. An oversized
  document fails sync explicitly and is never trimmed. On the cloud path, a reader that does not
  support the document version reports an unsupported-format sync failure for the record instead of
  interpreting it as text, and the lexicon requires personal-space readers to retain records they
  cannot read.
- **Export and restore.** Both archive formats carry structured ink.

In the overview and focus presentation the paper uses the theme's `surfaceContainerLow` surface in
both states and the ink adapts to it, so a block does not recolor as it expands. The earlier
presentation draws focused handwriting on white paper.

Taking a handwriting block into focus uses the shared-element transition for every block type
(`editorBlockFocusElement`): the paper and original ink are the shared element, while floating tools
and optional entry context stay outside it. The ink interpolates from the whole inline page to the
focused page coordinates, so no two ink images crossfade, and drawing controls enter after the paper
settles. A page's stored height grows to keep ink written below the initial page height.

## Testing the editor

- Focus continuity, geometry and interruption: `EntryDocumentContinuityTest`,
  `RetainedEditorFocusMotionTest`, `WorkspaceEntryPullTest` and `HomeEntryCreationAdmissionTest`.
  Input semantics on a real touch screen: `EditorEntrySwitchVideoTest`; the pull and mode flow with
  the native frame clock and the keyboard: `EditorHomePullVideoTest`. Resized emulator windows do not
  establish physical hinge or foldable behavior.
- Layout across sizes: `EntryFocusScenesTest` renders a populated overview and each focus (text,
  handwriting, photo, recorder) at compact phone, landscape, medium and tablet sizes, plus book and
  tabletop, dark theme, right-to-left and 200% text; `EntryPagePreviewScenesTest` renders the
  interactive previews in `EntryPagePreviews.kt`. These are desktop renders that prove layout, not
  keyboard, persistence, haptic or hardware behavior.
- Handwriting: `HandwritingPageTest` (serialization, segment-aware erasing, undo and redo),
  `HandwritingSharedElementTest` (opaque ink throughout focus and return), `HandwritingEntryIntegrationTest`
  (draft and saved reopen, failed draft writes) and `HandwritingStorageRestartTest` (disk store and
  Room restart, pagination, unreadable-record isolation, search exclusion). Storage and transport
  are covered by `HandwritingNoteMigrationTest` (existing text rows upgrade with the format column),
  `HandwritingContentSyncTest` (cloud content mapping), `HandwritingClientServerE2ETest` (the local
  HTTP contract) and `HandwritingArchiveRestoreTest` (ink drafts restore from archives). A saved
  note's capture location, device and sync version survive conversion by the editor.
- The durable end-to-end journey is `EntryFocusDraftJourneyTest` on Gradle Managed Devices, phone and
  tablet (`:app:android-main:flagshipPhoneApi37DebugAndroidTest` or
  `:app:android-main:largeScreenTabletApi37DebugAndroidTest`, filtered with
  `-Plogdate.androidTestClass=app.logdate.client.e2e.EntryFocusDraftJourneyTest`): it types with the real keyboard, draws six stylus strokes, leaves through Save as draft,
  reopens the draft from Entry options, requires the text and strokes to survive, then saves and
  reopens the published notes. Published blocks reopen individually.

### Debug design scenes

The debug manifest alone registers `HandwritingPrototypeActivity`, which hosts isolated editor
scenes for design work. Install a debug build on an emulator (never a physical phone) and start it
with a boolean extra: `entry_start` for the overview and focus study, `entry_stories` for the capture
bar study, or `real_editor` for the real editor, optionally with `resume_draft` or `resume_saved` to
reopen the latest draft or the newest saved handwriting note. With no extra it opens the standalone
canvas. The standalone scenes keep local state and do not touch journal data. A debug build of
`EditorActivity` also accepts the boolean extra `entry_focus` to turn on the overview and focus
presentation regardless of the flag.
