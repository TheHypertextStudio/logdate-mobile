package app.logdate.client.sync.migration

import io.github.aakira.napier.Napier
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

/**
 * Desktop implementation of MigrationStorage using files.
 */
class FileMigrationStorage(
    private val json: Json,
) : MigrationStorage {
    private val appDir: File = getAppDataDirectory()
    private val migrationStateFile = File(appDir, "migration_state.json")

    init {
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
    }

    override suspend fun storeMigrationState(state: MigrationState) {
        try {
            val stateJson = json.encodeToString(state)
            migrationStateFile.writeText(stateJson)
        } catch (e: Exception) {
            Napier.e("Failed to store migration state: ${e::class.simpleName}")
        }
    }

    override suspend fun retrieveMigrationState(): MigrationState? {
        if (!migrationStateFile.exists()) {
            return null
        }

        return try {
            val stateJson = migrationStateFile.readText()
            json.decodeFromString<MigrationState>(stateJson)
        } catch (e: Exception) {
            Napier.e("Failed to parse migration state: ${e::class.simpleName}")
            null
        }
    }

    override suspend fun clearMigrationState() {
        if (migrationStateFile.exists()) {
            migrationStateFile.delete()
        }
    }

    private fun getAppDataDirectory(): File {
        val userHome = System.getProperty("user.home")
        val osName = System.getProperty("os.name").lowercase()

        val appDataPath =
            when {
                osName.contains("win") -> {
                    Paths.get(System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming", "LogDate")
                }

                osName.contains("mac") -> {
                    Paths.get(userHome, "Library", "Application Support", "LogDate")
                }

                else -> {
                    // Linux or other Unix-like OS
                    Paths.get(userHome, ".logdate")
                }
            }

        return appDataPath.toFile()
    }
}
