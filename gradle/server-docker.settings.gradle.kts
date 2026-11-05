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
            url = uri("https://androidx.dev/snapshots/builds/13551459/artifacts/repository")
        }
    }
}

include(":client:util")
project(":client:util").projectDir = file("client/util")

include(":server")

include(":shared:atproto-crypto")
include(":shared:atproto-syntax")
include(":shared:atproto-identity")
include(":shared:atproto-oauth-client")
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
