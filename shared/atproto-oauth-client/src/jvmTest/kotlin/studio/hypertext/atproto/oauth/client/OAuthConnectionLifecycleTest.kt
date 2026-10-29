package studio.hypertext.atproto.oauth.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class OAuthConnectionLifecycleTest {
    @Test
    fun aRejectedDecisionDoesNotRevokeOrRemoveTheStoredGrant() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val original = harness.sessionStore.get(did)
            var guardCalled = false
            var removed = false

            val result =
                harness.client.revokeIf(did, beforeRevoke = {
                    guardCalled = true
                    false
                }, removeLocal = { removed = true })

            assertEquals(OAuthConditionalRevocation.SKIPPED, result)
            assertTrue(guardCalled)
            assertFalse(removed)
            assertEquals(original, harness.sessionStore.get(did))
            assertTrue(harness.atmosphere.revokedTokens.isEmpty())
        }

    @Test
    fun anAcceptedDecisionRevokesAndCommitsItsLocalCompletion() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val token = harness.sessionStore.get(did)!!.refreshToken
            var completed = false

            val result =
                harness.client.revokeIf(did, beforeRevoke = { true }, removeLocal = {
                    harness.sessionStore.delete(did)
                    completed = true
                })

            assertEquals(OAuthConditionalRevocation.CONFIRMED, result)
            assertTrue(token in harness.atmosphere.revokedTokens)
            assertTrue(completed)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun aMissingSessionCompletesLocallyWithoutClaimingRemoteConfirmation() =
        runTest {
            val harness = OAuthTestHarness()
            val did = AtprotoDid.require(harness.atmosphere.alice.did)
            var completed = false

            val result = harness.client.revokeIf(did, beforeRevoke = { true }, removeLocal = { completed = true })

            assertEquals(OAuthConditionalRevocation.NO_SESSION, result)
            assertTrue(completed)
            assertTrue(harness.atmosphere.revokedTokens.isEmpty())
        }

    @Test
    fun anExpiredSessionRetainsTheReasonAtTheDeletionBoundary() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val saved = harness.sessionStore.get(did)!!
            harness.sessionStore.save(saved.copy(refreshToken = null))
            val reasons = mutableListOf<OAuthSessionEndReason>()
            val tracking = reasonStore(harness, reasons)
            val client = harness.newClientInstance(sessions = tracking)
            harness.atmosphere.clock.advance(6.minutes)

            assertFailsWith<OAuthSessionExpiredException> { client.restore(did) }

            assertEquals(listOf(OAuthSessionEndReason.EXPIRED), reasons)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun anAmbiguousRefreshRejectionDoesNotInventExpiryOrRevocation() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val reasons = mutableListOf<OAuthSessionEndReason>()
            val client = harness.newClientInstance(sessions = reasonStore(harness, reasons))
            harness.atmosphere
                .grantsFor(harness.atmosphere.alice.did)
                .single()
                .revoked = true
            harness.atmosphere.clock.advance(6.minutes)

            assertFailsWith<OAuthSessionRejectedException> { client.restore(did) }

            assertEquals(listOf(OAuthSessionEndReason.REJECTED), reasons)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun strictHostingPolicyRequiresReconnectionWhenThePdsChangesWithinTheSameIssuer() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val reasons = mutableListOf<OAuthSessionEndReason>()
            val client =
                harness.newClientInstance(
                    sessions = reasonStore(harness, reasons),
                    requireAuthorizationAfterPdsChange = true,
                )
            harness.atmosphere.move(harness.atmosphere.alice, harness.atmosphere.secondPdsUrl)
            harness.atmosphere.clock.advance(6.minutes)
            harness.atmosphere.requestLog.clear()

            assertFailsWith<OAuthSessionAuthorityChangedException> { client.restore(did) }

            assertEquals(listOf(OAuthSessionEndReason.AUTHORITY_CHANGED), reasons)
            assertNull(harness.sessionStore.get(did))
            assertTrue(harness.atmosphere.requestLog.none { it.startsWith("GET " + harness.atmosphere.secondPdsUrl + "/xrpc/") })
            assertTrue(harness.atmosphere.requestLog.none { it == "POST " + harness.atmosphere.issuer + "/oauth/token" })
        }

    @Test
    fun ordinaryStoresRemainCompatibleWithReasonAwareDeletion() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did

            harness.sessionStore.delete(did, OAuthSessionEndReason.REJECTED)

            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun replacementWaitsUntilTheGuardedRevocationHasCompleted() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val old = harness.sessionStore.get(did)!!
            val waiting = CompletableDeferred<Unit>()
            val delegate = InProcessOAuthSessionLock()
            var observeReplacement = false
            val lock =
                object : OAuthSessionLock {
                    override suspend fun <T> withLock(
                        did: AtprotoDid,
                        block: suspend () -> T,
                    ): T {
                        if (observeReplacement) waiting.complete(Unit)
                        return delegate.withLock(did, block)
                    }
                }
            val client = harness.newClientInstance(sessionLock = lock)
            val redirect = client.authorize(harness.atmosphere.alice.handle)
            val parameters = harness.atmosphere.approve(redirect.url)
            var replacement: Deferred<OAuthCallbackResult>? = null

            val result =
                client.revokeIf(did, beforeRevoke = {
                    observeReplacement = true
                    replacement = async { client.callback(parameters) }
                    waiting.await()
                    assertFalse(replacement.isCompleted)
                    assertEquals(old, harness.sessionStore.get(did))
                    true
                }, removeLocal = { harness.sessionStore.delete(did) })

            assertEquals(OAuthConditionalRevocation.CONFIRMED, result)
            replacement!!.await()
            val renewed = harness.sessionStore.get(did)!!
            assertTrue(renewed.accessToken != old.accessToken)
            assertFalse(
                harness.atmosphere
                    .grantsFor(harness.atmosphere.alice.did)
                    .last()
                    .revoked,
            )
            assertEquals(listOf(old.refreshToken), harness.atmosphere.revokedTokens)
        }

    @Test
    fun providerFailureStillCompletesLocallyWithoutConfirmation() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            harness.atmosphere.beforeRequest = {
                if (it.url.encodedPath == "/oauth/revoke") throw IllegalStateException("provider unavailable")
            }
            var completed = false

            assertFailsWith<OAuthRevocationException> {
                harness.client.revokeIf(did, beforeRevoke = { true }, removeLocal = {
                    harness.sessionStore.delete(did)
                    completed = true
                })
            }

            assertTrue(completed)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun cancellationDuringRevocationStillCompletesLocally() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            harness.atmosphere.beforeRequest = {
                if (it.url.encodedPath == "/oauth/revoke") throw CancellationException("request canceled")
            }
            var completed = false

            assertFailsWith<CancellationException> {
                harness.client.revokeIf(did, beforeRevoke = { true }, removeLocal = {
                    harness.sessionStore.delete(did)
                    completed = true
                })
            }

            assertTrue(completed)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun aLocalCompletionFailureCannotBecomeASuccessfulReceipt() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val saved = harness.sessionStore.get(did)
            val failure = IllegalStateException("local transaction failed")

            val observed =
                assertFailsWith<IllegalStateException> {
                    harness.client.revokeIf(did, beforeRevoke = { true }, removeLocal = { throw failure })
                }

            assertTrue(observed === failure)
            assertEquals(saved, harness.sessionStore.get(did))
        }

    @Test
    fun aRemovedClientKeyRecordsAKnownSecurityTermination() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val reasons = mutableListOf<OAuthSessionEndReason>()
            val client =
                harness.newClientInstance(
                    configuration = harness.config.copy(keys = listOf(JvmOAuthCrypto.generateClientKey("replacement"))),
                    sessions = reasonStore(harness, reasons),
                )
            harness.atmosphere.clock.advance(6.minutes)

            assertFailsWith<OAuthSessionRevokedException> { client.restore(did) }

            assertEquals(listOf(OAuthSessionEndReason.REVOKED), reasons)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun strictHostingPolicyDoesNotDescribeAnIssuerChangeAsRevokedConsent() =
        runTest {
            val harness = OAuthTestHarness()
            val did = harness.signIn().session.did
            val reasons = mutableListOf<OAuthSessionEndReason>()
            val client =
                harness.newClientInstance(
                    sessions = reasonStore(harness, reasons),
                    requireAuthorizationAfterPdsChange = true,
                )
            harness.atmosphere.move(harness.atmosphere.alice, harness.atmosphere.otherPdsUrl)
            harness.atmosphere.clock.advance(6.minutes)

            assertFailsWith<OAuthSessionAuthorityChangedException> { client.restore(did) }

            assertEquals(listOf(OAuthSessionEndReason.AUTHORITY_CHANGED), reasons)
            assertNull(harness.sessionStore.get(did))
        }

    @Test
    fun aDelayedDataRejectionCannotDeleteANewerCallbackGrant() =
        runTest {
            val harness = OAuthTestHarness()
            val session = harness.signIn().session
            val did = session.did
            val original = harness.sessionStore.get(did)!!
            val redirect = harness.client.authorize(harness.atmosphere.alice.handle)
            val parameters = harness.atmosphere.approve(redirect.url)
            harness.atmosphere
                .grantsFor(harness.atmosphere.alice.did)
                .first()
                .accessTokenExpiresAt = harness.atmosphere.clock.now()
            var replaced = false
            var replacementAccessToken: String? = null
            harness.atmosphere.beforeRequest = { request ->
                if (request.url.encodedPath == "/xrpc/com.atproto.server.getSession") {
                    val latest = harness.sessionStore.get(did)!!
                    if (!replaced && latest.accessToken != original.accessToken) {
                        replaced = true
                        harness.client.callback(parameters)
                        replacementAccessToken = harness.sessionStore.get(did)!!.accessToken
                    }
                }
            }

            assertFailsWith<studio.hypertext.atproto.xrpc.XrpcTransportException> {
                session.xrpcClient().queryRaw(
                    studio.hypertext.atproto.syntax.Nsid
                        .require("com.atproto.server.getSession"),
                )
            }

            assertTrue(replaced, "the callback replaced the renewed session while its request was in flight")
            assertTrue(replacementAccessToken != null)
            assertEquals(replacementAccessToken, harness.sessionStore.get(did)?.accessToken)
        }

    private fun reasonStore(
        harness: OAuthTestHarness,
        reasons: MutableList<OAuthSessionEndReason>,
    ): OAuthSessionStore =
        object : OAuthSessionStore by harness.sessionStore {
            override suspend fun delete(
                did: AtprotoDid,
                reason: OAuthSessionEndReason,
            ) {
                reasons += reason
                harness.sessionStore.delete(did)
            }
        }
}
