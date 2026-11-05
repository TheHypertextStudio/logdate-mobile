# `:app:compose-main`

**Main cross-platform application module using Compose Multiplatform**

## Overview

This is the primary application entry point that orchestrates all feature modules into a cohesive user experience. It contains the main navigation structure, dependency injection setup, and platform-specific configurations for Android, iOS, and Desktop platforms.

Shared implementation supports LogDate's memory platform; it does not require one user experience
on every device. Shape navigation, capture, reflection, and sharing around the client's form factor,
inputs, and use case. Follow the [product positioning](../../docs/product-info/product-positioning.md)
when deciding what belongs in shared behavior and what should vary by client.

## Platforms Supported

- **Android**: Target SDK 35, Min SDK 30
- **iOS**: x64, arm64, simulator arm64
- **Desktop**: JVM with native distribution packages (DMG, MSI, DEB)

## Key Components

### Navigation Structure
- **Android (Navigation3)**: `MainNavigationRoot.kt`, `MainAppNavigator.kt`, scene/route files
- **Shared (Navigation Compose)**: `LogDateAppRoot.kt`, `LogDateNavHost.kt`, feature route files

### Platform Entry Points
- **Android**: `MainActivity.kt` and `LogDateApplication.kt`
- **iOS**: `MainViewController.kt` 
- **Desktop**: `main.kt`, `MainWindow.kt`, and `EditorWindow.kt`

### Dependency Injection
- **AppModule.kt** - Core DI module
- **AppModule.android.kt** - Android-specific DI
- **AppModule.desktop.kt** - Desktop-specific DI
- **AppModule.apple.kt** - iOS-specific DI

## Feature Modules Integration

### Core Features
- `:client:feature:core` - Core application features
- `:client:feature:onboarding` - User onboarding flows
- `:client:feature:editor` - Entry creation and editing
- `:client:feature:timeline` - Timeline and browsing
- `:client:feature:journal` - Journal management
- `:client:feature:rewind` - Memory recall features

### Infrastructure Modules
- `:client:data` - Data layer implementation
- `:client:ui` - Shared UI components
- `:client:theme` - Application theming
- `:client:networking` - Network layer
- `:client:sync` - Data synchronization

## External Dependencies

- **Compose Multiplatform** - UI framework
- **AndroidX Navigation3** - Android navigation stack
- **AndroidX Navigation Compose** - Shared navigation
- **Koin** - Dependency Injection
- **Napier** - Logging
- **FileKit Compose** - File operations
- **Material3 Adaptive** - Responsive layouts

## Architecture

The app module follows a feature-based modular architecture where:
- Each feature is self-contained with its own UI, navigation, and dependencies
- Infrastructure modules provide shared functionality
- Dependency injection wires everything together at the app level
- Adaptive layouts are used for different screen sizes and orientations

## Navigation Flow

```
Android (Navigation3)
├── Onboarding Flow (first launch)
├── Home Scene (tabs + adaptive panes)
├── Editor (modal)
└── Detail Screens

Shared (Navigation Compose)
├── Onboarding Flow
├── Home
├── Editor
└── Detail Screens
```

## Adaptive Layout

The app implements adaptive layouts to support different screen sizes and orientations:
- Phone: Single-pane layouts with bottom navigation
- Tablet/Desktop: Multi-pane layouts with navigation rail or sidebar
- Foldable: Dynamic layouts that adjust to fold state

## Build Configuration

### Android
```kotlin
applicationId = "studio.hypertext.logdate"
versionCode = 1
versionName = "0.1.0"
```

### Desktop
```kotlin
mainClass = "app.logdate.MainKt"
packageName = "LogDate"
description = "The LogDate desktop application"
```

## TODOs

### Core Features
- [ ] Implement deep linking support
- [ ] Add app shortcuts for quick entry creation
- [ ] Implement backup/restore functionality
- [ ] Add widget support for quick note taking
- [ ] Implement offline sync conflict resolution UI

### Platform-Specific
- [ ] Add accessibility improvements
- [ ] Implement app update mechanism
- [ ] Add telemetry and analytics integration
- [ ] Implement multi-window support for desktop
- [ ] Add app lifecycle state management

### UI/UX
- [ ] Improve adaptive layouts for foldable devices
- [ ] Add tablet-specific optimizations
- [ ] Implement dark/light theme transitions
- [ ] Add custom animations for navigation
- [ ] Improve performance on low-end devices
