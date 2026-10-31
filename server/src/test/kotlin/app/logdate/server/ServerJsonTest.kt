package app.logdate.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerJsonTest {
    @Serializable
    private data class Body(
        val name: String,
        val count: Int,
    )

    @Test
    fun `responses are encoded without indentation`() {
        assertEquals("""{"name":"a","count":1}""", serverJson.encodeToString(Body.serializer(), Body("a", 1)))
    }

    @Test
    fun `requests must be standard JSON`() {
        assertFailsWith<SerializationException> { serverJson.decodeFromString(Body.serializer(), """{name: a, count: 1}""") }
    }

    @Test
    fun `fields a newer client adds are ignored`() {
        assertEquals(Body("a", 1), serverJson.decodeFromString(Body.serializer(), """{"name":"a","count":1,"extra":true}"""))
    }
}
