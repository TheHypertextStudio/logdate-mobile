package app.logdate.client.domain.export

/**
 * Typed export progress stages. The presentation layer resolves
 * these to localized strings.
 */
enum class ExportStage {
    COLLECTING_JOURNALS,
    COLLECTING_NOTES,
    COLLECTING_DRAFTS,
    PREPARING_DATA,
    WRITING_ARCHIVE,
}

/**
 * Typed export errors. The presentation layer resolves these
 * to localized user-facing messages.
 */
enum class ExportError {
    UNKNOWN,
}
