@file:Suppress("UnstableApiUsage")

rootProject.name = "LogDateServerDocker"
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
            mavenContent { snapshotsOnly() }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The full build and the server's container build resolve from the same repositories.
apply(from = "build-logic/repositories.settings.gradle.kts")

include(":client:util")

include(":server")

include(":shared:atproto-crypto")
include(":shared:atproto-syntax")
include(":shared:atproto-identity")
include(":shared:atproto-xrpc")
include(":shared:atproto-repo")
include(":shared:atproto-plc")
include(":shared:atproto-lexicon")
include(":shared:atproto-pds")
include(":shared:atproto-pds-runtime")
include(":shared:config")
include(":shared:model")
include(":shared:lexicon")
include(":shared:space")
