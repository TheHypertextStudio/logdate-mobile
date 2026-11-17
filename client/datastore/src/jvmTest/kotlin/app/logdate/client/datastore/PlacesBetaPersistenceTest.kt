package app.logdate.client.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.logdate.client.datastore.featureflags.DataStoreFeatureFlagStore
import app.logdate.client.datastore.featureflags.FeatureFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val placesFlag get() = FeatureFlag.entries.single { it.key == "places_beta_enabled" }

class PlacesBetaPersistenceTest {
    @Test
    fun placesBetaChoiceSurvivesSeparateProcesses() {
        val directory = Files.createTempDirectory("logdate-places-process").toFile()
        val path = directory.resolve("settings.preferences_pb").absolutePath
        val classpath =
            (
                System.getProperty("java.class.path").split(File.pathSeparator).asSequence() +
                    generateSequence(javaClass.classLoader) { it.parent }
                        .filterIsInstance<URLClassLoader>()
                        .flatMap { it.urLs.asSequence() }
                        .map { File(it.toURI()).absolutePath }
            ).distinct()
                .joinToString(File.pathSeparator)

        fun launch(action: String) {
            val process =
                ProcessBuilder(
                    File(System.getProperty("java.home"), "bin/java").absolutePath,
                    "-Djava.awt.headless=true",
                    "-cp",
                    classpath,
                    PlacesBetaProcessProbe::class.java.name,
                    path,
                    action,
                ).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Preference process timed out: $action")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        try {
            launch("hidden")
            launch("enable")
            launch("enabled")
            launch("hide")
            launch("hidden")
            launch("enable")
            launch("enabled")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun placesBetaChoiceSurvivesClosingAndReopeningTheStore() =
        runBlocking {
            val directory = Files.createTempDirectory("logdate-places-mode").toFile()
            val path = directory.resolve("settings.preferences_pb").absolutePath.toPath()
            var storeJob = Job()

            fun openPreferences() =
                DataStoreFeatureFlagStore(
                    PreferenceDataStoreFactory.createWithPath(
                        scope = CoroutineScope(Dispatchers.IO + storeJob),
                        produceFile = { path },
                    ),
                )
            try {
                val firstLaunch = openPreferences()
                assertFalse(firstLaunch.isEnabled(placesFlag))
                firstLaunch.setEnabled(placesFlag, true)
                assertTrue(firstLaunch.isEnabled(placesFlag))
                storeJob.cancelAndJoin()

                storeJob = Job()
                val secondLaunch = openPreferences()
                assertTrue(secondLaunch.isEnabled(placesFlag))
                secondLaunch.setEnabled(placesFlag, false)
                assertFalse(secondLaunch.isEnabled(placesFlag))
                storeJob.cancelAndJoin()

                storeJob = Job()
                val thirdLaunch = openPreferences()
                assertFalse(thirdLaunch.isEnabled(placesFlag))
                thirdLaunch.setEnabled(placesFlag, true)
                assertTrue(thirdLaunch.isEnabled(placesFlag))
            } finally {
                storeJob.cancelAndJoin()
                directory.deleteRecursively()
            }
        }
}

object PlacesBetaProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            val job = Job()
            val preferences =
                DataStoreFeatureFlagStore(
                    PreferenceDataStoreFactory.createWithPath(
                        scope = CoroutineScope(Dispatchers.IO + job),
                        produceFile = { args[0].toPath() },
                    ),
                )
            try {
                when (args[1]) {
                    "enable" -> preferences.setEnabled(placesFlag, true)
                    "hide" -> preferences.setEnabled(placesFlag, false)
                    "enabled" -> assertTrue(preferences.isEnabled(placesFlag))
                    "hidden" -> assertFalse(preferences.isEnabled(placesFlag))
                    else -> error("Unknown preference action")
                }
            } finally {
                job.cancelAndJoin()
            }
        }
}
