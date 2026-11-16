# `:client:networking`

This module is the client's network layer. It provides the shared Ktor `HttpClient`, the clients for the LogDate server's HTTP APIs, a connectivity monitor, and a data usage policy that tells other modules how much network work is appropriate right now. Sync, media, intelligence, permissions and the account features all call the server through it.

## What it contains

All code lives under `app.logdate.client.networking`.

**HTTP client**

- `httpClient` is an `expect val` with one `actual` per platform. Android uses the Ktor Android engine, the JVM uses OkHttp, iOS uses Darwin, and wasmJs uses the JS engine. Every platform applies `configureClientDefaults()`, which sets a 15 second connect timeout, a 60 second socket timeout and no overall request limit (uploads can run long), and installs JSON content negotiation with `ignoreUnknownKeys` and a contextual `Uuid` serializer.
- `createNativeRestoreHttpClient()` on Android builds a separate client that does not follow redirects and uses short timeouts. Its caller owns it, so closing it cannot stop the application's shared client; `:client:data` uses it for native account recovery jobs.

**Server API clients.** Each has a Ktor-backed implementation, and most are declared through an interface named `...Contract`. Most calls return `Result` so callers can tell transport failures from server answers; `LocationHistoryApiClient` returns values directly and throws `LocationHistoryTransportException` on an HTTP failure.

| Client                        | Purpose                                                                                                                                                                                                                                                                            |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PasskeyApiClient`            | Username availability, passkey account creation and authentication, Google sign-up and sign-in, token refresh and logout, account info, entitlement and profile updates, passkey listing, adding and deletion, restore key registration and restore sign-in, and account deletion. |
| `DeviceEnrollmentApiClient`   | Creating, approving and rejecting a device enrollment, and issuing the new device its session tokens.                                                                                                                                                                              |
| `DeviceLinkApiClient`         | Minting, redeeming and removing device link codes under `/auth/devices`.                                                                                                                                                                                                           |
| `EmailVerificationApiClient`  | The begin and complete steps of Android Digital Credentials email verification.                                                                                                                                                                                                    |
| `IdentityApiClient`           | AT Protocol identity status, hosted PLC operations, and signing key export, rotation, import and recovery.                                                                                                                                                                         |
| `AccountKeyEnvelopeApi`       | Checking whether the server supports account key envelopes, and fetching and storing one per credential.                                                                                                                                                                           |
| `LocationHistoryApiClient`    | Uploading encrypted location history records and reading changes since a cursor.                                                                                                                                                                                                   |
| `QuotaApiClient`              | The signed-in user's storage quota usage.                                                                                                                                                                                                                                          |
| `PlanCatalogClient`           | The plans a server offers, readable without an account.                                                                                                                                                                                                                            |
| `CloudTranscriptionApiClient` | Reserving a realtime transcription session lease.                                                                                                                                                                                                                                  |
| `ServerDiscoveryClient`       | Identifying a LogDate server and reporting a `DiscoveryFailure` (`UNREACHABLE`, `NOT_RESPONDING` or `UNREADABLE`).                                                                                                                                                                 |
| `ServerHealthChecker`         | A health check against a server base URL.                                                                                                                                                                                                                                          |

`DeviceApprovalCode` is the value type for the code a person checks while approving a device; it has `parse` and `verify`.

**Connectivity and data usage**

- `NetworkAvailabilityMonitor` reports `isNetworkAvailable()` and a `SharedFlow<NetworkState>`. `NetworkState` is `Connected` or `NotConnected`, each with a `lastConnected` instant. Implementations are `AndroidNetworkAvailabilityMonitor`, `DesktopNetworkAvailabilityMonitor` and `IosNetworkAvailabilityMonitor`.
- `NetworkSaverModeProvider` and `NetworkSaverState` (package `saver`) describe Android's background data status, the active connection type, whether it is metered and whether the system validated it. The interface is owned here so this module can consume it without depending on the sensor module, which supplies the platform implementations. `ConfigurableNetworkSaverModeProvider` is a settable implementation for platforms and tests without data saver detection.
- `DataUsagePolicy` turns that state into a `DataUsageMode`: `Unrestricted` (an unmetered connection such as Wi-Fi or Ethernet), `Conservative` (a metered connection such as cellular, or a watch's Bluetooth link through its phone) or `Restricted` (no connection, or data saver on while the connection is metered or a Bluetooth link). `DefaultDataUsagePolicy` is the implementation. Extension functions such as `shouldSyncMedia()`, `shouldSyncMetadata()` and `shouldAllowAICalls()` answer the common questions, and `currentRestriction()` returns a `DataRestriction` (`NONE`, `OFFLINE` or `BACKGROUND_DATA_BLOCKED`) so the UI can say why nothing is happening.

**Dependency injection.** `networkingModule` is an `expect val` Koin module. Each platform binds `httpClient`, `NetworkAvailabilityMonitor` (not on wasmJs), `DataUsagePolicy`, `ServerHealthChecker`, `ServerDiscoveryClient`, `PlanCatalogClient` and `CloudTranscriptionApiClientContract`. The other API clients are constructed by the modules that use them.

## How it fits with other modules

The module depends on `:shared:model`, `:shared:config` and `:client:util`, and on Ktor, kotlinx.serialization, kotlinx.datetime, Koin and Napier. It exposes the Ktor content negotiation and serialization libraries as `api` dependencies.

`:client:sync`, `:client:domain`, `:client:data`, `:client:intelligence`, `:client:media`, `:client:permissions`, `:client:sensor`, `:client:feature:core`, `:client:feature:timeline`, `:integration:server-client-e2e` and the three app modules depend on it.

## Platforms

Android, JVM (desktop), iOS (`iosArm64` and `iosSimulatorArm64`) and wasmJs (browser).

## Running the tests

The shared tests in `commonTest` use the Ktor mock engine and run on the JVM and on Android host tests. `androidHostTest` adds a test for `AndroidNetworkAvailabilityMonitor`.

```bash
./gradlew :client:networking:jvmTest
./gradlew :client:networking:testAndroidHostTest
```

## Things to know

- The shared client never sends request bodies, headers or URLs to a logging sink. Ktor's general logger would include private origins, resource IDs, cookies and query values, so operation diagnostics are emitted separately by the sync module through an allowlisted event contract.
- Authenticated calls take the access token as a parameter, so the caller chooses which session a request runs under.
