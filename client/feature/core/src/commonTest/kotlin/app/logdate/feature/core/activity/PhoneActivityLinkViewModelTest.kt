package app.logdate.feature.core.activity

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.activity.PhoneActivityRegistration
import app.logdate.feature.core.settings.ui.labs.FakeFeatureFlagStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneActivityLinkViewModelTest {
    @Test
    fun aMissingOrRevokedGrantNeverEnablesThePreference() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var permitted = false
                val flags = FakeFeatureFlagStore()
                val registration =
                    PhoneActivityRegistration(
                        optedIn = { flags.isEnabled(FeatureFlag.ACTIVITY_AWARENESS) },
                        available = { true },
                        permitted = { permitted },
                        register = { true },
                        unregister = {},
                    )
                val link = PhoneActivityLinkViewModel(flags, registration)

                link.setLinked(true)
                runCurrent()
                assertFalse(flags.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))

                permitted = true
                link.setLinked(true)
                link.setLinked(true)
                runCurrent()
                assertTrue(flags.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))
                assertEquals(listOf(FeatureFlag.ACTIVITY_AWARENESS to true), flags.writes)

                permitted = false
                link.setLinked(false)
                runCurrent()
                assertFalse(flags.isEnabled(FeatureFlag.ACTIVITY_AWARENESS))
            } finally {
                Dispatchers.resetMain()
            }
        }
}
