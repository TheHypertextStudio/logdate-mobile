package studio.hypertext.atproto.lexicon.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexiconModelsPluginTest {
    @Test
    fun `the generator gets sorted inputs, the layout flag and the record index`() {
        val arguments =
            lexiconGeneratorArguments(
                schemas = listOf(File("/repo/lexicons/community"), File("/repo/lexicons/app/logdate")),
                outputDirectory = File("/repo/build/generated"),
                basePackage = "app.logdate.lexicon",
                namespaceLayout = true,
                recordIndex = "LogDateRecords",
            )

        assertEquals(
            listOf(
                "/repo/lexicons/app/logdate${File.pathSeparator}/repo/lexicons/community",
                "/repo/build/generated",
                "app.logdate.lexicon",
                "--namespace-layout",
                "--record-index",
                "LogDateRecords",
            ),
            arguments,
        )
    }

    @Test
    fun `a schema index is passed after the record index`() {
        val arguments =
            lexiconGeneratorArguments(
                schemas = listOf(File("/repo/fixture.json")),
                outputDirectory = File("/out"),
                basePackage = "app.logdate.lexicon",
                namespaceLayout = false,
                recordIndex = "LogDateRecords",
                schemaIndex = "LogDateSchemas",
            )

        assertEquals(
            listOf(
                "/repo/fixture.json",
                "/out",
                "app.logdate.lexicon",
                "--record-index",
                "LogDateRecords",
                "--schema-index",
                "LogDateSchemas",
            ),
            arguments,
        )
    }

    @Test
    fun `a flat layout without an index passes only the positional arguments`() {
        val arguments =
            lexiconGeneratorArguments(
                schemas = listOf(File("/repo/fixture.json")),
                outputDirectory = File("/out"),
                basePackage = "com.example.fixtures",
                namespaceLayout = false,
                recordIndex = null,
            )

        assertEquals(listOf("/repo/fixture.json", "/out", "com.example.fixtures"), arguments)
    }

    @Test
    fun `the default generator is the published library at the plugin's own version`() {
        val (group, name, version) = LexiconPluginCoordinates.generator.split(':')

        assertEquals("atproto-lexicon", name)
        assertTrue(group.isNotBlank() && version.isNotBlank() && version != "null", LexiconPluginCoordinates.generator)
    }
}
