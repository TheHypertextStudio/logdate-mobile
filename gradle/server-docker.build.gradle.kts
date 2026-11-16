plugins {
    // Puts build-logic's classes, such as GenerateConformanceFixtures, on every module's build
    // script classpath, as the full build's root plugins do.
    id("app.logdate.kmp-library") apply false
    alias(libs.plugins.studio.hypertext.atproto.lexicon) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kover) apply false
}
