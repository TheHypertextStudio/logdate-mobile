package app.logdate

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.createProjectEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiFactory

/** Enforces shared settings layout, typography and functional row controls. */
object SettingsUiContract {
    private val layouts = setOf("Column", "Row", "MaterialContainer")
    private val rows =
        setOf(
            "SettingsRow",
            "SettingsChoiceItem",
            "SimpleSettingsItem",
            "SettingsNavigationItem",
            "ToggleSettingsItem",
            "LinkedToggleSettingsItem",
            "ExportDataItem",
            "ImportBackupItem",
        )

    fun inspect(
        source: String,
        fileName: String = "",
    ): List<Int> {
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                createProjectEnvironment(
                    CompilerConfiguration(),
                    disposable,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                    MessageCollector.NONE,
                )
            val file = KtPsiFactory(environment.project).createFile(source)
            val aliases =
                file.importDirectives
                    .mapNotNull { directive ->
                        directive.aliasName?.let { it to directive.importedFqName!!.shortName().asString() }
                    }.toMap()
            val name = { call: KtCallExpression ->
                call.calleeExpression
                    ?.text
                    ?.substringAfterLast('.')
                    ?.let { aliases[it] ?: it }
            }
            val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            val settingsImplementation =
                fileName in setOf("ExportOptionsBottomSheet.kt", "RestoreBottomSheet.kt") ||
                    file.packageFqName
                        .asString()
                        .split('.')
                        .contains("settings") ||
                    calls.any { name(it) in setOf("SettingsScaffold", "SettingsGridScaffold", "SettingsSection") }
            val duplicatedRows =
                if (settingsImplementation) {
                    calls.filter { name(it) in setOf("ListItem", "Switch", "RadioButton") }
                } else {
                    emptyList()
                }
            val violations =
                wrappedRows(calls, name) + ungroupedCards(calls, name) + duplicatedRows +
                    typographyOverrides(calls, name) + standaloneActions(calls, name)
            return violations.map { source.take(it.textOffset).count { character -> character == '\n' } + 1 }.distinct().sorted()
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun wrappedRows(
        calls: Collection<KtCallExpression>,
        name: (KtCallExpression) -> String?,
    ): List<KtCallExpression> =
        calls.filter { name(it) == "SettingsSection" }.flatMap { section ->
            section.valueArguments
                .mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
                .flatMap { it.bodyExpression?.statements.orEmpty() }
                .filterIsInstance<KtCallExpression>()
                .filter { name(it) in layouts }
                .filter { wrapper ->
                    PsiTreeUtil.findChildrenOfType(wrapper, KtCallExpression::class.java).count { name(it) in rows } > 1
                }
        }

    private fun ungroupedCards(
        calls: Collection<KtCallExpression>,
        name: (KtCallExpression) -> String?,
    ): List<KtCallExpression> =
        calls
            .filter { name(it) in setOf("Card", "ElevatedCard", "OutlinedCard", "MaterialCard") }
            .filter { card ->
                PsiTreeUtil.findChildrenOfType(card, KtCallExpression::class.java).count { row ->
                    name(row) in rows &&
                        generateSequence(row.parent) { it.parent }
                            .takeWhile { it != card }
                            .none { it is KtCallExpression && name(it) == "SettingsSection" }
                } > 1
            }

    private fun standaloneActions(
        calls: Collection<KtCallExpression>,
        name: (KtCallExpression) -> String?,
    ): List<KtCallExpression> {
        val formSections =
            calls
                .filter {
                    name(it) in setOf("TextField", "OutlinedTextField", "Slider", "RangeSlider", "DatePicker", "TimePicker", "TimeInput")
                }.mapNotNull(::enclosingCall)
                .filter { name(it) == "SettingsSection" }
                .toSet()
        return calls.filter { call ->
            val owner = enclosingCall(call)
            name(call) in setOf("Button", "OutlinedButton", "TextButton", "FilledTonalButton", "ElevatedButton", "IconButton") &&
                owner?.let(name) == "SettingsSection" &&
                owner !in formSections
        }
    }

    private fun enclosingCall(call: KtCallExpression): KtCallExpression? =
        generateSequence(call.parent) { it.parent }.filterIsInstance<KtCallExpression>().firstOrNull()

    private fun typographyOverrides(
        calls: Collection<KtCallExpression>,
        name: (KtCallExpression) -> String?,
    ): List<KtCallExpression> =
        calls.filter { name(it) == "SettingsRow" }.flatMap { row ->
            row.valueArguments
                .filter { it.getArgumentName()?.asName?.asString() == "supportingContent" }
                .mapNotNull { it.getArgumentExpression() }
                .flatMap { PsiTreeUtil.findChildrenOfType(it, KtCallExpression::class.java) }
                .filter { call ->
                    name(call) == "Text" && call.valueArguments.any { it.getArgumentName()?.asName?.asString() == "style" }
                }
        }
}
