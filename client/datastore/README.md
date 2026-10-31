# `:client:datastore`

**Key-value preferences storage using DataStore**

## Overview

Manages application preferences and settings using AndroidX DataStore. Provides type-safe access to user preferences with reactive updates across all platforms.

## Architecture

```
DataStore Module
├── DataStore Platform Implementations
├── Preferences Data Sources
├── Settings Data Classes
└── Dependency Injection
```

## Key Components

### Core Components

- `DataStore.kt` - Common DataStore interface
- `LogdatePreferencesDataSource.kt` - Preferences management
- `DatastoreModule.kt` - DI configuration

### Platform Implementations

- `DataStore.android.kt` - Android-specific implementation
- `DataStore.ios.kt` - iOS-specific implementation
- `DataStore.jvm.kt` - Desktop/JVM implementation

## Features

### Preferences Management

- Type-safe preference access
- Reactive preference updates via Flow
- Platform-specific storage locations
- Automatic preference serialization

### Supported Data Types

- Primitive types (String, Int, Boolean, etc.)
- Enum preferences
- Complex objects via serialization
- Collections and lists

## Dependencies

- **AndroidX DataStore**: Core preference storage
- **Kotlinx Coroutines**: Reactive programming
- **Koin**: Dependency injection

## Usage Pattern

```kotlin
class PreferencesRepository(
    private val dataSource: LogdatePreferencesDataSource
) {
    suspend fun setTheme(theme: Theme) = dataSource.setTheme(theme)
    fun getThemeFlow(): Flow<Theme> = dataSource.themeFlow
}
```

## TODOs

- [ ] Add preferences backup/restore
- [ ] Implement preferences validation
- [ ] Add preferences encryption for sensitive settings
- [ ] Implement preferences migration
- [ ] Add preferences synchronization across devices
- [ ] Implement theme and appearance preferences
- [ ] Add accessibility preferences
- [ ] Implement notification preferences
- [ ] Add preference categories and organization
- [ ] Implement preference import/export
