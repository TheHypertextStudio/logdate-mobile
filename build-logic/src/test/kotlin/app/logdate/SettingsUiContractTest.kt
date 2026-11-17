package app.logdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsUiContractTest {
    @Test
    fun `direct rows preserve section spacing`() {
        val source = "fun Example() { SettingsSection { SimpleSettingsItem(); SettingsNavigationItem() } }"
        assertTrue(SettingsUiContract.inspect(source).isEmpty())
    }

    @Test
    fun `layout wrappers around multiple rows lose section spacing`() {
        val source = "fun Example() { SettingsSection { Column { ExportDataItem(); ImportBackupItem() } } }"
        assertEquals(listOf(1), SettingsUiContract.inspect(source))
    }

    @Test
    fun `aliases and conditional rows cannot bypass the rule`() {
        val source =
            """
            import androidx.compose.foundation.layout.Column as Stack
            import app.logdate.ui.common.SimpleSettingsItem as Item
            fun Example() { SettingsSection { Stack { Item(); if (enabled) Item() } } }
            """.trimIndent()
        assertEquals(listOf(3), SettingsUiContract.inspect(source))
    }

    @Test
    fun `forms and text mentioning components are allowed`() {
        val source =
            """
            fun Example() {
                SettingsSection { Column { Text("SimpleSettingsItem()"); Slider() } }
                // SettingsSection { Column { SimpleSettingsItem(); SimpleSettingsItem() } }
            }
            """.trimIndent()
        assertTrue(SettingsUiContract.inspect(source).isEmpty())
    }

    @Test
    fun `named content uses the same grouping contract`() {
        val source = "fun Example() { SettingsSection(content = { Row { ToggleSettingsItem(); LinkedToggleSettingsItem() } }) }"
        assertEquals(listOf(1), SettingsUiContract.inspect(source))
    }

    @Test
    fun `one row inside a form is allowed`() {
        val source = "fun Example() { SettingsSection { Column { SimpleSettingsItem(); Text() } } }"
        assertTrue(SettingsUiContract.inspect(source).isEmpty())
    }

    @Test
    fun `settings implementations cannot bypass the shared row base`() {
        val source =
            """
            package app.logdate.feature.core.settings.ui
            import androidx.compose.material3.ListItem as RawRow
            fun Example() { RawRow(headlineContent = { Text("Title") }) }
            """.trimIndent()
        assertEquals(listOf(3), SettingsUiContract.inspect(source))
    }

    @Test
    fun `ordinary content lists outside settings remain allowed`() {
        val source = "package app.logdate.feature.timeline; fun Entries() { ListItem() }"
        assertTrue(SettingsUiContract.inspect(source).isEmpty())
    }

    @Test
    fun `supporting typography cannot override the shared role`() {
        val source =
            """
            package app.logdate.feature.core.settings.ui
            fun Example() { SettingsRow(supportingContent = { Text("Description", style = MaterialTheme.typography.bodySmall) }) }
            """.trimIndent()
        assertEquals(listOf(2), SettingsUiContract.inspect(source))
    }

    @Test
    fun `settings scaffolds outside a settings package use shared rows`() {
        val source =
            """
            package app.logdate.feature.events.ui.calendarsync
            fun Calendars() { SettingsScaffold { ListItem() } }
            """.trimIndent()
        assertEquals(listOf(2), SettingsUiContract.inspect(source))
    }

    @Test
    fun `a settings grid outside a settings package uses shared rows too`() {
        val source =
            """
            package app.logdate.feature.library.ui
            fun Cleanup() { SettingsGridScaffold { item { Switch() } } }
            """.trimIndent()
        assertEquals(listOf(2), SettingsUiContract.inspect(source))
    }

    @Test
    fun `boolean and choice controls use the shared functional wrappers`() {
        val source =
            """
            package app.logdate.feature.core.settings.ui
            fun Example() { Row { Text("Title"); Switch(); RadioButton() } }
            """.trimIndent()
        assertEquals(listOf(2), SettingsUiContract.inspect(source))
    }

    @Test
    fun `local cards cannot group multiple shared preference rows`() {
        val source =
            """
            import androidx.compose.material3.Card as Panel
            fun Example() { Panel { Column { SettingsChoiceItem(); SettingsChoiceItem() } } }
            """.trimIndent()
        assertEquals(listOf(2), SettingsUiContract.inspect(source))
    }

    @Test
    fun `summary cards and proper settings sections remain allowed`() {
        val source =
            """
            fun Example() {
                MaterialCard { Text("Summary"); Button() }
                Card { SimpleSettingsItem(); Text("Summary") }
                Card { SettingsSection { SettingsChoiceItem(); SettingsChoiceItem() } }
                SettingsSection { SettingsChoiceItem(); SettingsChoiceItem() }
            }
            """.trimIndent()
        assertTrue(SettingsUiContract.inspect(source).isEmpty())
    }

    @Test
    fun `standalone conditional buttons cannot replace a settings row`() {
        val source =
            """
            import androidx.compose.material3.OutlinedButton as ExportButton
            fun Example() {
                SettingsSection { if (canExport) ExportButton(onClick = openExport) { Text("Export") } }
            }
            """.trimIndent()
        assertEquals(listOf(3), SettingsUiContract.inspect(source))
    }

    @Test
    fun `named section content rejects standalone action controls`() {
        val source = "fun Example() { SettingsSection(content = { Button(onClick = openExport) { Text(\"Export\") } }) }"
        assertEquals(listOf(1), SettingsUiContract.inspect(source))
    }

    @Test
    fun `compound row actions and specialized forms retain their buttons`() {
        val source =
            """
            fun Example() {
                SettingsSection {
                    SettingsRow(trailingContent = { Button(onClick = removePasskey) { Text("Remove") } })
                    Column { OutlinedTextField(value, update); Button(onClick = submit) { Text("Save") } }
                }
            }
            """.trimIndent()
        assertTrue(SettingsUiContract.inspect(source).isEmpty())
        val directForm =
            """
            import androidx.compose.material3.OutlinedTextField as Input
            fun Form() { SettingsSection { Input(value, update); Button(onClick = save) {}; TextButton(onClick = clear) {} } }
            """.trimIndent()
        assertTrue(SettingsUiContract.inspect(directForm).isEmpty())
    }

    @Test
    fun `archive option sheets retain the contract outside settings packages`() {
        listOf("ExportOptionsBottomSheet.kt" to "export", "RestoreBottomSheet.kt" to "restore").forEach { (fileName, feature) ->
            val source =
                """
                package app.logdate.feature.core.$feature
                import androidx.compose.material3.Switch as OptionSwitch
                fun Options() { ListItem(trailingContent = { OptionSwitch() }) }
                """.trimIndent()
            assertEquals(listOf(3), SettingsUiContract.inspect(source, fileName))
        }
    }

    @Test
    fun `archive sheet contract does not ban unrelated compound content`() {
        val source = "fun OtherContent() { ListItem(trailingContent = { Switch() }) }"
        assertTrue(SettingsUiContract.inspect(source, "OtherContent.kt").isEmpty())
    }
}
