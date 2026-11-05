package app.logdate.client.datastore

/** Surfaces DataStore failures to settings owners while preserving other storage consumers. */
fun KeyValueStorage.withFailureReporting(): KeyValueStorage = if (this is DataStoreKeyValueStorage) reportingFailures() else this
