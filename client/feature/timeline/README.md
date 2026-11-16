# `:client:feature:timeline`

**Timeline view and entry browsing**

## Overview

Provides timeline-based navigation through journal entries with filtering, search, and detailed views of daily activities. The primary interface for browsing and discovering past journal content.

## Architecture

```
Timeline Feature
├── Timeline List Views
├── Daily Detail Panels
├── Search & Filtering
├── Content Sections
└── Navigation Integration
```

## Key Components

### Core Timeline

- `TimelineRoute.kt` - Main timeline screen
- `TimelineViewModel.kt` - Timeline state management
- `Timeline.kt` - Timeline list component
- `TimelineTranscriptionPlanner.kt` - Chooses which visible recordings to transcribe automatically

### Day rendering

The list of days, the sticky date headers, the moment layouts and the voice cards live in
`:client:ui` (`app.logdate.ui.timeline.newstuff` and `app.logdate.ui.audio`), so other surfaces draw
them identically. This module builds the state they render:

- `TimelineViewModel` turns each day's notes, places and inferred moments into a day with
  `createSemanticTimelineDayUiState`, which supplies one moment itself when inference returns none for a day that has notes or places.
- `TimelineRoute` passes the list's visible audio note IDs back to the view model, which requests
  transcripts for them through `TimelineTranscriptionPlanner`. A recording that already has a
  transcript row is skipped, and one is requested again only if queueing it failed. The Home workspace host applies the same rule in `HomeViewModel`, which has its own copy, so the two must change together.

[Semantic timeline](../../../docs/feature-design/semantic-timeline.md) describes the day structure,
the voice card and the rules to keep when changing them.

### Detail Views

- `TimelineDayDetailPanel.kt` - Daily detail view
- `NotesListSection.kt` - Notes display section
- `PeopleEncounteredSection.kt` - People tracking
- `CollapsingTimelineAppBar.kt` - Collapsing header

### Navigation

- `TimelineNavRoute.kt` - Navigation definitions
- Timeline-to-editor navigation
- Deep linking to specific dates

## Features

### Timeline Navigation

- Chronological entry browsing
- Date-based navigation
- Infinite scroll with pagination
- Quick date jumper

### Daily Detail Views

- Comprehensive day summaries
- Entry previews and quick access
- People encountered tracking
- Activity and location context

### Search & Discovery

- Full-text search across entries
- Tag and category filtering
- Date range selection
- Advanced search operators

### Content Organization

- Grouped by date/time
- Content type categorization
- Priority and importance highlighting
- Related content suggestions

## Dependencies

### Core Dependencies

- `:client:domain` - Timeline business logic
- `:client:ui` - Shared UI components
- **Compose Multiplatform**: UI framework
- **AndroidX Navigation**: Navigation framework

### Data Dependencies

- Timeline data from repository layer
- Search indexing integration
- Media content loading

## Timeline Architecture

### Data Flow

```
Repository
    ↓
Timeline Use Cases
    ↓
Timeline ViewModel
    ↓
Timeline UI State
    ↓
Timeline Components
```

### Content Aggregation

```
Daily Entries
    ↓
Content Grouping
    ↓
┌─────────────┬─────────────┬─────────────┐
│ Text Entries │ Media       │ People      │
└─────────────┴─────────────┴─────────────┘
```

## UI Components

### Timeline List

- Virtualized scrolling for performance
- Adaptive item sizing
- Smooth animations and transitions
- Pull-to-refresh functionality

### Daily Panels

- Expandable/collapsible sections
- Rich content previews
- Quick action buttons
- Context menus for entry management

### Search Interface

- Real-time search suggestions
- Filter chips and tags
- Search history
- Saved search queries

## Performance Optimizations

### Data Loading

- Lazy loading with pagination
- Intelligent prefetching
- Background data synchronization
- Memory-efficient content caching

### UI Rendering

- Virtualized lists for large datasets
- Image lazy loading
- Adaptive content quality
- Smooth scroll performance

## TODOs

### Core Timeline Features

- [ ] Add timeline customization options
- [ ] Implement timeline themes and layouts
- [ ] Add timeline export functionality
- [ ] Implement timeline sharing capabilities
- [ ] Add timeline search enhancement with filters
- [ ] Implement timeline analytics and insights
- [ ] Add timeline automation and smart grouping
- [ ] Implement timeline performance optimization

### Advanced Navigation

- [ ] Add calendar view integration
- [ ] Implement timeline map view
- [ ] Add timeline statistics and visualizations
- [ ] Implement timeline bookmarks and favorites
- [ ] Add timeline collaboration features
- [ ] Implement timeline widgets
- [ ] Add timeline shortcuts and quick actions
- [ ] Implement timeline voice navigation

### Search & Discovery

- [ ] Implement AI-powered content discovery
- [ ] Add semantic search capabilities
- [ ] Implement content recommendations
- [ ] Add similar entry suggestions
- [ ] Implement trend analysis and insights
- [ ] Add content clustering and categorization
- [ ] Implement timeline memory triggers
- [ ] Add anniversary and milestone detection

### User Experience

- [ ] Add timeline accessibility improvements
- [ ] Implement timeline gestures and shortcuts
- [ ] Add timeline reading mode
- [ ] Implement timeline dark/light themes
- [ ] Add timeline font and sizing options
- [ ] Implement timeline layout customization
- [ ] Add timeline notification settings
- [ ] Implement timeline backup and sync status
