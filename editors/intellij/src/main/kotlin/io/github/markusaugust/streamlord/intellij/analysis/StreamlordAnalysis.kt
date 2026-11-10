package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.markusaugust.streamlord.analysis.AnalyzeOptions
import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.CallSite
import io.github.markusaugust.streamlord.analysis.Catalog
import io.github.markusaugust.streamlord.analysis.Issue
import io.github.markusaugust.streamlord.analysis.SignalReference
import io.github.markusaugust.streamlord.analysis.SiteIssues
import io.github.markusaugust.streamlord.analysis.ValueFact
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    /** The options for [file], with which of Streamlord's modules it can use; with no file, what the text alone says. */
    fun options(file: PsiFile? = null): AnalyzeOptions =
        AnalyzeOptions(
            prefix = prefix,
            checkHtmlAttributes = true,
            htmlDsl = file?.let { ProjectLibraries.known(it, StreamlordLibrary.HTML) },
            core = file?.let { ProjectLibraries.known(it, StreamlordLibrary.CORE) },
        )

    /**
     * The issues of a Kotlin file, by call site or HTML string, each value joined into an HTML string judged by what the Kotlin
     * plugin knows of its type. A call that resolves to something other than Streamlord is read as any call is, its HTML string
     * a free one. The text is judged again when the file, the settings or the project's modules change; an edit elsewhere types
     * the holes again, and judges them again only when a fact comes out different.
     */
    fun kotlinIssues(file: KtFile): List<SiteIssues> {
        val judged = judgedText(file)
        if (judged.issues.none { it.site != null }) return judged.issues
        val resolved = resolved(file)
        val foreign = foreignCalls(file, resolved, judged.issues.mapNotNull { it.site })
        if (foreign.isEmpty()) return judged.issues
        resolved.issues?.takeIf { it.first === judged && it.second == foreign }?.let { return it.third }
        val facts = judged.facts
        val options = judged.options.copy(foreignCalls = foreign, valueFact = { start, end -> facts[TextRange(start, end)] })
        val kept = analyzer.analyzeKotlinSites(file.text, options)
        resolved.issues = Triple(judged, foreign, kept)
        return kept
    }

    /** The issues of the text alone, as the facts and the modules the file can use give them. */
    private fun judgedText(file: KtFile): Judged {
        val facts = holeFacts(file)
        val options = options(file)
        val memo =
            CachedValuesManager.getCachedValue(
                file,
            ) { CachedValueProvider.Result.create(AtomicReference<Judged>(), file, settings.tracker) }
        memo.get()?.takeIf { it.facts == facts && it.options == options }?.let { return it }
        val judged =
            Judged(
                facts,
                options,
                analyzer.analyzeKotlinSites(file.text, options.copy(valueFact = { start, end -> facts[TextRange(start, end)] })),
            )
        memo.set(judged)
        return judged
    }

    /**
     * What resolving the calls of [file] found, and the second pass each verdict led to. A call comes to resolve elsewhere
     * through this file's own text, its imports among it, or through a change of the project's libraries, so the verdicts are
     * kept until one of those changes; a declaration moved in another file is seen at this file's next edit, as before.
     * Indexing starting or ending does not touch the libraries' tracker, so it counts too.
     */
    private fun resolved(file: KtFile): Resolved =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                Resolved(),
                file,
                settings.tracker,
                ProjectRootManager.getInstance(project),
                DumbService.getInstance(project).modificationTracker,
            )
        }

    /** The verdicts of [resolved], and the second passes that follow from them. */
    private class Resolved {
        /** Whether the call whose name starts at an offset resolves to something other than Streamlord. */
        val foreign = ConcurrentHashMap<Int, Boolean>()

        /** The issues of a first pass, the calls found foreign in it, and the issues with those named. */
        @Volatile
        var issues: Triple<Judged, Set<Int>, List<SiteIssues>>? = null

        /** The same for the signal references. */
        @Volatile
        var references: Triple<List<SignalReference>, Set<Int>, List<SignalReference>>? = null
    }

    /**
     * Where the name of each of [sites] starts that the Kotlin plugin resolves to something other than Streamlord. Each call
     * is resolved once for the verdicts of [resolved]; while the IDE indexes, none is.
     */
    private fun foreignCalls(
        file: KtFile,
        resolved: Resolved,
        sites: List<CallSite>,
    ): Set<Int> {
        if (DumbService.isDumb(project)) return emptySet()
        val out = HashSet<Int>()
        for (site in sites) {
            val foreign =
                resolved.foreign[site.nameStart] ?: (!StreamlordResolve.mayBeStreamlord(file, site)).also {
                    UiThreadWork.note()
                    resolves.incrementAndGet()
                    resolved.foreign[site.nameStart] = it
                }
            if (foreign) out += site.nameStart
        }
        return out
    }

    /**
     * Where the name starts of each of [sites] that the Kotlin plugin resolves to something other than Streamlord, for colouring,
     * spelling, completion and hover, which pass over every call and not only those with an issue. A call is resolved only when
     * there is a reason to doubt it: it is made on a receiver, or the file declares its name or imports it from another package.
     * Any other is taken by its name unless the diagnostics resolved it already, and a star import is no reason, since
     * kotlinx.html's own is in nearly every file.
     */
    fun foreignAmong(
        file: KtFile,
        sites: List<CallSite>,
    ): Set<Int> {
        // Nothing is resolved on the UI thread, where a typed handler or an action may ask: there the names decide.
        if (sites.isEmpty() || ApplicationManager.getApplication().isDispatchThread) return emptySet()
        val src = file.text
        val elsewhere = namesFromElsewhere(file)
        // A call the diagnostics resolve keeps their verdict, so the two never disagree on it; they are cached, and
        // highlighting runs them anyway.
        kotlinIssues(file)
        val resolved = resolved(file)
        val known = sites.filter { resolved.foreign[it.nameStart] == true }.mapTo(HashSet()) { it.nameStart }
        val doubted = sites.filter { it.nameStart !in known && (it.name in elsewhere || onReceiver(src, it.nameStart)) }
        if (doubted.isEmpty()) return known
        return known + foreignCalls(file, resolved, doubted)
    }

    /** Whether the innermost call with one of [names] whose parentheses hold [offset] resolves to something other than Streamlord. */
    fun isForeignCallAt(
        file: KtFile,
        offset: Int,
        names: Set<String>,
    ): Boolean {
        val src = file.text
        val site =
            findCallSites(src, names, analyzer.lex(src)).lastOrNull { it.openParen < offset && offset <= it.closeParen } ?: return false
        return foreignAmong(file, listOf(site)).isNotEmpty()
    }

    /** Whether the call whose name starts at [nameStart] is made on a receiver: `tag.dataText(...)`. */
    private fun onReceiver(
        src: String,
        nameStart: Int,
    ): Boolean {
        var i = nameStart - 1
        while (i >= 0 && src[i].isWhitespace()) i--
        return i >= 0 && src[i] == '.'
    }

    /** The names [file] declares as functions or imports from outside Streamlord, by an import of its own or an alias. */
    private fun namesFromElsewhere(file: KtFile): Set<String> =
        CachedValuesManager.getCachedValue(file) {
            val src = file.text
            val names = HashSet<String>()
            FUNCTION_NAME.findAll(src).mapTo(names) { it.groupValues[1] }
            for (m in IMPORT.findAll(src)) {
                val path = m.groupValues[1].replace("`", "")
                if (path.startsWith(StreamlordResolve.PACKAGE)) continue
                names += m.groupValues[2].ifEmpty { path.substringAfterLast('.') }
            }
            CachedValueProvider.Result.create(names, file)
        }

    /** How many calls have been resolved, for a test to see that an edit elsewhere resolves none again. */
    internal val resolves = AtomicInteger()

    /** The facts of a file's holes; what a value's type is can change with any file in the project. None while the IDE indexes. */
    internal fun holeFacts(file: KtFile): Map<TextRange, ValueFact> =
        CachedValuesManager.getCachedValue(file) {
            val dumb = DumbService.getInstance(project)
            val facts = if (dumb.isDumb) emptyMap() else HoleFacts.of(file)
            CachedValueProvider.Result.create(facts, PsiModificationTracker.getInstance(project), dumb.modificationTracker)
        }

    /** A first pass: the facts and the options it was run with, and the issues they gave. */
    private class Judged(
        val facts: Map<TextRange, ValueFact>,
        val options: AnalyzeOptions,
        val issues: List<SiteIssues>,
    )

    /** The issues of an HTML document or template. */
    fun htmlIssues(file: PsiFile): List<Issue> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(analyzer.analyzeHtml(file.text, options()), file, settings.tracker)
        }

    /** The options a Kotlin file's text is read with, and the signals its expressions read, by the text alone. */
    private fun kotlinSignalReferences(file: KtFile): Pair<AnalyzeOptions, List<SignalReference>> {
        val options = options(file)
        val memo =
            CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(AtomicReference<Pair<AnalyzeOptions, List<SignalReference>>>(), file, settings.tracker)
            }
        memo.get()?.takeIf { it.first == options }?.let { return it }
        val read = options to analyzer.signalReferencesInKotlin(file.text, options)
        memo.set(read)
        return read
    }

    /**
     * The signals the expressions of a Kotlin file read, with the calls the Kotlin plugin resolves elsewhere named: an
     * expression handed to one is no Datastar, and its HTML string is a free one. Only a call that holds a read is resolved.
     */
    fun streamlordSignalReferences(file: KtFile): List<SignalReference> {
        val (options, refs) = kotlinSignalReferences(file)
        if (refs.none { it.site != null }) return refs
        val resolved = resolved(file)
        val foreign = foreignCalls(file, resolved, refs.mapNotNull { it.site })
        if (foreign.isEmpty()) return refs
        resolved.references?.takeIf { it.first === refs && it.second == foreign }?.let { return it.third }
        val kept = analyzer.signalReferencesInKotlin(file.text, options.copy(foreignCalls = foreign))
        resolved.references = Triple(refs, foreign, kept)
        return kept
    }

    /** The signals the expressions of an HTML document or template read. */
    fun htmlSignalReferences(file: PsiFile): List<SignalReference> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(analyzer.signalReferencesInHtml(file.text, options()), file, settings.tracker)
        }

    companion object {
        /** The name of a function declared: `fun dataText(`, `fun HTMLTag.dataText(`, `fun <T> T.dataText(`. */
        private val FUNCTION_NAME = Regex("""\bfun\s[^(\n]*?`?\b(\w+)`?\s*\(""")

        /** An import of one name, with its alias: `import a.b.dataText` or `import a.b.c as dataText`. */
        private val IMPORT =
            Regex("""^[ \t]*import\s+([\w.`]+?)(?:\s+as\s+`?(\w+)`?)?[ \t]*;?[ \t]*(?://.*)?$""", RegexOption.MULTILINE)

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
