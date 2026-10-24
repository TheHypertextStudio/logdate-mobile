# ATProto Library

`studio.hypertext.atproto` is the standalone AT Protocol library surface that LogDate now builds on. The server remains the deployment target in this repository, but protocol contracts and core runtime behavior should live in the shared modules first and be consumed by `server`, not recreated there.

## Module Map

- `shared/atproto-syntax`
  - typed identifiers and parsers for DIDs, handles, NSIDs, record keys, TIDs, and AT URIs
- `shared/atproto-identity`
  - AT Protocol DID rules, DID documents, handle resolution, `did:web`, and `did:plc`
- `shared/atproto-crypto`
  - base58btc and multikey encoding, P-256 and K-256 curve definitions, and JVM-only EC key
    generation, signing, verification, and JWK public-key decoding
- `shared/atproto-plc`
  - PLC directory models and client/runtime behavior
- `shared/atproto-repo`
  - repo records, deterministic block storage, commits, export/import, and the canonical repo engine
  - the messages of `com.atproto.sync.subscribeRepos`: what a commit changed (`RepoCommitChanges`),
    the event types, and the frame encoding (`RepoEventFrames`)
- `shared/atproto-xrpc`
  - Ktor-backed XRPC client primitives
- `shared/atproto-lexicon`
  - lexicon parsing, validation, registry lookups, checked-in LogDate and official `com.atproto.*` resources, and deterministic codegen output
- `shared/atproto-pds`
  - shared request/response models and service contracts for discovery, identity, OAuth, session, repo, blob, and sync surfaces
- `shared/atproto-pds-runtime`
  - reusable runtime implementations for discovery, repo, blob, and sync services backed by the shared PDS contracts
- `shared/atproto-oauth-client`
  - a confidential OAuth client for signing in with accounts on other AT Protocol servers: identity
    and authorization server discovery, PAR, PKCE, DPoP with server nonces, `private_key_jwt` client
    authentication, token refresh and revocation, and DPoP-authenticated XRPC calls. See its
    [README](../../shared/atproto-oauth-client/README.md)
- `shared/atproto-bom`
  - a Gradle `java-platform` bill of materials that pins every module above to one version
- `shared/atproto-licensing`
  - not a Gradle module: the Apache 2.0 `LICENSE` and `NOTICE` that the publishing convention copies into each published jar

## Maven Coordinates

The recommended way to consume the library is via the BOM, which pins every
module to a single coordinated release version:

```kotlin
dependencies {
    implementation(platform("studio.hypertext.atproto:atproto-bom:0.1.0"))

    // Pick the modules you need — versions are inferred from the BOM.
    implementation("studio.hypertext.atproto:atproto-syntax")
    implementation("studio.hypertext.atproto:atproto-identity")
    implementation("studio.hypertext.atproto:atproto-repo")
    implementation("studio.hypertext.atproto:atproto-xrpc")
}
```

### Where the artifacts live (interim)

While Maven Central onboarding is in progress, releases publish to the
**GitHub Packages Maven repository on this repo**. Add the repo to your
`settings.gradle.kts` (or top-level `build.gradle.kts`) — and crucially,
GitHub Packages requires authentication even for public packages, so you
also have to supply credentials:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        val gprUser = providers.gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
            .orNull
        val gprToken = providers.gradleProperty("gpr.token")
            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
            .orNull
        if (gprUser != null && gprToken != null) {
            maven {
                name = "atproto-github-packages"
                url = uri("https://maven.pkg.github.com/TheHypertextStudio/logdate-mobile")
                credentials {
                    username = gprUser
                    password = gprToken
                }
            }
        }
    }
}
```

Set the credentials however suits your environment:

- **Local development**: put them in `~/.gradle/gradle.properties` so they
  don't land in any project repo:
  ```properties
  gpr.user=<your-github-username>
  gpr.token=<personal-access-token-with-read:packages>
  ```
  Generate the PAT at <https://github.com/settings/tokens/new> with the
  `read:packages` scope (read-only is enough to consume).
- **CI**: use the auto-injected `GITHUB_TOKEN` if your workflow runs in
  this org or a fork; otherwise create a repo secret with a PAT scoped to
  `read:packages` and pass it as `GITHUB_TOKEN`.

This auth step is the price of using GitHub Packages — public artifacts
on Maven Central will resolve without credentials. The migration path is
on the maintainers' side and won't change the artifact coordinates.

The full module set:

- `studio.hypertext.atproto:atproto-bom:$version` (BOM — pin once, version-less elsewhere)
- `studio.hypertext.atproto:atproto-crypto:$version`
- `studio.hypertext.atproto:atproto-syntax:$version`
- `studio.hypertext.atproto:atproto-identity:$version`
- `studio.hypertext.atproto:atproto-xrpc:$version`
- `studio.hypertext.atproto:atproto-repo:$version`
- `studio.hypertext.atproto:atproto-plc:$version`
- `studio.hypertext.atproto:atproto-lexicon:$version`
- `studio.hypertext.atproto:atproto-pds:$version`
- `studio.hypertext.atproto:atproto-pds-runtime:$version`
- `studio.hypertext.atproto:atproto-oauth-client:$version`

The repo-wide default version lives in [`gradle.properties`](../../gradle.properties) as
`atproto.version`.

## Quick Start

```kotlin
val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
val collection = Nsid.require("studio.hypertext.logdate.entry")
val engine = DefaultRepoEngine(InMemoryRepoBlockStore())

engine.createRecord(
    repo = repo,
    collection = collection,
    value = buildJsonObject {
        put("\$type", collection.toString())
        put("text", "hello")
    },
    recordKey = RecordKey.require("entry-1"),
)
```

The quick start uses the default `DigestRepoCommitSigner`, which puts a hash where the signature
belongs. That is fine for a repository that never leaves your process, but relays and other AT
Protocol services reject those commits. A repository that anyone else will read needs a
`RepoCommitSigner` backed by the account's signing key (the server's is
`HostedRepoCommitSigner`), passed as `DefaultRepoEngine(blockStore, signer = ...)`.

### How commits follow the repository specification

Every write produces a commit that matches the
[repository specification](https://atproto.com/specs/repository):

- **Fields.** The commit object has exactly `version` (always 3), `did`, `data` (the root of the
  record tree), `rev`, `prev` and `sig`. `prev` is always present, as `null` on the first commit.
- **Signature.** The signer receives the DAG-CBOR bytes of the commit without `sig`, hashes them
  with SHA-256 and signs the hash. A signer that cannot find the account's key must throw; the
  write then fails rather than storing an unsigned commit.
- **Revision.** `rev` is a TID (timestamp identifier) made from the current time, and every
  commit's `rev` is greater than the one before it. If the clock stands still or steps back, the
  engine uses one more than the previous revision instead. The engine stores revisions as the
  TID's 64-bit number, so repositories written when revisions were a counter (1, 2, 3, ...) carry
  on with TIDs, which are far larger.

The JVM tests check this with the reference implementation itself. They export repositories as CAR
files and run [`tools/atproto-repo-verify/verify.mjs`](../../tools/atproto-repo-verify/verify.mjs),
which uses the pinned `@atproto/repo` package to check block hashes, the commit shape, the
signature, the revision and the record tree's shape. The check needs Node.js 22 or newer on your
PATH, which `./run setup` checks. Gradle installs the pinned packages before the tests run, with
pnpm if you have it and otherwise with the pinned pnpm version through `npx`:

```bash
./gradlew :shared:atproto-repo:jvmTest
```

```kotlin
val client = KtorXrpcClient(
    httpClient = HttpClient(),
    baseUrl = "https://example.com",
)

val response: ResolveHandleResponse =
    client.query(Nsid.require("com.atproto.identity.resolveHandle")) {
        queryParameter("handle", "alice.example.com")
    }
```

### Reading and writing the repository event stream

`com.atproto.sync.subscribeRepos` sends each change to a repository as one WebSocket message: a
DAG-CBOR header and a DAG-CBOR body, back to back. `RepoEventFrames` writes and reads those
messages, so a server and a relay can share one definition of them.

```kotlin
// Server side: work out what a stored commit changed, and announce it.
val change = RepoCommitChanges.read(blockStore, commit).getOrThrow()
val frame = RepoEventFrames.encode(
    RepoCommitEvent(
        seq = 42,
        time = Clock.System.now(),
        repo = commit.commit.repo,
        commit = commit.cid,
        rev = Tid.fromLong(commit.commit.revision),
        since = null,
        blocks = change.toCommitCar(),
        ops = change.operations,
        prevData = change.previous?.commit?.root,
    ),
)

// Consumer side: read a frame and open the CAR file it carries.
val event = RepoEventFrames.decode(frame) as RepoCommitEvent
val car = CarCodec.readCar(event.blocks)
```

`change.toCommitCar()` holds the commit, the whole record tree and the records the commit wrote.
Sending the whole tree, not only the nodes that changed, lets a consumer undo a deletion without
asking for more. A server that cannot send that much (the lexicon allows 200 operations and 2 MB of
blocks) sends a `RepoSyncEvent` built from `change.toSyncCar()` instead, which tells the consumer to
fetch the repository again.

The JVM tests hand these CAR files to the reference implementation through
[`tools/atproto-repo-verify/verify-event.mjs`](../../tools/atproto-repo-verify/verify-event.mjs),
which checks the signature, that the blocks reach every change, that the changes are the event's
operations, and that undoing the operations gives back the previous tree, as a relay does.

## Publishing

Maintainers should use the detailed publishing guide in
[`atproto-publishing.md`](atproto-publishing.md)
for property precedence, remote publish task names, signing behavior, and
artifact expectations.

Publish locally:

```bash
./gradlew publishAtprotoToMavenLocal :shared:atproto-bom:publishToMavenLocal
```

`publishAtprotoToMavenLocal` publishes the ten Kotlin Multiplatform modules but not the BOM, so
add `:shared:atproto-bom:publishToMavenLocal` whenever a consumer, such as the standalone sample,
imports the BOM.

Publish to a remote Maven repository by setting:

- `ATPROTO_PUBLISH_URL`
- `ATPROTO_PUBLISH_USERNAME`
- `ATPROTO_PUBLISH_PASSWORD`
- `SIGNING_KEY_ID` optional
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

The build logic uses those values for Maven repository credentials, POM metadata, Dokka-backed `javadoc` jars, and in-memory PGP signing.

Generate API docs for every ATProto module:

```bash
./gradlew generateAtprotoDokka
```

Regenerate the checked-in LogDate lexicon models:

```bash
./gradlew :shared:atproto-lexicon:generateLogDateLexicons
```

Regenerate the checked-in official AT Protocol lexicon models used by this repo:

```bash
./gradlew :shared:atproto-lexicon:generateOfficialAtprotoLexicons
```

Android Studio users can run the same Dokka task through
[`Generate ATProto Dokka.run.xml`](../../.run/Generate%20ATProto%20Dokka.run.xml).

## Standalone Sample

The consumer sample in [`samples/atproto-consumer`](../../samples/atproto-consumer) is intentionally
outside the main Gradle build. It depends on `mavenLocal()` artifacts, not project modules, so it
verifies the published API surface the way an external JVM consumer would.

The sample imports `atproto-bom`, so publish the BOM along with the modules first:

```bash
./gradlew publishAtprotoToMavenLocal :shared:atproto-bom:publishToMavenLocal
./gradlew -p samples/atproto-consumer run
```

## Current Scope

- Standalone Kotlin/KMP modules with explicit public APIs and typed value objects
- ATProto identity resolution, OAuth/PDS wire models, reusable PDS runtime services, repo CRUD plus commit/export primitives, and low-level XRPC client support
- A confidential OAuth client (with DPoP) for signing in with accounts hosted on other AT Protocol servers
- Server integration in this repo now consumes shared PDS and repo contracts instead of defining duplicate route DTOs
- Checked-in LogDate lexicon JSON documents plus deterministic generated Kotlin models
- Checked-in official `com.atproto.*` lexicon JSON documents plus deterministic generated Kotlin models for the current server/repo/identity surface
- Publishable Maven metadata, signing hooks, Dokka-backed documentation jars, aggregate Dokka/publish tasks, and a standalone consumer sample
- GitHub Actions release automation for hosted Maven publishing

## Not Yet Complete

- Lexicon code generation for the full protocol surface. `LexiconParser` recognizes `object`,
  `query`, `procedure`, `params`, `string`, `boolean`, `integer`, `array`, `blob`, `token`, and
  `ref`, and maps every other type to `UNKNOWN`. That includes `record`, `union`, `cid-link`,
  `bytes`, and `subscription`. In practice:
  - a definition of type `record` or `subscription` produces no Kotlin class
  - a field of type `union`, `cid-link`, `bytes`, or `blob` becomes an untyped `JsonElement`
- A durable standalone PDS deployment story outside this repository's `server` module

The current library is a strong standalone core for syntax, identity, repo, PDS contracts/runtime,
and hosted-PDS server consumption. The remaining work is broader protocol surface and packaging,
not the core repo/CAR/MST implementation.
