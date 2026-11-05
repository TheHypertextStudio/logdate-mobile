package app.logdate.feature.core.settings.account.recovery

import app.logdate.client.text.UiText
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.BiometricGatekeeper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPhraseViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val phrase = List(12) { "word$it" }
    private val prompt = RevealPrompt("Show recovery phrase", "Unlock to continue", null)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial check is neutral until secure storage responds`() =
        runTest {
            val pending = CompletableDeferred<List<String>?>()
            val model = model(load = { pending.await() })
            assertEquals(RecoveryPhraseUiState.Checking, model.state.value)
            pending.complete(phrase)
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `overlapping checks share one secure storage read`() =
        runTest {
            val pending = CompletableDeferred<List<String>?>()
            var reads = 0
            val model =
                model(load = {
                    reads++
                    pending.await()
                })
            model.check()
            model.check()
            assertEquals(1, reads)
            pending.complete(phrase)
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `stored phrase stays hidden until authenticated`() =
        runTest {
            val model = model()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Revealed(phrase), model.available().reveal)
        }

    @Test
    fun `a device without the phrase does not report a storage failure`() =
        runTest {
            assertEquals(RecoveryPhraseUiState.NotOnThisDevice, model(load = { null }).state.value)
        }

    @Test
    fun `returning from phrase entry checks missing storage again`() =
        runTest {
            var stored: List<String>? = null
            val model = model(load = { stored })
            assertEquals(RecoveryPhraseUiState.NotOnThisDevice, model.state.value)
            model.onScreenLeft()
            stored = phrase
            model.onScreenActive()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `retry immediately clears a stale initial error`() =
        runTest {
            val pending = CompletableDeferred<List<String>?>()
            var fail = true
            val model = model(load = { if (fail) error("Storage unavailable") else pending.await() })
            assertEquals(RecoveryPhraseUiState.Unreadable, model.state.value)
            fail = false
            model.check()
            assertEquals(RecoveryPhraseUiState.Checking, model.state.value)
            pending.complete(phrase)
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `authentication preserves availability and ignores duplicate requests`() =
        runTest {
            val gate = Gatekeeper()
            val model = model(gate = gate)
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Authenticating, model.available().reveal)
            model.reveal(prompt)
            assertEquals(1, gate.requests)
            gate.complete(AppAuthState.AUTHENTICATED)
            assertEquals(PhraseRevealState.Revealed(phrase), model.available().reveal)
        }

    @Test
    fun `cancelled authentication returns quietly to hidden`() =
        runTest {
            val model = model(gate = Gatekeeper(AppAuthState.REQUIRE_PROMPT))
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `authentication unavailable is an inline reveal failure`() =
        runTest {
            val model = model(gate = Gatekeeper(AppAuthState.UNSUPPORTED))
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Failed(RevealFailure.AUTHENTICATION_UNAVAILABLE), model.available().reveal)
        }

    @Test
    fun `a thrown authentication request does not leave a busy action`() =
        runTest {
            val gate = Gatekeeper(throws = true)
            val model = model(gate = gate)
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Failed(RevealFailure.AUTHENTICATION_UNAVAILABLE), model.available().reveal)
        }

    @Test
    fun `a no prompt needed result can reveal the words`() =
        runTest {
            val model = model(gate = Gatekeeper(AppAuthState.NO_PROMPT_NEEDED))
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Revealed(phrase), model.available().reveal)
        }

    @Test
    fun `hiding invalidates a pending authentication callback`() =
        runTest {
            val gate = Gatekeeper()
            val model = model(gate = gate)
            model.reveal(prompt)
            model.hide()
            gate.complete(AppAuthState.AUTHENTICATED)
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `leaving invalidates a pending authentication callback`() =
        runTest {
            val gate = Gatekeeper()
            val model = model(gate = gate)
            model.reveal(prompt)
            model.onScreenLeft()
            gate.complete(AppAuthState.AUTHENTICATED)
            model.onScreenActive()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `system authentication can return after stopping without showing words behind it`() =
        runTest {
            val gate = Gatekeeper()
            var reads = 0
            val model =
                model(gate = gate, load = {
                    reads++
                    phrase
                })
            model.reveal(prompt)
            model.onScreenInactive()
            gate.complete(AppAuthState.AUTHENTICATED)
            assertEquals(1, reads)
            assertEquals(PhraseRevealState.Authenticating, model.available().reveal)
            model.onScreenActive()
            assertEquals(PhraseRevealState.Revealed(phrase), model.available().reveal)
        }

    @Test
    fun `leaving while system authentication is stopped prevents resume reveal`() =
        runTest {
            val gate = Gatekeeper()
            val model = model(gate = gate)
            model.reveal(prompt)
            model.onScreenInactive()
            gate.complete(AppAuthState.AUTHENTICATED)
            model.onScreenLeft()
            model.onScreenActive()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `backgrounding revealed words covers them and does not reveal on return`() =
        runTest {
            val model = model()
            model.reveal(prompt)
            model.onScreenInactive()
            model.onScreenActive()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `leaving during initial load can load again on return`() =
        runTest {
            val pending = CompletableDeferred<List<String>?>()
            val model = model(load = { pending.await() })
            model.onScreenLeft()
            pending.complete(phrase)
            model.onScreenActive()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `a read that ignores cancellation cannot reveal after leaving`() =
        runTest {
            val pending = CompletableDeferred<List<String>?>()
            var reads = 0
            val model = model(load = { if (++reads == 1) phrase else withContext(NonCancellable) { pending.await() } })
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Reading, model.available().reveal)
            model.onScreenLeft()
            pending.complete(phrase)
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `a cancelled secure read is not reported as storage failure`() =
        runTest {
            var reads = 0
            val model = model(load = { if (++reads == 1) phrase else throw CancellationException("Leaving") })
            model.reveal(prompt)
            model.onScreenLeft()
            assertEquals(PhraseRevealState.Hidden, model.available().reveal)
        }

    @Test
    fun `refresh preserves a revealed phrase`() =
        runTest {
            val model = model()
            model.reveal(prompt)
            model.check()
            assertEquals(PhraseRevealState.Revealed(phrase), model.available().reveal)
        }

    @Test
    fun `a failed secure read leaves the reveal action available to retry`() =
        runTest {
            var reads = 0
            val model = model(load = { if (++reads == 2) error("Read failed") else phrase })
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Failed(RevealFailure.UNREADABLE), model.available().reveal)
            model.reveal(prompt)
            assertEquals(PhraseRevealState.Revealed(phrase), model.available().reveal)
        }

    private fun model(
        gate: Gatekeeper = Gatekeeper(AppAuthState.AUTHENTICATED),
        load: suspend () -> List<String>? = { phrase },
    ) = RecoveryPhraseViewModel(gate, loadPhrase = load)

    private fun RecoveryPhraseViewModel.available() = assertIs<RecoveryPhraseUiState.Available>(state.value)

    private class Gatekeeper(
        private val result: AppAuthState? = null,
        private val throws: Boolean = false,
    ) : BiometricGatekeeper {
        override val authState = MutableStateFlow(AppAuthState.NO_PROMPT_NEEDED)
        private var callback: (AppAuthState) -> Unit = {}
        var requests = 0
            private set

        override fun authenticate(
            title: UiText,
            subtitle: UiText,
            cancelLabel: UiText,
            requireConfirmation: Boolean,
            requestEnrollmentIfNecessary: Boolean,
            description: UiText?,
            onResult: (AppAuthState) -> Unit,
        ) {
            requests++
            if (throws) error("Authentication unavailable")
            callback = onResult
            result?.let(onResult)
        }

        fun complete(result: AppAuthState) = callback(result)

        override fun requestEnrollment() {}
    }
}
