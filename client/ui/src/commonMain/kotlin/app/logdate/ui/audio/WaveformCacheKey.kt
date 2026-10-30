package app.logdate.ui.audio

/** The hex SHA-256 of [audioUri], so two recordings never share a cached waveform file. */
internal expect fun waveformCacheKey(audioUri: String): String
