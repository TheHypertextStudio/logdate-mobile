# `:shared:config`

This module holds the runtime configuration that tells the LogDate client which server to talk to, plus the compile-time defaults that configuration starts from. The client networking, sync, account and data modules read the server address from it, and the server module reads its default port from it.

## What it contains

Everything lives in the package `app.logdate.shared.config`, apart from one constant in `app.logdate`.

| Type                                          | Purpose                                                                                                                                                                                                                                                                           |
| --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `LogDateConfigRepository`                     | The interface for the current server selection: `backendUrl`, `apiVersion`, `apiBaseUrl`, `localServerAddress` and `serverDescriptor`, with update functions for each and `resetToDefaults()`.                                                                                    |
| `DefaultLogDateConfigRepository`              | The implementation the app uses. It keeps the selection in `StateFlow`s, builds `apiBaseUrl` as `<backend url>/api/<version>`, and adds `https://` to a backend URL that does not start with `http`.                                                                              |
| `PinnedLogDateConfigRepository`               | A read-only implementation fixed to one server origin. Every update function throws `UnsupportedOperationException`. It is for callers that must reach one particular server regardless of the app's current selection, such as signing in to the server an account is moving to. |
| `PrivacyScopeEpoch` and `PrivacyEpochStorage` | A persisted random identifier (a UUID string) that changes whenever the selected server changes. `PrivacyEpochStorage` is the interface a platform implements to read and write it.                                                                                               |
| `configModule`                                | A Koin module that binds `LogDateConfigRepository` to `DefaultLogDateConfigRepository`, passing a `PrivacyScopeEpoch` when one is registered in Koin.                                                                                                                             |
| `SERVER_PORT`                                 | The constant `8765`, the default port of the LogDate server.                                                                                                                                                                                                                      |

`DefaultLogDateConfigRepository` starts from these values: `DEFAULT_BACKEND_URL` and `GOOGLE_SERVER_CLIENT_ID` (both read from the generated `BuildConfig`), `DEFAULT_API_VERSION` (`v1`) and `DEFAULT_LOCAL_SERVER_ADDRESS` (`localhost:8765`).

### Build-time values

The `buildConfig` block in `build.gradle.kts` generates `BuildConfig` in `app.logdate.shared.config` with two fields:

- `DEFAULT_BACKEND_URL` comes from the Gradle property `logdate.backendUrl` and is `https://cloud.logdate.app` when the property is absent. Pass `-Plogdate.backendUrl=<url>` to point a build at another server.
- `GOOGLE_SERVER_CLIENT_ID` comes from the Gradle property `logdate.googleServerClientId`, then from the environment variable `LOGDATE_GOOGLE_SERVER_CLIENT_ID`, and is empty otherwise. An empty value means the client hides Google sign-in.

### Server selection and privacy

Changing the backend URL or API version, resetting to the defaults when the selection differs from them, or applying a server descriptor whose origin, API base URL, passkey relying party or passkey and owner-binding support differs from the stored one counts as a change of server. When a `PrivacyScopeEpoch` is supplied, `DefaultLogDateConfigRepository` rotates it in the same locked step that publishes the new selection, so code that holds an epoch value can tell whether the selection changed underneath it. `PrivacyScopeEpoch.withStableValue` runs a block only if the stored epoch still matches the value the caller read earlier. The epoch is documented as a private boundary that never appears in diagnostic events or reports.

`updateServerDescriptor` ignores a descriptor whose `serverOrigin` does not match the current backend URL, and `getCurrentServerDescriptor()` returns the stored descriptor only while it matches the backend URL.

## How it fits with other modules

The module depends on `:shared:model` (it uses `ServerDescriptor`, `ServerCapability` and `ServerProtocolFeature`), `kotlinx-datetime`, `kotlinx-serialization`, `kotlinx-coroutines` and Koin.

These modules declare a dependency on it: `:app:android-main`, `:app:compose-main`, `:app:wear`, `:client:data`, `:client:datastore`, `:client:device`, `:client:domain`, `:client:feature:core`, `:client:feature:journal`, `:client:feature:location-timeline`, `:client:networking`, `:client:sharing`, `:client:sync`, `:integration:server-client-e2e` and `:server`.

## Platforms

The module is Kotlin Multiplatform with a single `commonMain` source set and these targets: `iosArm64`, `iosSimulatorArm64`, `jvm` and `wasmJs` (browser). The Android and desktop clients consume the `jvm` artifact.

## Tests

The module has no test source set.
