# atproto-lexicon-gradle-plugin

A Gradle plugin that turns [Lexicon](https://atproto.com/specs/lexicon) schemas into Kotlin
classes.

A Lexicon schema is a JSON file that describes one kind of AT Protocol data, such as a record or
the input of an API call. This plugin reads your schemas on every build and writes a
`@Serializable` Kotlin class for each definition, so your code works with typed models instead of
raw JSON. Nothing generated is checked in: a schema change reaches the code the next time anything
compiles, and an invalid schema fails the build with a message naming the schema and the problem.

## Add it to a project

The plugin works in Kotlin JVM and Kotlin Multiplatform projects. Apply it next to the Kotlin and
serialization plugins, say where the schemas are, and add the library the generated code uses:

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

Gradle looks for plugins in the Gradle Plugin Portal by default. This plugin is published with the
other `studio.hypertext.atproto` artifacts, so add the repository that hosts them to
`settings.gradle.kts`. Today that is GitHub Packages, which asks for a GitHub user name and a token
with the `read:packages` scope even for public artifacts:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/TheHypertextStudio/logdate-mobile")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.token").get()
            }
        }
        gradlePluginPortal()
    }
}
```

The project's own `repositories { }` block needs the same repository, because the plugin downloads
its generator from there. [`docs/reference/atproto-library.md`](../../docs/reference/atproto-library.md)
covers the repositories and credentials in full.

## Options

| Option            | What it does                                                                                                                                                                                         | Default                                                          |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `schemas`         | Lexicon JSON files, or directories that are read recursively.                                                                                                                                        | none; required                                                   |
| `basePackage`     | The package for the generated code.                                                                                                                                                                  | none; required                                                   |
| `namespaceLayout` | When true, each schema goes in `basePackage` followed by its NSID's namespace, so `com.example.blog.post` lands in `<basePackage>.com.example.blog`. When false, every schema goes in `basePackage`. | `true`                                                           |
| `recordIndex`     | When set, also writes an object of this name that maps each record type to its serializer.                                                                                                           | not written                                                      |
| `schemaIndex`     | When set, also writes an object of this name that holds every schema's JSON, for checking records at run time.                                                                                       | not written                                                      |
| `sourceSet`       | The Kotlin source set the generated code joins.                                                                                                                                                      | `commonMain` in a Multiplatform project, `main` in a JVM project |

The `generateLexiconModels` task writes the code to `build/generated/lexicon/kotlin`. Compiling
runs it automatically; run it yourself to look at the output:

```bash
./gradlew generateLexiconModels
```

## Which generator runs

The code that reads schemas and writes Kotlin lives in the `atproto-lexicon` library, not in this
plugin. The plugin downloads the JVM build of `studio.hypertext.atproto:atproto-lexicon` at the
plugin's own version and runs it. Plugin and generator are released together, so the pair always
matches.

To run a different build of the generator, declare it on the `lexiconGenerator` configuration. The
modules in this repository do that to generate with the generator in the same checkout:

```kotlin
dependencies {
    lexiconGenerator(project(":shared:atproto-lexicon"))
}
```

## Working on the plugin

The plugin is a separate Gradle build, because a build cannot apply a plugin that one of its own
modules is still compiling. The repository's root `settings.gradle.kts` includes it, which is how
`shared/lexicon` and `shared/atproto-lexicon` apply it without a version.

Run its tests from the repository root:

```bash
./gradlew -p shared/atproto-lexicon-gradle-plugin test
```

`./gradlew publishAtprotoToMavenLocal` publishes the plugin to the local Maven repository with the
other artifacts.
