@file:Suppress("UnstableApiUsage")

rootProject.name = "LogDate"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven {
            // Get latest version from https://androidx.dev/snapshots/builds/
            url = uri("https://androidx.dev/snapshots/builds/13551459/artifacts/repository")
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        }
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
        maven {
            // Get latest version from https://androidx.dev/snapshots/builds/
            url = uri("https://androidx.dev/snapshots/builds/13551459/artifacts/repository")
        }
    }
}

// End build targets
include(":app:compose-main")
include(":app:wear")
include(":app:android-main")
include(":benchmark:phone-macro")
include(":benchmark:phone-baselineprofile")
include(":benchmark:wear-macro")
include(":benchmark:wear-baselineprofile")
include(":benchmark:micro")
// Core client modules
include(":client:data")
include(":client:ui")
include(":client:theme")
include(":client:text")
include(":client:domain")
include(":client:repository")
include(":client:database")
include(":client:logdate-datastore")
project(":client:logdate-datastore").projectDir = file("client/datastore")
include(":client:networking")
include(":client:intelligence")
include(":client:location")
include(":client:sensor")
include(":client:media")
include(":client:device")
include(":client:sharing")
include(":client:permissions")
include(":client:util")
include(":client:awareness")
include(":client:billing")
include(":client:notifications")
include(":client:sync")
include(":client:health-connect")
include(":client:calendar-sync")
include(":client:screenshot-scenes")
// Client Features
include(":client:feature:core")
include(":client:feature:editor")
include(":client:feature:journal")
include(":client:feature:rewind")
include(":client:feature:timeline")
include(":client:feature:location-timeline") // TODO: Probably consolidate into timeline at some point
include(":client:feature:onboarding")
include(":client:feature:search")
include(":client:feature:library")
include(":client:feature:android-widgets")
include(":client:feature:remotedisplay")
include(":client:feature:speechrecognition")
include(":client:feature:postcards")
include(":client:feature:stickers")
include(":client:feature:events")
// Shared cross-platform modules
include(":shared:activitypub")
include(":shared:atproto-crypto")
include(":shared:atproto-syntax")
include(":shared:atproto-identity")
include(":shared:atproto-xrpc")
include(":shared:atproto-repo")
include(":shared:atproto-plc")
include(":shared:atproto-lexicon")
include(":shared:atproto-pds")
include(":shared:atproto-pds-runtime")
include(":shared:atproto-oauth-client")
include(":shared:atproto-bom")
include(":shared:config")
include(":shared:lexicon")
include(":shared:space")
include(":shared:model")

// Server-specific modules
include(":server")
include(":integration:server-client-e2e")
include(":integration:atproto-oauth-fixtures")

// Developer tooling (not shipped)
include(":tools:passkey-test-provider")
include(":tools:native-restore-driver")
// Sample apps
//
// samples/atproto-consumer is wired as a *composite-build* (includeBuild)
// rather than a subproject. The sample reads `studio.hypertext.atproto:*`
// artifacts from Maven coordinates (mavenLocal first, then GH Packages /
// Maven Central) so it acts as a real consumer of the published library.
// As a subproject, the root `./gradlew test` would walk into it and try
// to resolve those coordinates at configuration time — which fails until
// publishing has happened, breaking CI. As an includedBuild it has its
// own settings + own task graph, isolated from root aggregate tasks.
//
// To run the sample after publishing locally:
//
//   ./gradlew \
//     :shared:atproto-bom:publishToMavenLocal \
//     :shared:atproto-syntax:publishToMavenLocal \
//     :shared:atproto-identity:publishToMavenLocal \
//     :shared:atproto-repo:publishToMavenLocal \
//     :shared:atproto-xrpc:publishToMavenLocal \
//     :shared:atproto-pds:publishToMavenLocal
//   ./gradlew -p samples/atproto-consumer run
//
// (or `./gradlew :atproto-consumer:run` from anywhere — the included
// build exposes its tasks under the root project name from its own
// settings.gradle.kts, which is `atproto-consumer`.)
includeBuild("samples/atproto-consumer")
