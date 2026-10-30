package app.logdate.ui.audio

import java.security.MessageDigest

internal actual fun waveformCacheKey(audioUri: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(audioUri.encodeToByteArray())
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
