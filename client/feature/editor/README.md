# `:client:feature:editor`

**Entry creation and editing functionality**

## Overview

Provides comprehensive entry editing capabilities including rich text, images, and multimedia content with real-time saving and block-based editing architecture. The core feature for creating and modifying journal entries.

The editor serves the **document** part of LogDate's purpose: **a home to document, reflect on,
and share memories**. Writing and media capture preserve an experience and its context; saving
an entry is part of the larger memory experience. Follow the
[product positioning](../../../docs/product-info/product-positioning.md) when describing this feature.

Documenting also calls for ongoing invention beyond the editor's existing inputs. Explore creative,
opinionated ways to record memories that feel fun and playful, while preserving meaningful results
for later reflection and sharing. A capture experience can differ by client or form factor while
contributing to the same lasting home for memories.

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

### Creating New Entry
```kotlin
navController.navigate(
    EditorNavRoute.NewEntry(
        journalId = selectedJournal.id,
        initialText = ""
    )
)
```

### Editing Existing Entry
```kotlin
navController.navigate(
    EditorNavRoute.EditEntry(
        entryId = entry.id,
        journalId = entry.journalId
    )
)
```

## TODOs

### Core Editor Features
- [x] **Auto-saving drafts functionality** (✅ Completed)
- [x] **Debounced auto-save with retry logic** (✅ Completed) 
- [x] **Draft management and cleanup** (✅ Completed)
- [ ] Add collaborative real-time editing
- [ ] Implement voice-to-text integration
- [ ] Add more block types (tables, code blocks, drawings)
- [ ] Implement editor plugin system
- [ ] Add advanced text formatting (bold, italic, links)
- [ ] Implement markdown export/import
- [ ] Add editor templates and snippets
- [ ] Implement editor history and undo/redo

### Media & Content
- [ ] Add video recording and editing
- [ ] Implement audio note recording
- [ ] Add drawing and sketching capabilities
- [ ] Implement document attachment support
- [ ] Add location tagging for entries
- [ ] Implement mood and weather integration
- [ ] Add hashtag and mention support
- [ ] Implement content search within editor

### User Experience
- [ ] Add writing analytics and insights
- [ ] Implement focus mode for distraction-free writing
- [ ] Add accessibility improvements (voice control, screen reader)
- [ ] Implement editor customization (themes, fonts)
- [ ] Add writing goals and progress tracking
- [ ] Implement editor shortcuts and power user features
- [ ] Add predictive text and auto-completion
- [ ] Implement writing prompts and suggestions

### Performance & Reliability
- [x] **Implement background saving optimization** (✅ Completed)
- [x] **Add auto-save error handling and retry logic** (✅ Completed)
- [ ] Optimize editor performance for large entries
- [ ] Implement efficient image compression
- [ ] Add editor crash recovery
- [ ] Add editor performance monitoring
- [ ] Implement memory usage optimization
- [ ] Add editor testing automation
- [ ] Implement editor debugging tools
