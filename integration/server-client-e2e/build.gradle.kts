plugins {
    id("app.logdate.jvm-service")
    id("app.logdate.test-database-environment")
}

dependencies {
    testImplementation(projects.server)
    testImplementation(projects.client.sync)
    testImplementation(projects.client.networking)
    testImplementation(projects.client.data)
    testImplementation(projects.client.database)
    testImplementation(projects.client.device)
    testImplementation(projects.client.media)
    testImplementation(projects.client.repository)
    testImplementation(projects.client.logdateDatastore)
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.exposed.jdbc)
    testImplementation(projects.shared.config)
    testImplementation(projects.shared.model)
    testImplementation(projects.shared.space)
    testImplementation(projects.shared.atprotoXrpc)
    testImplementation(projects.shared.atprotoRepo)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.core)

    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
}

// Build once, then launch the local Android fixture without holding a Gradle daemon or runner lock.
tasks.register("writeAndroidHistoryHarnessClasspath") {
    dependsOn(tasks.testClasses)
    val destination = layout.buildDirectory.file("android-history-harness.classpath")
    val harnessClasspath = sourceSets.test.get().runtimeClasspath
    inputs.files(harnessClasspath)
    outputs.file(destination)
    doLast {
        destination.get().asFile.writeText(harnessClasspath.asPath)
    }
}

tasks.withType<Test>().configureEach {
    // The server reads its feature flags from the environment, so the in-process test server needs these set.
    environment("LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED", "true")
    // The authorization page refuses to run without cryptographic passkey checks; the fixtures sign real ceremonies.
    environment("WEBAUTHN_STRICT_VERIFICATION", "true")
    if (System.getenv("LOGDATE_LARGE_BACKUP_TEST") == "1") {
        maxHeapSize = "512m"
    }
}

// Accounts created while personal spaces are on start in the personal space, and the legacy sync routes
// refuse them. The server reads the flag from the environment, so the personal space test runs in its own
// task with the flag set and every other test runs against legacy accounts.
val personalSpaceTest by tasks.registering(Test::class) {
    description = "Runs the personal space tests against a server with personal spaces enabled."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    environment("LOGDATE_PERSONAL_SPACE_ENABLED", "true")
    // The move tests move existing accounts, and don't wait out the two minutes a hold stands on LogDate Cloud.
    environment("LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED", "true")
    environment("LOGDATE_SPACE_MIGRATION_HOLD_GRACE_SECONDS", "0")
    filter { includeTestsMatching("*PersonalSpace*E2ETest") }
}

// The repository event stream shows only accounts that keep their journal in a personal space and whose
// identity other apps can look up, so this task turns on personal spaces, the stream and `did:web` identities
// (a `did:plc` counts as published only after it is sent to the real PLC directory, which a test never does).
val publicFirehoseTest by tasks.registering(Test::class) {
    description = "Runs the public repository event stream tests against a server with the stream enabled."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    environment("LOGDATE_PERSONAL_SPACE_ENABLED", "true")
    environment("ATPROTO_FIREHOSE_ENABLED", "true")
    environment("ATPROTO_HOSTED_DID_METHOD", "web")
    filter { includeTestsMatching("*PublicFirehose*E2ETest") }
}

tasks.test {
    filter {
        excludeTestsMatching("*PersonalSpace*E2ETest")
        excludeTestsMatching("*PublicFirehose*E2ETest")
    }
    finalizedBy(personalSpaceTest, publicFirehoseTest)
}
