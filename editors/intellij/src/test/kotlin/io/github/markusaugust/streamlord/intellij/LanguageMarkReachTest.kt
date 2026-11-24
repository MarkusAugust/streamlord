package io.github.markusaugust.streamlord.intellij

import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.isHtmlString
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.intellij.plugins.intelliLang.inject.InjectedLanguage
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.io.File

/**
 * Which literals `@Language("HTML")` and `// language=HTML` reach, as the analysis reads them, held to what the Kotlin
 * plugin injects HTML into: the editor shows the plugin's reading, and the analysis judges by its own. Each literal of
 * the cases is asked of both, but one whose text opens with a tag, which is HTML whatever marks it. A line that ends in
 * `// missed: #N` holds one or more the plugin injects and the analysis is known not to read, an issue of its own; one
 * the analysis reads that the plugin does not is never let pass, and a marked line with no such miss is told.
 */
private val MISSED = Regex("""// missed: #\d+$""")

class LanguageMarkReachTest : StreamlordTestCase() {
    private val dir = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures/language-marks")

    fun `test the analysis reads a mark as far as the Kotlin plugin injects it`() {
        myFixture.addFileToProject(
            "stubs/Language.kt",
            "package org.intellij.lang.annotations\nannotation class Language(val value: String, val prefix: String = \"\", val suffix: String = \"\")",
        )
        val contributors = LanguageInjectionContributor.INJECTOR_EXTENSION.allForLanguage(KotlinLanguage.INSTANCE)
        val analyzer = StreamlordAnalysis.getInstance(project).analyzer
        val differences = ArrayList<String>()
        val known = HashSet<Pair<String, Int>>()
        for (case in dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }) {
            val src = case.readText()
            myFixture.configureByText(case.name, src)
            val strings = analyzer.lex(src).strings.associateBy { it.start }
            val lines = src.lines()
            val marked = (1..lines.size).filter { MISSED.containsMatchIn(lines[it - 1]) }.toSet()
            for (host in PsiTreeUtil.collectElementsOfType(myFixture.file, KtStringTemplateExpression::class.java)) {
                val s = strings[host.textRange.startOffset] ?: continue
                if (s.unterminated || src.substring(s.start, s.end).trimStart('$', '"').startsWith("<")) continue
                // A sum is injected whole.
                var whole: PsiElement = host
                while (whole.parent.let { it is KtBinaryExpression && it.operationToken == KtTokens.PLUS }) whole = whole.parent
                // The language a mark names is looked up as IntelliJ looks it up: `// language=html` is HTML.
                val plugin =
                    contributors.any { c ->
                        listOf(host, whole).any { e ->
                            c.getInjection(e)?.let { InjectedLanguage.findLanguageById(it.injectedLanguageId)?.id } ==
                                "HTML"
                        }
                    }
                val analysis = isHtmlString(src, s)
                if (plugin != analysis) {
                    val line = src.substring(0, s.end).count { it == '\n' } + 1
                    if (plugin && line in marked) {
                        known += case.name to line
                        continue
                    }
                    val reads = if (plugin) "the plugin injects, the analysis does not" else "the analysis reads, the plugin does not"
                    differences += "${case.name}:$line ${src.substring(s.start, s.end)}: $reads"
                }
            }
            for (line in marked) if (case.name to line !in known) differences += "${case.name}:$line: marked missed, but read alike"
        }
        assertTrue(differences.joinToString("\n", "${differences.size} literals read otherwise:\n"), differences.isEmpty())
    }
}
