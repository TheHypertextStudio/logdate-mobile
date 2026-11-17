# Dependency injection with Koin

LogDate uses [Koin](https://insert-koin.io) to assemble its object graph on Android, iOS and
desktop. This page explains how the Koin modules are organised, how each platform starts them, and
which rules keep the layers of the client independent of each other. The Koin version is the
`koin` entry in `gradle/libs.versions.toml`.

## Why dependencies are injected

A ViewModel, a use case or a repository never constructs the things it depends on. It declares them
as constructor parameters, and Koin supplies them. This keeps two promises that the rest of the
client relies on:

- **Platform differences stay at the edge.** A biometric prompt, a file export or a database driver
  exists on one platform and not on another. Shared code asks for an interface, and each platform
  registers the implementation it has.
- **Tests can replace anything.** A test builds a small Koin application containing fakes for the
  interfaces under test, so no real database, network or device service is needed.

## How the layers depend on each other

The client is layered, and a layer may know only about the layer beneath it:

```
Feature modules   ViewModels and screens                  client/feature/*
      |  inject use cases and repository interfaces
Domain            use cases (business operations)         client/domain
      |  inject repository interfaces
Repository        interfaces and models                   client/repository
      ^
Data              implementations of those interfaces     client/data, client/database, client/sync
```

The arrow between Repository and Data points upward on purpose: `client/data` depends on the
interfaces and implements them, while `client/domain` depends only on the interfaces. Gradle
enforces this. `client/domain` has no dependency on `client/data` or `client/database`, so a use
case cannot name a concrete repository even by accident. Most feature modules are the same. The
exceptions are `client/feature/core` and `client/feature/postcards`, which also depend on
`client/database`; treat that as a reason to keep their concrete bindings in
their own Koin modules, not as a model for new features.

`client/domain` also depends on the sync, media, location, device, networking and similar client
modules, which expose their own interfaces. The rule about implementations applies to `client/data`
and `client/database` only.

The practical rule is to inject interfaces. A ViewModel takes the use cases it needs to perform an
operation and the repository interfaces it needs to observe state, as `JournalDetailViewModel`
does. It never takes a Room DAO, an HTTP client, or a class named after its storage (such as
`OfflineFirstJournalRepository`). The binding from interface to implementation happens once, in a
data module or a platform module:

```kotlin
single<JournalRepository> { OfflineFirstJournalRepository(get(), get(), get(), /* ... */) }
```

Use cases follow the conventions in [Writing use cases](./reference/architecture/use-cases.md), and
ViewModels follow [UI state and ViewModels](./reference/architecture/ui-state.md).

## Module composition

A Koin `module { }` is a bag of definitions. One module pulls another in with `includes(...)`, which
is how a feature module states what it needs:

```kotlin
val editorFeatureModule: Module =
    module {
        includes(domainModule)
        includes(platformEditorModule)
        includes(audioModule)
        includes(audioUiModule)

        factoryOf(::ContentLoader)
        viewModel { EntryEditorViewModel(savedStateHandle = get(), observeEditorData = get(), /* ... */) }
    }
```

Each feature module lives in its feature's `di` package and registers that feature's ViewModels. The
app module in `app/compose-main` (`app.logdate.di`) assembles everything:

- `appModule` is an `expect val`. Android, desktop and iOS each provide an `actual` that includes
  the data, networking, device, sensor, domain, health, calendar-sync and audio modules for that
  platform.
- `defaultModules`, in common code, is the set of feature modules every platform includes: core,
  account, onboarding, editor, timeline, rewind, journals, library, location timeline, search,
  postcards and events. When a new feature gets a Koin module, add it to this set.
- Android adds modules that only exist there: workers, ambient mode, shortcuts, Wear sync,
  widgets, windowing and the platform coordinators and schedulers.

Because Koin lets a later definition replace an earlier one for the same type, a type bound in two
modules silently takes the binding of whichever loads last. `DeviceModuleCompositionTest` guards
against this for the device modules by building them with `allowOverride(false)`. Bind each
interface in exactly one module.

### Breaking cycles

Two patterns keep circular dependencies out of the graph:

- `appDataModule` (in `client/data`) includes `dataModule` and `syncModule` together. Journal
  repositories need the sync manager and the sync manager needs repositories, so the pair is
  composed in one place rather than having either module include the other.
- A repository can take a provider instead of the object itself, for example
  `syncManagerProvider = { get() }`, so the dependency is resolved at first use instead of at
  construction.

## Platform implementations with expect and actual

A module whose contents differ by platform is declared with Kotlin's `expect`/`actual` mechanism.
Common code declares the module, and each platform source set provides its own:

```kotlin
// commonMain
expect val corePlatformModule: Module

// androidMain
actual val corePlatformModule: Module = module {
    single { AndroidBiometricGatekeeper() }
    single<BiometricGatekeeper> { get<AndroidBiometricGatekeeper>() }
    single<ExportLauncher> { get<AndroidExportLauncher>() }
    // ...
}

// desktopMain
actual val corePlatformModule: Module = module {
    single<BiometricGatekeeper> { NoOpBiometricGatekeeper() }
    single<ExportLauncher> { DesktopExportLauncher() }
    // ...
}
```

The platform modules are named `*PlatformModule` where a feature has one (`corePlatformModule`,
`platformEditorModule`, `onboardingPlatformModule`, `postcardsPlatformModule`), and a few
infrastructure modules such as `dataModule`, `databaseModule` and `locationModule` are `expect`
themselves. Choose the implementation by source set, never with a runtime check such as
`if (platform == Android)`: a runtime check compiles every platform's classes into every build and
hides which platform lacks a capability. Where a platform has nothing to offer, bind a real
do-nothing implementation (`NoOpBiometricGatekeeper`, `NoopEventInferenceLauncher`) so shared code
never has to handle a missing binding.

Optional collaborators use `getOrNull` with a neutral default, which lets a module work whether or
not another module is loaded:

```kotlin
captureActivity = getOrNull<CaptureActivitySource>() ?: CaptureActivitySource.None,
```

## Scopes

Koin offers three definition kinds, and LogDate uses them consistently:

| Definition      | Use it for                                                                                                                                                                                | Example                                        |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `single { }`    | Objects that hold state or must exist once: repositories, coordinators, schedulers                                                                                                        | `single<JournalRepository> { ... }`            |
| `factory { }`   | Stateless objects created on each request, which includes almost every use case; a use case that must hold shared state is registered with `single` instead, as `RefreshStreakUseCase` is | `factory { FetchEntryUseCase(get()) }`         |
| `viewModel { }` | ViewModels, owned by a ViewModel store rather than by Koin                                                                                                                                | `viewModel { NoteViewerViewModel(/* ... */) }` |

A ViewModel whose identity is fixed when it is created takes that value as a Koin parameter:
`viewModel { (noteId: Uuid, journalId: Uuid?) -> NoteViewerViewModel(noteId, journalId, /* ... */) }`.
A value that can change while the ViewModel lives, such as a search query arriving from a deep link, is
instead delivered through a method called from a `LaunchedEffect` in the screen, and an identifier
that must survive process death is kept in `SavedStateHandle` (see [UI state and ViewModels](reference/architecture/ui-state.md)).
Screens obtain ViewModels with `koinViewModel()` from `koin-compose-viewmodel`.

The shortcut forms `factoryOf`, `singleOf` and `viewModelOf` are fine when every constructor
parameter is a plain dependency. Write the definition out when a parameter is optional or has a
default, because the shortcut would ask Koin for it and fail at runtime; the Android workers in
`corePlatformModule` are an example.

## Starting Koin on each platform

- **Android.** `initializeKoin()` in `AppModule.android.kt` calls `startKoin` with `androidLogger()`,
  `androidContext(...)` and `appModule`, then configures WorkManager. It runs only after the device
  user has unlocked, because the journal database is encrypted and nothing may touch it earlier.
  `LogDateApplication` triggers it, and other entry points such as the capture tiles and the
  startup gate wait on the same startup through `ApplicationStartupCoordinator`, which starts Koin
  once if no Koin context exists yet.
- **Desktop.** `main()` wraps the window in `KoinApplication(koinConfiguration { modules(appModule) })`
  from `koin-compose`.
- **iOS.** SwiftUI does not create a Koin context. `initKoinIos()` starts Koin with `appModule` and
  returns immediately if a context already exists. The iOS host application must call it before any screen is shown.
- **Wear OS.** The watch app is a separate application with its own small set of modules, started in
  `LogDateWearApplication`; it does not use `appModule`.

## Testing with Koin

Build a small Koin application with only what the test needs, and close it afterwards:

```kotlin
val koinApplication =
    koinApplication {
        allowOverride(true)
        modules(
            module {
                single<PlaceDao> { FakePlaceDao() }
                single<NotePlaceResolver> { DatabaseNotePlaceResolver(get()) }
            },
        )
    }
try {
    assertIs<DatabaseNotePlaceResolver>(koinApplication.koin.get<NotePlaceResolver>())
} finally {
    koinApplication.close()
}
```

`DataModuleTest` is the reference example. Tests that exist to catch a wiring mistake, such as
`DeviceModuleCompositionTest`, compose the real modules instead and assert that the graph builds.
