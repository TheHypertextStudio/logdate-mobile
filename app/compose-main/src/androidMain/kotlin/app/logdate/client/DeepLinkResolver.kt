package app.logdate.client

import android.content.Intent
import android.net.Uri
import androidx.navigation3.runtime.NavKey
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_DRAFT
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_EVENT_DETAIL
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_MEMORY_RECALL
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_NEW_ENTRY
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_DRAFT_ID
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_EVENT_ID
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_RECALL_DATE
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_TARGET
import app.logdate.client.capture.ACTION_CAPTURE
import app.logdate.client.capture.CAPTURE_HOST
import app.logdate.client.capture.CAPTURE_JOURNAL_PARAM
import app.logdate.client.capture.EXTRA_CAPTURE_JOURNAL_ID
import app.logdate.client.capture.EXTRA_CAPTURE_MODE
import app.logdate.client.capture.EXTRA_CAPTURE_TEXT
import app.logdate.client.capture.captureRoute
import app.logdate.client.capture.widgetCaptureRoute
import app.logdate.client.feature.widgets.EXTRA_WIDGET_CAMERA
import app.logdate.client.feature.widgets.EXTRA_WIDGET_NOTE_ID
import app.logdate.client.feature.widgets.EXTRA_WIDGET_PHOTO_URI
import app.logdate.client.feature.widgets.EXTRA_WIDGET_RECORD
import app.logdate.client.feature.widgets.EXTRA_WIDGET_TARGET_DATE
import app.logdate.client.feature.widgets.NAV_SOURCE_FIXED_MEMORY_WIDGET
import app.logdate.client.feature.widgets.NAV_SOURCE_NEW_ENTRY_WIDGET
import app.logdate.client.feature.widgets.NAV_SOURCE_ON_THIS_DAY_WIDGET
import app.logdate.client.location.tracking.NAV_SOURCE_LOCATION_HISTORY
import app.logdate.client.media.audio.EXTRA_NAV_SOURCE
import app.logdate.client.media.audio.EXTRA_NOTE_ID
import app.logdate.client.media.audio.EXTRA_RECORDING_DRAFT_ID
import app.logdate.client.media.audio.NAV_SOURCE_AUDIO_PLAYBACK
import app.logdate.client.media.audio.NAV_SOURCE_AUDIO_RECORDING
import app.logdate.client.rewind.EXTRA_REWIND_NOTIFICATION_ID
import app.logdate.client.rewind.EXTRA_REWIND_NOTIFICATION_TARGET
import app.logdate.client.rewind.REWIND_NOTIFICATION_TARGET_DETAIL
import app.logdate.client.testing.navigation.readNavigationTestDestination
import app.logdate.client.ui.navigation.LocationTimelineRoute
import app.logdate.client.ui.navigation.LogdateHostClass
import app.logdate.client.ui.navigation.SearchRoute
import app.logdate.client.ui.navigation.classifyLogdateHost
import app.logdate.client.ui.navigation.searchRouteFromParams
import app.logdate.feature.core.account.navigation.blueskyReturnRoute
import app.logdate.feature.core.notifications.NAV_SOURCE_DATA_TRANSFER
import app.logdate.feature.core.settings.navigation.ExportSettingsRoute
import app.logdate.feature.core.watch.continuity.WatchHandoffRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.events.navigation.EventDetailRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.feature.postcards.navigation.PostcardViewerRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.navigation.TimelineDetailRoute
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid
import app.logdate.client.location.tracking.EXTRA_NAV_SOURCE as EXTRA_LOCATION_NAV_SOURCE
import app.logdate.feature.core.notifications.EXTRA_NAV_SOURCE as EXTRA_DATA_TRANSFER_NAV_SOURCE

private const val PATH_JOURNAL = "journal"
private const val PATH_JOURNAL_SHORT = "j"
private const val PATH_DAY = "day"
private const val PATH_NOTE = "note"
private const val PATH_POSTCARD = "postcard"
private const val PATH_REWIND = "rewind"
private const val PATH_LOCATION = "location"
private const val PATH_EVENT = "event"
private const val PATH_SEARCH = "search"
private const val SEARCH_QUERY_PARAM = "q"
private const val SEARCH_TYPE_PARAM = "type"
private const val SEARCH_DATE_PARAM = "date"

internal val LOGDATE_API_BASE_URL: Uri = Uri.parse(BuildConfig.LOGDATE_API_BASE_URL)

/**
 * Resolves a deep link URI to the corresponding navigation route.
 *
 * Supports:
 *   - app-scheme URIs (`logdate://journal/{id}`,
 *     `studio.hypertext.logdate://journal/{id}`),
 *   - apex web URIs (`https://logdate.app/{type}/{id}`),
 *   - tenant subdomain web URIs (`https://{handle}.logdate.app/{type}/{id}`).
 *
 * Returns null if the URI doesn't match any known pattern, including any
 * traffic on a reserved subdomain (`app.logdate.app`, `api.logdate.app`,
 * etc.) and the apex marketing root.
 */
fun resolveDeepLinkUri(uri: Uri): NavKey? {
    blueskyReturnRoute(uri.toString())?.let { return it }
    val segments = uri.pathSegments ?: return null
    val host = uri.host ?: return null

    return when (classifyLogdateHost(host, BuildConfig.LOGDATE_ORIGIN)) {
        is LogdateHostClass.Apex,
        is LogdateHostClass.TenantClaimed,
        -> resolveWebPath(segments)

        LogdateHostClass.Other -> resolveCustomSchemeHost(host, segments, uri)
    }
}

private fun resolveCustomSchemeHost(
    host: String,
    segments: List<String>,
    uri: Uri,
): NavKey? =
    when (host) {
        PATH_JOURNAL -> segments.firstOrNull()?.parseUuidTo { JournalDetailsRoute(it) }
        PATH_DAY -> segments.firstOrNull()?.parseDateString { TimelineDetailRoute(it) }
        "handoff" ->
            segments.firstOrNull()?.parseUuidTo { id ->
                val target = uri.getQueryParameter("target")?.takeIf { it in setOf("edit", "share", "day", "play") } ?: "edit"
                WatchHandoffRoute(id.toString(), target)
            }
        PATH_NOTE -> segments.firstOrNull()?.parseUuidTo { NoteDetailRoute(it) }
        PATH_POSTCARD -> segments.firstOrNull()?.parseUuidTo { PostcardViewerRoute(it) }
        PATH_REWIND -> segments.firstOrNull()?.parseUuidTo { RewindDetailRoute(it) }
        PATH_LOCATION -> LocationTimelineRoute
        PATH_EVENT -> segments.firstOrNull()?.let { EventDetailRoute(it) }
        PATH_SEARCH -> uri.toSearchRoute()
        CAPTURE_HOST ->
            captureRoute(
                mode = segments.firstOrNull() ?: uri.getQueryParameter(EXTRA_CAPTURE_MODE),
                journalId = uri.getQueryParameter(CAPTURE_JOURNAL_PARAM),
            )
        else -> null
    }

private fun Uri.toSearchRoute(): SearchRoute =
    searchRouteFromParams(
        rawQuery = getQueryParameter(SEARCH_QUERY_PARAM),
        rawTypes = getQueryParameter(SEARCH_TYPE_PARAM),
        rawDate = getQueryParameter(SEARCH_DATE_PARAM),
    )

/**
 * Converts a navigation destination to its canonical logdate.app web URL.
 *
 * Inverse of [resolveDeepLinkUri] for web-scheme URIs. Returns null for destinations
 * with no stable addressable URL (list screens, settings, etc.).
 *
 * Currently emits the apex shape (`https://logdate.app/<type>/<id>`); the
 * web proxy 308s those to the canonical tenant subdomain when cloud knows
 * the share's owner. Once the share-sheet has the user's handle threaded
 * through (see logdate-web/docs/deep-linking.md item N-4), this should
 * emit the canonical shape directly.
 */
fun NavKey.toWebUrl(): String? =
    when (this) {
        is JournalDetailsRoute ->
            LOGDATE_API_BASE_URL
                .buildUpon()
                .appendPath(PATH_JOURNAL)
                .appendPath(journalId)
                .build()
                .toString()
        is TimelineDetailRoute ->
            LOGDATE_API_BASE_URL
                .buildUpon()
                .appendPath(PATH_DAY)
                .appendPath(dateIso)
                .build()
                .toString()
        is NoteDetailRoute ->
            LOGDATE_API_BASE_URL
                .buildUpon()
                .appendPath(PATH_NOTE)
                .appendPath(noteId)
                .build()
                .toString()
        is PostcardViewerRoute ->
            LOGDATE_API_BASE_URL
                .buildUpon()
                .appendPath(PATH_POSTCARD)
                .appendPath(postcardId)
                .build()
                .toString()
        is RewindDetailRoute ->
            LOGDATE_API_BASE_URL
                .buildUpon()
                .appendPath(PATH_REWIND)
                .appendPath(id)
                .build()
                .toString()
        is EventDetailRoute ->
            LOGDATE_API_BASE_URL
                .buildUpon()
                .appendPath(PATH_EVENT)
                .appendPath(eventId)
                .build()
                .toString()
        else -> null
    }

private fun resolveWebPath(segments: List<String>): NavKey? {
    val type = segments.getOrNull(0) ?: return null
    val value = segments.getOrNull(1)
    return when (type) {
        PATH_JOURNAL, PATH_JOURNAL_SHORT -> value?.parseUuidTo { JournalDetailsRoute(it) }
        PATH_NOTE -> value?.parseUuidTo { NoteDetailRoute(it) }
        PATH_DAY -> value?.parseDateString { TimelineDetailRoute(it) }
        PATH_POSTCARD -> value?.parseUuidTo { PostcardViewerRoute(it) }
        PATH_REWIND -> value?.parseUuidTo { RewindDetailRoute(it) }
        PATH_LOCATION -> LocationTimelineRoute
        PATH_EVENT -> value?.let { EventDetailRoute(it) }
        else -> null
    }
}

private inline fun <T : NavKey> String.parseUuidTo(create: (Uuid) -> T): T? = runCatching { create(Uuid.parse(this)) }.getOrNull()

private inline fun <T : NavKey> String.parseDateString(create: (String) -> T): T? =
    runCatching {
        LocalDate.parse(this)
        create(this)
    }.getOrNull()

/**
 * Resolves the optional launch destination from the activity intent.
 *
 * This is the single resolver used by `MainActivity` for deep links, notification taps,
 * widget launches, ambient prompts, and the Android 16 handoff fallback URI.
 */
fun resolveMainActivityNavKey(intent: Intent?): NavKey? {
    if (intent == null) return null
    intent.readNavigationTestDestination()?.let { return it }
    val navSource = intent.getStringExtra(EXTRA_NAV_SOURCE)
    val ambientTarget = intent.getStringExtra(EXTRA_AMBIENT_PROMPT_TARGET)
    // The first family the intent belongs to decides; a malformed one resolves to nothing rather
    // than to another family's destination.
    return when {
        intent.action == ACTION_CAPTURE -> {
            captureRoute(
                mode = intent.getStringExtra(EXTRA_CAPTURE_MODE),
                journalId = intent.getStringExtra(EXTRA_CAPTURE_JOURNAL_ID),
                text = intent.getStringExtra(EXTRA_CAPTURE_TEXT),
            )
        }

        navSource in WIDGET_AND_PLAYBACK_SOURCES -> resolveNavSourceRoute(intent, navSource)

        intent.getStringExtra(EXTRA_LOCATION_NAV_SOURCE) == NAV_SOURCE_LOCATION_HISTORY -> {
            LocationTimelineRoute
        }

        ambientTarget in AMBIENT_PROMPT_TARGETS -> resolveAmbientPromptRoute(intent, ambientTarget)

        intent.getStringExtra(EXTRA_REWIND_NOTIFICATION_TARGET) == REWIND_NOTIFICATION_TARGET_DETAIL -> {
            val rewindId = intent.getStringExtra(EXTRA_REWIND_NOTIFICATION_ID) ?: return null
            runCatching { RewindDetailRoute(Uuid.parse(rewindId)) }.getOrNull()
        }

        intent.getStringExtra(EXTRA_DATA_TRANSFER_NAV_SOURCE) == NAV_SOURCE_DATA_TRANSFER -> {
            ExportSettingsRoute
        }

        // Deep link URIs: logdate://journal/{id}, logdate://day/{date}, etc.
        intent.data != null -> resolveDeepLinkUri(intent.data!!)

        else -> null
    }
}

private val WIDGET_AND_PLAYBACK_SOURCES =
    setOf(
        NAV_SOURCE_AUDIO_PLAYBACK,
        NAV_SOURCE_AUDIO_RECORDING,
        NAV_SOURCE_ON_THIS_DAY_WIDGET,
        NAV_SOURCE_FIXED_MEMORY_WIDGET,
        NAV_SOURCE_NEW_ENTRY_WIDGET,
    )

private val AMBIENT_PROMPT_TARGETS =
    setOf(
        AMBIENT_PROMPT_TARGET_NEW_ENTRY,
        AMBIENT_PROMPT_TARGET_DRAFT,
        AMBIENT_PROMPT_TARGET_MEMORY_RECALL,
        AMBIENT_PROMPT_TARGET_EVENT_DETAIL,
    )

/** Where a widget or audio playback launch opens, or null when its extras are missing or invalid. */
private fun resolveNavSourceRoute(
    intent: Intent,
    navSource: String?,
): NavKey? =
    when (navSource) {
        NAV_SOURCE_AUDIO_RECORDING ->
            intent.getStringExtra(EXTRA_RECORDING_DRAFT_ID)?.parseUuidTo {
                EntryEditorRoute(draftId = it.toString(), resumeRecording = true)
            }
        NAV_SOURCE_AUDIO_PLAYBACK -> intent.getStringExtra(EXTRA_NOTE_ID)?.parseUuidTo(::NoteDetailRoute)
        NAV_SOURCE_ON_THIS_DAY_WIDGET -> intent.getStringExtra(EXTRA_WIDGET_TARGET_DATE)?.parseDateString(::TimelineDetailRoute)
        NAV_SOURCE_FIXED_MEMORY_WIDGET -> intent.getStringExtra(EXTRA_WIDGET_NOTE_ID)?.parseUuidTo(::NoteDetailRoute)
        NAV_SOURCE_NEW_ENTRY_WIDGET -> {
            widgetCaptureRoute(
                photoUri = intent.getStringExtra(EXTRA_WIDGET_PHOTO_URI),
                record = intent.getBooleanExtra(EXTRA_WIDGET_RECORD, false),
                camera = intent.getBooleanExtra(EXTRA_WIDGET_CAMERA, false),
            )
        }
        else -> null
    }

/** Where a capture nudge or memory prompt opens, or null when its extras are missing or invalid. */
private fun resolveAmbientPromptRoute(
    intent: Intent,
    target: String?,
): NavKey? =
    when (target) {
        AMBIENT_PROMPT_TARGET_NEW_ENTRY -> EntryEditorRoute()
        AMBIENT_PROMPT_TARGET_DRAFT -> {
            intent.getStringExtra(EXTRA_AMBIENT_PROMPT_DRAFT_ID)?.parseUuidTo { EntryEditorRoute(draftId = it.toString()) }
        }
        AMBIENT_PROMPT_TARGET_MEMORY_RECALL -> {
            intent.getStringExtra(EXTRA_AMBIENT_PROMPT_RECALL_DATE)?.parseDateString(::TimelineDetailRoute)
        }
        AMBIENT_PROMPT_TARGET_EVENT_DETAIL -> {
            intent.getStringExtra(EXTRA_AMBIENT_PROMPT_EVENT_ID)?.let { runCatching { EventDetailRoute(it) }.getOrNull() }
        }
        else -> null
    }
