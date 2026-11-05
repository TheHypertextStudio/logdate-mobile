package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.main.HomeRoute
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class BlueskySavedStateAndroidTest {
    @Test
    fun `the real app saved state configuration restores only the opaque return destination`() {
        val stack: List<NavKey> = listOf(HomeRoute, BlueskySignInRoute("a".repeat(43)))
        val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))
        val saved = encodeToSavedState(serializer, stack, appNavSavedStateConfiguration)
        assertEquals(stack, decodeFromSavedState(serializer, saved, appNavSavedStateConfiguration))
    }
}
