# `:client:datastore`

This module holds the small, local settings that are not journal content: user preferences, feature flags, the saved server configuration, the profile, and the key-value storage that other modules use for their own bookkeeping. Everything is stored in one AndroidX DataStore `Preferences` file, `logdate.preferences_pb`, which the module creates on each platform. It also defines the session types and the interfaces that store sign-in credentials. The directory is `client/datastore`, and the Gradle project is `:client:datastore`.

Nearly every client module reads settings from here, and [`:client:data`](../data/README.md), `:client:sync`, `:client:device`, `:client:domain` and the feature modules take their preferences and flags from it.

## What it contains

Everything is under `app.logdate.client.datastore`, except the Koin module, which is in `app.logdate.client.di`.

| Type                                                  | What it does                                                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `createDataStore`                                     | `expect` function that builds the `DataStore<Preferences>` at a platform-specific path. The shared helper registers `LegacyCloudCredentialMigration`, which removes the old plain-text token preferences                                                                                                                                                           |
| `LogdatePreferencesDataSource`                        | Typed `observe...` and `set...` accessors over the preferences: entry start mode, developer mode, quiet save, day start hour and day bounds, first day of week, journal layout, rewind settings, device calendar sync, event inference, people contact access and onboarding state. It also exposes the profile (display name, birthday, photo, bio) as `UserData` |
| `ArchiveProfileRecoveryStore`                         | Applies a restored profile and records its receipt in one preferences transaction                                                                                                                                                                                                                                                                                  |
| `featureflags`                                        | `FeatureFlag` lists each flag with its stored key, its default and whether it is available at launch. `FeatureFlagStore` and `DataStoreFeatureFlagStore` observe and set flags                                                                                                                                                                                     |
| `KeyValueStorage`                                     | Platform-neutral interface for strings, booleans, numbers and observation, plus `...OrThrow` variants for atomic snapshots, updates and compare-and-set                                                                                                                                                                                                            |
| `DataStoreKeyValueStorage`, `PrefixedKeyValueStorage` | The DataStore implementation, and a wrapper that namespaces keys so several stores can share one underlying storage                                                                                                                                                                                                                                                |
| `LogDateConfigDataSource`                             | Saves and loads the backend URL, API version and server descriptor from `LogDateConfigRepository`                                                                                                                                                                                                                                                                  |
| `SessionStorage`, `OriginSessionVault`, `UserSession` | Interfaces and types for the saved sign-in. The vault keeps one session per server origin. This module declares them, and other modules provide the implementations                                                                                                                                                                                                |
| `NativeRestorePolicy`                                 | Records, per server origin, whether a native backup restore may sign in                                                                                                                                                                                                                                                                                            |
| `EntryStartMode`                                      | Plain types for settings screens                                                                                                                                                                                                                                                                                                                                   |

`FeatureFlag` states that a flag is temporary: when a feature ships to everyone, the flag and its branches should be deleted. A flag's `key` is permanent once released, because changing it resets everyone who had set it. `DataStoreFeatureFlagStore` clears the stored value when a flag is set back to its effective default, so the flag follows the build default again.

`commonDatastoreModule` registers the main DataStore, a `KeyValueStorage`, the `deviceKeyValueStorage` and `userKeyValueStorage` named storages, `LogdatePreferencesDataSource`, `FeatureFlagStore` and `LogDateConfigDataSource`. The Android `datastoreModule` also reads the `app.logdate.watchExperienceDefault` and `app.logdate.quietSaveDefault` manifest metadata and passes them to the flag store as default overrides.

## Storage locations

| Platform | Location of `logdate.preferences_pb`                                                                                                                   |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Android  | The app's `filesDir`. `createAndroidMainDataStore` returns one shared instance per file, so native backup code and normal consumers use the same store |
| Desktop  | `~/.logdate`                                                                                                                                           |
| iOS      | The documents directory                                                                                                                                |

## How it fits with other modules

The module depends on `:shared:model`, `:shared:config` and `:client:util`, plus AndroidX DataStore Preferences, kotlinx serialization and datetime, Koin and Napier. `proguard-rules.pro` is published as a consumer keep rule for `app.logdate.client.datastore.**`.

## Platforms

Android, JVM (`jvm()`), `iosArm64` and `iosSimulatorArm64`. The per-platform `createDataStore` and `datastoreModule` implementations live in the `androidMain`, `jvmMain` and `iosMain` source sets.

## Tests

```bash
./gradlew :client:datastore:jvmTest
./gradlew :client:datastore:testAndroidHostTest
```

`jvmTest` runs the `commonTest` and `jvmTest` sources. They cover the preferences data source, feature flags, key-value storage, native restore policy, profile persistence and the credential migration. `testAndroidHostTest` runs `AndroidMainDataStoreRegistryTest`.
