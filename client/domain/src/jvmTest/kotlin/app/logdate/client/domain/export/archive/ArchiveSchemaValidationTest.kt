package app.logdate.client.domain.export.archive

import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.archive.support.ArchiveSamples
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the published JSON Schemas against real output from the DTOs, and against documents that
 * must be rejected, so a schema that accepts everything cannot pass.
 */
class ArchiveSchemaValidationTest {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    private fun errors(
        schemaPath: ArchivePath,
        instance: String,
        strict: Boolean = false,
    ): List<String> {
        val text = ArchiveSchemas.files.getValue(schemaPath)
        val schema = registry.getSchema(if (strict) strictCopy(text) else text)
        return schema.validate(instance, InputFormat.JSON).map { it.message }
    }

    /** The same schema with `additionalProperties: false` on every object, so an unlisted field is an error. */
    private fun strictCopy(schemaText: String): String =
        Json.encodeToString(JsonElement.serializer(), strict(Json.parseToJsonElement(schemaText)))

    private fun strict(element: JsonElement): JsonElement =
        when (element) {
            is JsonArray -> {
                JsonArray(element.map(::strict))
            }

            is JsonObject -> {
                val children = element.mapValues { strict(it.value) }
                val isObjectSchema = (element["type"] as? JsonPrimitive)?.content == "object" && "properties" in element
                JsonObject(if (isObjectSchema) children + ("additionalProperties" to JsonPrimitive(false)) else children)
            }

            else -> {
                element
            }
        }

    private fun <T> json(
        serializer: KSerializer<T>,
        value: T,
    ) = ArchiveJson.document.encodeToString(serializer, value)

    @Test
    fun `every maximal sample validates against its schema, including a strict copy that rejects unlisted fields`() {
        val cases =
            mapOf(
                ArchiveLayout.SCHEMA_MANIFEST to json(ArchiveManifest.serializer(), ArchiveSamples.manifest),
                ArchiveLayout.SCHEMA_JOURNALS to json(ArchiveJournalFile.serializer(), ArchiveSamples.journalFile),
                ArchiveLayout.SCHEMA_NOTES to json(ArchiveNoteFile.serializer(), ArchiveSamples.noteFile),
                ArchiveLayout.SCHEMA_DRAFTS to json(ArchiveDraftFile.serializer(), ArchiveSamples.draftFile),
                ArchiveLayout.SCHEMA_PLACES to json(ArchivePlaceFile.serializer(), ArchiveSamples.placeFile),
                ArchiveLayout.SCHEMA_PROFILE to json(ArchiveProfileFile.serializer(), ArchiveSamples.profileFile),
                ArchiveLayout.SCHEMA_LOCATION_SAMPLE to
                    ArchiveJson.line.encodeToString(ArchiveLocationSample.serializer(), ArchiveSamples.locationSample),
                ArchiveLayout.SCHEMA_MEDIA to json(ArchiveMediaFile.serializer(), ArchiveSamples.mediaFile),
            )

        cases.forEach { (schemaPath, instance) ->
            assertEquals(emptyList(), errors(schemaPath, instance), "$schemaPath rejects its own sample")
            assertEquals(emptyList(), errors(schemaPath, instance, strict = true), "$schemaPath forgot a field the DTO writes")
        }
    }

    @Test
    fun `every schema written to the archive is a draft 2020-12 document`() {
        assertEquals(8, ArchiveSchemas.files.size)
        ArchiveSchemas.files.forEach { (path, text) ->
            val schema = Json.parseToJsonElement(text) as JsonObject
            assertEquals("https://json-schema.org/draft/2020-12/schema", (schema.getValue("\$schema") as JsonPrimitive).content, "$path")
        }
    }

    private val validNote =
        """{"id":"0b6c1d2e-3f4a-4b5c-8d6e-7f8091a2b3c4","type":"text","createdAt":"2026-09-18T03:30:05Z","updatedAt":"2026-09-18T03:30:05Z","journalIds":[]}"""

    private fun noteFile(note: String) = """{"notes":[$note]}"""

    @Test
    fun `a minimal note is valid`() {
        assertEquals(emptyList(), errors(ArchiveLayout.SCHEMA_NOTES, noteFile(validNote)))
    }

    @Test
    fun `an included media file without a path is rejected`() {
        val note = validNote.replace("\"journalIds\"", "\"media\":{\"status\":\"included\",\"mediaType\":\"image/jpeg\"},\"journalIds\"")

        assertTrue(errors(ArchiveLayout.SCHEMA_NOTES, noteFile(note)).isNotEmpty())
    }

    @Test
    fun `an omitted media file must say why`() {
        val note = validNote.replace("\"journalIds\"", "\"media\":{\"status\":\"omitted\"},\"journalIds\"")

        assertTrue(errors(ArchiveLayout.SCHEMA_NOTES, noteFile(note)).isNotEmpty())
    }

    @Test
    fun `a media path that climbs out of the archive or names a device is rejected`() {
        val unsafePaths =
            listOf(
                "../../etc/passwd",
                "/data/user/0/app/files/a.jpg",
                "content://media/external/1",
                "C:\\\\Users\\\\me\\\\a.jpg",
            )
        unsafePaths.forEach { path ->
            val note =
                validNote.replace(
                    "\"journalIds\"",
                    "\"media\":{\"status\":\"included\",\"path\":\"$path\",\"mediaType\":\"image/jpeg\"},\"journalIds\"",
                )

            assertTrue(errors(ArchiveLayout.SCHEMA_NOTES, noteFile(note)).isNotEmpty(), "accepted $path")
        }
    }

    @Test
    fun `an id that is not a lowercase uuid is rejected`() {
        assertTrue(
            errors(
                ArchiveLayout.SCHEMA_NOTES,
                noteFile(validNote.replace("0b6c1d2e-3f4a-4b5c-8d6e-7f8091a2b3c4", "NOT-A-UUID")),
            ).isNotEmpty(),
        )
        assertTrue(
            errors(
                ArchiveLayout.SCHEMA_NOTES,
                noteFile(validNote.replace("0b6c1d2e-3f4a-4b5c-8d6e-7f8091a2b3c4", "0B6C1D2E-3F4A-4B5C-8D6E-7F8091A2B3C4")),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `a timestamp with an offset instead of Z is rejected`() {
        assertTrue(
            errors(
                ArchiveLayout.SCHEMA_NOTES,
                noteFile(validNote.replace("2026-09-18T03:30:05Z\",\"updatedAt", "2026-09-17T21:30:05-06:00\",\"updatedAt")),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `an unknown note type is rejected`() {
        assertTrue(errors(ArchiveLayout.SCHEMA_NOTES, noteFile(validNote.replace("\"text\"", "\"podcast\""))).isNotEmpty())
    }

    @Test
    fun `a location outside the globe is rejected`() {
        val note = validNote.replace("\"journalIds\"", "\"location\":{\"latitude\":91.0,\"longitude\":0.0},\"journalIds\"")

        assertTrue(errors(ArchiveLayout.SCHEMA_NOTES, noteFile(note)).isNotEmpty())
    }

    @Test
    fun `a semantic place can retain only its known identity`() {
        val details = "\"locationDetails\":{\"place\":{\"id\":\"d7042fbb-485c-44f1-8f74-7083b7074394\"}},"
        val note = validNote.replace("\"journalIds\"", details + "\"journalIds\"")

        assertEquals(emptyList(), errors(ArchiveLayout.SCHEMA_NOTES, noteFile(note), strict = true))
    }

    @Test
    fun `a semantic place cannot have half a center`() {
        val details = "\"locationDetails\":{\"place\":{\"id\":\"d7042fbb-485c-44f1-8f74-7083b7074394\",\"latitude\":36.17}},"
        val note = validNote.replace("\"journalIds\"", details + "\"journalIds\"")

        assertTrue(errors(ArchiveLayout.SCHEMA_NOTES, noteFile(note)).isNotEmpty())
    }

    @Test
    fun `the earlier 2 0 manifest remains readable`() {
        val manifest = json(ArchiveManifest.serializer(), ArchiveSamples.manifest.copy(schemaVersion = ExportSchemaVersion.V2_0))

        assertEquals(emptyList(), errors(ArchiveLayout.SCHEMA_MANIFEST, manifest))
    }

    @Test
    fun `a media inventory entry needs a sha-256 of the right length`() {
        val entry = """{"files":[{"path":"media/a.jpg","mediaType":"image/jpeg","bytes":1,"sha256":"abc"}]}"""

        assertTrue(errors(ArchiveLayout.SCHEMA_MEDIA, entry).isNotEmpty())
    }

    @Test
    fun `a manifest from the 1 x line is not a 2 x manifest`() {
        val manifest = json(ArchiveManifest.serializer(), ArchiveSamples.manifest.copy(schemaVersion = ExportSchemaVersion.V1_2))

        assertTrue(errors(ArchiveLayout.SCHEMA_MANIFEST, manifest).isNotEmpty())
    }
}
