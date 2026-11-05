package app.logdate.server.identity

import app.logdate.server.database.RawJdbcTransactionContext
import app.logdate.server.identity.adoption.IdentityAdoptionBlobAdmission
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.repo.Cid
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAdoptionBlobReadbackTest {
    private val ciphertext = ByteArray(180_013) { (it * 17).toByte() }
    private val admission =
        IdentityAdoptionBlobAdmission(
            "admission",
            "operation",
            3,
            "private-source-space",
            Cid.rawSha256(ciphertext).value,
            "users/synthetic/spaces/ciphertext",
            ciphertext.size.toLong(),
            "application/octet-stream",
        )

    @Test fun completeCiphertextProducesEvidenceBoundToTheExactAdmission() =
        runBlocking {
            val source = Probe(ciphertext)
            val verified = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, source))
            assertEquals(admission, verified.admission)
            assertEquals(1, source.opens)
            assertTrue(source.closed)
            assertTrue(source.largestRead in 1..65_536)
            assertEquals(ciphertext.size.toLong(), source.readBytes)
        }

    @Test fun changedCiphertextCannotProduceEvidence() =
        runBlocking {
            val bytes = ciphertext.copyOf().also { it[100] = (it[100] + 1).toByte() }
            val source = Probe(bytes)
            assertNull(IdentityAdoptionVerifiedBlob.read(admission, source))
            assertTrue(source.closed)
        }

    @Test fun declaredProviderSizeMustMatchTheAdmittedSize() =
        runBlocking {
            val source = Probe(ciphertext, ciphertext.size + 1L)
            assertNull(IdentityAdoptionVerifiedBlob.read(admission, source))
            assertEquals(0L, source.readBytes)
            assertTrue(source.closed)
        }

    @Test fun anEarlyEndCannotProduceEvidence() =
        runBlocking {
            val source = Probe(ciphertext.copyOf(ciphertext.size - 1), ciphertext.size.toLong())
            assertNull(IdentityAdoptionVerifiedBlob.read(admission, source))
            assertTrue(source.closed)
        }

    @Test fun anExtraByteCannotProduceEvidence() =
        runBlocking {
            val source = Probe(ciphertext + byteArrayOf(0), ciphertext.size.toLong())
            assertNull(IdentityAdoptionVerifiedBlob.read(admission, source))
            assertTrue(source.closed)
            assertTrue(source.readBytes <= admission.size + 1)
        }

    @Test fun missingObjectsCannotProduceEvidence() =
        runBlocking {
            val source =
                object : LogDateBlobStorage by InMemoryLogDateBlobStorage() {
                    override fun openBlob(storagePath: String): LogDateBlobStream? = null
                }
            assertNull(IdentityAdoptionVerifiedBlob.read(admission, source))
        }

    @Test fun invalidOrOversizedAdmissionsNeverContactTheProvider() =
        runBlocking {
            for (invalid in listOf(
                admission.copy(size = -1),
                admission.copy(size = 100L * 1024 * 1024 + 1),
                admission.copy(cid = "not-a-cid"),
                admission.copy(cid = Cid.sha256(0x71, ciphertext).value),
            )) {
                val source = Probe(ciphertext)
                assertNull(IdentityAdoptionVerifiedBlob.read(invalid, source))
                assertEquals(0, source.opens)
            }
        }

    @Test fun cancellationDuringReadClosesTheProviderStream() =
        runBlocking {
            val source = Probe(ciphertext, fail = { throw CancellationException("synthetic cancellation") })
            assertFailsWith<CancellationException> { IdentityAdoptionVerifiedBlob.read(admission, source) }
            assertTrue(source.closed)
        }

    @Test fun providerFailuresPropagateWithoutManufacturingEvidence() =
        runBlocking {
            val source = Probe(ciphertext, fail = { throw IOException("synthetic provider failure") })
            assertFailsWith<IOException> { IdentityAdoptionVerifiedBlob.read(admission, source) }
            assertTrue(source.closed)
        }

    @Test fun rawSqlTransactionsCannotReachTheProvider() =
        runBlocking {
            val source = Probe(ciphertext)
            withContext(RawJdbcTransactionContext()) {
                assertFailsWith<IdentityLifecycleConflictException> { IdentityAdoptionVerifiedBlob.read(admission, source) }
            }
            assertEquals(0, source.opens)
        }

    private class Probe(
        private val bytes: ByteArray,
        private val declaredSize: Long = bytes.size.toLong(),
        private val fail: (() -> Unit)? = null,
    ) : LogDateBlobStorage by InMemoryLogDateBlobStorage() {
        var opens = 0
        var closed = false
        var largestRead = 0
        var readBytes = 0L

        override fun openBlob(storagePath: String): LogDateBlobStream {
            opens++
            return LogDateBlobStream(
                declaredSize,
                object : ByteArrayInputStream(bytes) {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        fail?.invoke()
                        largestRead = maxOf(largestRead, length)
                        return super.read(buffer, offset, length).also { if (it > 0) readBytes += it }
                    }

                    override fun read(): Int = super.read().also { if (it >= 0) readBytes++ }

                    override fun close() {
                        closed = true
                        super.close()
                    }
                },
            )
        }
    }
}
