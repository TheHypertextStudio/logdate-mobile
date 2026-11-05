# `:client:intelligence`

**AI and machine learning features for understanding and rediscovering memories**

## Overview

Provides features like entry summarization, people extraction, and content analysis using AI
services. These capabilities support reflection by helping people understand and rediscover
their memories. They serve LogDate's purpose as **a home to document, reflect on, and share
memories**; AI and journaling are enabling functionality, not the primary product pitch. See the
[product positioning](../../docs/product-info/product-positioning.md).

## Architecture

```
Intelligence Module
├── AI Service Clients
├── Content Analysis
├── Entity Extraction
├── Summarization Engine
└── Platform Integrations
```

## Key Components

### Core Intelligence
- `EntrySummarizer.kt` - Entry content summarization
- `PeopleExtractor.kt` - People and entity extraction
- `OpenAiClient.kt` - OpenAI API integration
- `ClientsModule.kt` - AI service dependency injection

### Analysis Features
- Natural language processing
- Content categorization
- Sentiment analysis (planned)
- Topic extraction (planned)

## Features

### Content Summarization
- Automatic entry summarization
- Key point extraction
- Context-aware summaries
- Multiple summary lengths

### Entity Extraction
- People mentioned in entries
- Location identification
- Event recognition
- Relationship mapping

### Content Analysis
- Writing pattern recognition
- Mood detection from text
- Topic classification
- Habit identification

## Dependencies

### External AI Services
- **OpenAI API**: GPT models for text processing
- **Local ML Models**: On-device processing (planned)

### Core Dependencies
- `:client:networking` - API communication
- **Kotlinx Coroutines**: Async operations
- **Kotlinx Serialization**: API serialization

## Usage Examples

### Entry Summarization
```kotlin
val summarizer = EntrySummarizer(cache, openAiClient, networkMonitor)
val result = summarizer.summarize(
    summaryId = "entry-123",
    text = "Long journal entry text...",
    useCached = true
)
val summary = when (result) {
    is AIResult.Success -> result.value
    is AIResult.Unavailable -> null
    is AIResult.Error -> null
}
```

### People Extraction
```kotlin
val extractor = PeopleExtractor(cache, openAiClient, networkMonitor)
val result = extractor.extractPeople(
    documentId = "entry-123",
    text = "Had lunch with Alice and Bob today."
)
val people = when (result) {
    is AIResult.Success -> result.value
    is AIResult.Unavailable -> emptyList()
    is AIResult.Error -> emptyList()
}
```

### Structured Output Support
- OpenAI requests can include JSON schema response formats for stronger parsing guarantees.
- People extraction and narrative synthesis use structured output schemas to improve reliability.
- Map `AIResult.Error` to user-friendly copy in UI layers; error types are the source of truth.

## Platform Considerations

### Privacy-First Approach
- Optional AI features (user consent required)
- Local processing when possible
- Data anonymization for cloud processing
- No persistent storage of AI service data

### Performance Optimization
- Intelligent batching of requests
- Result caching for repeated analysis
- Background processing for non-critical features
- Rate limiting compliance

## Testing

### Test Coverage
The intelligence module has comprehensive unit tests covering all core functionality:

#### EntrySummarizer Tests (`EntrySummarizerTest.kt`)
- **10 test cases** covering:
  - Summary generation with various text inputs
  - Caching behavior (cache hits, misses, forced refresh)
  - Error handling and fallback responses
  - AI client integration and prompt validation
  - Edge cases (empty text, null responses, long text)

#### PeopleExtractor Tests (`PeopleExtractorTest.kt`)
- **13 test cases** covering:
  - Name extraction from various text formats
  - Complex names (titles, compound names, international names)
  - Caching and response processing
  - Edge cases (empty input, whitespace handling)
  - System prompt validation

#### OpenAI Client Tests (`OpenAiClientTest.kt`)
- **10 test cases** covering:
  - Data structure serialization and conversion
  - Message format transformation
  - Request/response object validation
  - Type-safe API integration

#### Text Processing Accuracy (`TextProcessingAccuracyTest.kt`)
- **8 integration test scenarios** with realistic journal content:
  - Daily life entries with social interactions
  - Work meetings and professional contexts
  - Family gatherings and relationships
  - Travel entries with international names
  - Medical appointments with titles
  - Large social events with many people
  - Emotional entries requiring context preservation
  - Achievement entries capturing positive sentiment

### Test Infrastructure

#### Fake Implementations
- **`FakeGenerativeAICache`**: Complete cache simulation with call tracking
- **`FakeGenerativeAIChatClient`**: Configurable AI client for deterministic testing
- **Features**: Error simulation, response customization, call verification

#### Testing Patterns
- **Coroutine Testing**: Uses `StandardTestDispatcher` for deterministic async behavior
- **Response Mocking**: Configurable AI responses for different test scenarios
- **Edge Case Coverage**: Empty inputs, malformed responses, network errors
- **Integration Testing**: End-to-end workflows with realistic data

### Running Tests

```bash
# Run all intelligence module tests
./gradlew :client:intelligence:test

# Test output shows comprehensive coverage
# 43 tests completed successfully
```

### Test Data Quality

The tests use realistic journal entry scenarios including:
- **Professional Context**: "Today's quarterly review meeting was intense..."
- **Family Events**: "Sunday dinner at Grandma's house was wonderful..."
- **Travel Experiences**: "First day in Tokyo was incredible!"
- **Medical Appointments**: "Had my annual checkup with Dr. Sarah Mitchell..."
- **Social Gatherings**: "Birthday party for Rachel was a blast!"

Each scenario validates:
- **Accuracy**: Correct extraction of people's names and relationships
- **Context Preservation**: Maintaining important contextual information
- **Edge Cases**: Handling of various text formats and edge conditions
- **Performance**: Efficient processing and caching behavior

### Test Quality Metrics
- **Unit Test Coverage**: All public APIs tested
- **Integration Coverage**: Realistic end-to-end workflows
- **Error Coverage**: Network failures, malformed responses, edge cases
- **Performance Testing**: Caching efficiency and response handling

## TODOs

### Core AI Features
- [ ] Implement local AI models for offline operation
- [ ] Add sentiment analysis for mood tracking
- [ ] Implement topic extraction and categorization
- [ ] Add writing style analysis and suggestions
- [ ] Implement habit detection from entry patterns
- [ ] Add mood tracking intelligence
- [ ] Implement location-based insights
- [ ] Add personalized content recommendations

### Advanced Intelligence
- [ ] Implement conversational AI for entry prompts
- [ ] Add predictive text and auto-completion
- [ ] Implement smart entry templates
- [ ] Add writing coach and improvement suggestions
- [ ] Implement content quality scoring
- [ ] Add automated tagging and categorization
- [ ] Implement relationship network analysis
- [ ] Add goal tracking and progress insights

### Performance & Privacy
- [ ] Implement on-device model deployment
- [ ] Add federated learning capabilities
- [ ] Implement differential privacy
- [ ] Add AI model version management
- [ ] Implement intelligent caching strategies
- [ ] Add AI service failover mechanisms
- [ ] Implement usage analytics and optimization
- [ ] Add AI ethics and bias monitoring
