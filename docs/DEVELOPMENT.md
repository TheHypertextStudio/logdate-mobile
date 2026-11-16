# Development

This guide takes you from a fresh clone to a running LogDate app and server, and lists the
commands that build and test them. Every command goes through `./run`, which wraps Gradle,
Docker, and the helper scripts; `./run help` lists all of them. Deeper references are linked from
each section.

## Developer mode

In About LogDate, tap the app version seven times to reveal Developer Tools and Labs.
This choice is saved on this installation and survives app restarts. Choose **Hide developer mode**
on the same screen to hide both entries again, including after a restart. You can repeat the
seven-tap unlock later. Developer mode starts hidden on installations that have never enabled it.
Hiding the entries does not change diagnostic, location, or feature-preview preferences.

## Places beta

Enable **Places (beta)** in Settings to show Places in Home. The opt-in is saved across
app restarts and starts off. Turning it off returns an open Places view to Home and
removes Places links from the main navigation. Location recording, permission choices,
saved places and Rewind maps are unchanged. Developer mode is not required.

## Toolchain

LogDate is a Kotlin Multiplatform project built with Gradle. Install these before setting up:

- **JDK 17 or newer** to launch the Gradle wrapper. The wrapper pins Gradle 9.4.1, and the Gradle
  daemon runs on JDK 21, which Gradle downloads by itself from the daemon JVM criteria in
  `gradle/gradle-daemon-jvm.properties`.
- **Android Studio** (latest stable) with the Android SDK, emulator, and platform tools (`adb`).
  On macOS, `brew install --cask android-platform-tools` installs `adb` on its own.
- **Docker and Docker Compose** for the local PostgreSQL and Redis the server uses.
- **Xcode**, only for iOS work.
- **Node.js and pnpm**, only for `./run lint:lexicons`, `./run lint:conformance` and
  `./run conformance:update`.

## Set up

One command prepares a fresh clone or worktree: tools, Git hooks, `local.properties`, Firebase
configuration, and test dependencies. It is safe to re-run at any time.

```bash
./run setup            # set up this machine
./run setup --check    # report what is missing without changing anything
```

[Project setup](reference/project-setup.md) explains every step, including the environment
setup (`./run setup staging` and `./run setup production`) that only release maintainers need.

### Configuration

No production credentials are needed for ordinary development. Configuration lives in files Git
ignores, and each setting is read from exactly one place:

- `local.properties` holds settings that Gradle reads while building the client. `./run setup`
  creates it with `sdk.dir`, the Android SDK location. Two optional properties are read from it:
  `logdate.googleMapsApiKey`, the Maps and Places key the Android app needs for maps (the
  `LOGDATE_GOOGLE_MAPS_API_KEY` environment variable and the `-Plogdate.googleMapsApiKey` Gradle
  property take precedence, in that order), and `logdate.metaAppId`, the Meta app id for sharing.
  Server settings placed in this file have no effect.
- `.env` holds settings for processes that `./run` starts. `./run` first sources
  [`docs/environment/.env.example`](environment/.env.example) and then `.env`, exporting everything,
  so a value in `.env` wins. Copy the example to `.env` and change what you need. The server reads
  `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` and `REDIS_URL` from its process environment,
  so start it with `./run server`. The example's values match the Docker services that
  `./run start` creates (PostgreSQL on port 15432 and Redis on port 16379, both with the user and
  password `logdate`). Those container settings are fixed in `docker-compose.yml` and do not follow
  `.env`. [Server environment variables](../server/docs/environment-variables.md) lists every server
  setting and its default.
- `OPENAI_API_KEY` enables the cloud features in the intelligence module. The server also reads it
  from its environment for cloud transcription, which stays disabled while it is unset, so a server
  started with `./run server` gets the placeholder described below. The app reads it from the
  process environment or a JVM system property at runtime, so for the desktop app export it before
  `./run desktop`. It is not read from `local.properties`, no build step packages it into an app, and
  iOS never reads it. Without a key the cloud features report that they are unavailable. The example
  file sets a placeholder value that `./run` exports; until you replace it in `.env`, requests reach
  OpenAI with the placeholder and fail as unauthorized.

## Run

Start the local services first, then run the part of LogDate you are working on.

### Start the development services

```bash
./run start     # PostgreSQL, Redis, and dev tools in Docker
./run status    # show what is running
./run stop      # stop them; data is kept
```

### Run the server

```bash
./run server    # Ktor server on the local database
```

### Run the Android app

Run the Android app on an **emulator**. `./run android` installs on whatever device `adb` selects,
so name the emulator explicitly with `ANDROID_SERIAL`. Agents must never install on, test on, or
run `adb` against a physical Android device; see "Android Device Safety" in [AGENTS.md](../AGENTS.md).

```bash
adb devices                                  # find the emulator serial, such as emulator-5554
ANDROID_SERIAL=emulator-5554 ./run android   # install and open the debug build
ANDROID_SERIAL=emulator-5556 ./run run:wear  # the Wear OS app, on a Wear OS emulator
```

### Run the desktop app

```bash
./run desktop
```

## Build

Build only the targets you changed. The pre-push hook already compiles the Android app, so a full
build of every module is rarely needed.

```bash
./run build:android          # Android debug APK
./run build:wear             # Wear OS debug APK
./run build:desktop          # desktop package for this OS
./run build:server           # server JAR
./run build:server:docker    # server Docker image
```

Release builds (`./run build:android:release`, `./run build:wear:release`) need the signing setup
from `./run setup production`; see [Release and recovery](RELEASE.md).

## Test

Run the tests for the modules you changed, plus any suite whose contract your change touches. The
pre-push hook re-runs the tests for affected modules before code leaves your machine.

```bash
./gradlew :client:feature:timeline:test :client:repository:test   # tests for the modules you changed
./run test:server                                                 # server tests
./gradlew :integration:server-client-e2e:test                     # API and sync contract tests
./run test:screenshots                                            # Android, desktop, and Wear screenshot references
./run lint:check                                                  # ktlint
./run lint:complexity                                             # Detekt size limits
```

Instrumented Android tests run only on Gradle Managed Devices, described in [Devices](#devices).
Never run `connectedDebugAndroidTest`. `./run test` and `./run check` run every test and check in
the project; they take a long time and a lot of disk, so save them for changes to shared tooling or
configuration. The [testing guides](testing/introduction.md) explain each kind of test.

## Devices

LogDate follows the Studio
[device testing standard](https://github.com/TheHypertextStudio/product-standards/blob/main/docs/device-testing.md):
every automated device run uses a Gradle Managed Device declared in the root `build.gradle.kts`.
Gradle downloads the system image, creates the emulator, runs the tests, and removes it, so every
machine and CI lane tests the same devices. Never create an emulator by hand.

| Device                                                    | Role                                                    | Hardware profile    | API level |
| --------------------------------------------------------- | ------------------------------------------------------- | ------------------- | --------- |
| `oldestSupportedPhoneApi30`                               | Oldest supported phone (`minSdk`)                       | Pixel 2             | 30        |
| `phoneApi31`                                              | Behavior boundary: Android 12                           | Pixel 9 Pro         | 31        |
| `phoneApi33`                                              | Behavior boundary: Android 13                           | Pixel 9 Pro         | 33        |
| `phoneApi34`                                              | Behavior boundary: Android 14                           | Pixel 9 Pro         | 34        |
| `flagshipPhoneApi37`                                      | Target phone (`targetSdk`)                              | Pixel 9 Pro         | 37        |
| `foldableApi37`                                           | Foldable                                                | Pixel 9 Pro Fold    | 37        |
| `largeScreenTabletApi37`                                  | Tablet                                                  | Pixel Tablet        | 37        |
| `desktopApi34`                                            | Desktop windowing                                       | Medium Desktop      | 34        |
| `wearSmallRoundApi34` (`:app:wear`)                       | Watch                                                   | Wear OS Small Round | 34        |
| `restorePairApi34` (`:tools:native-restore-driver`)       | Native backup/restore source and destination shards     | Pixel 9 Pro         | 34        |
| `d2dPairApi34` (`:tools:native-restore-driver`)           | Google Play D2D transport source and destination shards | Pixel 9 Pro         | 34        |
| `restoreCallbackApi36_1` (`:tools:native-restore-driver`) | Modern full-restore callback contract                   | Pixel 9 Pro         | 36.1      |

Native transfer acceptance runs through
[`managed-native-restore-acceptance.py`](../tests/e2e/managed-native-restore-acceptance.py).
It pins the app/probe APKs and requires two concurrent Gradle-owned shards with matching
run-generation receipts. Use the [driver instructions](../tools/native-restore-driver/README.md)
for the native transfer, widget, media, and permission scenarios. A standalone emulator or a passing
waiting driver does not verify those journeys. The modern callback uses its separate managed-device
entrypoint and checks the full SDK version, including the minor version.

The phone behavior-boundary devices exist because LogDate's code branches on Android 12, 13, and
14 behavior. When a minimum-SDK raise or a code change removes such a branch, remove its device too.

| Group                           | Devices                                                        | Run it when                                          |
| ------------------------------- | -------------------------------------------------------------- | ---------------------------------------------------- |
| `smokeDevices`                  | Target phone, tablet                                           | Any change to Android UI                             |
| `deviceMatrix`                  | Oldest phone, target phone, foldable, tablet, desktop          | Before a release, and when adaptive behavior changes |
| `apiMatrix`                     | Oldest phone, the three behavior-boundary phones, target phone | When platform-version branches change                |
| `phoneDevices`, `tabletDevices` | Phones only, tablet only                                       | Focused runs                                         |
| `wearDevices` (`:app:wear`)     | Watch                                                          | Any change to the Wear app                           |

```bash
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest          # a group
./gradlew :app:android-main:foldableApi37DebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.DeepLinkResolverTest    # one device, one class
./gradlew :app:wear:wearDevicesGroupDebugAndroidTest                    # the watch
```

Two scenario profiles add their own devices, selected with `-Plogdate.managedDeviceProfile`:
`tabletOnly` (a single API 35 tablet) and `recoveryAcceptance` (three API 35 tablets for the
backup and restore journey). Managed-device state is build output; clear it with
`./gradlew cleanManagedDevices` once no session is running managed-device tests.

**Known blocker:** on this Apple silicon host with emulator 37.2.12, API 36 and 37 16 KB-page images
(foldable and resizable profiles) stopped booting with hanging CPU threads, while the API 34 desktop
device boots and passes. Until that is resolved, report results from devices that cannot boot as
unverified, and never substitute another device.

## Inspect the first workflow

Passing tests do not show that the app works. After a change, check the first thing a new user
does:

1. Install the debug build on a managed device run, or on an emulator you start from a managed
   device, with `ANDROID_SERIAL=<emulator serial> ./run android`.
2. Open LogDate, finishing onboarding if it appears.
3. Write an entry with text, then add a photo or a voice recording, and save it.
4. Confirm the entry is on Home, then restart the app and confirm it is still there.

No account or network connection is needed for this workflow. UI changes have a stricter
acceptance bar, described in [AGENTS.md](../AGENTS.md): populated screenshots at phone and tablet
sizes, dark theme, right-to-left layout, and large text.

## Land a change

Every change reaches `main` through a pull request merged with a rebase. The
[Git guidelines](reference/standards/git-guidelines.md#branch-and-pull-request-workflow) describe
the branch, pull request, and merge steps.

### Places supporting-sheet interactions

Places uses the shared `WorkspaceSupportingSheet` for direct finger tracking, velocity-based settling,
content-scroll handoff and Back-to-peek. Its overlay anchors remain distinct at large text sizes;
short windows temporarily use the focused layout and restore the chosen extent when space returns.
Resizing preserves the mounted map and selected content, while explicit collapse/full-view choices persist.
The shared rail separates navigation targets by 8dp while retaining its bounded native widths.
All Home destinations continue to use the same shell search field; contextual Places search replaces
that field instead of adding a second search control.

Run `:client:ui:desktopTest` and `checkHomeWorkspaceContract` for these changes. Native interaction
and capture coverage is `:app:android-main:phoneApi34DebugAndroidTest
-Plogdate.androidTestClass=app.logdate.client.e2e.PlacesSheetGestureE2ETest`. It retains a bounded,
silent interaction video with an explicitly synthetic map viewport. Native map readiness fallback
coverage is `PlacesMapFallbackE2ETest`: cards fill the available area, filtering and place actions work,
and no standalone map error or unavailable map controls remain. Its phone 411x891, foldable-sized
720x960 and landscape-tablet 1280x800 captures use actual native unconfigured-map readiness and
production components with synthetic memories. The real map host remains composed for late ready
callbacks; recovery restores the requested sheet extent and existing selection. Card reading width
remains bounded without reserving an empty map pane.
Resized emulator windows do not certify physical hinges, production Activity system bars or native map tiles.
