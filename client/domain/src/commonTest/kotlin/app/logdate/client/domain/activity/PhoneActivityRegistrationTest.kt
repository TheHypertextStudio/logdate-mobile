package app.logdate.client.domain.activity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneActivityRegistrationTest {
    @Test
    fun noRegistrationUntilOptedInAndPermitted() =
        runTest {
            val provider = Provider()
            val registration = provider.registration()

            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.Off, registration.state.value)
            provider.enabled = true
            provider.permitted = false
            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.NeedsPermission, registration.state.value)
            assertEquals(0, provider.starts)

            provider.permitted = true
            registration.reconcile()
            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.Linked, registration.state.value)
            assertEquals(1, provider.starts)
            assertTrue(registration.canReceiveTransitions())

            provider.permitted = false
            assertFalse(registration.canReceiveTransitions())
            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.NeedsPermission, registration.state.value)
            assertEquals(1, provider.stops)
        }

    @Test
    fun unavailableOrFailedProviderIsNotLinked() =
        runTest {
            val provider =
                Provider().apply {
                    enabled = true
                    available = false
                }
            val registration = provider.registration()

            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.Unavailable, registration.state.value)
            assertEquals(0, provider.starts)

            provider.available = true
            provider.startResult = false
            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.Failed, registration.state.value)
            provider.startResult = true
            registration.reconcile()
            assertEquals(PhoneActivityRegistrationState.Linked, registration.state.value)
        }

    @Test
    fun grantRevokedDuringProviderRegistrationCannotPublishLinked() =
        runTest {
            val provider = Provider().apply { enabled = true }
            val result = CompletableDeferred<Boolean>()
            val registration = provider.registration { result.await() }

            launch { registration.reconcile() }
            runCurrent()
            assertEquals(PhoneActivityRegistrationState.Starting, registration.state.value)
            assertFalse(registration.canReceiveTransitions())

            provider.permitted = false
            result.complete(true)
            runCurrent()
            assertEquals(PhoneActivityRegistrationState.NeedsPermission, registration.state.value)
            assertEquals(1, provider.stops)
            assertFalse(registration.canReceiveTransitions())
        }

    private class Provider {
        var enabled = false
        var permitted = true
        var available = true
        var startResult = true
        var starts = 0
        var stops = 0
        private var registered = false

        fun registration(result: suspend () -> Boolean = { startResult }): PhoneActivityRegistration =
            PhoneActivityRegistration(
                optedIn = { enabled },
                available = { available },
                permitted = { permitted },
                register = {
                    starts++
                    result().also { registered = it }
                },
                unregister = {
                    if (registered) {
                        stops++
                        registered = false
                    }
                },
            )
    }
}
