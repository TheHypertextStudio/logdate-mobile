package app.logdate.feature.editor.ui

import java.util.Locale

internal actual fun formatMediaDuration(
    durationMs: Long,
    padMinutes: Boolean,
): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val pattern = if (padMinutes) "%02d:%02d" else "%d:%02d"
    return String.format(Locale.getDefault(), pattern, minutes, seconds)
}
