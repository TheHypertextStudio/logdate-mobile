package app.logdate

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import java.security.MessageDigest

/**
 * Makes the database a test task connects to part of its build cache key.
 *
 * Tests that use PostgreSQL read `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD` from the
 * environment, and skip when no database is configured. Gradle doesn't track environment variables,
 * so without this a run that skipped them could be replayed from the cache as passing on a machine
 * that has a database, and the reverse. The key holds a hash of the URL and user, and only whether a
 * password is set, so the password never reaches the key.
 */
class TestDatabaseEnvironmentPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val variables =
            listOf(
                "DATABASE_URL",
                "DATABASE_USER",
                "DATABASE_PASSWORD",
                "LOGDATE_DIAGNOSTIC_TEST_DATABASE_URL",
                "LOGDATE_DIAGNOSTIC_TEST_DISPOSABLE",
            ).associateWith(project.providers::environmentVariable)
        val url = variables.getValue("DATABASE_URL").orElse("")
        val user = variables.getValue("DATABASE_USER").orElse("")
        val hasPassword =
            variables
                .getValue("DATABASE_PASSWORD")
                .map { it.isNotEmpty() }
                .orElse(false)
        val fingerprint =
            url.zip(user) { u, name -> u to name }.zip(hasPassword) { (u, name), password ->
                databaseFingerprint(u, name, password)
            }
        project.tasks.withType(Test::class.java).configureEach {
            // Preserve lazy inheritance; bind managed fixture values only before a fork.
            doFirst(RefreshDatabaseEnvironment(variables))
            inputs.property("databaseEnvironment", fingerprint)
            inputs.property(
                "disposableDatabaseEnvironment",
                variables
                    .getValue("LOGDATE_DIAGNOSTIC_TEST_DATABASE_URL")
                    .orElse("")
                    .map { databaseFingerprint(it, "", false) },
            )
            inputs.property(
                "disposableDatabaseAllowed",
                variables.getValue("LOGDATE_DIAGNOSTIC_TEST_DISPOSABLE").orElse(""),
            )
        }
    }
}

private class RefreshDatabaseEnvironment(
    private val variables: Map<String, Provider<String>>,
) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as Test
        val current = variables.mapNotNull { (name, value) -> value.orNull?.let { name to it } }.toMap()
        test.setEnvironment(test.environment.filterKeys { it !in variables } + current)
    }
}

/**
 * Describes the database environment for a build cache key: a hash of [url], a hash of [user], and
 * whether a password is set. An unset database is `none`, so it never matches one that is set.
 */
fun databaseFingerprint(
    url: String,
    user: String,
    hasPassword: Boolean,
): String {
    if (url.isEmpty()) return "none"
    return "url=${sha256(url)} user=${sha256(user)} password=${if (hasPassword) "set" else "unset"}"
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
