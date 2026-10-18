package app.logdate.client.database.converters

import kotlin.test.Test
import kotlin.test.assertEquals

class StringListConverterTest {
    private val converter = StringListConverter()

    @Test
    fun `empty list is stored as an empty string so a not-null column accepts it`() {
        assertEquals("", converter.toString(emptyList()))
    }

    @Test
    fun `null and blank-only lists are stored as an empty string`() {
        assertEquals("", converter.toString(null))
        assertEquals("", converter.toString(listOf(" ", "")))
    }

    @Test
    fun `list round trips through its stored form`() {
        val stored = converter.toString(listOf("Sam", " Sammy "))

        assertEquals(listOf("Sam", "Sammy"), converter.fromString(stored))
    }

    @Test
    fun `empty string and null read back as an empty list`() {
        assertEquals(emptyList(), converter.fromString(""))
        assertEquals(emptyList(), converter.fromString(null))
    }
}
