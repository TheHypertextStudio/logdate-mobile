# iOS Build Setup

LogDate iOS engineers must configure company signing and Firebase before
building or exporting the app. The Apple application identifier is
`studio.hypertext.apps.logdate` under company team `T95VDD3A4W`.

## Prerequisites

- Xcode 16 or newer (current builds were verified on Xcode 26.4)
- An Apple Developer account with provisioning enabled for the bundle
  `studio.hypertext.apps.logdate` under team `T95VDD3A4W`
- Kotlin/JDK already provisioned by the repo's Gradle wrapper — no extra setup

The Xcode project lives in
[`iosApp/iosApp.xcodeproj`](../../iosApp/iosApp.xcodeproj). The Compose
Multiplatform Kotlin framework it embeds comes from
[`:app:compose-main`](../../app/compose-main).

## Bundle Identifier

The runtime bundle identifier is configured in
[`iosApp/Configuration/Config.xcconfig`](../../iosApp/Configuration/Config.xcconfig)
and assembled by `PRODUCT_BUNDLE_IDENTIFIER = "${BUNDLE_ID}"` in the
`iosApp` target.

| Setting          | Value                           |
| ---------------- | ------------------------------- |
| `BUNDLE_ID`      | `studio.hypertext.apps.logdate` |
| `TEAM_ID`        | `T95VDD3A4W`                    |
| Effective bundle | `${BUNDLE_ID}`                  |

The Compose macOS package and the separate native implementation in
`logdate-apple` use the same application identifier. They are alternative
implementations of LogDate, so they must not be installed as separate editions.
The embedded Kotlin framework has its own identifier,
`studio.hypertext.apps.logdate.compose`.

Before the first build, register the App ID in
[Apple Developer Console](https://developer.apple.com/account/resources/identifiers/list)
under team `T95VDD3A4W`. The App ID needs **HealthKit**, **Associated
Domains**, and **Push Notifications** capabilities — without them
`xcodebuild` fails with `Failed Registering Bundle Identifier: ... not
available` and the auto-generated provisioning profile lacks the required
entitlements. HealthKit access for verifiable health records additionally
requires an Apple-side approval ticket; submit it from the same screen and
omit `com.apple.developer.healthkit.access` from `iosApp.entitlements` until
the entitlement is granted.

The bundle identifier is the source of truth and **must match** several other
settings:

- `iosApp/iosApp/Info.plist` — `CFBundleURLTypes[0].CFBundleURLName` and every
  entry in `BGTaskSchedulerPermittedIdentifiers`
- `iosApp/iosApp/AppDelegate.swift` — the `syncTaskIdentifier` constant
- The Firebase `BUNDLE_ID` field in `GoogleService-Info-Release.plist`
- Apple Developer App ID + provisioning profile

If you change the bundle ID, update every location in that list.

### Provider registration and existing installations

Register the application identifier with the capabilities above, create matching
development and distribution profiles, and use an App Store Connect app whose
bundle ID matches. A provisioning profile for an earlier identifier cannot sign
this app. Keep the publish safety switches disabled until these inputs are ready.

Register a new iOS app in the production Firebase project for this bundle ID.
Download its plist; changing `BUNDLE_ID` inside an earlier plist does not create a
Firebase registration. Set the GitHub repository variable
`LOGDATE_FIREBASE_IOS_APP_ID` to the new registration's `GOOGLE_APP_ID` and replace
`LOGDATE_IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64` with that plist. For local validation
and `scripts/sync-firebase-configs.sh ios`, export the same
`LOGDATE_FIREBASE_IOS_APP_ID`. Validation rejects missing configuration, mismatched
app IDs, and the retired Firebase registration.

Google OAuth iOS clients and any iOS API-key restrictions must name
`studio.hypertext.apps.logdate`; the web/server OAuth audience remains its separate
registered client ID. When enabling Sign in with Apple, register this native
bundle ID as an accepted audience with the identity provider. The current server
does not configure an Apple sign-in audience. Configure APNs sender topics and
App Store notification verifiers for the same bundle ID wherever those services
are deployed.

The associated domains `logdate.app` and `*.logdate.app` must serve an
`apple-app-site-association` file authorizing
`T95VDD3A4W.studio.hypertext.apps.logdate` for the applicable `applinks` and
`webcredentials` services. Those files are maintained by the web host, outside
this repository. Keep earlier app entries while existing installations need them.
The return URL schemes `studio.hypertext.logdate` and `logdate` remain supported
for existing links and OAuth callbacks; they are separate from the bundle ID.

Changing a bundle ID gives the app a different sandbox and default Keychain
access group. Preserve existing installations and their data; do not uninstall
them to validate this migration. Keychain service names, database paths, and
Android packages remain unchanged in source. That does not grant the new app
access to an earlier app's sandbox or Keychain group. Establish an explicit
export/import or supported transfer path and verify recovery before replacing an
installation containing real journal data.

## Firebase Setup

Firebase Crashlytics and Analytics ship with **Release builds only**. Debug
builds run without Firebase so dev devices stay decoupled from the production
project.

### Production plist

The Release plist is gitignored. Drop it into the repo before shipping:

1. Download the production iOS Firebase project's `GoogleService-Info.plist`
   from the [Firebase console](https://console.firebase.google.com/) — make
   sure the iOS app there is registered with bundle id
   `studio.hypertext.apps.logdate`.
2. Save it as
   `iosApp/iosApp/Firebase/GoogleService-Info-Release.plist` (not tracked; the directory exists only after you add the file).
3. Export `LOGDATE_FIREBASE_IOS_APP_ID` from the registration's `GOOGLE_APP_ID`,
   then run `./scripts/validate-firebase-config.sh ios
iosApp/iosApp/Firebase/GoogleService-Info-Release.plist` before a Release build.

### How the plist gets into the bundle

The `iosApp` target has a Run Script build phase named **"Copy Firebase config
(Release only)"** that:

- Copies `GoogleService-Info-Release.plist` to
  `${BUILT_PRODUCTS_DIR}/${PRODUCT_NAME}.app/GoogleService-Info.plist` when
  `$CONFIGURATION = Release`.
- Removes any stale `GoogleService-Info.plist` from the bundle for every other
  configuration (Debug, etc.).

`iOSApp.swift` calls `FirebaseApp.configure()` only when the plist exists in
the bundle, so Debug builds launch without Firebase and never crash on the
missing config.

### Verifying

```bash
# Debug — no plist in the bundle.
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination generic/platform=iOS build
ls iosApp/build/Build/Products/Debug-iphoneos/LogDate.app/GoogleService-Info.plist
# → No such file or directory (correct)

# Release — plist gets copied in.
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination generic/platform=iOS build
plutil -extract BUNDLE_ID raw \
  iosApp/build/Build/Products/Release-iphoneos/LogDate.app/GoogleService-Info.plist
# → studio.hypertext.apps.logdate
```

## Building from the Command Line

```bash
# Resolve the connected device's UDID
xcrun xctrace list devices

# Build, install, and launch on a physical device
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "id=<UDID>" -derivedDataPath build \
  -allowProvisioningUpdates build
xcrun devicectl device install app --device <UDID> \
  build/Build/Products/Debug-iphoneos/LogDate.app
xcrun devicectl device process launch --console --device <UDID> \
  studio.hypertext.apps.logdate
```

The Kotlin framework is rebuilt by the **"Compile Kotlin Framework"** Run
Script phase (`./gradlew :app:compose-main:embedAndSignAppleFrameworkForXcode`)
on every Xcode build. First-time builds take 4–5 minutes; incremental builds
are fast.

## Common Pitfalls

- **`FirebaseApp.configure() could not find a valid GoogleService-Info.plist`** —
  the bundle has the plist but Firebase rejected it. Verify the plist's
  `BUNDLE_ID` matches `PRODUCT_BUNDLE_IDENTIFIER` exactly (case-sensitive).
- **`Failed to register BGTask identifier`** at launch — the
  `BGTaskSchedulerPermittedIdentifiers` entry in `Info.plist` and the
  `syncTaskIdentifier` constant in `AppDelegate.swift` must both start with the
  runtime bundle id. iOS rejects identifiers that don't.
- **App locked by Springboard** when launching from `devicectl` — the device
  must be unlocked. This is not a project misconfiguration.
- **`Cannot locate tasks that match ':composeApp:embedAndSignAppleFrameworkForXcode'`** —
  `iosApp.xcodeproj` is out of sync with the Kotlin module path. The Run Script
  must invoke `:app:compose-main:embedAndSignAppleFrameworkForXcode`.
