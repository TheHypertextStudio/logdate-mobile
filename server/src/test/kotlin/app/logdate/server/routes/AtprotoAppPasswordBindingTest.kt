package app.logdate.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AtprotoAppPasswordBindingTest {
    @Test
    fun `a challenge is bound to the reviewed account identity and app name`() {
        val account = Uuid.random()
        val did = "did:plc:abcdefghijklmnopqrstuvwx"
        val binding = appPasswordBinding(account, did, "Example app")

        assertEquals(binding, appPasswordBinding(account, did, "Example app"))
        assertNotEquals(binding, appPasswordBinding(Uuid.random(), did, "Example app"))
        assertNotEquals(binding, appPasswordBinding(account, "did:plc:bcdefghijklmnopqrstuvwxy", "Example app"))
        assertNotEquals(binding, appPasswordBinding(account, did, "Another app"))
    }
}
