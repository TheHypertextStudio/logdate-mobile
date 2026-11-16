# Google Play Publishing

This document covers the Android app's automated Google Play publishing path.
It explains the repo-level inputs, GitHub Actions workflow, versioning model,
and the helper scripts used to prepare the required secrets.

## Scope

The automated path is intentionally narrow:

- publish the Android app bundle (`.aab`) only
- publish **internal** track builds only when manually dispatched from `main`
- promote **production** releases only by manual dispatch, in stages, for a tested `android-v*` tag
  (pushing the tag publishes nothing; it only names the tested commit)
- read the publishing secrets from the `production` GitHub Environment, which
  only `main` and release tags may use
- stay disabled until maintainers explicitly enable each path

`./run setup production` sets all of this up and verifies it; see
[`project-setup.md`](project-setup.md).

### Dogfood and production

"Dogfood" is the Play **internal testing** track. An explicit `workflow_dispatch`
from `main` publishes a real, non-debuggable `release` bundle there, built against
the production backend `https://cloud.logdate.app`. The local `dogfood` Gradle
build type is something else: a debuggable build for sideloading, which Play
would reject.

The internal publish command sets `-Plogdate.watchExperienceDefault=true` for both phone
and watch. Only that built bundle defaults to the redesigned Watch experience; a person who
turned it off explicitly in Labs keeps their choice. Ordinary builds retain the flag's
declared default of off. Internal and production tracks remain separate.

Production is the same bundle, promoted from internal testing by explicit
workflow dispatches that name its tested `android-v*` tag and version code. Nothing is
rebuilt for production.

The workflow lives in [`publish-android-play.yml`](../../.github/workflows/publish-android-play.yml).

The Android module wiring lives in [`app/android-main/build.gradle.kts`](../../app/android-main/build.gradle.kts)
and uses Gradle Play Publisher.

## Safety Switch

Automated publishing is guarded by repository variables:

- `LOGDATE_PLAY_INTERNAL_PUBLISH_ENABLED`
- `LOGDATE_PLAY_PRODUCTION_PUBLISH_ENABLED`
- `LOGDATE_PLAY_WEAR_PUBLISH_ENABLED` (the watch's internal publish, off unless set)

If a variable is not set to `true`, that publish path is skipped. This keeps
`main` shippable before the Play and signing secrets exist and lets internal
and production roll out independently.

## Required GitHub Environment

Both jobs declare `environment: production`. Its deployment policy, not a
required reviewer, is what protects the secrets: only `main`, `android-v*`
tags, and `server-v*` tags may use it. A reviewer gate would also pause every
internal publish from `main`, which defeats continuous dogfooding. The
`gh-environment` setup step enforces this policy.

## Required Secrets

| Purpose                   | Secret name                                           | Required for                             | Notes                                                                                                                                                                                              |
| ------------------------- | ----------------------------------------------------- | ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Play Developer API access | none (variable `LOGDATE_PLAY_SERVICE_ACCOUNT`)        | Internal + production                    | Keyless. Both jobs authenticate through Workload Identity Federation as this service account, and Gradle Play Publisher uses the resulting application-default credentials.                        |
| Firebase debug config     | `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON_DEBUG_BASE64`   | CI, screenshot tests, local debug parity | Base64 of `app/android-main/google-services.json`. Materialized by [`setup-firebase-configs`](../../.github/actions/setup-firebase-configs/action.yml) with `android-flavor: debug` (default).     |
| Firebase release config   | `LOGDATE_ANDROID_GOOGLE_SERVICES_JSON_RELEASE_BASE64` | Internal Play publishing                 | Base64 of `app/android-main/src/release/google-services.json`. Materialized by [`setup-firebase-configs`](../../.github/actions/setup-firebase-configs/action.yml) with `android-flavor: release`. |
| Release keystore file     | `LOGDATE_RELEASE_STORE_BASE64`                        | Internal                                 | Base64-encoded `.jks` or `.keystore` file content.                                                                                                                                                 |
| Release keystore password | `LOGDATE_RELEASE_STORE_PASSWORD`                      | Internal                                 | Passed into the existing Android release signing config.                                                                                                                                           |
| Release key alias         | `LOGDATE_RELEASE_KEY_ALIAS`                           | Internal                                 | Passed into the existing Android release signing config.                                                                                                                                           |
| Release key password      | `LOGDATE_RELEASE_KEY_PASSWORD`                        | Internal                                 | Passed into the existing Android release signing config.                                                                                                                                           |

The workflow materializes the keystore at runtime and removes it before the
job exits. Firebase config materialization is handled by the
`setup-firebase-configs` composite action; see
[`docs/runbook/release-secrets.md`](../runbook/release-secrets.md) for the
full secret rotation runbook.

## Helper Scripts

`./run setup production` calls these for you. They remain usable on their own:

```bash
./scripts/create-signing-keystore.sh --environment production   # creates the keystore
./scripts/sync-firebase-configs.sh android-all       # uploads both Firebase JSONs
./scripts/upload-play-keystore.sh --keyless # uploads the upload keystore
```

`create-signing-keystore.sh` owns the keystore itself. It creates one per
environment on first run and reuses it forever after — re-running is a no-op
that reprints the same values, and it refuses to continue if the keystore
survives but its credentials file does not, because that passphrase cannot be
recovered. Every downstream value is derived from the certificate rather than
transcribed: the colon-hex fingerprint for Digital Asset Links, the base64url
`apk-key-hash` origin for WebAuthn, the `android_signing_certificates` block
for Terraform, and the `LOGDATE_RELEASE_*` secrets below. Pass
`--play-fingerprint` once the app exists in Play Console to fold the Play
app-signing certificate in, and `--write-assetlinks PATH` to write the
`assetlinks.json` served from `logdate.app` directly.

`sync-firebase-configs.sh` validates each JSON before transmission (`jq`
sanity-checks `.project_info.project_id`) and is idempotent — re-running
with a newer file rotates the secret in place.

`upload-play-keystore.sh`:

- validates that `gh` is installed and authenticated
- reads the keystore settings from `~/.logdate-signing/production-upload.env`,
  prompting only for what is missing (`--non-interactive` never prompts)
- uploads the keystore secrets to the `production` environment on stdin, so no
  value ever appears in a command line
- with `--keyless` (what setup uses), skips `ANDROID_PUBLISHER_CREDENTIALS`,
  because CI authenticates to Play through Workload Identity Federation
- can optionally set the internal and production enable variables

If you want either publish path to stay disabled until later, leave its
prompt at `n`.

Production enablement assumes the tagged commit has already been published
to the internal track, because production releases now **promote** that
tested internal release instead of rebuilding the app bundle from source.

## Versioning Model

### versionCode: assigned by Play

Play rejects any upload whose `versionCode` is not higher than every code it
has seen for the app. Rather than derive codes from git, which breaks when
history is rewritten, squashed, or fetched shallowly, Gradle Play Publisher
runs with `resolutionStrategy = AUTO`. Every upload gets Play's highest
existing `versionCode` + 1, so codes can never collide or go backwards.

A commit therefore has no predictable `versionCode`. To promote a tag,
`scripts/resolve-play-internal-release.sh` looks up the internal release whose
name contains the tag's short SHA, and the workflow promotes that release's
code. When the tagged commit never reached the internal track, the lookup fails
and nothing is promoted.

### versionName: derived from tags

The version name comes from git history via:

```bash
./scripts/resolve-android-play-version.sh
```

The script expects Android release tags in this format:

- `android-v<major>.<minor>.<patch>`

Examples:

- `android-v0.1.0`
- `android-v1.4.2`

Rules:

1. The latest reachable `android-v*` tag defines the base version.
2. Internal builds from non-tag refs use a derived `versionName` like
   `1.4.2-main.3+abc1234`.
3. Tag builds resolve a tag-flavored `versionName`.
4. Internal release names include the short SHA, which is how promotion finds
   the build for a tag.
5. The first-ever Play upload must still be done manually in Play Console
   before Gradle Play Publisher can publish subsequent releases.

## Workflow Behavior

The workflow has two publish paths:

### Internal track

- Runs only for a manual `workflow_dispatch` with stage `internal` from `main`
- Requires `LOGDATE_PLAY_INTERNAL_PUBLISH_ENABLED=true`
- Materializes the **release** Firebase config because Gradle Play Publisher
  builds the signed `release` bundle even when publishing to the internal
  track
- Publishes with:

  ```bash
  ./gradlew :app:android-main:publishReleaseBundle
  ```

  using:

  - `-Plogdate.backendUrl=https://cloud.logdate.app`, explicitly, so a change
    to `gradle.properties` can never retarget Play builds
  - `-Plogdate.watchExperienceDefault=true`, so the internal phone and watch
    open the redesigned companion by default
  - `-Plogdate.quietSaveDefault=true`, so the internal phone starts with
    [Quiet Save](../features/quiet-save.md) on unless the person turned it off
  - `LOGDATE_PLAY_TRACK=internal`
  - `LOGDATE_VERSION_NAME=<derived>` (Play assigns the `versionCode`)
  - `--release-name "<internal release name with short SHA>"`

- After publishing, reads the internal track and requires exactly one completed
  release whose name contains the commit's short SHA; that release's version
  code is the one to test and later promote.
- Uploads the release bundle (`.aab`) as a workflow artifact
  (`android-release-bundle-internal-<sha>`, 7-day retention) for QA pickup.

### Production track

Production actions run only from a manual dispatch whose `stage` is not `internal`,
need `LOGDATE_PLAY_PRODUCTION_PUBLISH_ENABLED=true`, and run in the `production`
Environment, which only release tags and `main` may use. Runs share one concurrency
group, so two actions never race on the track.

Every production action takes the same three inputs:

| Input                    | Meaning                                                                                                                 |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `candidate_tag`          | The tested tag, `android-v<major>.<minor>.<patch>`. The workflow checks out that tag.                                   |
| `candidate_version_code` | The exact version code Play assigned to the internal release built from the tag's commit.                               |
| `gate_evidence`          | A link to the record of the acceptance journeys and, after the first stage, the review of the previous stage's signals. |

The workflow requires all three, then asks the Play API for an internal release whose
name contains the tagged commit's short SHA and that version code, and fails if there
is none. It never rebuilds and never needs the keystore. Production changes only the
track and rollout fraction of the artifact that was already tested. The evidence input
records a maintainer's decision: the workflow cannot read the record behind the link or
confirm that the observation period passed.

`scripts/check-play-rollout-transition.sh` reads the production track and allows each
stage only from the matching state of that version code:

| Stage          | Required state of the version on production                   | Result                                                                                |
| -------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `start-5`      | Not on production, and no other release in progress or halted | In progress at 5%; a draft GitHub Release is created for the tag with generated notes |
| `advance-25`   | In progress at 5%                                             | In progress at 25%                                                                    |
| `complete-100` | In progress at 25%                                            | Completed, at 100%                                                                    |
| `halt`         | In progress at 5% or 25%                                      | Halted at the same fraction                                                           |
| `resume-5`     | Halted at 5%                                                  | In progress at 5%                                                                     |
| `resume-25`    | Halted at 25%                                                 | In progress at 25%                                                                    |

Operate a release in this order:

1. Record the acceptance journeys' results and the internal version code, then tag
   the tested commit.
2. Run `start-5`. Review crash, ANR, backup, sign-in, and support signals for at
   least 24 hours, and add the timestamped review to the record.
3. If healthy, run `advance-25` with the same tag, version code, and the updated
   record. Review again for at least 24 hours at 25%, then run `complete-100`.
4. On a data-loss, restore, sign-in, or material stability regression, run `halt`.
   New installations of the release stop; devices that already updated keep it. Record
   the incident and the recovery action before using `resume-5` or `resume-25`. A fix
   that needs a new bundle starts over with a new internal release, acceptance pass,
   version code, and tag.

The internal bundle is the only one built; the matching `.aab` lives in the
internal-track workflow artifact (7-day retention), so attach it to the GitHub Release
manually if you want it kept longer.

## Wear OS

The watch app (`:app:wear`) ships on the **same Play listing** as the phone app, as a Wear OS
form factor. It uses the phone's package name, `studio.hypertext.logdate` (`.debug` for debug
builds), and is signed with the same key. That is not a preference: the Wear Data Layer only
carries notes and audio between a phone app and a watch app that match on both package name and
signing key, so a watch app under its own package could never hand a recording to the phone.

`app/wear/build.gradle.kts` gets its package name, version, signing material and Play track from
the `app.logdate.android-release` plugin in `build-logic`, and the values follow the same rules as
the phone's: [versionCode is assigned by Play](#versioncode-assigned-by-play), `versionName` comes
from git, and signing material is read from the environment, Gradle properties, or the local
signing env file. A release build with no signing material fails instead of falling back to the
debug key, unless `-Plogdate.allowDebugReleaseSigning=true` is passed for a local build that will
not be published.

### How it publishes

The `publish-internal` job publishes the phone bundle, then, when
`LOGDATE_PLAY_WEAR_PUBLISH_ENABLED` is `true`, the watch bundle to the `wear:internal` track.
`WearPlayTrack` in `build-logic` adds the `wear:` prefix to the phone track alias.
Beta and production are `wear:beta` and `wear:production`. The two publishes run one after the other in one
job because Play assigns both apps version codes from a single sequence, and two uploads racing for
the next code would collide.

Promoting the watch to production is not wired up. The production stages promote only the
phone's internal release.

### One-time Play Console setup

Configure these in Play Console, then verify the registered tracks through the API:

1. Add the Wear OS form factor to the LogDate app.
2. Create the first Wear OS release, by hand, as the phone's first release had to be.
3. Confirm your account is on the internal testers list for the Wear OS form factor, and that its
   Internal testing track exists. LogDate's registered API alias is `wear:internal`.
4. Set the repository variable `LOGDATE_PLAY_WEAR_PUBLISH_ENABLED` to `true`.

Then install LogDate from the Play Store on the watch. If publishing reports a missing track,
run **Inspect Android Play tracks** from GitHub Actions. It lists the app's actual tracks and
release versions through a discarded edit, without publishing. Google's general documentation
uses `wear:qa` as an example; that alias returned 404 for LogDate even after creating its internal
track. Use the registered API alias rather than guessing from the Console's display label.

### Building locally

```shell
./run build:wear:release
```

writes `app/wear/build/outputs/bundle/release/wear-release.aab`. Pass
`-Plogdate.allowDebugReleaseSigning=true` on a machine with no signing material. CI builds the same
bundle when an internal release is dispatched. Run the local Wear build before committing
watch changes so a broken watch release cannot reach `main`.

## Local Verification

Advertising-ID collection is disabled on phone and watch while ordinary Firebase Analytics and
Crashlytics remain enabled. `verifyAdvertisingIdReleaseBundle` checks the merged release manifest
and every packaged module manifest before either app can publish. It rejects advertising-ID
permissions, a missing collection opt-out, or disabled Firebase collection; the phone also requires
both Firebase component registrations. This keeps the existing no-advertising-ID Play declaration
consistent with dependency updates.

To verify the Android release bundle still builds locally without
publishing:

```bash
./gradlew :app:android-main:bundleRelease \
  -Plogdate.versionCode=1010000 \
  -Plogdate.versionName=1.1.0-local
```

To inspect the Gradle Play Publisher task surface:

```bash
./gradlew :app:android-main:help --task publishReleaseBundle
./gradlew :app:android-main:help --task promoteReleaseArtifact
```

Local publishing is possible too, but it requires the same Play
service-account credentials and release signing inputs as CI.
