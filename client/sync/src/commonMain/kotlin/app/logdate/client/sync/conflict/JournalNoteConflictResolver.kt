package app.logdate.client.sync.conflict

import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.withCapturedDuring
import app.logdate.client.repository.journals.withCapturedOn
import app.logdate.client.repository.journals.withTimeZoneId
import kotlin.time.Instant

/**
 * Conflict resolver for notes that attempts content-safe merges before deferring to manual review.
 *
 * A note's capture time zone, capture device and capture activity are set once, where the note was
 * captured, and never edited. Whichever copy wins, the resolved note records each of them from either copy: a copy from
 * an app version or server that predates a field must not erase it.
 */
class JournalNoteConflictResolver : ConflictResolver<JournalNote> {
    override fun resolve(
        local: JournalNote,
        remote: JournalNote,
        localTimestamp: Instant,
        remoteTimestamp: Instant,
    ): ConflictResolution<JournalNote> {
        val either =
            CaptureFacts(
                timeZoneId = local.timeZoneId ?: remote.timeZoneId,
                capturedOn = local.capturedOn ?: remote.capturedOn,
                capturedDuring = local.capturedDuring ?: remote.capturedDuring,
            )
        val resolution = resolveContent(local, remote)
        return resolution.withCaptureFactsFallback(either)
    }

    /** What a note records once, where it is captured. */
    private data class CaptureFacts(
        val timeZoneId: String?,
        val capturedOn: CaptureDevice?,
        val capturedDuring: CaptureActivity?,
    )

    private fun JournalNote.withCaptureFactsIfMissing(facts: CaptureFacts): JournalNote {
        val withZone = if (timeZoneId == null && facts.timeZoneId != null) withTimeZoneId(facts.timeZoneId) else this
        val withDevice =
            if (withZone.capturedOn == null &&
                facts.capturedOn != null
            ) {
                withZone.withCapturedOn(facts.capturedOn)
            } else {
                withZone
            }
        if (withDevice.capturedDuring != null || facts.capturedDuring == null) return withDevice
        return withDevice.withCapturedDuring(facts.capturedDuring)
    }

    private fun ConflictResolution<JournalNote>.withCaptureFactsFallback(facts: CaptureFacts): ConflictResolution<JournalNote> =
        when (this) {
            is ConflictResolution.KeepLocal -> ConflictResolution.KeepLocal(value.withCaptureFactsIfMissing(facts))
            is ConflictResolution.KeepRemote -> ConflictResolution.KeepRemote(value.withCaptureFactsIfMissing(facts))
            is ConflictResolution.Merge -> ConflictResolution.Merge(merged.withCaptureFactsIfMissing(facts))
            is ConflictResolution.RequiresManualResolution -> this
        }

    private fun resolveContent(
        local: JournalNote,
        remote: JournalNote,
    ): ConflictResolution<JournalNote> {
        if (local == remote) {
            return ConflictResolution.KeepRemote(remote)
        }

        if (local::class != remote::class) {
            return ConflictResolution.RequiresManualResolution(
                local,
                remote,
                "Note type changed between local and remote versions.",
            )
        }

        return when (local) {
            is JournalNote.Handwriting -> {
                remote as JournalNote.Handwriting
                if (local.document == remote.document &&
                    local.unreadableContent == remote.unreadableContent &&
                    local.location == remote.location
                ) {
                    ConflictResolution.KeepRemote(remote)
                } else {
                    ConflictResolution.RequiresManualResolution(local, remote, "Handwriting differs between local and remote versions.")
                }
            }
            is JournalNote.Text -> resolveText(local, remote as JournalNote.Text)
            is JournalNote.Image -> resolveMedia(local, remote as JournalNote.Image, "Image")
            is JournalNote.Video -> resolveMedia(local, remote as JournalNote.Video, "Video")
            is JournalNote.Audio -> resolveMedia(local, remote as JournalNote.Audio, "Audio")
        }
    }

    private fun resolveText(
        local: JournalNote.Text,
        remote: JournalNote.Text,
    ): ConflictResolution<JournalNote> {
        if (local.content == remote.content && local.location == remote.location) {
            return ConflictResolution.KeepRemote(remote)
        }

        return ConflictResolution.RequiresManualResolution(
            local,
            remote,
            "Text differs between local and remote versions.",
        )
    }

    private fun resolveMedia(
        local: JournalNote.Image,
        remote: JournalNote.Image,
        label: String,
    ): ConflictResolution<JournalNote> {
        if (local.mediaRef == remote.mediaRef && local.location == remote.location) {
            return ConflictResolution.KeepRemote(remote)
        }

        return ConflictResolution.RequiresManualResolution(
            local,
            remote,
            "$label note media reference differs between local and remote versions.",
        )
    }

    private fun resolveMedia(
        local: JournalNote.Video,
        remote: JournalNote.Video,
        label: String,
    ): ConflictResolution<JournalNote> {
        if (local.mediaRef == remote.mediaRef && local.location == remote.location) {
            return ConflictResolution.KeepRemote(remote)
        }

        return ConflictResolution.RequiresManualResolution(
            local,
            remote,
            "$label note media reference differs between local and remote versions.",
        )
    }

    private fun resolveMedia(
        local: JournalNote.Audio,
        remote: JournalNote.Audio,
        label: String,
    ): ConflictResolution<JournalNote> {
        if (local.mediaRef == remote.mediaRef &&
            local.durationMs == remote.durationMs &&
            local.caption == remote.caption &&
            local.location == remote.location
        ) {
            return ConflictResolution.KeepRemote(remote)
        }

        return ConflictResolution.RequiresManualResolution(
            local,
            remote,
            "$label note media reference differs between local and remote versions.",
        )
    }
}
