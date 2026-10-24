package app.logdate.client.media.audio

import app.logdate.client.device.restore.admitNativeContentBlocking
import io.github.aakira.napier.Napier
import java.io.File

internal fun AudioRecordingService.resolveOutputFile(outputPath: String?): File =
    if (outputPath != null) {
        File(outputPath).also { file ->
            file.parentFile?.let(::admitNativeContentBlocking)
            if (file.exists()) file.delete()
        }
    } else {
        File.createTempFile("audio_recording_", ".m4a", applicationContext.cacheDir)
    }

/** The file the recorder writes: the m4a itself, or the raw AAC that is wrapped into it when it ends. */
internal fun AudioRecordingService.recordingFileFor(finalFile: File): File =
    when {
        options.crashSafe -> {
            CrashSafeRecording
                .inFlightFile(
                    finalFile,
                    isolateForBackup = options.excludeIncompleteFromBackup,
                ).also { it.delete() }
        }

        options.excludeIncompleteFromBackup -> {
            CrashSafeRecording.backupExcludedFile(finalFile).also { it.delete() }
        }

        else -> {
            finalFile
        }
    }

/**
 * Wraps a crash-safe recording into its m4a. The raw AAC file stays beside it: its owner deletes it
 * once the recording is saved, and until then it marks the recording as unsaved for startup recovery.
 * A recording that cannot be wrapped is reported as a failed stop and recovered from the raw file at
 * the next launch.
 */
internal fun AudioRecordingService.finalizeOutputFile(): String? {
    val finalFile = outputFile ?: return null
    if (options.crashSafe) {
        val inFlight = CrashSafeRecording.inFlightFile(finalFile, isolateForBackup = options.excludeIncompleteFromBackup)
        if (AdtsToM4aRemuxer.remuxToM4a(inFlight, finalFile)) {
            inFlight.delete()
            CrashSafeRecording.clearCompletedRetryMarker(inFlight)
            return finalFile.absolutePath
        }
        check(inFlight.isFile && inFlight.length() > 0L) {
            "Could not preserve the recording for recovery"
        }
        Napier.w("Completed recording ${finalFile.name} is queued for startup recovery")
        return finalFile.absolutePath
    }
    if (options.excludeIncompleteFromBackup) {
        val publication = CrashSafeRecording.publishCompletedRecording(CrashSafeRecording.backupExcludedFile(finalFile), finalFile)
        check(publication != CompletedRecordingPublication.FAILED) {
            "Could not publish or preserve the completed recording"
        }
    }
    return finalFile.absolutePath
}
