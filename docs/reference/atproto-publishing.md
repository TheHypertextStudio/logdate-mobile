# ATProto Publishing

This document is for maintainers of the standalone `studio.hypertext.atproto`
modules in this repository. It explains the shared publication convention,
which inputs it reads, what it publishes, and how to run local and remote
publish flows without guessing.

## Scope

The publishing convention is implemented in
[`AtprotoPublishedModulePlugin.kt`](../../build-logic/src/main/kotlin/app/logdate/AtprotoPublishedModulePlugin.kt)
and applied by the ten Kotlin Multiplatform modules: `atproto-crypto`, `atproto-syntax`,
`atproto-identity`, `atproto-xrpc`, `atproto-repo`, `atproto-plc`, `atproto-lexicon`,
`atproto-pds`, `atproto-pds-runtime`, and `atproto-oauth-client`.

Two other `shared/atproto-*` directories do not apply it:

- `shared/atproto-bom` is a `java-platform` project. The plugin assumes Kotlin Multiplatform
  publications, so the BOM's own `build.gradle.kts` repeats the plugin's group, version,
  repository, POM, and signing settings.
- `shared/atproto-licensing` is not a Gradle module. It holds the Apache 2.0 `LICENSE` and
  `NOTICE` that the plugin copies into each published jar.

Today it is intentionally ATProto-specific, not a generic repo-wide publishing
plugin.

The repo also includes supporting release workflow and aggregate tasks:

- [`publish-atproto.yml`](../../.github/workflows/publish-atproto.yml)
  runs hosted Maven publication from CI
- `publishAtprotoToMavenLocal` publishes the full module set locally
- `generateAtprotoDokka` generates HTML docs for the full module set

## Plugin ID

Apply this convention with:

```kotlin
plugins {
    id("app.logdate.atproto-published-module")
}
```

Each module still owns its own Kotlin target setup, dependencies, namespace,
and `description`. The convention only handles publication concerns.

## What The Plugin Does

When applied, the plugin:

- applies `org.jetbrains.dokka`
- applies `maven-publish`
- applies `signing`
- sets the default group to `studio.hypertext.atproto`
- sets the default version to `0.1.0`
- registers `dokkaHtmlJar`
- attaches that jar to every Maven publication as the `javadoc` artifact
- attaches shared POM metadata to every publication
- copies `LICENSE` and `NOTICE` from `shared/atproto-licensing` into the `META-INF/` of each
  binary jar (not the sources or javadoc jars)
- optionally registers a remote Maven repository named `atproto`
- optionally signs all publications with in-memory PGP keys

This is meant to keep all `shared/atproto-*` modules aligned on one release
policy and one POM shape.

## Property Precedence

For every configurable value except the version, precedence is:

1. Gradle property
2. environment variable
3. hard-coded default, when one exists

Supported overrides:

| Purpose                                    | Gradle Property            | Environment Variable       | Default                                                             |
| ------------------------------------------ | -------------------------- | -------------------------- | ------------------------------------------------------------------- |
| Group                                      | `atproto.group`            | `ATPROTO_GROUP`            | `studio.hypertext.atproto`                                          |
| Version                                    | `atproto.version`          | `ATPROTO_VERSION`          | `0.1.0`                                                             |
| Repository URL (legacy / GH Packages)      | `atproto.publish.url`      | `ATPROTO_PUBLISH_URL`      | none                                                                |
| Repository username (legacy / GH Packages) | `atproto.publish.username` | `ATPROTO_PUBLISH_USERNAME` | none                                                                |
| Repository password (legacy / GH Packages) | `atproto.publish.password` | `ATPROTO_PUBLISH_PASSWORD` | none                                                                |
| Maven Central username                     | `ossrh.username`           | `OSSRH_USERNAME`           | none                                                                |
| Maven Central password                     | `ossrh.password`           | `OSSRH_PASSWORD`           | none                                                                |
| Maven Central URL override                 | `ossrh.publish.url`        | `OSSRH_PUBLISH_URL`        | `https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/` |
| Signing key ID                             | `signingKeyId`             | `SIGNING_KEY_ID`           | optional                                                            |
| Signing key                                | `signingKey`               | `SIGNING_KEY`              | none                                                                |
| Signing password                           | `signingPassword`          | `SIGNING_PASSWORD`         | none                                                                |

The version is the one exception: `ATPROTO_VERSION` wins over `atproto.version`. Gradle
properties include the entries in the repository's root
[`gradle.properties`](../../gradle.properties), not only `-P` flags, and that file always sets
`atproto.version=0.1.0`. If the Gradle property won, the version the release workflow derives
from a tag could never take effect. So:

1. `ATPROTO_VERSION`, when set (the release workflow sets it from the tag)
2. `atproto.version` from `-P` or `gradle.properties`
3. `0.1.0`

The BOM resolves its version the same way.

The plugin registers up to two named remote Maven repositories, each
independently gated:

- `atproto` — the legacy single-target slot, currently pointed at GitHub
  Packages by `publish-atproto.yml`. Skipped entirely when
  `ATPROTO_PUBLISH_URL` is unset (publishToMavenLocal still works).
- `atprotoMavenCentral` — Sonatype OSSRH staging endpoint, registered only
  when both `OSSRH_USERNAME` and `OSSRH_PASSWORD` are present. URL defaults
  to `https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/`
  but is overridable via `OSSRH_PUBLISH_URL` in case Sonatype migrates the
  staging host or the namespace moves to the central-publishing portal.

Each repo gets its own Gradle task: `publishAllPublicationsToAtprotoRepository`
and `publishAllPublicationsToAtprotoMavenCentralRepository`. They're
independent — a single CI run can publish to either or both depending on
which credential sets are configured.

If no signing key and signing password are configured, the plugin does not
enable signing. This is intentional so local development can publish unsigned
artifacts to `mavenLocal()` with zero release credentials. **Maven Central
rejects unsigned uploads**, so publishing to `atprotoMavenCentral` without
`SIGNING_KEY` + `SIGNING_PASSWORD` will produce artifacts that fail
Sonatype's staging-validation step. The CI workflow gates the MC publish
on signing being present so that misconfiguration is caught before an
upload starts.

## Published Artifacts

Each ATProto module publishes Kotlin Multiplatform outputs, which means Gradle
creates multiple publications per module.

For a representative module such as `shared/atproto-syntax`, the generated
publish task surface includes:

- `publishToMavenLocal`
- `publishAllPublicationsToAtprotoRepository`
- `publishKotlinMultiplatformPublicationToAtprotoRepository`
- `publishJvmPublicationToAtprotoRepository`
- `publishAndroidPublicationToAtprotoRepository`
- `publishIosArm64PublicationToAtprotoRepository`
- `publishIosSimulatorArm64PublicationToAtprotoRepository`

The plugin also ensures the module exposes:

- `sourcesJar`
- `dokkaHtmlJar`

The root multiplatform artifact published to Maven Local includes:

- the main module jar
- `-sources.jar`
- `-javadoc.jar`
- `.pom`
- `.module`
- Kotlin tooling metadata

## Shared POM Metadata

The convention writes the same POM metadata to every ATProto module:

- project name from the Gradle project name
- project description from the module `description`
- repository URL pointing at the main repo, <https://github.com/TheHypertextStudio/logdate-mobile>
- Apache 2.0 license
- organization metadata for The Hypertext Studio
- developer metadata for The Hypertext Studio
- SCM URLs
- GitHub issue tracker URL

If you change ownership, licensing, or repository location, update the
convention plugin instead of patching individual modules, and make the same
change in `shared/atproto-bom/build.gradle.kts`, which repeats this metadata
because it cannot apply the plugin.

## Local Publish Flow

Publish the current ATProto module set to Maven Local:

```bash
./gradlew publishAtprotoToMavenLocal :shared:atproto-bom:publishToMavenLocal
```

`publishAtprotoToMavenLocal` covers the ten Kotlin Multiplatform modules only. The BOM needs its
own `:shared:atproto-bom:publishToMavenLocal`, and the standalone sample imports the BOM, so it
needs both.

This is the correct flow for:

- validating POM metadata locally
- checking `sources` and `javadoc` artifacts
- exercising the standalone sample consumer
- preparing local integration tests outside the main repo build

Generate all ATProto Dokka HTML publications:

```bash
./gradlew generateAtprotoDokka
```

This task writes real HTML output under each module's `build/dokka/html/`
directory, for example:

- `shared/atproto-syntax/build/dokka/html/index.html`
- `shared/atproto-pds-runtime/build/dokka/html/index.html`

The repo includes an Android Studio run configuration for the same task in
[`Generate ATProto Dokka.run.xml`](../../.run/Generate%20ATProto%20Dokka.run.xml).

Regenerate the checked-in LogDate lexicon models:

```bash
./gradlew :shared:atproto-lexicon:generateLogDateLexicons
```

Regenerate the checked-in official `com.atproto.*` lexicon models used by the
current server surface:

```bash
./gradlew :shared:atproto-lexicon:generateOfficialAtprotoLexicons
```

## Remote Publish Flow

The current target is **GitHub Packages on this repository** (interim until
Maven Central onboarding completes — see "Migrating to Maven Central" below).
Maintainers cut a release purely by tagging:

```bash
git tag -a atproto-v0.1.0 -m "Initial public release"
git push origin atproto-v0.1.0
```

`.github/workflows/publish-atproto.yml` fires on the tag, derives
`ATPROTO_VERSION` from the tag name (`atproto-v0.1.0` → `0.1.0`), and runs
`publishAllPublicationsToAtprotoRepository` for the nine Kotlin Multiplatform
modules plus the BOM. It can also be started by hand (`workflow_dispatch`)
with an optional `version` input, which sets `ATPROTO_VERSION` the same way.

Because `ATPROTO_VERSION` takes precedence over `gradle.properties` (see
[Property Precedence](#property-precedence)), the tag decides the published
version. Keep `atproto.version` in `gradle.properties` in step with the latest
release anyway, since local builds and the consumer sample use it.

The workflow uses:

- `ATPROTO_PUBLISH_URL`: `https://maven.pkg.github.com/${{ github.repository }}`
- `ATPROTO_PUBLISH_USERNAME`: `${{ github.actor }}` (the user who pushed the tag)
- `ATPROTO_PUBLISH_PASSWORD`: `${{ secrets.GITHUB_TOKEN }}` (auto-injected; needs `permissions: { packages: write }`)

There are no manual secrets to configure — the GH Packages target is
zero-config because the runner's token already has package-publish rights
on this repo.

Artifacts land at
`https://maven.pkg.github.com/<owner>/<repo>/studio/hypertext/atproto/<module>/<version>/...`
within ~1 minute of the tag push. Successful publishes show under the
repo's **Packages** sidebar.

### Manual or local publishes against a remote

If you need to publish from a workstation (e.g. testing a snapshot version),
set the same repository env vars yourself and choose the version with either
`ATPROTO_VERSION` or `-Patproto.version` (an `ATPROTO_VERSION` in your
environment wins over `-P`):

```bash
ATPROTO_PUBLISH_URL=https://maven.pkg.github.com/TheHypertextStudio/logdate-mobile \
ATPROTO_PUBLISH_USERNAME=<your-github-username> \
ATPROTO_PUBLISH_PASSWORD=<personal-access-token-with-write:packages> \
  ./gradlew :shared:atproto-syntax:publishAllPublicationsToAtprotoRepository \
    -Patproto.version=0.1.0-SNAPSHOT
```

Generate the PAT at <https://github.com/settings/tokens/new> with the
`write:packages` scope (no other scopes needed for publishing). The token
must belong to a user with push access to the repo.

You can replace `publishAllPublicationsToAtprotoRepository` with a narrower
publication task if you only need one target variant.

### What's not signed yet

GitHub Packages doesn't require GPG-signed artifacts, so the convention
plugin's signing path stays gated on `SIGNING_KEY`/`SIGNING_PASSWORD` being
set — both are unset in the workflow today, so artifacts publish unsigned.
Maven Central does require signatures; restoring them is part of the
migration step below, not a separate effort.

### Adding the Maven Central target

The Maven Central wiring is already in place — both the convention plugin
and `publish-atproto.yml` have the slots — and only switches on once the
following repo-scoped GH Actions secrets are configured:

| Secret                           | Purpose                                                                                                                                |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `OSSRH_USERNAME`                 | Sonatype OSSRH portal username (or token name for the new central-publishing portal)                                                   |
| `OSSRH_PASSWORD`                 | Sonatype OSSRH password (or token secret)                                                                                              |
| `OSSRH_PUBLISH_URL` _(optional)_ | Override the default `https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/` if the namespace migrates to a different host |
| `SIGNING_KEY_ID` _(optional)_    | Short ID of the GPG key, only useful when multiple keys live in the keyring                                                            |
| `SIGNING_KEY`                    | ASCII-armored GPG **secret** key (`gpg --armor --export-secret-keys <id>`)                                                             |
| `SIGNING_PASSWORD`               | Passphrase for the secret key                                                                                                          |

When _all four_ of `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `SIGNING_KEY`, and
`SIGNING_PASSWORD` are present, the workflow runs an additional
`publishAllPublicationsToAtprotoMavenCentralRepository` step after the GH
Packages publish. Missing any of them skips the MC step silently — GH
Packages still publishes — so partial onboarding doesn't break the
existing release flow.

One-time prerequisites before flipping these on:

1. Register `studio.hypertext` (or your final namespace) at
   <https://central.sonatype.com>. New namespaces require DNS TXT
   verification on the registrable domain (`hypertext.studio`), which
   typically takes a few hours to a day.
2. Generate a GPG keypair locally
   (`gpg --full-generate-key` → RSA 4096), upload the **public** key to a
   keyserver (`gpg --send-keys <id>`), then export the **secret** key for
   `SIGNING_KEY` (`gpg --armor --export-secret-keys <id> | pbcopy`).
3. Set the secrets above.
4. Cut the next `atproto-v*` tag. The workflow publishes to GH Packages
   first, then Maven Central; if the OSSRH push fails, GH Packages is
   already done so consumers using the interim repo aren't affected.

After artifacts land in Sonatype's staging area, you'll need to **close +
release** the staging repository in the Sonatype web UI (or wire
`io.github.gradle-nexus.publish-plugin` for full automation — not done
here yet because the manual close step is also a useful sanity check
before an artifact becomes immutable on Central).

Maven Central downloads don't require authentication, so consumers will
drop the `dependencyResolutionManagement` credentials block from
`docs/reference/atproto-library.md` once the same coordinates are
available there. Group/artifact IDs (`studio.hypertext.atproto:*`) are
identical across both targets — no coordinate migration needed.

## Standalone Consumer Verification

The standalone sample in
[`samples/atproto-consumer`](../../samples/atproto-consumer)
is intentionally outside the main build so it consumes published artifacts,
not project dependencies.

After publishing locally:

```bash
./gradlew -p samples/atproto-consumer run
```

The expected output is a single success line showing a resolved handle and a
loaded AT URI from the sample repo record.

## What This Plugin Does Not Do

The convention does not currently provide:

- staging or promotion logic
- repository-specific validation rules
- release notes generation
- generic non-ATProto publication defaults

CI release automation exists at the repo level through the GitHub Actions
workflow. The plugin intentionally stays narrower and only owns module-level
publication behavior.

Those concerns remain outside the plugin on purpose. This plugin owns the
module-level publication contract, not the entire release pipeline.

## When To Generalize It

If this repo grows more publishable libraries, the likely refactor is:

1. extract a generic publishing convention plugin
2. keep a thin ATProto wrapper that supplies the ATProto-specific defaults

Until then, keeping this plugin ATProto-specific is simpler and makes the
expected artifact family obvious.
