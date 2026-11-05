# `:client:domain`

**Business logic and use case implementation for the application**

## Overview

Implements the core business logic of the application through use cases, following clean architecture principles. This module serves as the bridge between the UI layer and the data layer, containing all the application's business rules and behaviors.

The domain supports LogDate's shared memory platform across purpose-built clients. Keep memory
behavior and the person's content, context, and audience choices coherent while allowing workflows
and capability coverage to differ by device. Creative recording experiences should produce lasting
memories that remain useful for reflection and sharing. Follow the
[product positioning](../../docs/product-info/product-positioning.md).

## Architecture

```
Domain Module
├── Use Cases (Application Logic)
├── Domain Models
├── Result Types
└── Business Rules
```

## Key Components

### Core Use Case Categories

- **Account**: User authentication and account management
- **Notes**: Journal note creation, manipulation, and retrieval
- **Drafts**: Draft entry management and persistence
- **Journals**: Journal collection management
- **Timeline**: Chronological data presentation
- **Rewind**: Historical data analysis and summary
- **Location**: Geolocation tracking and history
- **World**: Environmental context integration
- **Entities**: People and entity extraction
- **Quota**: Usage tracking and limits

### Important Use Cases

- `AddNoteUseCase`: Creates and stores journal notes
- `GetTimelineUseCase`: Retrieves timeline data with proper formatting
- `GetStreamingTimelineUseCase`: Streaming timeline with fast first paint (used by HomeViewModel)
- `SummarizeJournalEntriesUseCase`: Generates summaries of journal content
- `GetRewindUseCase`: Creates retrospective content from past entries
- `ObserveLocationUseCase`: Monitors device location changes
- `CreatePasskeyUseCase`: Handles secure authentication
- `GetDayBoundsUseCase`: Determines semantic day boundaries based on user activity patterns

### Domain Models

- `RewindQueryResult`: Represents rewind operation results
- `TimelineConfig`: Timeline presentation configuration
- `ExportModels`: Data structures for export operations

## Features

### Clean Architecture Implementation

- **Separation of Concerns**: Business logic isolated from implementation details
- **Dependency Inversion**: Domain layer depends only on abstractions
- **Use Case Pattern**: Each business function encapsulated in a use case
- **Repository Abstraction**: Data access through repository interfaces
- **Domain-Driven Design**: Models represent business concepts

### Business Logic

- **Journal Management**: Creation, update, and deletion of journals
- **Note Processing**: Text and media note handling
- **Timeline Generation**: Chronological organization of user data
- **Draft System**: Auto-save and draft management
- **Location Tracking**: User movement and place tracking
- **Rewind Generation**: Nostalgic content creation from history

### Intelligence Features

- **Content Analysis**: Extracting meaning and patterns from user entries
- **Entity Recognition**: Identifying people, places, and events in content
- **Summarization**: Creating concise summaries of journal content
- **Memory Generation**: Creating meaningful connections between past entries
- **Context Enhancement**: Adding contextual information to user data
- **Semantic Understanding**: Comprehending the meaning behind user entries
- **Insight Generation**: Providing users with meaningful observations about their data
- **Pattern Recognition**: Identifying recurring themes and behaviors

### Platform Abstractions

- **Multiplatform Design**: Core logic works across all platforms
- **Interface-Based Architecture**: Implementation details provided by platform modules
- **Consistent API Surface**: Same functionality exposed to all platforms

## Dependencies

### Core Dependencies

- `:shared:model`: Shared data models
- `:client:repository`: Data access interfaces
- `:client:media`: Media handling abstractions
- `:client:location`: Location services
- `:client:intelligence`: AI and intelligent features
- **Kotlinx Coroutines**: Asynchronous programming
- **Kotlinx DateTime**: Date and time handling
- **Koin**: Dependency injection

## Usage Patterns

### Standard Use Case Pattern

```kotlin
class GetTimelineUseCase(
    private val journalRepository: JournalRepository,
    private val intelligenceClient: IntelligenceClient
) {
    suspend operator fun invoke(params: TimelineParams): Flow<List<TimelineItem>> {
        // Business logic implementation
    }
}
```

### Injection Pattern

```kotlin
// In DomainModule.kt
factory { GetTimelineUseCase(get(), get()) }

// In ViewModel
class TimelineViewModel(
    private val getTimelineUseCase: GetTimelineUseCase
) : ViewModel() {
    // Use the use case
}
```

## Testing

The domain module includes comprehensive testing:

- **Unit Tests**: Individual use case testing
- **Integration Tests**: Use case interactions
- **Mock-Based Testing**: Repository and service mocking
- **Business Rule Verification**: Ensuring business rules are enforced

## TODOs

### Core Improvements
- [ ] Add comprehensive unit testing for all use cases
- [ ] Implement result wrapper for all use cases
- [ ] Add proper error handling and recovery strategies
- [ ] Improve documentation for business rules
- [ ] Add validation for all input parameters

### Intelligence Enhancements
- [ ] Expand entity recognition capabilities
- [ ] Improve summarization quality and customization
- [ ] Add sentiment analysis for emotional insights
- [ ] Implement topic modeling for better content organization
- [ ] Add personalized content recommendations
- [ ] Improve context-aware processing

### Advanced Features
- [ ] Implement offline-first business logic
- [ ] Add domain-level analytics tracking
- [ ] Define strategies for handling large datasets
- [ ] Add domain-level validation rules
- [ ] Implement business-rule based retry policies

### Domain Logic Refinements
- [ ] Refine business rules for data integrity
- [ ] Improve error classification and handling
- [ ] Enhance domain model structure and relationships
- [ ] Add more sophisticated domain events
- [ ] Implement comprehensive validation rules
