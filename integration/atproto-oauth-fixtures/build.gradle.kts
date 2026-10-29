plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "Test-only Atmosphere OAuth provider with independent JOSE verification."

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(projects.shared.atprotoOauthClient)
    implementation(libs.ktor.client.mock)
    implementation(libs.nimbus.jose.jwt)
}
