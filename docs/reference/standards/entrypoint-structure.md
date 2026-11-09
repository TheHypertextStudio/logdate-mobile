# Entrypoint Structure and Size Limits

> How top-level entrypoints (server `Application.kt`, Android `MainActivity.kt`, and their
> equivalents) stay small, and the tooling that keeps them that way.

## The Rule

**An entrypoint is a table of contents, not an implementation.**

The entry function (`Application.module()`, `MainActivity.onCreate()`, `LogDateApplication.onCreate()`)
reads as a sequence of calls, one per concern. Each call is defined in its own file, next to the
code it configures. Someone reading the entrypoint should learn _what_ the app is made of; they
open the concern's file to learn _how_ it works.

An entrypoint that has grown past this is not "complex". It has simply absorbed work that belongs
elsewhere. The fix is always the same: name the concern, move it into its own file, leave one line
behind.

## Patterns

### Ktor server: `installX()` and `xRoutes()`

Plugins are installed by `internal fun Application.installX()` functions; routes are registered by
`internal fun Route.xRoutes(...)` functions. The composition root that resolves dependencies from
Koin and hands them to route functions lives in `routes/ServerRouting.kt`. Route files never touch
the container: they take explicit parameters, so the in-memory test apps can construct them
directly.

```kotlin
fun Application.module(...) {
    val openApiSpec = installOpenApi()
    installNetworkEdge()
    installServerKoin(isDatabaseAvailable)
    installSyncMaintenance(isDatabaseAvailable)
    installJsonSerialization()
    routing {
        openApiRoutes(openApiSpec)
        serverMetaRoutes(isDatabaseAvailable, healthInternalToken, releaseVersion)
    }
    atprotoRoutes()
    accountApiRoutes()
    contentApiRoutes()
}
```

Worked example: `server/src/main/kotlin/app/logdate/server/Application.kt` and the files it calls
into (`OpenApiPlugin.kt`, `NetworkEdge.kt`, `SyncMaintenance.kt`, `ServerJson.kt`,
`di/ServerKoin.kt`, `routes/ServerMetaRoutes.kt`, `routes/ServerRouting.kt`).

### Android Activity: `MainActivity<Concern>.kt`

State-free concerns become `internal fun MainActivity.<verb>()` extension functions in a
`MainActivity<Concern>.kt` file (following standard Kotlin filename conventions without suppressions).
Compose trees go into a top-level `@Composable` in their own file. Anything that must read or write
the Activity's private state stays in the class as a short private method that `onCreate` calls in
sequence.

Worked example: `app/compose-main/src/androidMain/kotlin/app/logdate/client/MainActivity.kt` with
`MainActivityMultiWindow.kt`, `MainActivityShare.kt`, `MainActivityHandoff.kt`, and
`MainActivityContent.kt`.

## Size Limits (detekt)

Keep new and modified source files at 500 lines or fewer, including tests. Split by responsibility;
do not compress formatting or add an exception to accommodate growth. Size enforcement belongs to
the existing linters, without a separate changed-file script or its own CI gate. Kotlin structural
limits use detekt; native Apple file lengths use SwiftLint's `file_length` rule.

detekt enforces two rules across every module, on main source sets only:

| Rule         | Limit     | Notes                              |
| ------------ | --------- | ---------------------------------- |
| `LongMethod` | 60 lines  | `@Composable` functions are exempt |
| `LargeClass` | 600 lines |                                    |

Run it with:

```bash
./run lint:complexity      # ./gradlew detekt
```

`./gradlew check` and the CI "Static analysis" job run it too. Configuration lives in
`config/detekt/detekt.yml`. Only size rules are active; the rest of detekt stays off until 2.0 is
stable (the repo runs a 2.0 alpha because no stable detekt supports Kotlin 2.3).

### Baseline policy

Each module may carry a `detekt-baseline.xml` recording findings that predate the rule. The
baseline exists so the rule bites on new growth without demanding a repo-wide rewrite.

- **Never add a new finding to a baseline.** If detekt flags code you wrote, split the function or
  class.
- **Shrink baselines when you can.** When you move code out of a baselined function, regenerate
  that module's baseline (`./gradlew :module:detektBaseline`) so the entry disappears.
- Regenerating a baseline is only acceptable when the diff removes entries or moves them to a new
  signature for the same pre-existing code.
