package app.logdate.client.capture

import app.logdate.feature.editor.navigation.EditorLaunchMode
import app.logdate.feature.editor.navigation.EntryEditorRoute
import kotlin.uuid.Uuid

/**
 * The public way to start a new LogDate entry from outside the editor.
 *
 * Launcher shortcuts, Quick Settings tiles, notifications, assistants, and other apps send
 * [ACTION_CAPTURE] (or open `logdate://capture`) with an optional [EXTRA_CAPTURE_MODE] and
 * [EXTRA_CAPTURE_JOURNAL_ID]. The intent resolver turns that into an [EntryEditorRoute] through
 * [captureRoute], so every surface opens the editor the same way. A new entry opened this way always
 * starts empty: no other app can fill it in.
 */
const val ACTION_CAPTURE = "app.logdate.action.CAPTURE"

/** `text`, `voice`, `camera`, or `video`. Anything else uses the saved starting mode. */
const val EXTRA_CAPTURE_MODE = "mode"

/** A journal UUID to pre-select for the new entry. */
const val EXTRA_CAPTURE_JOURNAL_ID = "journal_id"

/**
 * Text the person already wrote in LogDate to start the new entry with. MainActivity keeps it only on
 * LogDate's own internal capture route, never from another app.
 */
const val EXTRA_CAPTURE_TEXT = "text"

/** The `logdate://capture` deep-link host. */
const val CAPTURE_HOST = "capture"

/** The `logdate://capture?journal=` query parameter. */
const val CAPTURE_JOURNAL_PARAM = "journal"

/**
 * The editor route for a capture request.
 *
 * A missing or unknown [mode] uses the saved starting mode, so a generic capture respects the
 * person's preference. An invalid [journalId] is ignored for the same reason.
 */
fun captureRoute(
    mode: String?,
    journalId: String?,
    text: String? = null,
): EntryEditorRoute =
    EntryEditorRoute(
        launchMode = captureLaunchMode(mode),
        journalIds = journalId?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }?.let(::listOf).orEmpty(),
        initialText = text?.takeIf(String::isNotBlank),
    )

/** The editor route for the New entry widget's legacy extras, which placed widgets still send. */
fun widgetCaptureRoute(
    photoUri: String?,
    record: Boolean,
    camera: Boolean,
): EntryEditorRoute =
    EntryEditorRoute(
        launchMode =
            when {
                camera -> EditorLaunchMode.Camera
                record -> EditorLaunchMode.Voice
                photoUri != null -> EditorLaunchMode.Text
                else -> EditorLaunchMode.Default
            },
        promptPhotoUri = photoUri,
    )

private fun captureLaunchMode(mode: String?): EditorLaunchMode =
    when (mode?.trim()?.lowercase()) {
        "voice" -> EditorLaunchMode.Voice
        "camera" -> EditorLaunchMode.Camera
        "video" -> EditorLaunchMode.Video
        "text" -> EditorLaunchMode.Text
        else -> EditorLaunchMode.Default
    }

/**
 * The id of the static launcher shortcut that starts [mode], or null when no shortcut does. These
 * ids match `res/xml/shortcuts.xml` in the Android app.
 */
fun captureShortcutId(mode: EditorLaunchMode): String? =
    when (mode) {
        EditorLaunchMode.Default -> null
        EditorLaunchMode.Text -> "new_note"
        EditorLaunchMode.Voice -> "record_voice"
        EditorLaunchMode.Camera -> "take_photo"
        EditorLaunchMode.Video -> null
    }
