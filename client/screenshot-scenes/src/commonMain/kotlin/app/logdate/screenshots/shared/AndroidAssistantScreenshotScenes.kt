package app.logdate.screenshots.shared

import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.feature.core.assistants.AndroidAssistantsContent
import app.logdate.feature.core.assistants.AssistantAccessSelection
import app.logdate.feature.core.assistants.AssistantJournalOption
import kotlin.uuid.Uuid

private val walks = Uuid.parse("00000000-0000-4000-8000-000000000011")
private val work = Uuid.parse("00000000-0000-4000-8000-000000000012")

/** Fictional choices for assistants on this phone; rendering them grants no access. */
internal val androidAssistantScenes =
    listOf(
        sharedScene(SharedScreenshotSceneId.AndroidAssistants, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            AndroidAssistantsContent(
                journals = listOf(AssistantJournalOption(walks, "Walks"), AssistantJournalOption(work, "Work")),
                access =
                    AssistantAccessSelection(
                        capabilities = setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.START_CAPTURE),
                        journalIds = setOf(walks),
                    ),
                onAccessChange = {},
                onBack = {},
            )
        },
    )
