package app.logdate.client.appfunctions

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import app.logdate.client.MainActivity
import app.logdate.client.capture.ACTION_CAPTURE
import app.logdate.client.capture.EXTRA_CAPTURE_MODE
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.domain.assistants.DeviceAssistantAccessStore
import app.logdate.client.domain.assistants.DeviceAssistantLibrary
import app.logdate.client.launch.ApplicationStartupCoordinator
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantSourceKind
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.shared.model.user.AppSecurityLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * LogDate's actions for assistants on Android 16 and later. An assistant can read only what the
 * person allowed in Settings › Privacy & security › Android assistants, only while LogDate is
 * unlocked, and can open a new entry for the person to finish. No action writes or changes an entry.
 *
 * The AppFunctions compiler generates `LogDateAppFunctionService` from this class, with the schema
 * `logdate_app_functions.xml`. The service is declared disabled and enabled by
 * `PlatformComponentToggler` while the APP_FUNCTIONS flag is on.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "LogDateAppFunctionService",
    appFunctionXmlFileName = "logdate_app_functions",
)
abstract class LogDateAppFunctions :
    AppFunctionService(),
    KoinComponent {
    private val access: DeviceAssistantAccessStore by inject()
    private val library: DeviceAssistantLibrary by inject()
    private val preferences: LogdatePreferencesDataSource by inject()
    private val gatekeeper: BiometricGatekeeper by inject()

    /**
     * Lists the LogDate journals the person lets assistants read, so one can be named when the person
     * talks about it. Fails with a message to pass on when the person hasn't shared their entries or
     * LogDate is locked.
     *
     * @return The journals the person shared.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listJournals(): List<AssistantJournal> =
        withContext(Dispatchers.IO) {
            val allowed = requireReadAccess(setOf(AssistantCapability.READ_MEMORIES))
            library.journals(allowed).map { AssistantJournal(id = it.id.toString(), title = it.title) }
        }

    /**
     * Searches what the person lets assistants see in LogDate: their entries and voice-note transcripts
     * in the journals they chose, those journals, the places they've been, and the people who come up
     * in their entries. Fails with a message to pass on when the person hasn't shared anything or
     * LogDate is locked.
     *
     * @param query The words to look for.
     * @return Up to 10 matches, best first, each shortened to one line.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun search(query: String): List<AssistantFinding> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) throw AppFunctionInvalidArgumentException("The search query is empty.")
            val allowed = requireReadAccess(ASSISTANT_SEARCH_CAPABILITIES)
            library.search(allowed, query, ASSISTANT_SEARCH_LIMIT).map { found ->
                AssistantFinding(
                    id = found.id.toString(),
                    kind = found.kind.assistantLabel,
                    text = assistantExcerpt(found.content),
                    createdAt = found.created.toString(),
                )
            }
        }

    /**
     * Opens LogDate to a new voice note or written entry for the person to record or write
     * themselves. The entry starts empty, and nothing is saved until the person saves it. Use it when
     * the person asks to record, note or journal something. Fails with a message to pass on when the
     * person hasn't allowed it.
     *
     * @param voice True to start recording a voice note; false to open a new written entry.
     * @return The way to open LogDate to the new entry.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun startEntry(voice: Boolean): AssistantEntryStart =
        withContext(Dispatchers.IO) {
            awaitJournal()
            assistantCaptureRefusal(access.current())?.let { throw AppFunctionDeniedException(it.message) }
            val mode = if (voice) "voice" else "text"
            val intent =
                Intent(ACTION_CAPTURE)
                    .setClass(this@LogDateAppFunctions, MainActivity::class.java)
                    .putExtra(EXTRA_CAPTURE_MODE, mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            AssistantEntryStart(
                intentToOpen =
                    PendingIntent.getActivity(
                        this@LogDateAppFunctions,
                        mode.hashCode(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
            )
        }

    /** Waits for LogDate's storage, which a process Android starts only to answer an assistant hasn't opened. */
    private suspend fun awaitJournal() {
        if (!ApplicationStartupCoordinator.start(application).awaitReady()) {
            throw AppFunctionAppUnknownException(AssistantRefusal.Unavailable.message)
        }
    }

    /** What the person allows, or a refusal with the reason to pass on. */
    private suspend fun requireReadAccess(needed: Set<AssistantCapability>): DeviceAssistantAccess {
        awaitJournal()
        val allowed = access.current()
        // The gatekeeper starts out as "no prompt needed" in every new process, so whether the app
        // lock is on comes from the setting itself.
        val appLockOn = preferences.userData.first().securityLevel != AppSecurityLevel.NONE
        val unlocked = assistantUnlocked(appLockOn, authenticated = gatekeeper.authState.value == AppAuthState.AUTHENTICATED)
        assistantReadRefusal(allowed, needed, unlocked)?.let { throw AppFunctionDeniedException(it.message) }
        return allowed
    }
}

private val AssistantSourceKind.assistantLabel: String
    get() =
        when (this) {
            AssistantSourceKind.TEXT -> "entry"
            AssistantSourceKind.TRANSCRIPT -> "voice note"
            AssistantSourceKind.JOURNAL -> "journal"
            AssistantSourceKind.PLACE -> "place"
            AssistantSourceKind.PERSON -> "person"
            AssistantSourceKind.ORIGINAL_MEDIA, AssistantSourceKind.UNSUPPORTED -> "other"
        }

/** Something an assistant found in what the person shared. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantFinding(
    /** The stable id of the entry, journal, place or person. */
    val id: String,
    /** What it is: "entry", "voice note", "journal", "place" or "person". */
    val kind: String,
    /** Its text on one line, shortened when long. */
    val text: String,
    /** When it was written or added, as an ISO 8601 instant. */
    val createdAt: String,
)

/** A journal as an assistant sees it. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantJournal(
    /** The journal's stable id. */
    val id: String,
    /** The journal's title. */
    val title: String,
)

/** A new entry ready for the person to record or write. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantEntryStart(
    /** Opens LogDate to the new entry. Open it for the person; LogDate saves nothing until they do. */
    val intentToOpen: PendingIntent,
)
