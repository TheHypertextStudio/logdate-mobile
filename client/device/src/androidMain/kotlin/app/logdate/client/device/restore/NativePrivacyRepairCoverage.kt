package app.logdate.client.device.restore

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Coverage only: the caller has verified the sealed cohort, authorized its policy and validated an
 * isolated database copy. The caller removes only SHM after that proof; this helper never admits it.
 * Manifest integrity, native delivery identity and durable acknowledgement remain caller duties.
 */
internal fun nativePrivacyRepairCohortCovered(
    payloads: List<NativeIncomingJournal.Payload>,
    protectedHashes: Map<String, String>,
    preferencesSha256: String,
): Boolean =
    try {
        nativePrivacyRepairCoverage(payloads, protectedHashes, preferencesSha256)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun nativePrivacyRepairCoverage(
    payloads: List<NativeIncomingJournal.Payload>,
    protectedHashes: Map<String, String>,
    preferencesSha256: String,
): Boolean {
    if (payloads.isEmpty() || !COVERAGE_SHA.matches(preferencesSha256) || protectedHashes.values.any { !COVERAGE_SHA.matches(it) }) {
        return false
    }
    if (payloads.any { !it.complete || !coverageRegularFile(it.file, it.expectedSize) }) return false
    val hashes = payloads.associateWith { coverageChecksum(it.file) }
    val byPath = payloads.groupBy { it.path }
    val required = protectedHashes.keys.filter(::coverageProtectedPath)
    if (!byPath.keys.containsAll(required)) return false
    val preferences = byPath[COVERAGE_PREFERENCES] ?: return false
    if (preferences.map { hashes.getValue(it) }.distinct() != listOf(preferencesSha256)) return false
    val acceptedWal =
        protectedHashes[COVERAGE_WAL]?.let { expected ->
            byPath[COVERAGE_WAL]?.filter { hashes.getValue(it) == expected }?.distinctBy { hashes.getValue(it) }?.singleOrNull()
                ?: return false
        }
    for (payload in payloads) {
        if (payload.path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        when {
            payload.path == COVERAGE_PREFERENCES -> {
                Unit
            }

            payload.path == COVERAGE_WAL -> {
                if (acceptedWal == null || !coveragePrefix(payload.file, acceptedWal.file)) return false
            }

            coverageProtectedPath(payload.path) -> {
                if (hashes.getValue(payload) != protectedHashes[payload.path]) return false
            }

            coverageSdkPath(payload.path) -> {
                Unit
            }

            else -> {
                return false
            }
        }
    }
    return payloads.all { coverageRegularFile(it.file, it.expectedSize) && coverageChecksum(it.file) == hashes.getValue(it) }
}

private fun coverageProtectedPath(path: String): Boolean =
    path in setOf("database/logdate", COVERAGE_WAL, "database/logdate-journal") ||
        listOf("files/db_recovery/", "files/identity_recovery/", "files/archive_recovery/records/").any(path::startsWith) ||
        path == "files/datastore/entry_drafts.preferences_pb"

private fun coverageSdkPath(path: String): Boolean = COVERAGE_SESSION.matches(path) || COVERAGE_HEARTBEAT.matches(path)

private fun coverageRegularFile(
    file: File,
    expectedSize: Long,
): Boolean {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    return attributes.isRegularFile && attributes.size() == expectedSize
}

private fun coverageChecksum(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Protected input could not be read")
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun coveragePrefix(
    prefix: File,
    accepted: File,
): Boolean {
    if (prefix.length() > accepted.length()) return false
    Files.newInputStream(prefix.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { left ->
        Files.newInputStream(accepted.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { right ->
            val leftBytes = ByteArray(8192)
            val rightBytes = ByteArray(leftBytes.size)
            while (true) {
                val count = left.read(leftBytes)
                if (count < 0) return true
                if (count == 0) return false
                var offset = 0
                while (offset < count) {
                    val read = right.read(rightBytes, offset, count - offset)
                    if (read <= 0) return false
                    offset += read
                }
                if (!(0 until count).all { leftBytes[it] == rightBytes[it] }) return false
            }
        }
    }
}

private const val COVERAGE_WAL = "database/logdate-wal"
private const val COVERAGE_PREFERENCES = "files/logdate.preferences_pb"
private val COVERAGE_SHA = Regex("[a-f0-9]{64}")
private val COVERAGE_SESSION =
    Regex("files/datastore/firebaseSessions/(sessionDataStore|sessionConfigsDataStore)\\.data(\\.lock|\\.version)?")

// FirebaseApp.getPersistenceKey uses URL-safe Base64 without padding for default-name + application ID.
private val COVERAGE_HEARTBEAT = Regex("files/datastore/FirebaseHeartBeatW0RFRkFVTFRd(\\+[A-Za-z0-9_-]+)?\\.preferences_pb")
