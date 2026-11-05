# `:app` Modules

This directory contains modules for final build targets and application entry points.

LogDate is a home to document, reflect on, and share memories. From an engineering perspective,
the apps are purpose-built clients of a shared memory platform. Each client should adapt workflows,
inputs, navigation, and content presentation to its use case and form factor. Common behavior and
memory continuity matter; identical interfaces and feature parity are not the goal. See the
[product positioning](../docs/product-info/product-positioning.md) for the governing direction and
the distinction between design opportunities and shipped client support.

## Available Applications

- [`:app:android-main`](./android-main/) - Android application wrapper and entry point.
- [`:app:compose-main`](./compose-main/README.md) - Shared Compose Multiplatform module for Android/iOS/Desktop UI and app logic.
- [`:app:wear`](./wear/README.md) - Wear OS client for brief memory capture and voice playback.

## Architecture

Each application module provides the platform-specific entry points, navigation structure, and dependency injection wiring that connects all feature modules together. The applications themselves contain minimal business logic, delegating instead to the various feature and infrastructure modules in the `:client` directory.

## Build & Run Commands

Agent installation and UI validation must use a verified emulator or Gradle Managed Device,
following the [Android device safety rules](../AGENTS.md#android-device-safety).

### Android Main App

```bash
# Build debug APK
./gradlew :app:android-main:assembleDebug
```

### Wear OS App

```bash
# Build debug APK
./gradlew :app:wear:assembleDebug
```

### Desktop App

```bash
# Run desktop app
./gradlew :app:compose-main:run

# Package desktop app
./gradlew :app:compose-main:packageReleaseDmg        # macOS
./gradlew :app:compose-main:packageReleaseMsi        # Windows
./gradlew :app:compose-main:packageReleaseDeb        # Linux
```

### iOS App

```bash
# Generate Xcode project
./gradlew :app:compose-main:podInstall

# Open in Xcode
open iosApp/iosApp.xcworkspace
```
