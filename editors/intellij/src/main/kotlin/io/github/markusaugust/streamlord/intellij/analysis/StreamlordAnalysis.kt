package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.markusaugust.streamlord.analysis.AnalyzeOptions
import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.Catalog
import io.github.markusaugust.streamlord.analysis.Issue
import io.github.markusaugust.streamlord.analysis.SiteIssues
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtFile

/**
 * The analysis, bound to the project's settings and cached per file: every inspection, the
 * annotator and the quick fixes read the same result for the same text.
 */
@Service(Service.Level.PROJECT)
class StreamlordAnalysis(
    private val project: Project,
) {
    val analyzer: Analyzer = Analyzer(Catalog.default)
    val catalog: Catalog get() = analyzer.catalog

    private val settings: StreamlordSettings get() = StreamlordSettings.getInstance(project)

    val prefix: String get() = settings.attributePrefix

    fun options(): AnalyzeOptions = AnalyzeOptions(prefix = prefix, checkHtmlAttributes = true)

    /** The issues of a Kotlin file, by call site or HTML string, with calls that resolve to something other than Streamlord left out. */
    fun kotlinIssues(file: KtFile): List<SiteIssues> =
        CachedValuesManager.getCachedValue(file) {
            val prefix = prefix
            val sites = analyzer.analyzeKotlinSites(file.text, AnalyzeOptions(prefix, true))
            val kept = sites.filter { s -> s.site?.let { StreamlordResolve.mayBeStreamlord(file, it) } ?: true }
            CachedValueProvider.Result.create(kept, file, settings.tracker)
        }

    /** The issues of an HTML document or template. */
    fun htmlIssues(file: PsiFile): List<Issue> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(analyzer.analyzeHtml(file.text, options()), file, settings.tracker)
        }

    companion object {
        fun getInstance(project: Project): StreamlordAnalysis = project.service()

        /** The HTML side applies to HTML and XHTML files, and to the HTML view of a template file, but not to HTML injected into Kotlin. */
        fun isHtmlSide(file: PsiFile): Boolean {
            if (!file.language.isKindOf(HTMLLanguage.INSTANCE)) return false
            val manager = InjectedLanguageManager.getInstance(file.project)
            if (!manager.isInjectedFragment(file)) return true
            // Inside a Kotlin string the Kotlin side judges the text, with the exact offsets; the injected copy stays quiet.
            return manager.getInjectionHost(file)?.containingFile !is KtFile
        }

        /**
         * A template file no plugin has given an HTML tree (a `.jte` or `.ftl` in Community, say) is judged as text: the
         * analysis reads the markup itself and knows the engines' syntax. Only the base file of the document, and only when
         * no HTML view exists, so a plugin that does provide one is never doubled.
         */
        fun isTemplateText(file: PsiFile): Boolean {
            val vf = file.viewProvider.virtualFile
            val extension = vf.extension?.lowercase() ?: return false
            if (extension !in StreamlordSettings.getInstance(file.project).templateExtensions) return false
            val provider = file.viewProvider
            if (provider.getPsi(provider.baseLanguage) !== file) return false
            return provider.allFiles.none { it.language.isKindOf(HTMLLanguage.INSTANCE) }
        }

        /** Either side of markup: an HTML tree, or a template read as text. */
        fun isMarkupSide(file: PsiFile): Boolean = isHtmlSide(file) || isTemplateText(file)
    }
}
