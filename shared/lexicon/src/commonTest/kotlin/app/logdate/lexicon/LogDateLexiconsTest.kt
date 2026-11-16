package app.logdate.lexicon

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.LexiconParser
import studio.hypertext.atproto.lexicon.LexiconType
import studio.hypertext.atproto.lexicon.LexiconValidator
import studio.hypertext.atproto.syntax.Nsid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The schemas built into the app check records on every platform, with no schema files to read. */
class LogDateLexiconsTest {
    @Test
    fun `every LogDate and community schema parses and passes the checker`() {
        val documents = LogDateSchemas.documents.map(LexiconParser::parse)

        val issues =
            documents.flatMap { document ->
                LexiconValidator.validate(document, LogDateLexicons.registry).issues.map { "${document.id} ${it.path}: ${it.message}" }
            }

        assertTrue(documents.size > 40, "Expected the whole family, found ${documents.size} schemas")
        assertEquals(emptyList(), issues)
    }

    @Test
    fun `every LogDate record type is parsed as a record with a key rule`() {
        val records =
            LogDateSchemas.documents
                .map(LexiconParser::parse)
                .filter { it.id.toString().startsWith("app.logdate.") }
                .mapNotNull { document -> document.definitions["main"]?.takeIf { it.type == LexiconType.RECORD } }

        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.recordKey != null && it.record?.type == LexiconType.OBJECT })
    }

    @Test
    fun `every record type the models know has its schema built in`() {
        LogDateRecords.records.keys.forEach { collection ->
            assertNotNull(LogDateLexicons.registry.document(Nsid.require(collection)), collection)
        }
    }

    @Test
    fun `a valid journal passes and one without its title is reported`() {
        val journal = Nsid.require("app.logdate.journal.journal")
        val valid =
            buildJsonObject {
                put("\$type", journal.toString())
                put("title", "Weekend walks")
                put("createdAt", "2026-10-16T18:00:00.000Z")
            }
        val missingTitle =
            buildJsonObject {
                put("\$type", journal.toString())
                put("createdAt", "2026-10-16T18:00:00.000Z")
            }

        assertEquals(emptyList(), LogDateLexicons.validator.validateRecord(journal, valid).issues)
        val issues = LogDateLexicons.validator.validateRecord(journal, missingTitle).issues
        assertTrue(issues.any { it.path == "title" }, issues.toString())
    }
}
