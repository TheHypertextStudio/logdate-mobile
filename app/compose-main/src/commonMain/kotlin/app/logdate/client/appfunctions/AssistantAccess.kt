package app.logdate.client.appfunctions

import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.text.oneLineExcerpt

/** How many matches one assistant search returns at most. */
const val ASSISTANT_SEARCH_LIMIT = 10

/** The longest excerpt of an entry an assistant receives. */
const val ASSISTANT_EXCERPT_LENGTH = 280

/** What an assistant can find with a search. */
val ASSISTANT_SEARCH_CAPABILITIES =
    setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.READ_PLACES, AssistantCapability.READ_PEOPLE)

private const val SETTINGS_PATH = "LogDate Settings > Privacy & security > Android assistants"

/**
 * Why an assistant on this phone may not do what it asked. Each message tells the assistant what to
 * say to the person, so a refusal is never a dead end.
 */
enum class AssistantRefusal(
    val message: String,
) {
    /** The person has not let assistants on this phone see this kind of content. */
    NotShared(
        "The person hasn't let assistants on this phone see this in LogDate. " +
            "They can choose what assistants see in $SETTINGS_PATH.",
    ),

    /** The person has not let assistants on this phone start entries. */
    CaptureOff(
        "The person hasn't let assistants on this phone start entries in LogDate. " +
            "They can turn that on in $SETTINGS_PATH.",
    ),

    /** LogDate's app lock is on and the person has not unlocked it in this session. */
    Locked("LogDate is locked. The person needs to open LogDate and unlock it first."),

    /** LogDate could not open its journal, for example while a restore is finishing. */
    Unavailable(
        "LogDate can't open the journal right now. The person needs to open LogDate to finish getting it ready.",
    ),
}

/**
 * Whether an assistant may read: [needed] lists the permissions any one of which makes the read
 * useful. Reading also needs an unlocked app.
 *
 * @return why reading is refused, or null when it is allowed.
 */
fun assistantReadRefusal(
    access: DeviceAssistantAccess,
    needed: Set<AssistantCapability>,
    unlocked: Boolean,
): AssistantRefusal? =
    when {
        !access.allowsAny(needed) -> AssistantRefusal.NotShared
        !unlocked -> AssistantRefusal.Locked
        else -> null
    }

/**
 * Whether an assistant may open a new entry for the person. Opening reveals nothing, and LogDate's
 * own lock still applies once it opens, so only the person's permission matters.
 */
fun assistantCaptureRefusal(access: DeviceAssistantAccess): AssistantRefusal? =
    AssistantRefusal.CaptureOff.takeUnless { AssistantCapability.START_CAPTURE in access.capabilities }

/**
 * Whether LogDate counts as unlocked for an assistant. With the app lock on, only an unlock in this
 * process counts: a process Android starts just to answer an assistant has never asked.
 */
fun assistantUnlocked(
    appLockOn: Boolean,
    authenticated: Boolean,
): Boolean = !appLockOn || authenticated

/** An entry's text as an assistant receives it: on one line, shortened with an ellipsis when long. */
fun assistantExcerpt(
    content: String,
    maxLength: Int = ASSISTANT_EXCERPT_LENGTH,
): String = oneLineExcerpt(content, maxLength)
