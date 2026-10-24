package app.logdate.client.domain.export.archive

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A readable copy of the journal that the archive includes, and the page to start from. */
data class ReadableCopy(
    val format: ReadableFormat,
    val indexPath: ArchivePath,
)

enum class ReadableFormat {
    HTML,
    MARKDOWN,
}

/**
 * Writes `README.txt`, the first file a person meets in the archive.
 *
 * It is plain ASCII text with short lines so it opens the same in any editor on any system, and it
 * is written for someone who has never heard of JSON: what the folders are, where to start, what is
 * missing and why, and how to check that no file was damaged.
 */
object ReadmeTemplate {
    private const val WIDTH = 78
    private const val LABEL_WIDTH = 30

    fun render(
        manifest: ArchiveManifest,
        readableCopies: List<ReadableCopy>,
    ): String {
        val zone = runCatching { TimeZone.of(manifest.exportTimeZone) }.getOrDefault(TimeZone.UTC)
        val written = WrittenFiles(manifest)
        val text =
            buildString {
                introduction(manifest, zone)
                contents(manifest.counts)
                whereToLook(readableCopies, written)
                whatIsMissing(manifest.scope, zone)
                times(manifest.exportTimeZone, readableCopies.isNotEmpty(), written)
                checkingFiles()
                if (written.data) movingToAnotherApp(readableCopies.any { it.format == ReadableFormat.MARKDOWN })
                paragraph("This is LogDate export format ${manifest.schemaVersion}.")
            }
        return text.trimEnd() + "\n"
    }

    /** What the archive holds, read from the manifest so the README never describes a file that was not written. */
    private class WrittenFiles(
        manifest: ArchiveManifest,
    ) {
        private val roles = manifest.contents.mapTo(mutableSetOf()) { it.role }

        val notes = ArchiveRole.NOTES in roles
        val data = roles.any { it in DATA_ROLES }
        val media = manifest.counts.media > 0

        private companion object {
            val DATA_ROLES =
                setOf(
                    ArchiveRole.JOURNALS,
                    ArchiveRole.NOTES,
                    ArchiveRole.DRAFTS,
                    ArchiveRole.PLACES,
                    ArchiveRole.PROFILE,
                    ArchiveRole.LOCATION_HISTORY,
                    ArchiveRole.MEDIA_INVENTORY,
                )
        }
    }

    private fun StringBuilder.introduction(
        manifest: ArchiveManifest,
        zone: TimeZone,
    ) {
        title("YOUR LOGDATE EXPORT")
        paragraph("Made on ${manifest.exportedAt.dayAndTime(zone)} (${manifest.exportTimeZone}).")
        val scope =
            if (manifest.scope.complete) {
                "This folder is a complete, self-contained copy of what LogDate stored for you."
            } else {
                "This folder is a self-contained copy of your LogDate journal. " +
                    "It does not include everything LogDate stores for you; see WHAT IS MISSING below."
            }
        paragraph(
            "$scope You do not need the LogDate app, an account or an internet connection to open anything in it. " +
                "Nothing in it is encrypted, so store it somewhere private.",
        )
    }

    private fun StringBuilder.contents(counts: ArchiveCounts) {
        heading("WHAT IS IN THIS COPY")
        val lines =
            listOfNotNull(
                counts.journals.takeIf { it > 0 }?.let { count(it, "journal", "journals") },
                counts.notes.takeIf { it > 0 }?.let { count(it, "entry", "entries") },
                counts.media.takeIf { it > 0 }?.let { count(it, "photo, video or voice recording", "photos, videos and voice recordings") },
                counts.drafts.takeIf { it > 0 }?.let { count(it, "unfinished draft", "unfinished drafts") },
                counts.places.takeIf { it > 0 }?.let { count(it, "saved place", "saved places") },
                counts.locationSamples.takeIf { it > 0 }?.let { count(it, "location point", "location points") },
                "your profile".takeIf { counts.hasProfile },
            ).ifEmpty { listOf("Nothing yet: there were no entries to export.") }
        lines.forEach { line("  $it") }
        blank()
    }

    private fun count(
        n: Int,
        singular: String,
        plural: String,
    ) = "$n ${if (n == 1) singular else plural}"

    private fun StringBuilder.whereToLook(
        readableCopies: List<ReadableCopy>,
        written: WrittenFiles,
    ) {
        val html = readableCopies.firstOrNull { it.format == ReadableFormat.HTML }
        val markdown = readableCopies.firstOrNull { it.format == ReadableFormat.MARKDOWN }
        heading("WHERE TO LOOK")
        if (html != null) {
            entry(html.indexPath.value, "START HERE. Open this page in a web browser to read your entries day by day. It also prints well.")
        }
        if (markdown != null) {
            val lead = if (html == null) "START HERE. " else ""
            entry(
                markdown.indexPath.value,
                "${lead}Your entries as plain text files. They open in any text editor and in apps such as Obsidian.",
            )
        }
        if (readableCopies.isEmpty() && written.notes) {
            entry(
                ArchiveLayout.NOTES.value,
                "START HERE. Your entries, as text you can open in any text editor. " +
                    "There is no page-by-page reading copy in this export.",
            )
        }
        if (written.media) {
            entry("media/", "Your photos, videos and voice recordings. Each is named for when it was captured, so they sort in order.")
        }
        if (written.data) {
            entry(
                "data/",
                "The same information in a form other programs can read (JSON). " +
                    "You can ignore this unless you are moving to another app or writing a script.",
            )
        }
        entry("schema/", "Descriptions of the files in data/, for programmers.")
        entry("manifest.json", "A summary of this export: what it contains, what it leaves out and why.")
        entry("SHA256SUMS", "A checklist for confirming that no file was damaged.")
        blank()
    }

    private fun StringBuilder.whatIsMissing(
        scope: ArchiveScope,
        zone: TimeZone,
    ) {
        heading("WHAT IS MISSING")
        dateLimit(scope.dateRange, zone)?.let { paragraph("Entries, drafts and location history are limited to those $it.") }
        if (scope.complete && scope.omitted.isEmpty()) {
            paragraph("Nothing. This export contains everything LogDate stores for you.")
            return
        }
        paragraph("This export does not include the following. manifest.json records the same list.")
        scope.omitted.forEach { line("  - ${label(it.category)}: ${reason(it.reason)}") }
        blank()
    }

    private fun dateLimit(
        range: ArchiveDateRange?,
        zone: TimeZone,
    ): String? {
        val from = range?.from
        val to = range?.to
        return when {
            from != null && to != null -> "from ${from.day(zone)} to ${to.day(zone)}"
            from != null -> "on or after ${from.day(zone)}"
            to != null -> "on or before ${to.day(zone)}"
            else -> null
        }
    }

    private fun StringBuilder.times(
        exportTimeZone: String,
        hasReadableCopy: Boolean,
        written: WrittenFiles,
    ) {
        val sentences =
            listOfNotNull(
                (
                    "Times in the readable copy are shown in the time zone you were in when you wrote each entry, " +
                        "where LogDate recorded it, and otherwise in $exportTimeZone."
                ).takeIf { hasReadableCopy },
                "The data files store times in UTC, marked by the letter Z at the end.".takeIf { written.data },
                (
                    "Entries in notes.json also give their local time, with its offset from UTC, " +
                        "where LogDate recorded the time zone."
                ).takeIf { written.notes },
            )
        if (sentences.isEmpty()) return
        heading("TIMES")
        paragraph(sentences.joinToString(" "))
    }

    private fun StringBuilder.checkingFiles() {
        heading("CHECKING YOUR FILES (OPTIONAL)")
        paragraph("SHA256SUMS lets you confirm that every file is exactly as it was exported. Open a terminal in this folder and run:")
        line("  macOS or Linux:  shasum -a 256 -c SHA256SUMS")
        line("  Windows (PowerShell):")
        line("    Get-Content SHA256SUMS | ForEach-Object { \$h, \$f = \$_ -split '  ', 2;")
        line("      if ((Get-FileHash \$f -Algorithm SHA256).Hash.ToLower() -ne \$h) { \"CHANGED: \$f\" } }")
        blank()
        paragraph("Every file reporting OK, or no CHANGED lines, means nothing was damaged. It does not show who made the files.")
    }

    private fun StringBuilder.movingToAnotherApp(hasMarkdown: Boolean) {
        heading("MOVING TO ANOTHER APP")
        val markdown = if (hasMarkdown) " The Markdown files can be copied straight into a note-taking app." else ""
        paragraph(
            "The files in data/ are JSON, which most apps and programming languages can read, " +
                "and manifest.json lists each one with the schema that describes it.$markdown",
        )
    }

    private fun label(category: ArchiveCategory): String =
        when (category) {
            ArchiveCategory.JOURNALS -> "journals"
            ArchiveCategory.NOTES -> "entries"
            ArchiveCategory.DRAFTS -> "unfinished drafts"
            ArchiveCategory.EDITOR_DRAFTS -> "drafts you had open in the entry editor"
            ArchiveCategory.MEDIA -> "photos, videos and voice recordings"
            ArchiveCategory.PROFILE -> "your profile"
            ArchiveCategory.PLACES -> "saved places"
            ArchiveCategory.LOCATION_HISTORY -> "location history"
            ArchiveCategory.TRANSCRIPTS -> "transcripts of voice recordings"
            ArchiveCategory.AUDIO_TAGS -> "sound labels on voice recordings"
            ArchiveCategory.PEOPLE -> "people"
            ArchiveCategory.EVENTS -> "events"
            ArchiveCategory.REWINDS -> "rewinds"
            ArchiveCategory.POSTCARDS -> "postcards"
            ArchiveCategory.STICKERS -> "stickers"
            ArchiveCategory.HEALTH_SNAPSHOTS -> "health readings"
            ArchiveCategory.FAVORITES -> "favorites"
            ArchiveCategory.JOURNAL_COVERS -> "journal cover photos"
            ArchiveCategory.PROFILE_PHOTO -> "your profile photo"
            ArchiveCategory.SETTINGS -> "app settings"
        }

    private fun reason(reason: ArchiveOmissionReason): String =
        when (reason) {
            ArchiveOmissionReason.NOT_REQUESTED -> "you chose to leave it out"
            ArchiveOmissionReason.OUTSIDE_DATE_RANGE -> "it falls outside the dates you chose"
            ArchiveOmissionReason.UNREADABLE -> "it could not be read while exporting"
            ArchiveOmissionReason.NOT_YET_SUPPORTED -> "this version of LogDate does not export it yet"
        }

    private fun StringBuilder.title(text: String) {
        line(text)
        line("=".repeat(text.length))
        blank()
    }

    private fun StringBuilder.heading(text: String) {
        line(text)
        line("-".repeat(text.length))
    }

    private fun StringBuilder.paragraph(text: String) {
        wrap(text, WIDTH).forEach { line(it) }
        blank()
    }

    private fun StringBuilder.entry(
        label: String,
        description: String,
    ) {
        wrap(description, WIDTH - LABEL_WIDTH - 2).forEachIndexed { index, text ->
            val prefix = if (index == 0) "  ${label.padEnd(LABEL_WIDTH - 2)}" else " ".repeat(LABEL_WIDTH)
            line("$prefix$text")
        }
    }

    private fun StringBuilder.line(text: String) {
        append(text.trimEnd()).append('\n')
    }

    private fun StringBuilder.blank() = append('\n')

    private fun wrap(
        text: String,
        width: Int,
    ): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        text.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            when {
                current.isEmpty() -> {
                    current = word
                }

                current.length + 1 + word.length > width -> {
                    lines += current
                    current = word
                }

                else -> {
                    current = "$current $word"
                }
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun Instant.day(zone: TimeZone) = toLocalDateTime(zone).date.toString()

    private fun Instant.dayAndTime(zone: TimeZone): String {
        val local = toLocalDateTime(zone)
        return "${local.date} at ${local.hour.pad()}:${local.minute.pad()}"
    }

    private fun Int.pad() = toString().padStart(2, '0')
}
