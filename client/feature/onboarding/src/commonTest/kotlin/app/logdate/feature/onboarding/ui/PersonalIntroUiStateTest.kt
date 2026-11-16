package app.logdate.feature.onboarding.ui

import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_bio_required
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalIntroUiStateTest {
    @Test
    fun `an empty bio does not block setup`() {
        val state = PersonalIntroUiState(name = "Alice", bio = "")

        assertTrue(
            state.canContinueFromBio,
            "requiring a bio left anyone unwilling to write one unable to finish setting up",
        )
    }

    @Test
    fun `a name is still required`() {
        assertFalse(PersonalIntroUiState(name = "   ").canContinueFromName)
        assertTrue(PersonalIntroUiState(name = "Alice").canContinueFromName)
    }

    @Test
    fun `a rejected bio still blocks`() {
        val state = PersonalIntroUiState(bio = "x", bioError = Res.string.onboarding_personal_intro_bio_required)

        assertFalse(state.canContinueFromBio)
    }
}
