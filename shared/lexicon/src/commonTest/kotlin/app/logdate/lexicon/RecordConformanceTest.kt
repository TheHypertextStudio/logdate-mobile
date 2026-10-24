package app.logdate.lexicon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.lexicon.LexiconParser
import studio.hypertext.atproto.lexicon.LexiconRecordValidator
import studio.hypertext.atproto.lexicon.LexiconRegistry
import studio.hypertext.atproto.lexicon.LexiconValidationIssue
import studio.hypertext.atproto.lexicon.data.LexiconData
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DagCborCodec
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared client conformance suite in `conformance/records/`: valid and invalid example records
 * for every `app.logdate.*` record type, generated from the lexicons by
 * `tools/lexicon-check/conformance.mjs`. The Mac and Linux apps run the same files, so what passes
 * here is what every LogDate client must accept and refuse.
 *
 * The fixtures and the schemas are embedded in the test sources at build time, so these tests run
 * on every target. The values in the records are synthetic: they follow the schemas and mean
 * nothing.
 */
class RecordConformanceTest {
    private val validator =
        LexiconRecordValidator(LexiconRegistry(LexiconSchemaFixtures.names.map { LexiconParser.parse(LexiconSchemaFixtures.text(it)) }))
    private val manifest = readJson("manifest.json")
    private val recordFiles =
        manifest
            .getValue("files")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .filter { it.startsWith("records/") }

    @Test
    fun `the suite is a version these tests understand`() {
        assertEquals(SUPPORTED_VERSION, manifest.getValue("version").jsonPrimitive.int)
    }

    @Test
    fun `the suite covers every LogDate record type and every file is read`() {
        val listed = manifest.getValue("recordTypes").jsonArray.map { it.jsonPrimitive.content }
        val generated = LogDateRecords.records.keys.filter { it.startsWith("app.logdate.") }

        assertEquals(generated.sorted(), listed.sorted())
        assertEquals(listed.map { "records/$it.json" }.sorted(), recordFiles.sorted())
        assertEquals(recordFiles.sorted(), RecordConformanceFixtures.names.filter { it.startsWith("records/") }.sorted())
    }

    @Test
    fun `every valid record validates and encodes to the known DAG-CBOR and CID`() {
        forEachSuite { collection, suite ->
            suite.cases("valid").forEach { case ->
                val label = "$collection ${case.name}"
                val record = case.record
                assertEquals(emptyList(), validator.issuesFor(collection, record).map { "${it.path}: ${it.message}" }, "$label: validation")

                val bytes = DagCborCodec.encode(record)
                assertEquals(case.string("dagCbor"), bytes.toHex(), "$label: DAG-CBOR")
                assertEquals(case.string("cid"), Cid.sha256(DAG_CBOR_CODEC, bytes).value, "$label: CID")
                assertEquals(record, DagCborCodec.decode(case.string("dagCbor").hexToBytes()), "$label: decoding the DAG-CBOR")
            }
        }
    }

    @Test
    fun `every valid record round-trips through its generated model`() {
        forEachSuite { collection, suite ->
            suite.cases("valid").filter { it.boolean("modelRoundTrip") ?: true }.forEach { case ->
                @Suppress("UNCHECKED_CAST")
                val serializer = requireNotNull(LogDateRecords.records[collection.toString()]) as KSerializer<Any>
                val decoded = LexiconData.json.decodeFromJsonElement(serializer, case.record)
                val label = "$collection ${case.name}"

                assertEquals(case.record, LexiconData.json.encodeToJsonElement(serializer, decoded), "$label: JSON")
                assertEquals(case.string("dagCbor"), LexiconData.encodeToDagCbor(serializer, decoded).toHex(), "$label: DAG-CBOR")
            }
        }
    }

    @Test
    fun `every invalid record fails with its labeled failure`() {
        forEachSuite { collection, suite ->
            suite.cases("invalid").forEach { case ->
                val failure = case.value.getValue("failure").jsonObject
                val expected = Failure(failure.getValue("kind").jsonPrimitive.content, failure.getValue("path").jsonPrimitive.content)
                val actual = validator.issuesFor(collection, case.record).map { classify(it) }

                assertTrue(expected in actual, "$collection ${case.name}: expected $expected, the validator reported $actual")
            }
        }
    }

    @Test
    fun `record keys follow the record type's key rule`() {
        forEachSuite { collection, suite ->
            val rule =
                suite.value
                    .getValue("recordKey")
                    .jsonPrimitive.content
            val keys = suite.value.getValue("recordKeys").jsonObject
            keys.getValue("valid").jsonArray.map { it.jsonPrimitive.content }.forEach { key ->
                assertTrue(isValidKey(rule, key), "$collection: \"$key\" should be a valid $rule key")
            }
            keys.getValue("invalid").jsonArray.map { it.jsonPrimitive.content }.forEach { key ->
                assertFalse(isValidKey(rule, key), "$collection: \"$key\" should be an invalid $rule key")
            }
        }
    }

    private fun isValidKey(
        rule: String,
        key: String,
    ): Boolean =
        when (rule) {
            "any" -> RecordKey.isValid(key)
            "tid" -> Tid.isValid(key)
            "literal:self" -> key == "self"
            else -> error("Unknown record key rule $rule")
        }

    private fun forEachSuite(check: (Nsid, Suite) -> Unit) {
        assertTrue(recordFiles.size > 30, "Expected a suite per record type, found ${recordFiles.size}")
        recordFiles.forEach { name ->
            val suite = Suite(readJson(name))
            check(
                Nsid.require(
                    suite.value
                        .getValue("collection")
                        .jsonPrimitive.content,
                ),
                suite,
            )
        }
    }

    private fun LexiconRecordValidator.issuesFor(
        collection: Nsid,
        record: JsonObject,
    ): List<LexiconValidationIssue> = validateRecord(collection, record).issues

    private class Suite(
        val value: JsonObject,
    ) {
        fun cases(kind: String): List<Case> = value.getValue(kind).jsonArray.map { Case(it.jsonObject) }
    }

    private class Case(
        val value: JsonObject,
    ) {
        val name: String get() = string("name")
        val record: JsonObject get() = value.getValue("record").jsonObject

        fun string(key: String): String = value.getValue(key).jsonPrimitive.content

        fun boolean(key: String): Boolean? = value[key]?.jsonPrimitive?.boolean
    }

    private data class Failure(
        val kind: String,
        val path: String,
    )

    private companion object {
        const val SUPPORTED_VERSION = 1
        const val DAG_CBOR_CODEC = 0x71

        fun classify(issue: LexiconValidationIssue): Failure = Failure(kindOf(issue), pointer(issue.path))

        /** `blocks[0].text` as the JSON Pointer `/blocks/0/text`. */
        fun pointer(path: String): String =
            path
                .split('.', '[', ']')
                .filter { it.isNotEmpty() }
                .joinToString("") { "/$it" }

        fun kindOf(issue: LexiconValidationIssue): String {
            val message = issue.message
            return when {
                issue.path == "\$type" -> "wrong-record-type"

                message == "required field is missing" -> "missing-required"

                message.startsWith("expected ") ||
                    message.startsWith("not a valid blob") ||
                    message == "not valid bytes" ||
                    message == "not a valid content link" -> "wrong-type"

                message.startsWith("a union member must have") -> "union-member-missing-type"

                message.contains("in this closed union") -> "union-member-not-allowed"

                message.startsWith("longer than") || Regex("^more than \\d+ items$").matches(message) -> "too-long"

                message.startsWith("shorter than") || message.startsWith("fewer than") -> "too-short"

                message.contains("the minimum of") || message.contains("the maximum of") -> "out-of-range"

                message.startsWith("not a valid ") -> "invalid-format"

                message.contains("allowed values") || message.startsWith("must be ") -> "not-allowed-value"

                else -> "unclassified: $message"
            }
        }

        fun readJson(name: String): JsonObject = Json.parseToJsonElement(RecordConformanceFixtures.text(name)).jsonObject

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
