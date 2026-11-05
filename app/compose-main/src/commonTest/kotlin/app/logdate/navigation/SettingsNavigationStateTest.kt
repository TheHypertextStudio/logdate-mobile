package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.AccountSettingsRoute
import app.logdate.feature.core.settings.navigation.AdvancedSettingsRoute
import app.logdate.feature.core.settings.navigation.DeleteAccountRoute
import app.logdate.feature.core.settings.navigation.DeveloperToolsRoute
import app.logdate.feature.core.settings.navigation.HostingRoute
import app.logdate.feature.core.settings.navigation.MoveServerRoute
import app.logdate.feature.core.settings.navigation.RecoveryPhraseEntrySettingsRoute
import app.logdate.feature.core.settings.navigation.RecoveryPhraseRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.core.settings.navigation.SignInMethodsRoute
import app.logdate.feature.core.settings.navigation.SoundRecognitionSettingsRoute
import app.logdate.feature.core.settings.navigation.TranscriptionSettingsRoute
import app.logdate.feature.core.settings.navigation.VoiceNotesSettingsRoute
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class SettingsNavigationStateTest {
    @Test
    fun `audio capability back stacks survive saved state restoration`() {
        for (destination in listOf(TranscriptionSettingsRoute, SoundRecognitionSettingsRoute)) {
            val stack: List<NavKey> = listOf(HomeRoute, SettingsRoute(), VoiceNotesSettingsRoute, destination)
            val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))
            val saved = encodeToSavedState(serializer, stack, appNavSavedStateConfiguration)
            val restored = decodeFromSavedState(serializer, saved, appNavSavedStateConfiguration)
            assertEquals(stack, restored)
        }
    }

    @Test
    fun `account settings back stacks survive saved state restoration`() {
        val destinations =
            listOf(
                SignInMethodsRoute,
                RecoveryPhraseRoute,
                HostingRoute,
                MoveServerRoute,
                DeleteAccountRoute,
                RecoveryPhraseEntrySettingsRoute,
            )
        for (destination in destinations) {
            val stack: List<NavKey> = listOf(HomeRoute, SettingsRoute(), AccountSettingsRoute, destination)
            val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))

            val saved = encodeToSavedState(serializer, stack, appNavSavedStateConfiguration)
            val restored = decodeFromSavedState(serializer, saved, appNavSavedStateConfiguration)

            assertEquals(stack, restored, destination.toString())
        }
    }

    @Test
    fun `developer tools back stack survives saved state restoration`() {
        val stack: List<NavKey> = listOf(HomeRoute, SettingsRoute(), AdvancedSettingsRoute, DeveloperToolsRoute)
        val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))

        val saved = encodeToSavedState(serializer, stack, appNavSavedStateConfiguration)
        val restored = decodeFromSavedState(serializer, saved, appNavSavedStateConfiguration)

        assertEquals(stack, restored)
    }
}
