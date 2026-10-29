package app.logdate.navigation

import app.logdate.shared.model.BlueskyBrowserReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountBrowserReturnTest {
    @Test fun `both fixed return schemes preserve connection purpose without ordinary sign in`() =
        runTest {
            for (base in listOf(BlueskyBrowserReturn.URL, BlueskyBrowserReturn.NATIVE_URL)) {
                val raw = "$base?attempt=$ID"
                var calls = 0
                assertEquals(
                    AccountBrowserReturn.CONNECTION,
                    resolveAccountBrowserReturn(raw) { received ->
                        calls += 1
                        assertEquals(raw, received)
                        true
                    },
                )
                assertEquals(1, calls)
            }
        }

    @Test fun `ordinary sign in is reached only after connection handling explicitly declines`() =
        runTest {
            var calls = 0
            assertEquals(
                AccountBrowserReturn.ORDINARY_SIGN_IN,
                resolveAccountBrowserReturn("${BlueskyBrowserReturn.URL}?attempt=$ID") {
                    calls +=
                        1
                    ; false
                },
            )
            assertEquals(1, calls)
        }

    @Test fun `failed purpose lookup never falls through to another account flow and cancellation propagates`() =
        runTest {
            val raw = "${BlueskyBrowserReturn.URL}?attempt=$ID"
            assertEquals(
                AccountBrowserReturn.UNAVAILABLE,
                resolveAccountBrowserReturn(raw) { throw IllegalStateException("private-store-diagnostic") },
            )
            assertFailsWith<CancellationException> { resolveAccountBrowserReturn(raw) { throw CancellationException() } }
        }

    @Test fun `unrecognized malformed or credential-bearing links never reach a purpose handler`() =
        runTest {
            for (raw in listOf(
                null,
                "https://other.example/auth/bluesky/return?attempt=$ID",
                "${BlueskyBrowserReturn.URL}?attempt=short",
                "${BlueskyBrowserReturn.URL}?attempt=$ID&token=private-token",
                "${BlueskyBrowserReturn.URL}?attempt=$ID#fragment",
            )) {
                assertEquals(
                    AccountBrowserReturn.NOT_A_RETURN,
                    resolveAccountBrowserReturn(raw) { error("Must not read pending authorization") },
                )
            }
        }

    private companion object {
        val ID = "A".repeat(43)
    }
}
