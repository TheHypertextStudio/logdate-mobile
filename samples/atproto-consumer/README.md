# ATProto Consumer Sample

This sample is intentionally outside the main Gradle build. It consumes
`studio.hypertext.atproto` from `mavenLocal()` so it exercises the
published library surface instead of project dependencies.

It is the quickest way to prove that the ATProto modules can be published and
then consumed by an external JVM project without any dependency on `server` or
the main repo module graph.

It also applies the `studio.hypertext.atproto.lexicon` Gradle plugin. The build
generates a Kotlin model from the schema in
[`src/main/lexicons`](src/main/lexicons), and the sample writes and reads a
record through that model. Inside this repository the plugin is built from its
source in
[`shared/atproto-lexicon-gradle-plugin`](../../shared/atproto-lexicon-gradle-plugin),
so the sample configures before anything is published. The generator the plugin
runs is the published `atproto-lexicon` artifact, like every other dependency
here.

## Run

```bash
./gradlew publishAtprotoToMavenLocal :shared:atproto-bom:publishToMavenLocal

./gradlew -p samples/atproto-consumer run
```

To regenerate the ATProto API docs before validating the sample, run:

```bash
./gradlew generateAtprotoDokka
```

Expected output:

```text
Resolved alice.example.com to did:web:example.com and loaded at://did:web:example.com/com.example.sample.note/note-1
```

## Version Override

By default the sample reads `atproto.version` from the root
[`gradle.properties`](../../gradle.properties).

To point it at a different locally published version:

```bash
./gradlew -p samples/atproto-consumer run -PatprotoVersion=0.1.0
```
