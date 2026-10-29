package studio.hypertext.atproto.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaimedHandleTest {
    @Test
    fun firstValidHandleWinsAndNonHandleUrisAreIgnored() {
        val document = document("https://profile.example.com", "at://not/a/handle", "at://ALICE.example.com", "at://other.example.com")
        assertEquals("alice.example.com", document.claimedHandle()?.normalized)
    }

    @Test
    fun reservedButSyntacticallyValidHandleDoesNotFallThroughToAnotherAlias() {
        assertEquals("stale.invalid", document("at://stale.invalid", "at://alice.example.com").claimedHandle()?.normalized)
    }

    @Test
    fun noValidHandleProducesNoClaim() {
        assertNull(document("https://profile.example.com", "at://invalid/path", "at://alice.example.com/").claimedHandle())
    }

    private fun document(vararg aliases: String) =
        DidDocument(
            id = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx"),
            alsoKnownAs = aliases.toList(),
        )
}
