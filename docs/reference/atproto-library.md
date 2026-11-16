# ATProto Library

`studio.hypertext.atproto` is the standalone AT Protocol library surface that LogDate builds on. The server remains the deployment target in this repository, but protocol contracts and core runtime behavior should live in the shared modules first and be consumed by `server`, not recreated there.

## Module Map

- `shared/atproto-syntax`
  - typed identifiers and parsers for DIDs, handles, NSIDs, record keys, TIDs, and AT URIs
  - parsing is looser or stricter than the specifications in places: `Did` allows digits in the
    method name, so do not rely on it to reject malformed input
- `shared/atproto-identity`
  - AT Protocol DID rules, DID documents, handle resolution, `did:web`, and `did:plc`
- `shared/atproto-crypto`
  - base58btc and multikey encoding, P-256 and K-256 curve definitions, and EC key generation,
    low-S signing, verification, and JWK public-key decoding on every platform; see
    [Signing keys](#signing-keys)
- `shared/atproto-plc`
  - PLC directory models and client/runtime behavior
- `shared/atproto-repo`
  - repo records, deterministic block storage, commits, export/import, and the canonical repo engine
  - the messages of `com.atproto.sync.subscribeRepos`: what a commit changed (`RepoCommitChanges`),
    the event types, and the frame encoding (`RepoEventFrames`)
  - `Cid` accepts only base32 strings and checks nothing beyond the `b` prefix after trimming
    whitespace. The DAG-CBOR codec refuses floating-point numbers but does not enforce the other
    data-model rules, such as a record being an object that carries a string `$type`
- `shared/atproto-xrpc`
  - Ktor-backed XRPC client primitives
- `shared/atproto-lexicon`
  - lexicon parsing, a schema checker, registry lookups, a record validator, the `Blob`, `CidLink`
    and `Bytes` data types, and the Kotlin code generator described under
    [Lexicon code generation](#lexicon-code-generation)
  - checked-in schemas and generated models for the official `com.atproto.*` methods the server
    uses
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

### Where the artifacts live

Releases publish to the **GitHub Packages Maven repository on this repo**. Add the repo to your
`settings.gradle.kts` (or top-level `build.gradle.kts`). GitHub Packages requires authentication
even for public packages, so you also have to supply credentials:

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
- `studio.hypertext.atproto:atproto-lexicon-gradle-plugin:$version` (Gradle plugin
  `studio.hypertext.atproto.lexicon`; see [Generating models from lexicons](#generating-models-from-lexicons))

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
  TID's 64-bit number.

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

## Lexicon code generation

A [Lexicon](https://atproto.com/specs/lexicon) is a JSON schema for one kind of AT Protocol data: a
record, a method, or a reusable object. `shared/atproto-lexicon` reads those schemas, checks them,
and writes Kotlin source for them, so the shape of a record is defined once in a schema file and
never copied by hand into a model class. The generated code is plain `kotlinx.serialization`
classes that build for Android, iOS and the JVM.

The pieces are:

- [`LexiconParser`](../../shared/atproto-lexicon/src/commonMain/kotlin/studio/hypertext/atproto/lexicon/LexiconParser.kt)
  turns a schema's JSON into a `LexiconDocument`.
- [`LexiconValidator`](../../shared/atproto-lexicon/src/commonMain/kotlin/studio/hypertext/atproto/lexicon/LexiconValidator.kt)
  is the schema checker. It reports problems in a schema itself, such as a reference that points
  nowhere.
- [`LexiconCodegen`](../../shared/atproto-lexicon/src/commonMain/kotlin/studio/hypertext/atproto/lexicon/LexiconCodegen.kt)
  writes one Kotlin file per schema document. Its output depends only on its input, so the same
  schemas always produce the same bytes.
- [`LexiconRecordValidator`](../../shared/atproto-lexicon/src/commonMain/kotlin/studio/hypertext/atproto/lexicon/LexiconRecordValidator.kt)
  checks a record's data against its schema at runtime.
- [`LexiconData`](../../shared/atproto-lexicon/src/commonMain/kotlin/studio/hypertext/atproto/lexicon/data/LexiconData.kt)
  holds the JSON configuration generated models use and encodes them to and from DAG-CBOR, the
  protocol's binary record encoding.

### What each kind of schema becomes

Class names come from the schema's NSID. The document's last segment, in Pascal case, names its
`main` definition, and every other definition appends its own name: `app.logdate.journal.entry`
generates `Entry`, and its `#textBlock` definition generates `EntryTextBlock`. Each file also
holds an object named `<Document>Lexicon` whose `ID` is the document's NSID.

| Schema                                                                                               | Generated Kotlin                                                                                                                                                                                                                                                                          |
| ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `object`, `params`                                                                                   | A `@Serializable` data class with one property per field, in alphabetical order                                                                                                                                                                                                           |
| `record`                                                                                             | A data class for the record's fields, plus a `$type` property and a companion with `TYPE` (the collection NSID) and `KEY` (the record-key rule)                                                                                                                                           |
| `query`, `procedure`                                                                                 | `<Document>Params`, `<Document>Input` and `<Document>Output` data classes, each only when that part is declared as an object; a `ref` input or output, or a body that is not JSON, gets no class, and the generator writes data classes only, with no client functions that call a method |
| `space` (from the AT Protocol Spaces proposal)                                                       | An object with `TYPE`, `NAME` when the schema has one, `KEY` and `COLLECTIONS`                                                                                                                                                                                                            |
| `permission-set`                                                                                     | An object with `SCOPE`, the OAuth scope `include:<NSID>` an app asks for it with                                                                                                                                                                                                          |
| A named `string`, `integer`, `boolean`, `array`, `bytes`, `cid-link`, `blob` or `unknown` definition | A `typealias`, which fields that refer to it use                                                                                                                                                                                                                                          |
| A named `union` or `ref` definition                                                                  | Nothing; a field that refers to one by name produces code that does not compile, so declare the union on the field that uses it                                                                                                                                                           |
| `token` definition                                                                                   | Nothing; a token is a name, not a type                                                                                                                                                                                                                                                    |
| `subscription`                                                                                       | Nothing; no model is generated for a subscription                                                                                                                                                                                                                                         |

Fields map to these types:

| Field type        | Kotlin type                                                       |
| ----------------- | ----------------------------------------------------------------- |
| `string`, `token` | `String`                                                          |
| `boolean`         | `Boolean`                                                         |
| `integer`         | `Long`                                                            |
| `array`           | `List` of the item type                                           |
| `ref`             | The class or type alias generated for the definition it points at |
| `union`           | A sealed interface generated beside the class that uses it        |
| `blob`            | `Blob`                                                            |
| `cid-link`        | `CidLink`                                                         |
| `bytes`           | `Bytes`                                                           |
| `unknown`         | `JsonElement`                                                     |

### Where generated code lives

Three sets of models are generated in this repository, in two different ways.

LogDate's own records are generated on every build and never checked in. The
[`shared/lexicon`](../../shared/lexicon/README.md) module applies the `app.logdate.lexicon-models`
Gradle plugin, which runs the generator over [`lexicons/app/logdate`](../../lexicons) and
`lexicons/community` before compiling and writes into `build/generated/lexicon/kotlin`. A schema
change therefore reaches the code the next time anything builds, and an invalid schema fails the
build with a message naming the schema and the problem. Any Kotlin Multiplatform module can do the
same by applying the plugin and listing its schemas in a `lexiconModels { }` block;
[`LexiconModelsPlugin`](../../build-logic/src/main/kotlin/app/logdate/LexiconModelsPlugin.kt)
documents the options.

The models inside `shared/atproto-lexicon` itself are checked in, because that module is the
generator and cannot run itself before compiling its own sources. They cover the official
`com.atproto.*` methods the server implements, and the published library includes generated models
only for those methods; `app.bsky` and the other `com.atproto` schemas must be generated by the
consumer. Regenerate them with
`generateOfficialAtprotoLexicons`; a JVM test fails when the checked-in files no longer match what
the generator writes.

The library's own tests use a third set, generated at build time into its common test sources from
a few real LogDate and community schemas plus
[`kitchenSink.json`](../../shared/atproto-lexicon/src/commonTest/lexicons/kitchenSink.json), a
synthetic schema that exercises every kind LogDate's schemas do not use. Those tests run on the
JVM, Android and iOS and check that each model survives a round trip through JSON and through
DAG-CBOR unchanged.

The generator can also be run directly. Its entry point is `GenerateLexiconsKt` in the module's
JVM build, and it takes `<inputs> <outputDir> [packageName]` followed by any of these options:

| Option                        | Effect                                                                                                                                                                                                                                                                          |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `--namespace-layout`          | Puts each document in `packageName` followed by its NSID's namespace, so `app.logdate.journal.entry` lands in `<packageName>.app.logdate.journal`. Without it every file goes in `packageName`. A whole schema family needs this, because document names such as `defs` repeat. |
| `--record-index <ObjectName>` | Also writes an object whose `records` map gives the serializer for every record type, keyed by collection NSID, for code that learns a record's type only at runtime.                                                                                                           |
| `--schema-index <ObjectName>` | Also writes an object holding every schema's JSON, so an app can check records at runtime on platforms that cannot read schema files.                                                                                                                                           |

`inputs` is a list of schema files and directories separated by the platform's path separator.
Every document is checked before anything is written, and generation stops at the first invalid
one.

### Decisions behind the generated code

These choices are not visible from the output alone, and each has a reason.

**`Blob`, `CidLink` and `Bytes` live in the schema library.** The three data-model types that
schemas refer to are small classes in the `studio.hypertext.atproto.lexicon.data` package of
`shared/atproto-lexicon`. Generated models import them from there, so a module that only reads and
writes records declares one dependency, on the schema library, and never names the repository
engine. The types are built on `Cid` and the DAG-CBOR codec from `shared/atproto-repo`, which the
schema library exposes as a transitive dependency.

**A union is a sealed interface with one wrapper per member.** For a field such as an entry's
`blocks`, the generator writes a sealed interface named after the owning class and the field
(`EntryBlocksItem` for the items of `Entry.blocks`), with one small data class per allowed type
that wraps the member's own class, such as `EntryBlocksItem.TextBlock(val value: EntryTextBlock)`.
`EntryTextBlock` does not implement the interface directly, because a shared type such as a
location can belong to unions in many schema documents, and an interface list on that class would
make the code for every document depend on every other document that mentions it. A member's wrapper is named after its definition, and when two members would share a name, both
take their full class names instead.

**Open unions keep members they do not recognize.** Lexicon unions are open unless a schema says
`"closed": true`, meaning a newer version of the schema may add member types. An open union's
generated interface has an extra `Unknown(type, value)` member that keeps the `$type` and the full
JSON of anything this version of the code does not know, and writes it back exactly as it was
read. A device running an older app can therefore open and save an entry that contains a block
type added later without deleting that block. A closed union has no `Unknown` member and fails to
decode a `$type` outside its list.

**Each union has a generated serializer that reads and writes `$type`.** The protocol's rule is
that a union member always carries a `$type` naming its schema, while an object reached through a
plain `ref` carries none. The serializer adds `$type` on the way out and uses it to pick the
member class on the way in. A record carries `$type` too, so a generated record class has a
`lexiconType` property, serialized as `$type`, that defaults to the collection's NSID and is
always written.

**An optional field is nullable with a `null` default, and stays absent.** A field the schema does
not list as required decodes when it is missing and is left out again when the record is encoded,
so reading and re-saving a record never adds fields. A field that is required but listed as
`nullable` has no default and is always written, as `null` when it has no value. Fields the schema
does not describe at all are ignored on read, so a record written for a newer schema still decodes.
They are not kept, though: unlike an unknown union member, an unknown field is gone when the model
is encoded again.

**The record-key rule is part of the model.** A record schema's `key` is parsed into
`LexiconRecordKey`: `Tid` for timestamp identifiers, `Nsid`, `Literal` for one fixed key such as
`self`, or `Any`. The generated class exposes it as `KEY`, so storage code can check a key before
writing instead of finding out when another service rejects the record.

**Bytes are standard base64 in JSON.** The data model writes a `bytes` value in JSON as
`{"$bytes": "<base64>"}` using the standard alphabet. The library writes it without padding and
accepts it with or without. `Bytes` and the DAG-CBOR codec in `shared/atproto-repo` use the same
rule, because the URL-safe alphabet would turn a record that passes through JSON into a different
value, and a different content identifier, from the one other AT Protocol implementations produce.
In DAG-CBOR a `bytes` value is a native byte string and a `cid-link` is a native link.

**Models encode through JSON, including to DAG-CBOR.** The serializers for `Blob`, `CidLink`,
`Bytes` and unions work only with the JSON format. `LexiconData.encodeToDagCbor` and
`decodeFromDagCbor` convert a model to its JSON form first and then to or from DAG-CBOR, so there
is one definition of each type's wire shape instead of one per format.

**Only the current blob form is read.** A blob is `{"$type": "blob", "ref": {"$link": …},
"mimeType": …, "size": …}`. The data model also allows an older form with a `cid` and no size,
found only in very old records from other apps. LogDate's records and the community schemas it
reuses never contain it, so `Blob` rejects that form with a message that says so instead of
guessing a size.

### What the schema checker reports

`LexiconValidator.validate` returns every problem it finds, each with the path of the definition
it concerns. Beyond unsupported type names and missing array item types, it reports:

- a primary type (`record`, `query`, `procedure`, `subscription`, `permission-set` or `space`)
  defined anywhere but `main`;
- a record without a `key`, with a key rule that is not `tid`, `nsid`, `any` or `literal:<value>`,
  or whose `record` is not an object;
- a `ref` with no target, or whose target is found neither in the document nor in the
  `LexiconRegistry` of other documents passed to the checker;
- a union member that does not resolve, or that points at anything other than an object or a
  record;
- a closed union with no members;
- a blob `accept` entry that is not a MIME type, `type/*` or `*/*`;
- a negative size or length limit, or a minimum above its maximum;
- a `space` declaration or `permission-set` that breaks the rules for those kinds, such as a
  permission that names a collection outside the set's own namespace.

`LexiconRecordValidator` is the counterpart for data. Given a collection and a record, it checks
required fields, types, string formats and lengths, integer ranges, allowed values, blob types and
sizes, and union membership, and it accepts fields and open-union members the schema does not
describe. It checks a record's `$type` against the collection only when the record has one.

## Publishing

Maintainers should use the detailed publishing guide in
[`atproto-publishing.md`](atproto-publishing.md)
for property precedence, remote publish task names, signing behavior, and
artifact expectations.

Publish locally:

```bash
./gradlew publishAtprotoToMavenLocal :shared:atproto-bom:publishToMavenLocal
```

`publishAtprotoToMavenLocal` publishes the ten Kotlin Multiplatform modules and the lexicon Gradle
plugin but not the BOM, so add `:shared:atproto-bom:publishToMavenLocal` whenever a consumer, such
as the standalone sample, imports the BOM.

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

Regenerate the checked-in official AT Protocol lexicon models used by this repo:

```bash
./gradlew :shared:atproto-lexicon:generateOfficialAtprotoLexicons
```

Android Studio users can run the same Dokka task through
[`Generate ATProto Dokka.run.xml`](../../.run/Generate%20ATProto%20Dokka.run.xml).

## Signing keys

`EcPrivateKey` and `EcPublicKey` in `atproto-crypto` generate keys, sign, and verify on the JVM,
Android, and iOS, for both curves the AT Protocol uses: P-256 and K-256 (secp256k1).

```kotlin
val privateKey = EcPrivateKey.generate(EcCurve.K256)
val signature = privateKey.signSha256(payload)

// What goes in a DID document's publicKeyMultibase.
val multikey = privateKey.publicKey.toMultikey()

val valid = EcPublicKey.fromMultikey(multikey).verifySha256(payload, signature)
```

A private key is its 32-byte scalar (`toByteArray()` and `EcPrivateKey.fromByteArray`), and a public
key is its 33-byte compressed point, so a key stored on one platform works on another. Storing the
scalar safely, in the Keychain or the Android Keystore for example, is the caller's job.

Signatures are always "low-S", the one form of an ECDSA signature the AT Protocol accepts, and
`verifySha256` refuses the other form unless `allowHighS = true`. Pass that only when checking
JOSE signatures such as JWTs and DPoP proofs, which allow both.

Each platform uses an established cryptography library for the curve arithmetic, and the choice has
consequences worth knowing:

- **JVM and Android use BouncyCastle** directly, not through `java.security`. Android ships a
  cut-down provider under BouncyCastle's name without secp256k1, so the `java.security` route
  fails there for K-256. Signatures are deterministic (RFC 6979): the same key and payload always
  give the same bytes, and no random number generator can weaken a signature.
- **iOS uses OpenSSL**, linked statically into the app through
  [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin). Apple's CryptoKit and
  Security frameworks have P-256 but not secp256k1, so one library covers both curves. This adds
  OpenSSL's `libcrypto` to the app binary, a few megabytes before dead-code stripping. Signatures
  use a random nonce from OpenSSL's generator, so the same payload signs to different bytes each
  time. They verify everywhere, but code that compares signature bytes across platforms will not
  hold on iOS.

On the JVM, `EcKeySupport` keeps the `java.security` key types for code that stores keys as PKCS#8,
and converts them with `toEcPrivateKey` and `toEcPublicKey`.

## Generating models from lexicons

The `studio.hypertext.atproto.lexicon` Gradle plugin generates `@Serializable` Kotlin classes from
your own [Lexicon](https://atproto.com/specs/lexicon) schemas on every build, in Kotlin JVM and
Kotlin Multiplatform projects:

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("studio.hypertext.atproto.lexicon") version "<version>"
}

lexiconModels {
    schemas.from("src/main/lexicons")
    basePackage = "com.example.models"
}

dependencies {
    implementation("studio.hypertext.atproto:atproto-lexicon:<version>")
}
```

The [plugin's README](../../shared/atproto-lexicon-gradle-plugin/README.md) explains every option
and how the plugin finds its generator. The standalone sample below uses the plugin for its own
record type.

## Changing the public API

Each published module keeps a text listing of its public API in its `api` directory: one file for
the JVM and one for iOS (`.klib.api`). The listings are checked in, and `apiCheck` compares them
with the code, so a change to anything public shows up in review as a diff to these files and
cannot slip in unnoticed. `./gradlew check` includes `apiCheck`, and CI runs it on every change.

CI runs on Linux, which cannot compile for iOS. There the check compares the JVM listing in full
and takes the iOS listing as it is checked in. A Mac compiles both, so run `./gradlew apiCheck` on
a Mac before changing anything public in `iosMain` or common code.

When `apiCheck` fails, decide whether the change is intended. If it is, regenerate the listings
and commit them with the change:

```bash
./gradlew apiDump
```

Removing or changing a declaration that already shipped breaks consumers compiled against the old
version, so prefer adding alongside and deprecating the old declaration. The listings cover the
JVM and iOS builds; Android compiles the same shared code as the JVM, plus any `androidMain`
sources, which the listings do not track separately.

## Standalone Sample

The consumer sample in [`samples/atproto-consumer`](../../samples/atproto-consumer) is intentionally
outside the main Gradle build. It depends on `mavenLocal()` artifacts, not project modules, so it
verifies the published API surface the way an external JVM consumer would.
It generates the model for its record from a lexicon schema with the lexicon Gradle plugin.

The sample imports `atproto-bom`, so publish the BOM along with the modules first:

```bash
./gradlew publishAtprotoToMavenLocal :shared:atproto-bom:publishToMavenLocal
./gradlew -p samples/atproto-consumer run
```
