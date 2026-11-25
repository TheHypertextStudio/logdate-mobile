package app.logdate

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.createProjectEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

/** Parses Kotlin, including import aliases, and inspects every statement of each scoped Home root. */
object HomeWorkspaceContract {
    fun inspect(
        source: String,
        roots: Set<String>,
    ): List<String> {
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
            val functions = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).filter { it.name in roots }
            val missing = roots.filter { root -> functions.none { it.name == root } }.map { "$it|missing-root|1" }
            return missing +
                functions.flatMap { function ->
                    val scopes = listOfNotNull(function.bodyExpression)
                    scopes
                        .flatMap { scope ->
                            PsiTreeUtil.findChildrenOfType(scope, KtCallExpression::class.java).mapNotNull { call ->
                                val name = call.calleeExpression?.text?.substringAfterLast('.') ?: return@mapNotNull null
                                val resolved = aliases[name] ?: name
                                val rule =
                                    when {
                                        resolved in
                                            setOf(
                                                "currentWindowAdaptiveInfoV2",
                                                "currentWindowAdaptiveInfo",
                                                "rememberFoldableLayoutInfo",
                                                "calculateFoldableSplitLayout",
                                                "FoldableBookLayout",
                                                "FoldableTabletopLayout",
                                            ) -> "global-layout"

                                        resolved.contains("adaptivePanelShape", ignoreCase = true) -> "panel-shape"

                                        resolved in
                                            setOf(
                                                "JournalSearchToolbar",
                                                "LibraryTopBar",
                                                "SearchAppBar",
                                                "SearchBar",
                                                "ExpandedFullScreenSearchBar",
                                            ) -> "destination-search"

                                        resolved in setOf("Surface", "Scaffold") && !withinContentComponent(call, scope) -> "outer-frame"

                                        resolved in setOf("background", "clip") && !withinContentComponent(call, scope) -> "raw-frame-style"

                                        else -> null
                                    }
                                rule?.let { "${function.name}|$it|${source.take(call.textOffset).count { char -> char == '\n' } + 1}" }
                            } +
                                PsiTreeUtil.findChildrenOfType(scope, KtNameReferenceExpression::class.java).mapNotNull { reference ->
                                    val name = aliases[reference.getReferencedName()] ?: reference.getReferencedName()
                                    if (name in setOf("LocalWindowInfo", "LocalConfiguration")) {
                                        "${function.name}|global-layout|${source.take(reference.textOffset).count { it == '\n' } + 1}"
                                    } else {
                                        null
                                    }
                                }
                        }.distinct()
                }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun withinContentComponent(
        call: PsiElement,
        scope: PsiElement,
    ): Boolean {
        var parent = call.parent
        while (parent != null && parent != scope) {
            if (parent is KtCallExpression &&
                parent.calleeExpression?.text in setOf("item", "items", "itemsIndexed", "IconButton", "PanelHeader", "PanelGroup")
            ) {
                return true
            }
            parent = parent.parent
        }
        return false
    }
}
