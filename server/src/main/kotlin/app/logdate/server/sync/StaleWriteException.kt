package app.logdate.server.sync

/** Ordinary sync cannot overwrite a deletion; preserve the competing edit for review. */
class StaleWriteException(
    val recordId: String,
    val tombstonedAt: Long,
) : RuntimeException("Record was deleted; save the retained edit with a new identity")
