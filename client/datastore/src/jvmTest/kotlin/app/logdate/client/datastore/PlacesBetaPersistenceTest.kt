package app.logdate.client.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.datastore.featureflags.DataStoreFeatureFlagStore
import app.logdate.client.datastore.featureflags.FeatureFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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

private val placesFlag get() = checkNotNull(FeatureFlag.forKey("places_beta_enabled"))

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
    fun placesBetaChoiceSurvivesReopeningWithoutChangingLegacyHistoryOrRecording() =
        runBlocking {
            val placesKey = booleanPreferencesKey("places_beta_enabled")
            listOf(false, true).forEach { legacyHistoryEnabled ->
                val directory = Files.createTempDirectory("logdate-places-mode").toFile()
                val path = directory.resolve("settings.preferences_pb").absolutePath.toPath()
                val originalPreferences =
                    mutablePreferencesOf(
                        booleanPreferencesKey("human_location_history_enabled") to legacyHistoryEnabled,
                        booleanPreferencesKey("developer_mode_enabled") to false,
                        booleanPreferencesKey("location_background_tracking_enabled") to true,
                        longPreferencesKey("location_tracking_interval_minutes") to 7L,
                        stringPreferencesKey("location_capture_mode") to "ACTIVE",
                    )
                val preserved = originalPreferences.asMap()
                try {
                    repeat(3) { launch ->
                        val storeJob = Job()
                        try {
                            val preferences =
                                PreferenceDataStoreFactory.createWithPath(
                                    scope = CoroutineScope(Dispatchers.IO + storeJob),
                                    produceFile = { path },
                                )
                            if (launch == 0) {
                                preferences.updateData { originalPreferences }
                            }
                            val store = DataStoreFeatureFlagStore(preferences)
                            assertEquals(launch == 1, store.isEnabled(placesFlag))
                            when (launch) {
                                0 -> store.setEnabled(placesFlag, true)
                                1 -> store.setEnabled(placesFlag, false)
                            }
                            assertEquals(
                                preserved,
                                preferences.data
                                    .first()
                                    .asMap()
                                    .filterKeys { it != placesKey },
                                "Legacy history=$legacyHistoryEnabled must survive launch $launch",
                            )
                        } finally {
                            storeJob.cancelAndJoin()
                        }
                    }
                } finally {
                    directory.deleteRecursively()
                }
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
