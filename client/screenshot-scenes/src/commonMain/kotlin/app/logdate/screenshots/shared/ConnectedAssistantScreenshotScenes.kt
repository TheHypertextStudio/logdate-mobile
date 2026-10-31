package app.logdate.screenshots.shared

import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.feature.core.assistants.AssistantAccessSelection
import app.logdate.feature.core.assistants.AssistantActivity
import app.logdate.feature.core.assistants.AssistantConnectContent
import app.logdate.feature.core.assistants.AssistantConnectRequest
import app.logdate.feature.core.assistants.AssistantJournalOption
import app.logdate.feature.core.assistants.ConnectedAssistantContent
import app.logdate.feature.core.assistants.ConnectedAssistantSummary
import app.logdate.feature.core.assistants.ConnectedAssistantsContent
import kotlin.uuid.Uuid

private val travelJournal = Uuid.parse("00000000-0000-4000-8000-000000000001")
private val notesJournal = Uuid.parse("00000000-0000-4000-8000-000000000002")
private val assistantJournals = listOf(AssistantJournalOption(travelJournal, "Travel"), AssistantJournalOption(notesJournal, "Notes"))
private val everythingRequested = AssistantCapability.entries.toSet()
private val claudeAccess =
    AssistantAccessSelection(
        capabilities = setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.READ_PLACES),
        journalIds = setOf(travelJournal, notesJournal),
    )
private val claude = ConnectedAssistantSummary("claude", "Claude", claudeAccess.capabilities, "October 30", "today")

/** Fictional connection states; rendering them grants no access. */
internal val connectedAssistantScenes =
    listOf(
        sharedScene(SharedScreenshotSceneId.AssistantConnect, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            AssistantConnectContent(
                request = AssistantConnectRequest("Claude", "Alex’s Mac", everythingRequested),
                journals = assistantJournals,
                selection = claudeAccess,
                anytimeAccess = false,
                allowing = false,
                onSelectionChange = {},
                onAllow = {},
                onDeny = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.ConnectedAssistants, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            ConnectedAssistantsContent(listOf(claude), "Alex’s Mac", false, {}, {}, {}, {})
        },
        sharedScene(SharedScreenshotSceneId.ConnectedAssistantsEmpty, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            ConnectedAssistantsContent(emptyList(), "Alex’s Mac", null, {}, {}, {}, {})
        },
        sharedScene(SharedScreenshotSceneId.ConnectedAssistant, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            ConnectedAssistantContent(
                assistant = claude,
                offered = everythingRequested,
                journals = assistantJournals,
                access = claudeAccess,
                activity =
                    listOf(
                        AssistantActivity(AssistantCapability.READ_MEMORIES, "Searched “canal walk”", "2 hours ago"),
                        AssistantActivity(AssistantCapability.READ_PLACES, "Looked at October places", "Yesterday"),
                    ),
                disconnecting = false,
                onAccessChange = {},
                onDisconnect = {},
                onBack = {},
            )
        },
    )
