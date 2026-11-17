package app.logdate.client.repository.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptDocumentTest {
    @Test
    fun `plain text joins final segments in timeline order`() {
        val document =
            TranscriptDocument(
                segments =
                    listOf(
                        segment(id = "later", text = "second thought", startMs = 1_000),
                        segment(id = "earlier", text = "First thought.", startMs = 0),
                    ),
            )

        assertEquals("First thought. second thought", document.plainText)
    }

    @Test
    fun `is final is true only when document and segments are final`() {
        val finalDocument =
            TranscriptDocument(
                status = TranscriptDocumentStatus.FINAL,
                segments =
                    listOf(
                        segment(id = "s1", isFinal = true),
                        segment(id = "s2", isFinal = true),
                    ),
            )

        val refiningDocument =
            finalDocument.copy(
                status = TranscriptDocumentStatus.REFINING,
                segments = finalDocument.segments.plus(segment(id = "s3", isFinal = false)),
            )

        assertTrue(finalDocument.isFinal)
        assertFalse(refiningDocument.isFinal)
    }

    private fun segment(
        id: String,
        text: String = "hello",
        startMs: Long = 0,
        source: TranscriptSource = TranscriptSource.LOCAL_LIVE,
        isFinal: Boolean = true,
    ): TranscriptSegment =
        TranscriptSegment(
            segmentId = id,
            text = text,
            startMs = startMs,
            endMs = startMs + 500,
            source = source,
            isFinal = isFinal,
        )
}
