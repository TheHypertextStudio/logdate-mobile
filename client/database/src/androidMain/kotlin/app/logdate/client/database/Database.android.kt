package app.logdate.client.database

import android.content.Context
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.aakira.napier.Napier
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.zetetic.database.sqlcipher.SQLiteDatabase as SQLCipherDatabase

/**
 * Creates a database builder for the LogDate database.
 *
 * This creates the database using the system-provided database path.
 *
 * @param context The context to use for the database.
 */
fun getDatabaseBuilder(
    context: Context,
    passphrase: ByteArray? = null,
    databaseFile: File = context.applicationContext.getDatabasePath(DATABASE_NAME),
): RoomDatabase.Builder<LogDateDatabase> {
    val appContext = context.applicationContext
    val dbFile = databaseFile
    val builder =
        Room.databaseBuilder<LogDateDatabase>(
            context = appContext,
            name = dbFile.absolutePath,
        )
    val hasExistingDb = passphrase != null && dbFile.exists()
    var needsLegacyHook = false

    if (passphrase != null) {
        System.loadLibrary("sqlcipher")
        if (hasExistingDb) {
            val opensWithCurrent = canOpenEncryptedDatabaseWithCurrentSettings(dbFile, passphrase)
            needsLegacyHook = !opensWithCurrent && canOpenEncryptedDatabaseWithLegacySettings(dbFile, passphrase)
            if (opensWithCurrent || needsLegacyHook) {
                requireSupportedDatabaseVersion(
                    openAndPingEncryptedDatabase(dbFile, passphrase, if (needsLegacyHook) createLegacySqlCipherHook() else null),
                )
            }
            Napier.i(
                "Existing encrypted DB probe: opensWithCurrent=$opensWithCurrent needsLegacyHook=$needsLegacyHook",
                tag = DB_MIGRATION_TAG,
            )
        }

        Napier.i("Configuring SQLCipher open helper (existingDb=$hasExistingDb)", tag = DB_MIGRATION_TAG)
        val migrationHook =
            if (needsLegacyHook) {
                Napier.i("Applying legacy SQLCipher compatibility hook before keying", tag = DB_MIGRATION_TAG)
                createLegacySqlCipherHook()
            } else {
                null
            }
        val sqlCipherFactory =
            if (migrationHook != null) {
                SupportOpenHelperFactory(passphrase, migrationHook, false)
            } else {
                SupportOpenHelperFactory(passphrase)
            }
        builder.openHelperFactory(nativeJournalOpenHelperFactory(dbFile, sqlCipherFactory))
    }

    if (needsLegacyHook) {
        builder.addCallback(
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    Napier.i("Running PRAGMA cipher_migrate on existing encrypted database", tag = DB_MIGRATION_TAG)
                    runCatching {
                        db.query("PRAGMA cipher_migrate").use { cursor ->
                            if (cursor.moveToFirst()) {
                                Napier.i("cipher_migrate result=${cursor.getInt(0)}", tag = DB_MIGRATION_TAG)
                            }
                        }
                    }.onFailure { error ->
                        Napier.e("PRAGMA cipher_migrate failed", error, tag = DB_MIGRATION_TAG)
                        throw error
                    }
                }
            },
        )
    }

    return builder
}

/**
 * One-time migration from plaintext SQLite to SQLCipher for installs that predate
 * on-device encryption.
 *
 * This is intentionally non-destructive:
 * - original files are moved to timestamped backups before replacement
 * - the encrypted output is validated before replacement
 */
fun migratePlaintextDatabaseIfNeeded(
    context: Context,
    passphrase: ByteArray,
) {
    val dbFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
    if (!dbFile.exists()) {
        Napier.i("No existing database file found; skipping plaintext migration", tag = DB_MIGRATION_TAG)
        return
    }
    System.loadLibrary("sqlcipher")

    if (!isPlaintextSqliteDatabase(dbFile)) {
        if (canOpenEncryptedDatabaseWithCurrentSettings(dbFile, passphrase)) {
            Napier.i("Existing database opens with current SQLCipher settings; skipping plaintext migration", tag = DB_MIGRATION_TAG)
            return
        }
        if (canOpenEncryptedDatabaseWithLegacySettings(dbFile, passphrase)) {
            Napier.i("Existing database opens with legacy SQLCipher settings; skipping plaintext migration", tag = DB_MIGRATION_TAG)
            return
        }

        // An unreadable encrypted file may have a valid later key candidate. Replacing it with
        // an older plaintext migration backup here would destroy the journal before the caller
        // has tried every retained key. Preserve both files and let the ordered opener decide.
        Napier.w(
            "Existing encrypted database did not open with this key candidate; preserving it for remaining candidates",
            tag = DB_MIGRATION_TAG,
        )
        return
    }
    android.database.sqlite.SQLiteDatabase
        .openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { requireSupportedDatabaseVersion(it.version) }
    Napier.w("Detected plaintext SQLite DB. Starting one-time SQLCipher migration", tag = DB_MIGRATION_TAG)
    migratePlaintextDatabaseFile(dbFile, passphrase)
}

private fun migratePlaintextDatabaseFile(
    dbFile: File,
    passphrase: ByteArray,
) {
    val tempEncryptedFile = File(dbFile.parentFile, "$DATABASE_NAME.sqlcipher-migrating")
    val backupBase = File(dbFile.parentFile, "$DATABASE_NAME.plaintext-backup-${System.currentTimeMillis()}")
    var movedOriginal = false

    runCatching { tempEncryptedFile.delete() }.onFailure { error ->
        Napier.w("Failed to clear stale temp migration file: ${tempEncryptedFile.absolutePath}", error, tag = DB_MIGRATION_TAG)
    }

    try {
        Napier.i("Exporting plaintext DB to encrypted temp file", tag = DB_MIGRATION_TAG)
        exportPlaintextDatabaseToEncrypted(
            plaintextFile = dbFile,
            encryptedFile = tempEncryptedFile,
            passphrase = passphrase,
        )

        Napier.i("Validating encrypted temp database", tag = DB_MIGRATION_TAG)
        verifyEncryptedDatabase(encryptedFile = tempEncryptedFile, passphrase = passphrase)

        Napier.i("Backing up original plaintext DB to ${backupBase.absolutePath}", tag = DB_MIGRATION_TAG)
        moveIfExists(dbFile, backupBase)
        movedOriginal = true
        moveIfExists(File("${dbFile.absolutePath}-wal"), File("${backupBase.absolutePath}-wal"))
        moveIfExists(File("${dbFile.absolutePath}-shm"), File("${backupBase.absolutePath}-shm"))
        moveIfExists(File("${dbFile.absolutePath}-journal"), File("${backupBase.absolutePath}-journal"))

        Napier.i("Replacing live DB with encrypted migration output", tag = DB_MIGRATION_TAG)
        moveReplacing(tempEncryptedFile, dbFile)
        Napier.i("Migrated plaintext DB to SQLCipher. Backup: ${backupBase.absolutePath}", tag = DB_MIGRATION_TAG)
    } catch (error: Throwable) {
        Napier.e("Plaintext to SQLCipher migration failed", error, tag = DB_MIGRATION_TAG)
        runCatching { tempEncryptedFile.delete() }
        if (movedOriginal && !dbFile.exists() && backupBase.exists()) {
            runCatching {
                moveReplacing(backupBase, dbFile)
                Napier.w("Restored original plaintext DB after migration failure", tag = DB_MIGRATION_TAG)
            }.onFailure { rollbackError ->
                Napier.e("Failed to restore original DB after migration failure", rollbackError, tag = DB_MIGRATION_TAG)
            }
        }
        throw error
    }
}

private fun exportPlaintextDatabaseToEncrypted(
    plaintextFile: File,
    encryptedFile: File,
    passphrase: ByteArray,
) {
    val plaintextDb =
        SQLCipherDatabase.openDatabase(
            plaintextFile.absolutePath,
            ByteArray(0),
            null,
            SQLCipherDatabase.OPEN_READWRITE or SQLCipherDatabase.CREATE_IF_NECESSARY,
            null,
            null,
        )

    try {
        plaintextDb.execSQL(
            "ATTACH DATABASE ? AS encrypted KEY ?",
            arrayOf<Any>(encryptedFile.absolutePath, passphrase),
        )
        try {
            plaintextDb.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { cursor ->
                cursor.moveToFirst()
            }
            val existingVersion = plaintextDb.version
            plaintextDb.execSQL("PRAGMA encrypted.user_version = $existingVersion")
        } finally {
            plaintextDb.execSQL("DETACH DATABASE encrypted")
        }
    } finally {
        plaintextDb.close()
    }
}

private fun verifyEncryptedDatabase(
    encryptedFile: File,
    passphrase: ByteArray,
) {
    openAndPingEncryptedDatabase(encryptedFile, passphrase, hook = null)
}

internal fun canOpenEncryptedDatabaseWithCurrentSettings(
    encryptedFile: File,
    passphrase: ByteArray,
): Boolean {
    if (!encryptedFile.exists()) {
        return false
    }

    return runCatching { openAndPingEncryptedDatabase(encryptedFile, passphrase, hook = null) }
        .onFailure { error ->
            Napier.w(
                "Database did not open with current SQLCipher settings: ${error::class.java.simpleName}: ${error.message}",
                tag = DB_MIGRATION_TAG,
            )
        }.isSuccess
}

internal fun canOpenEncryptedDatabaseWithLegacySettings(
    encryptedFile: File,
    passphrase: ByteArray,
): Boolean {
    if (!encryptedFile.exists()) {
        return false
    }

    return runCatching {
        openAndPingEncryptedDatabase(encryptedFile, passphrase, hook = createLegacySqlCipherHook())
    }.onFailure { error ->
        Napier.w(
            "Database did not open with legacy SQLCipher settings: ${error::class.java.simpleName}: ${error.message}",
            tag = DB_MIGRATION_TAG,
        )
    }.isSuccess
}

private fun openAndPingEncryptedDatabase(
    encryptedFile: File,
    passphrase: ByteArray,
    hook: SQLiteDatabaseHook?,
): Int {
    val encryptedDb =
        SQLCipherDatabase.openDatabase(
            encryptedFile.absolutePath,
            passphrase,
            null,
            SQLCipherDatabase.OPEN_READONLY,
            null,
            hook,
        )
    try {
        encryptedDb.rawQuery("SELECT COUNT(*) FROM sqlite_schema", emptyArray<String>()).use { cursor ->
            cursor.moveToFirst()
        }
        return encryptedDb.version
    } finally {
        encryptedDb.close()
    }
}

private fun requireSupportedDatabaseVersion(version: Int) {
    if (version > DATABASE_VERSION) throw NewerDatabaseVersionException(version, DATABASE_VERSION)
}

internal fun createLegacySqlCipherHook(): SQLiteDatabaseHook =
    object : SQLiteDatabaseHook {
        override fun preKey(database: SQLiteConnection) {
            val cancellationSignal = CancellationSignal()
            database.execute("PRAGMA cipher_compatibility = 3", emptyArray(), cancellationSignal)
        }

        override fun postKey(database: SQLiteConnection) {
        }
    }

internal fun isPlaintextSqliteDatabase(dbFile: File): Boolean {
    if (!dbFile.isFile || dbFile.length() < SQLITE_FILE_HEADER.size) {
        return false
    }

    val header = ByteArray(SQLITE_FILE_HEADER.size)
    return runCatching {
        FileInputStream(dbFile).use { stream ->
            val bytesRead = stream.read(header)
            bytesRead == SQLITE_FILE_HEADER.size && header.contentEquals(SQLITE_FILE_HEADER)
        }
    }.onFailure { error ->
        Napier.e("Failed reading DB header for plaintext detection", error, tag = DB_MIGRATION_TAG)
    }.getOrDefault(false)
}

private fun moveIfExists(
    source: File,
    target: File,
) {
    if (!source.exists()) {
        return
    }
    moveReplacing(source, target)
}

private fun moveReplacing(
    source: File,
    target: File,
) {
    target.parentFile?.mkdirs()
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

fun protectDatabaseFile(context: Context) {
    val dbFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
    if (!dbFile.exists()) {
        return
    }
    dbFile.setReadable(false, false)
    dbFile.setWritable(false, false)
    dbFile.setReadable(true, true)
    dbFile.setWritable(true, true)
}

private const val DB_MIGRATION_TAG = "LogDateDatabase"
private val SQLITE_FILE_HEADER =
    byteArrayOf(
        'S'.code.toByte(),
        'Q'.code.toByte(),
        'L'.code.toByte(),
        'i'.code.toByte(),
        't'.code.toByte(),
        'e'.code.toByte(),
        ' '.code.toByte(),
        'f'.code.toByte(),
        'o'.code.toByte(),
        'r'.code.toByte(),
        'm'.code.toByte(),
        'a'.code.toByte(),
        't'.code.toByte(),
        ' '.code.toByte(),
        '3'.code.toByte(),
        0.toByte(),
    )

/** Owns only an isolated recovery copy; its close never changes retained or working files. */
class RetainedDatabaseCopy internal constructor(
    val database: LogDateDatabase,
    val directory: File,
) : AutoCloseable {
    override fun close() {
        try {
            database.close()
        } finally {
            directory.deleteRecursively()
        }
    }
}

/** A late key is accepted only when it opens and validates the actual preserved SQLCipher database. */
fun openRetainedDatabaseCopy(
    context: Context,
    sourceFiles: Map<String, File>,
    passphrases: List<ByteArray>,
    scratchRoot: File,
): RetainedDatabaseCopy? {
    val allowed = setOf(DATABASE_NAME, "$DATABASE_NAME-wal", "$DATABASE_NAME-shm", "$DATABASE_NAME-journal")
    require(sourceFiles.keys.all { it in allowed } && DATABASE_NAME in sourceFiles)
    require(passphrases.size <= 128)
    sourceFiles.values.forEach { require(Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) }
    check(scratchRoot.mkdirs() || scratchRoot.isDirectory)
    System.loadLibrary("sqlcipher")
    for (candidate in passphrases) {
        if (candidate.size != 32) continue
        val directory = Files.createTempDirectory(scratchRoot.toPath(), "retained-db-").toFile()
        var opened: LogDateDatabase? = null
        var accepted = false
        try {
            sourceFiles.forEach { (name, source) ->
                val target = File(directory, name)
                source.inputStream().use { input ->
                    java.io.FileOutputStream(target).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            val copy = File(directory, DATABASE_NAME)
            val hook =
                when {
                    canOpenEncryptedDatabaseWithCurrentSettings(copy, candidate) -> null
                    canOpenEncryptedDatabaseWithLegacySettings(copy, candidate) -> createLegacySqlCipherHook()
                    else -> continue
                }
            requireSupportedDatabaseVersion(openAndPingEncryptedDatabase(copy, candidate, hook))
            val validated =
                SQLCipherDatabase.openDatabase(
                    copy.absolutePath,
                    candidate,
                    null,
                    SQLCipherDatabase.OPEN_READONLY,
                    null,
                    hook,
                )
            validated.use(::requireRetainedDatabaseIntegrity)
            opened = getRoomDatabase(getDatabaseBuilder(context, candidate.copyOf(), copy), driver = null)
            opened.openHelper.writableDatabase
            accepted = true
            return RetainedDatabaseCopy(opened, directory)
        } catch (error: NewerDatabaseVersionException) {
            throw error
        } catch (_: Exception) {
            Napier.w("Retained database candidate did not validate; original and working journals remain preserved")
        } finally {
            if (!accepted) {
                opened?.close()
                directory.deleteRecursively()
            }
        }
    }
    return null
}

internal fun requireRetainedDatabaseIntegrity(database: SQLCipherDatabase) {
    database.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext())
    }
}

/** Source-only portable key reads never select or persist the active database passphrase. */
fun readRetainedDatabasePassphrases(files: List<File>): List<ByteArray> =
    files
        .mapNotNull { file ->
            runCatching {
                require(Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && file.length() in 1..128)
                kotlin.io.encoding.Base64
                    .decode(file.readText().trim())
                    .takeIf { it.size == 32 }
            }.getOrNull()
        }.distinctBy {
            kotlin.io.encoding.Base64
                .encode(it)
        }
