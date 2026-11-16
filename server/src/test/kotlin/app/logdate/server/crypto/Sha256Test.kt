package app.logdate.server.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The digests the server stores and compares, checked against the published SHA-256 test vectors. */
class Sha256Test {
    @Test
    fun `text is digested as UTF-8 and written as lowercase hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals(sha256Hex("café".encodeToByteArray()), sha256Hex("café"))
    }

    @Test
    fun `the raw digest is the same one its hex form writes`() {
        val digest = sha256("abc")

        assertEquals(32, digest.size)
        assertEquals(sha256Hex("abc"), digest.joinToString("") { "%02x".format(it) })
        assertContentEquals(digest, sha256("abc".encodeToByteArray()))
    }

    @Test
    fun `hex keeps leading zeros`() {
        assertEquals("00ff0a", lowercaseHex(byteArrayOf(0, -1, 10)))
    }
}
