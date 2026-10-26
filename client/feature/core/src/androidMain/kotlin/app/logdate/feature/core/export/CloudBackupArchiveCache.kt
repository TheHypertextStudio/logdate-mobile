package app.logdate.feature.core.export

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** One privately retained, unfinished archive, shared by immediate and periodic requests. */
internal class CloudBackupArchiveCache(
    directory: File,
) {
    val archive = File(directory, "cloud-backup-pending.zip")
    val plaintext = File(directory, "cloud-backup-pending.plain.part")
    val encryptedPart = File(directory, "cloud-backup-pending.encrypted.part")
    private val marker = File(directory, "cloud-backup-pending.scope")

    fun hasCandidate(): Boolean = archive.isFile && marker.isFile

    fun reusable(identity: CloudArchiveCacheIdentity): Boolean {
        if (!hasCandidate()) return false
        if (marker.length() !in 1L..4096L) return false
        val lines = marker.readLines(Charsets.UTF_8)
        if (lines.size != 7 || lines[0] != VERSION) return false
        val retained = CloudArchiveCacheIdentity(lines[1], lines[2], lines[3], lines[4], lines[5])
        return reusableCloudArchive(retained, identity, lines[6], archive.sha256(), archive.length() > 33L)
    }

    fun finalize(identity: CloudArchiveCacheIdentity) {
        require(encryptedPart.length() > 33L) { "Encrypted archive is incomplete" }
        RandomAccessFile(encryptedPart, "rw").use { it.fd.sync() }
        check(encryptedPart.renameTo(archive)) { "Unable to finalize encrypted archive" }
        val values =
            listOf(
                VERSION,
                identity.origin,
                identity.accountId,
                identity.identityFingerprint,
                identity.options,
                identity.sourceFingerprint,
                archive.sha256(),
            )
        require(values.none { '\n' in it || '\r' in it })
        val temporary = File(marker.parentFile, "${marker.name}.part")
        FileOutputStream(temporary).use { output ->
            output.write(values.joinToString("\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(marker)) { "Unable to finalize backup retry marker" }
    }

    fun discard() {
        // Removing the authorization marker first prevents reuse even if removing the bytes fails.
        if (marker.exists()) check(marker.delete()) { "Unable to remove backup retry marker" }
        if (archive.exists()) check(archive.delete()) { "Unable to remove retained archive" }
        cleanupParts()
    }

    fun cleanupParts() {
        plaintext.delete()
        encryptedPart.delete()
        File(marker.parentFile, "${marker.name}.part").delete()
    }

    companion object {
        private const val VERSION = "cloud-backup-unfinished-v2"
    }
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().hexDigest()
}

internal fun ByteArray.hexDigest(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
