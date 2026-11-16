package app.logdate.client.appfunctions

import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.repository.assistants.AssistantCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantAccessTest {
    private val nothing = DeviceAssistantAccess()
    private val places = DeviceAssistantAccess(capabilities = setOf(AssistantCapability.READ_PLACES))
    private val capture = DeviceAssistantAccess(capabilities = setOf(AssistantCapability.START_CAPTURE))

    @Test
    fun `reading is refused until the person shares something`() {
        assertEquals(AssistantRefusal.NotShared, assistantReadRefusal(nothing, ASSISTANT_SEARCH_CAPABILITIES, unlocked = true))
        assertEquals(AssistantRefusal.NotShared, assistantReadRefusal(capture, ASSISTANT_SEARCH_CAPABILITIES, unlocked = true))
    }

    @Test
    fun `any one shared kind of content is enough to search`() {
        assertNull(assistantReadRefusal(places, ASSISTANT_SEARCH_CAPABILITIES, unlocked = true))
    }

    @Test
    fun `listing journals needs entries specifically`() {
        assertEquals(
            AssistantRefusal.NotShared,
            assistantReadRefusal(places, setOf(AssistantCapability.READ_MEMORIES), unlocked = true),
        )
    }

    @Test
    fun `reading is refused while LogDate is locked`() {
        assertEquals(AssistantRefusal.Locked, assistantReadRefusal(places, ASSISTANT_SEARCH_CAPABILITIES, unlocked = false))
    }

    @Test
    fun `starting an entry needs its own permission and ignores the lock`() {
        assertEquals(AssistantRefusal.CaptureOff, assistantCaptureRefusal(places))
        assertNull(assistantCaptureRefusal(capture))
    }

    @Test
    fun `refusals tell the person where to change it`() {
        listOf(AssistantRefusal.NotShared, AssistantRefusal.CaptureOff).forEach { refusal ->
            assertTrue("Settings > Privacy & security > Android assistants" in refusal.message)
        }
    }

    @Test
    fun `with the app lock on LogDate counts as locked until the person unlocks it`() {
        // A process started only for an assistant has never shown the unlock prompt.
        assertEquals(false, assistantUnlocked(appLockOn = true, authenticated = false))
        assertEquals(true, assistantUnlocked(appLockOn = true, authenticated = true))
    }

    @Test
    fun `without the app lock LogDate is always unlocked`() {
        assertEquals(true, assistantUnlocked(appLockOn = false, authenticated = false))
    }

    @Test
    fun `excerpts are one line and shortened when long`() {
        assertEquals("Walked home. Quiet light.", assistantExcerpt("Walked home.\n  Quiet light.  "))
        val excerpt = assistantExcerpt("a".repeat(400))
        assertEquals(ASSISTANT_EXCERPT_LENGTH, excerpt.length)
        assertEquals('…', excerpt.last())
    }
}
