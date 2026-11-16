# LogDate

A home for your memories.

LogDate lets a person keep memories as entries made of text, photos, video, audio recordings, and
places. They look back through them on a timeline, in a media library, in search, and in a weekly
Rewind that summarizes the week. They choose what to share. Everything is stored on the device
first, so the app works without an account or a network connection. An optional LogDate Cloud
account adds sync between devices and an encrypted backup.

This repository holds the apps and the server. It is a Kotlin Multiplatform project: the apps share
their code through Compose Multiplatform, and the server is a Ktor application.

## Platforms

| Platform                        | Module                        | State                                              | What it does                                                                                                                                                                                                                            |
| ------------------------------- | ----------------------------- | -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android phone, tablet, foldable | `:app:android-main`           | Google Play internal testing track                 | Everything the shared app does, plus home screen widgets, launcher shortcuts, and speech transcription.                                                                                                                                 |
| Wear OS                         | `:app:wear`                   | Play internal testing track, with a gated redesign | Captures voice recordings, moods, and marked moments on the watch. The phone app is the editor and manages setup and reminders.                                                                                                         |
| iOS                             | `iosApp`, `:app:compose-main` | TestFlight builds from `main`                      | The shared app: entries, timeline, library, search, and Rewind. It records audio, captures photos and video, tracks location, and signs in to LogDate Cloud with a passkey.                                                             |
| Desktop (macOS, Linux, Windows) | `:app:compose-main`           | Local packages only, not published                 | The shared app. It records audio, imports photos and video from files, drag and drop, and paste, and plays video. It has no camera capture, no operating system location (a default location can be set), and no LogDate Cloud sign-in. |
| Server                          | `:server`                     | Runs on Google Cloud Run                           | Sync, encrypted backup, accounts, and hosted AT Protocol identities and repositories. See [`server/README.md`](server/README.md).                                                                                                       |

[Release and recovery](docs/RELEASE.md) describes how each platform ships.

## Repository layout

| Directory                                                | Contents                                                                                                                                                                                                               |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app/`                                                   | Application entry points. [`app/README.md`](app/README.md) introduces the three modules, and [`app/compose-main`](app/compose-main/README.md) and [`app/wear`](app/wear/README.md) have their own READMEs.             |
| `client/`                                                | Client code shared by the apps: UI, theme, domain, repositories, Room database, networking, sync, location, media, and one module per feature under `client/feature/`. Each module with a `README.md` explains itself. |
| `shared/`                                                | Code used by both the apps and the server: models, configuration, sealed personal space records, and the `atproto-*` AT Protocol libraries.                                                                            |
| `server/`                                                | The Ktor server and its API documents.                                                                                                                                                                                 |
| `iosApp/`                                                | The Xcode project that hosts the shared Compose UI on iOS.                                                                                                                                                             |
| `integration/`                                           | `server-client-e2e`, which exercises the real client against the real server.                                                                                                                                          |
| `benchmark/`                                             | Macrobenchmarks, microbenchmarks, and baseline profile generators for the phone and watch.                                                                                                                             |
| `lexicons/`, `conformance/`                              | The record schemas LogDate stores, and the test cases every LogDate client runs to read and write the same data.                                                                                                       |
| `tools/`                                                 | Developer tooling that is not shipped, such as the lexicon checker and the native restore driver.                                                                                                                      |
| `tests/`, `test-fixtures/`                               | End-to-end acceptance scripts, load tests, and shared fixtures.                                                                                                                                                        |
| `build-logic/`, `gradle/`, `config/`, `kotlin-js-store/` | Gradle convention plugins, the version catalog, lint and screenshot configuration, and the Kotlin/Wasm dependency lockfile.                                                                                            |
| `libs/`, `artwork/`                                      | Prebuilt speech recognition libraries used by the Android and desktop builds, and the app icon sources.                                                                                                                |
| `infra/`, `scripts/`                                     | Terraform for the Google Cloud deployment, signing evidence, and the scripts behind `./run`.                                                                                                                           |
| `docs/`                                                  | Documentation, indexed in [`docs/README.md`](docs/README.md).                                                                                                                                                          |
| `samples/`                                               | A sample consumer of the published AT Protocol libraries.                                                                                                                                                              |

## Build and run

Every task goes through `./run`; `./run help` lists them all. The toolchain is a JDK (17 or newer)
to start the Gradle wrapper, Android Studio with the Android SDK, and Docker for the server's
database. Xcode is needed only for iOS.

```bash
./run setup          # tools, git hooks, local.properties, Firebase configs, test dependencies
./run setup --check  # report what is missing without changing anything
```

Setup is safe to repeat. Its Firebase step downloads LogDate's Firebase configuration, which needs
access to the maintainers' Google Cloud project. Without that access, the step reports that it is
waiting on you, and you can carry on: the Android build writes a placeholder
`app/android-main/google-services.json` by itself, so debug builds compile and run with no
Firebase access. Crash reporting does nothing with the placeholder. The deployment environments
(`./run setup staging` and `./run setup production`) are for maintainers.

```bash
./run start          # PostgreSQL in Docker
./run server         # the Ktor server on http://localhost:8765
./run desktop        # the desktop app

adb devices                                  # find an emulator serial such as emulator-5554
ANDROID_SERIAL=emulator-5554 ./run android   # install and open the debug build
```

Install on an emulator only. `./run android` installs on whichever device `adb` selects, so set
`ANDROID_SERIAL` to an emulator. The server starts without cloud credentials: in development it
generates a throwaway signing secret and uses the local database that `./run start` provides, with
the connection settings from `docs/environment/.env.example`.

```bash
./gradlew :client:feature:timeline:test   # tests for one module
./run test:server                         # server tests
./run lint:check                          # ktlint
```

[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) covers the toolchain, configuration, Gradle Managed
Devices for Android tests, and the full command list. [`docs/reference/project-setup.md`](docs/reference/project-setup.md)
explains each setup step.

## Documentation

[`docs/README.md`](docs/README.md) indexes the documentation by kind: explanations of how systems
work, references, feature design, testing guides, and operational runbooks. Each module's
`README.md` describes that module.

## How changes land

Every change reaches `main` through a pull request merged with Rebase and merge, so history stays
linear. [`docs/reference/standards/git-guidelines.md`](docs/reference/standards/git-guidelines.md)
describes the branch and pull request workflow, and
[`docs/reference/standards/commit-messages.md`](docs/reference/standards/commit-messages.md) the
commit message format. Tests and quality checks must pass before a commit; the checks are listed in
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## License

LogDate is released under the MIT License; see [`LICENSE`](LICENSE). The standalone AT Protocol
libraries in `shared/atproto-*` are licensed under the Apache License 2.0; see
[`shared/atproto-licensing`](shared/atproto-licensing/).
