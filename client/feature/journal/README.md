# `:client:feature:journal`

**Journal management and display functionality**

## Overview

Provides journal management capabilities including creation, viewing, organization, and sharing
of journals and their entries. Journals organize memories within LogDate's broader purpose:
**a home to document, reflect on, and share memories**. This module supplies part of that experience;
it does not define the whole product or its market category. See the
[product positioning](../../../docs/product-info/product-positioning.md).

## Architecture

```
Journal Feature
├── Journal Management
├── Entry Organization
├── Journal UI Components
├── Sharing Features
└── Navigation Structure
```

## Key Components

### Core Journal Features

- `JournalsOverviewScreen.kt` - Main journals hub
- `JournalCreationScreen.kt` - Journal creation flow
- `JournalDetailScreen.kt` - Journal detail view
- `JournalSettingsScreen.kt` - Journal configuration

### UI Components

- `JournalCover.kt` - Journal cover presentation
- `JournalCoverFlowCarousel.kt` - Interactive journal carousel
- `JournalList.kt` - Scrollable journal collection
- `NoteViewerScreen.kt` - Individual note viewing

### Journal Management

- `JournalsOverviewViewModel.kt` - Journals state management
- `JournalCreationViewModel.kt` - Journal creation logic
- `JournalDetailViewModel.kt` - Journal content management
- `JournalSettingsViewModel.kt` - Journal configuration

### Sharing & Collaboration

- `ShareJournalScreen.kt` - Journal sharing interface
- `ShareJournalViewModel.kt` - Sharing implementation

### Navigation

- `JournalsNavRoute.kt` - Main navigation entry point
- `JournalDetailsRoute.kt` - Detail view navigation
- `JournalCreationRoute.kt` - Creation flow navigation
- `NoteDetailRoute.kt` - Note viewing navigation

## Features

### Journal Management

- **Journal Creation**: Create new journals with customizable properties
- **Journal Organization**: Group and organize entries by journal
- **Cover Customization**: Personalize journal appearance
- **Journal Settings**: Configure journal behavior and appearance
- **Multiple Journals**: Support for multiple simultaneous journals

### Entry Organization

- **Chronological View**: Timeline-based organization of entries
- **Entry Grouping**: Organize entries by journal
- **Entry Filtering**: Filter and search journal entries
- **Entry Details**: Detailed view of individual entries
- **Note Types**: Support for text, images, and other media

### UI Components

- **Cover Flow**: Interactive journal selection carousel
- **Journal Covers**: Visual representation of journals
- **Entry Lists**: Organized display of journal entries
- **Empty States**: Guided experience for new users
- **Search Capability**: Find journals and entries

### Sharing & Collaboration

- **Journal Sharing**: Share journals with other users
- **Entry Sharing**: Share individual entries
- **Export Options**: Export journals in various formats
- **Collaboration Controls**: Manage shared journal permissions

## Dependencies

### Core Dependencies

- `:client:domain` - Business logic
- `:client:ui` - Shared UI components
- `:client:repository` - Data access
- `:client:sharing` - Sharing functionality
- `:client:feature:editor` - Entry editing
- **Compose Multiplatform**: UI framework
- **Material 3**: Design components

## Usage Patterns

### Journal Overview

```kotlin
@Composable
fun JournalsOverviewScreen(
    onOpenJournal: JournalClickCallback,
    onBrowseJournals: () -> Unit,
    onCreateJournal: () -> Unit,
    viewModel: JournalsOverviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    
    // Journals display logic
    JournalListPanel(
        journals = state.journals,
        onOpenJournal = onOpenJournal,
        onBrowseJournals = onBrowseJournals,
        onCreateJournal = onCreateJournal
    )
}
```

### Journal Detail View

```kotlin
@Composable
fun JournalDetailScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNewEntry: (Uuid) -> Unit,
    onOpenEntry: (Uuid) -> Unit,
    viewModel: JournalDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Journal detail implementation
}
```

## Dependency Injection

```kotlin
val journalsFeatureModule: Module = module {
    includes(sharingModule)
    viewModel { JournalsOverviewViewModel(get()) }
    viewModel { JournalCreationViewModel(get(), get()) }
    viewModel { JournalDetailViewModel(get(), get(), get(), get()) }
    viewModel { (noteId: Uuid) ->
        NoteViewerViewModel(
            noteId = noteId,
            notesRepository = get(),
            removeNoteUseCase = get(),
        )
    }
    viewModel { (noteId: Uuid) ->
        AudioNoteViewerViewModel(
            noteId = noteId,
            notesRepository = get(),
            audioContextProcessor = get(),
            durationResolver = get(),
            audioPlaybackManager = get(),
        )
    }
    viewModel { JournalSettingsViewModel(get(), get(), get(), get()) }
    viewModel { ShareJournalViewModel(get(), get()) }
}
```

## TODOs

### Core Features
- [ ] Implement journal templates
- [ ] Add journal statistics and insights
- [ ] Implement advanced search capabilities
- [ ] Add journal archiving functionality
- [ ] Implement journal categories/tags

### Entry Management
- [ ] Add batch operations for entries
- [ ] Implement entry templates
- [ ] Add entry categorization
- [ ] Implement entry search within journals
- [ ] Add entry highlighting and favorites

### Sharing & Collaboration
- [ ] Enhance sharing permissions
- [ ] Add collaborative editing
- [ ] Implement comment functionality
- [ ] Add version history for shared journals
- [ ] Implement notification system for shared journals

### UI Improvements
- [ ] Add more journal cover styles
- [ ] Implement customizable journal themes
- [ ] Add animations for journal interactions
- [ ] Improve empty state guidance
- [ ] Add accessibility features for journal navigation
