# `:client:permissions`

This module gives the client one cross-platform surface for two related jobs: asking the operating system for runtime permissions (location, notifications, media, calendar, contacts, Health Connect) and obtaining credentials from the platform's sign-in machinery (passkeys, Google ID tokens, Android restore credentials and Android email verification). Feature modules and the app shells depend on it so that their code talks to interfaces and never to Android, iOS or JVM APIs directly.

## What it contains

Everything lives in the package `app.logdate.client.permissions`, with Koin wiring in `app.logdate.client.permissions.di`.

| Type or file                                                                                                                                                                                                                  | What it does                                                                                                                                                                                                                                                                                 |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PermissionManager`, `PermissionType`, `PermissionStatus`, `PermissionResult`                                                                                                                                                 | Check, observe and request permissions, open the system settings screens, and report whether a rationale should be shown. `createPermissionManager()` is an `expect` function that returns the platform implementation.                                                                      |
| `ConfigurablePermissionManager`                                                                                                                                                                                               | A `PermissionManager` that grants everything by default and accepts per-permission overrides. The JVM manager extends it, and tests use it to simulate denial.                                                                                                                               |
| `rememberLocationPermissionState`, `rememberNotificationPermissionState`, `rememberHealthConnectPermissionState`, `rememberMediaLibraryPermissionState`, `rememberCalendarPermissionState`, `rememberContactsPermissionState` | Compose `expect` functions that return a small state object for one permission group. Each state has `hasPermission`, `permissionRequested` and `requestPermission`; most also have `shouldShowRationale`, and the location, notification and Health Connect states add `isRequestInFlight`. |
| `LocationPermissionRequiredScreen`                                                                                                                                                                                            | A full-screen prompt that explains why location is needed, requests it, and calls `onPermissionGranted` once access exists. Its strings live in `src/commonMain/composeResources/values/strings.xml`.                                                                                        |
| `PasskeyManager`, `PasskeyException`, `PasskeyErrorCodes`                                                                                                                                                                     | Registers and authenticates WebAuthn passkeys from the options the server supplies, returning the credential response as JSON.                                                                                                                                                               |
| `PasskeyAuthenticationResult`                                                                                                                                                                                                 | Returned by `authenticateWithUnlock`. It splits the assertion JSON that is safe to send to the server from the optional 32-byte PRF unlock secret, which stays on the device and is left out of serialization and `toString`.                                                                |
| `RestoreCredentialManager`, `GuardedRestoreCredentialManager`, `RestoreCredentialError`, `NoOpRestoreCredentialManager`                                                                                                       | Android restore credentials: a backup key created after account creation and redeemed silently on a restored device. Other platforms use the no-op implementation.                                                                                                                           |
| `GoogleSignInManager`, `NoOpGoogleSignInManager`                                                                                                                                                                              | Returns a Google ID token for the server's Google sign-up and sign-in endpoints.                                                                                                                                                                                                             |
| `EmailVerificationManager`, `EmailVerificationOutcome`, `UnavailableEmailVerificationManager`                                                                                                                                 | Drives the begin, system picker, complete round trip for verifying an email through Android Digital Credentials. Every outcome is a value of the sealed `EmailVerificationOutcome`; nothing throws.                                                                                          |
| `commonPermissionsModule`, `permissionsModule`                                                                                                                                                                                | Koin modules. The common module binds the permission manager and the no-op or unavailable defaults; each platform's `permissionsModule` includes it and overrides what the platform supports.                                                                                                |

Platform implementations sit beside the interfaces: `AndroidPermissionManager`, `AndroidPasskeyManager`, `AndroidRestoreCredentialManager`, `AndroidEmailVerificationManager` and `AndroidGoogleSignInManager` in `androidMain`; `IosPermissionManager` and `IosPasskeyManager` (built on `ASAuthorizationPlatformPublicKeyCredentialProvider`) in `iosMain`; `JvmPermissionManager` and `DesktopPasskeyManager` in `jvmMain`. `DesktopPasskeyManager` reports passkeys as unsupported.

## How it fits with other modules

The module depends on `:shared:model` (which defines `PasskeyRegistrationOptions`, `PasskeyAuthenticationOptions` and `PasskeyCapabilities`) and `:client:networking` (the email verification API client). On Android it also uses Accompanist Permissions, AndroidX Credentials with the Play services provider, the Google Identity Services `googleid` library, AndroidX Activity Compose and Health Connect.

The build files of `:app:android-main`, `:app:compose-main`, `:app:wear`, `:client:data`, `:client:domain`, `:client:location` and the feature modules `core`, `editor`, `events`, `location-timeline`, `onboarding` and `timeline` declare a dependency on this module.

## Platforms

Android, iOS (`iosArm64`, `iosSimulatorArm64`) and JVM. The common code is shared by all three.

## Things to know

- On Android, passkey registration requests PRF support and the unlock request evaluates the WebAuthn PRF extension on a fixed input (`PasskeyUnlockInput.first`). A provider that returns no PRF output still authenticates, and a malformed PRF output is rejected.
- On iOS the relying-party identifier in the passkey options must match a domain in the app's associated-domains entitlement and its `apple-app-site-association` file, otherwise iOS rejects the request and the failure arrives as a `PasskeyException`.
- `EmailVerificationManager.isSupported` is true only on Android with a working Digital Credentials integration, so callers gate their UI on it.
- Restore key creation failing with `RestoreCredentialError.BackupUnavailable` is a normal outcome on devices without end-to-end encrypted backup.

## Running the tests

```bash
./gradlew :client:permissions:testAndroidHostTest
./gradlew :client:permissions:jvmTest
```

`commonTest` covers the email verification outcome mapping and the passkey unlock parsing. `androidHostTest` covers the Android passkey and restore credential request JSON.
