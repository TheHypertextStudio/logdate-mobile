# LogDate Testing Strategy

This document explains LogDate's testing approach, organized by the Diataxis framework for clarity.

## Quick Start Tutorial

Get started with testing in LogDate:

### 1. Write Your First Unit Test

Create a test file in `src/commonTest/kotlin/`:

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

class MyUseCaseTest {
    @Test
    fun whenExecuting_thenReturnsExpectedResult() {
        // Arrange: Set up test data
        val useCase = MyUseCase()
        val input = "test"

        // Act: Execute the code
        val result = useCase.execute(input)

        // Assert: Verify the result
        assertEquals("expected", result)
    }
}
```

Run it:

```bash
./gradlew :module:test
```

### 2. Create a Screenshot Test

Create a preview in `src/screenshotTest/kotlin/app/logdate/screenshots/`:

```kotlin
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@PreviewLightDark
fun MyScreenPreview() {
    LogDateTheme {
        MyScreen()
    }
}
```

Generate baselines:

```bash
./gradlew :app:android-main:updateDebugScreenshotTest
```

Validate:

```bash
./gradlew :app:android-main:validateDebugScreenshotTest
```

## Explanation: Why We Test This Way

LogDate follows the [Android Testing Guide](https://developer.android.com/training/testing) recommendations:

1. **Test Pyramid**: More unit tests (70%), fewer integration tests (20%), minimal E2E tests (10%)
2. **Isolation**: Each test focuses on a single responsibility
3. **Speed**: Fast feedback loops for quick iteration
4. **Clarity**: Clear test names and organization
5. **Coverage**: Focus on critical paths and business logic

## How-to Guides

Learn how to write specific test types:

- [Android In-App Updates Behavior](../feature-design/android-in-app-updates.md) - Product behavior, thresholds, and UX rules for Play-managed updates
- [Unit Tests Guide](./unit-tests.md) - Testing individual components with fakes and mocks
- [Integration Tests Guide](./integration-tests.md) - Testing database and API interactions
- [UI Tests Guide](./ui-tests.md) - Testing Compose components and user interactions
- [Screenshot Tests Guide](./screenshot-tests.md) - Visual regression testing for screens
- [Android Benchmarking](../performance/README.md) - Macrobenchmark, Baseline Profile, microbenchmark, CI policy, and triage runbooks
- [Android In-App Updates](./android-in-app-updates.md) - Verifying Play-managed update flows locally and through Play tracks

## Reference: Test Types

### Unit Tests

- **Purpose**: Test individual functions and classes in isolation
- **Technology**: JUnit, Kotlin Test, Mockk
- **Location**: `src/commonTest/kotlin/` or `src/*/Test/kotlin/`
- **Coverage**: 70%+ for critical business logic
- **Speed**: Fast (milliseconds)
- **Scope**: Functions, classes, use cases

### Integration Tests

- **Purpose**: Test interactions between components and with real data sources
- **Technology**: Room testing, MockWebServer, real HTTP clients
- **Location**: `src/*/Test/kotlin/` directories
- **Coverage**: 20% - critical workflows and data persistence
- **Speed**: Slower (seconds)
- **Scope**: Database operations, API interactions, repository implementations

### UI/Compose Tests

- **Purpose**: Test UI components and user interactions
- **Technology**: Compose Testing, Mockk
- **Location**: `src/androidTest/kotlin/`
- **Coverage**: Critical user journeys
- **Speed**: Slow (multiple seconds)
- **Scope**: Component rendering, interactions, state updates

### Screenshot Tests

- **Purpose**: Visual regression testing for key screens
- **Technology**: Google Compose Preview Screenshot Testing
- **Location**: `src/screenshotTest/kotlin/`
- **Baseline**: `src/screenshotTestDebug/reference/`
- **Coverage**: Key screens and states
- **Speed**: Moderate (seconds per screen)
- **Scope**: Visual appearance, light/dark themes, multiple sizes

### End-to-End Tests (Rare)

- **Purpose**: Full user workflows from app launch to data persistence
- **Technology**: Espresso, Compose Testing, real backend
- **Location**: Integration test suites
- **Coverage**: 10% - critical user journeys only
- **Speed**: Slow (multiple seconds)
- **Scope**: Complete workflows, data persistence, cross-feature interactions

### Benchmarks

- **Purpose**: Measure startup, frame timing, interaction latency, and hot-path CPU/allocation cost
- **Technology**: Macrobenchmark, Baseline Profiles, Microbenchmark, Perfetto, Android vitals
- **Location**: `benchmark/` modules and `docs/performance/`
- **Coverage**: Release-critical user journeys and hot paths
- **Speed**: Slow for macrobenchmarks, fast-to-moderate for microbenchmarks
- **Scope**: Startup, scroll/jank, baseline profile generation, deterministic hot-path performance

## Test Organization

```
module/src/
├── commonMain/
│   └── kotlin/
├── commonTest/              # Shared unit tests
│   └── kotlin/
│       ├── usecase/
│       ├── entity/
│       ├── util/
│       └── fakes/           # Test doubles
├── androidMain/
├── androidTest/             # Android-specific tests
│   └── kotlin/
├── screenshotTest/          # Screenshot tests
│   └── kotlin/
│       └── app/logdate/screenshots/
└── screenshotTestDebug/
    └── reference/           # Baseline images
```

## Reference: Common Commands

### Running Tests

```bash
# Run all unit and integration tests
./gradlew test

# Run for specific module
./gradlew :module:test

# Run specific test class
./gradlew test --tests "MyUseCaseTest"

# Run specific test method
./gradlew test --tests "MyUseCaseTest.testMethodName"
```

### Screenshot Tests

```bash
# Update baseline screenshots
./gradlew :app:android-main:updateDebugScreenshotTest  # phone
./gradlew :app:wear:updateDebugScreenshotTest           # Wear OS

# Validate against baseline
./gradlew :app:android-main:validateDebugScreenshotTest  # phone
./gradlew :app:wear:validateDebugScreenshotTest           # Wear OS
```

### Coverage Reports

```bash
# Generate coverage report
./gradlew koverHtmlReport

# Verify coverage meets threshold
./gradlew koverVerify
```

### Benchmarks

```bash
# Phone and Wear macrobenchmarks
./gradlew managedPhoneBenchmark
./gradlew managedWearBenchmark

# Generate baseline profiles
./gradlew generatePhoneBaselineProfile
./gradlew generateWearBaselineProfile

# Android microbenchmarks
./gradlew managedMicroBenchmark
```

## Reference: Testing Patterns

### Test Naming Convention

Use the "given-when-then" pattern for clarity:

```kotlin
@Test
fun givenEmptyDatabase_whenFetching_thenReturnsEmpty() { }

@Test
fun whenUserCreatesEntry_thenDataIsPersisted() { }

@Test
fun givenNetworkError_whenFetching_thenReturnsCachedData() { }
```

### Arrange-Act-Assert Structure

Every test follows this three-step pattern:

```kotlin
@Test
fun exampleTest() {
    // Arrange: Set up test data and conditions
    val input = testData()
    val useCase = MyUseCase(fakeRepository)

    // Act: Execute the code being tested
    val result = useCase.execute(input)

    // Assert: Verify the results
    assertThat(result).isEqualTo(expected)
}
```

### Test Doubles

Choose the right tool for the job:

- **Fakes**: Full working implementations (preferred for most cases)
- **Mocks**: Verify interactions and method calls
- **Stubs**: Return fixed values without logic
- **Spies**: Track calls while executing real code

Example fakes available in `src/*/Test/kotlin/fakes/` directories

## Reference: Kotlin Multiplatform Testing

### Shared Tests

Run on all platforms using Kotlin's testing infrastructure:

```kotlin
// src/commonTest/kotlin/ - runs on Android, iOS, Desktop
@Test
fun sharedLogicWorks() { }
```

### Platform-Specific Tests

For platform-specific behavior:

```kotlin
// src/androidTest/kotlin/
@Test
fun androidSpecificFeature() { }

// src/iosTest/kotlin/
@Test
func iosSpecificFeature() { }
```

## Reference: Continuous Integration

Tests run automatically on pull requests and main branch:

| Stage            | Tests               | Command                                         |
| ---------------- | ------------------- | ----------------------------------------------- |
| **Pull Request** | Unit + Integration  | `./gradlew test`                                |
| **Pull Request** | Phone screenshots   | `:app:android-main:validateDebugScreenshotTest` |
| **Pull Request** | Wear OS screenshots | `:app:wear:validateDebugScreenshotTest`         |
| **Main Branch**  | Full suite          | All tests + coverage checks                     |

Configuration: `.github/workflows/ci.yml`

## Explanation: Best Practices

### Core Principles

1. **Write Tests First**: TDD clarifies requirements and prevents bugs
2. **One Concern Per Test**: Easier to understand failures
3. **Test Behavior, Not Implementation**: More robust to refactoring
4. **Keep Tests Fast**: Immediate feedback improves workflow
5. **Use Descriptive Names**: Test names document expected behavior

### Organization Principles

1. **Use Fixtures and Builders**: Reduce test setup duplication
2. **Leverage Fakes Over Mocks**: Simpler and more maintainable
3. **Mock Only External Dependencies**: Database, network, system
4. **Maintain Baseline Images**: Version control screenshot baselines
5. **Keep Tests Current**: Update tests when code changes

### Coverage Goals

Target coverage by layer:

- **Domain (Use Cases)**: 80%+
- **Data (Repositories)**: 70%+
- **UI (ViewModels)**: 60%+
- **Utilities**: 90%+

## Resources

- [Android Testing Guide](https://developer.android.com/training/testing) - Official Android best practices
- [Compose Testing](https://developer.android.com/jetpack/compose/testing) - Compose-specific testing
- [Kotlin Testing](https://kotlinlang.org/docs/reference/testing.html) - Kotlin multiplatform testing
- [Mockk Documentation](https://mockk.io/) - Kotlin mocking library
- [JUnit Documentation](https://junit.org/junit4/) - Test framework

## See Also

- [Unit Tests Guide](./unit-tests.md) - Detailed unit testing patterns
- [Integration Tests Guide](./integration-tests.md) - Database and API testing
- [UI Tests Guide](./ui-tests.md) - Compose component testing
- [Screenshot Tests Guide](./screenshot-tests.md) - Visual regression testing
- [E2E Test Index](./e2e-test-index.md) - Critical user paths and the tests that cover them
