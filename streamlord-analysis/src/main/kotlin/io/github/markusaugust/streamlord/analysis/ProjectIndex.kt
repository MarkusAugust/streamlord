package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.regex

/**
 * What a project's files give each other, kept as an editor reads them: the signals each names and
 * defines, its ids and classes, the constants of its Kotlin, and what it says about the signals the
 * server reads. An editor puts a file in when its text changes and takes it out when it is gone, and
 * asks; when to read a file is the editor's to know. A path is whatever the editor calls a file, with
 * `/` between its folders. The text is read and let go, so a question that needs it again is handed it.
 *
 * The DSL's short helpers, `set`, `toggle`, `signal` and their kin, share their names with other APIs.
 * With no [hasDsl] a Kotlin file's imports say whether they are the DSL's. An editor that sees the
 * classpath gives [hasDsl], which answers for the file at a path, null while it cannot tell. A file
 * moved to another module changes no text, so the answer is asked for anew by every question but
 * [definitions], which keeps its set: the editor calls [modulesChanged] when the answers may differ.
 *
 * Not safe to use from two threads at once.
 */
public class ProjectIndex(
    private val prefix: String = CatalogNames.PREFIX,
    private val hasDsl: ((path: String) -> Boolean?)? = null,
) {
    private class Names(
        val signals: Set<String>,
        val definitions: Set<String>,
    )

    private class Entry(
        val language: SourceLanguage,
        /** The length and the hash of the text read: what is worked out later is kept only for that text. */
        val length: Int,
        val hash: Int,
        val names: Names,
        /** What the file names and defines without its short DSL helpers, where [hasDsl] may say it has none and it calls some. */
        val withoutDsl: Names?,
        val selectors: Selectors,
        val declared: DeclaredNames?,
        /** What the file says about the signals the server reads, and with [stale] what an earlier text of it said. */
        var facts: SignalFacts,
        var stale: Boolean,
    ) {
        /** The names the file may read on the server: a name declared elsewhere or no more changes its verdict only when it is one. */
        val serverReads: Set<String> get() =
            facts.lookups.mapTo(HashSet()) { it.name } +
                facts.classes.values.flatMap { c -> c.map { it.name } }

        /** Where the file defines signals, with and without its short DSL helpers, and where it reads them: each worked out when first asked for. */
        var declarations: List<SignalDeclaration>? = null
        var declarationsWithoutDsl: List<SignalDeclaration>? = null
        var reads: List<SignalReference>? = null

        fun readFrom(text: String) = text.length == length && text.hashCode() == hash
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var definitionsUnion: Set<String>? = null
    private var constantsUnion: Set<String>? = null

    /**
     * Reads [text] as the file at [path]. True where what the other files are told has changed, so
     * that they are to be judged again: a new file that gives them nothing leaves them as they were. Without [facts], what the file says about the signals the
     * server reads is left as it was until [readFacts]: an editor reads it when it judges the file,
     * not on each keystroke.
     */
    public fun put(
        path: String,
        text: String,
        language: SourceLanguage,
        facts: Boolean = true,
    ): Boolean {
        val previous = entries[path]
        val source = source(text, language)
        val withoutDsl =
            if (hasDsl != null && language == SourceLanguage.KOTLIN &&
                callsShortHelper(text)
            ) {
                names(source.copy(modules = WITHOUT_DSL))
            } else {
                null
            }
        val next =
            Entry(
                language,
                text.length,
                text.hashCode(),
                names(source),
                withoutDsl,
                if (language == SourceLanguage.SETTINGS) Selectors.EMPTY else collectSelectors(text),
                if (language == SourceLanguage.KOTLIN) declaredNames(text) else null,
                if (facts) collectSignalFacts(source) else previous?.facts ?: NO_FACTS,
                stale = !facts,
            )
        entries[path] = next
        val defines =
            previous?.names?.definitions.orEmpty() != next.names.definitions ||
                previous?.withoutDsl?.definitions != next.withoutDsl?.definitions
        val declares =
            previous?.declared?.constants.orEmpty() != next.declared?.constants.orEmpty() ||
                previous?.declared?.others.orEmpty() != next.declared?.others.orEmpty()
        if (defines) definitionsUnion = null
        if (declares) constantsUnion = null
        return defines || declares || (facts && factsMatter(path, previous?.facts ?: NO_FACTS, next.facts))
    }

    /**
     * Reads what [text] says about the signals the server reads, where a [put] left the file at [path]
     * waiting and [text] is the text it was put in with; any other text is left unread, and the file
     * waits on. True where the other files are to be judged again.
     */
    public fun readFacts(
        path: String,
        text: String,
    ): Boolean {
        val e = entries[path]?.takeIf { it.stale && it.readFrom(text) } ?: return false
        val before = e.facts
        e.facts = collectSignalFacts(source(text, e.language))
        e.stale = false
        return factsMatter(path, before, e.facts)
    }

    /** The paths of the files whose facts wait for [readFacts]. */
    public fun waiting(): List<String> = entries.filter { it.value.stale }.keys.toList()

    /** Forgets the file at [path], or every file under the folder there. True where the other files are to be judged again. */
    public fun remove(path: String): Boolean {
        val folder = if (path.endsWith("/")) path else "$path/"
        var told = false
        val each = entries.entries.iterator()
        while (each.hasNext()) {
            val (key, e) = each.next()
            if (key != path && !key.startsWith(folder)) continue
            each.remove()
            told = told || tells(e) || factsMatter(key, e.facts, NO_FACTS)
        }
        if (told) dropUnions()
        return told
    }

    /** Forgets every file. */
    public fun clear() {
        entries.clear()
        dropUnions()
    }

    /** Every signal some file names: what it defines, and what it only reads. */
    public fun signals(): Set<String> = entries.flatMapTo(LinkedHashSet()) { (path, e) -> namesOf(path, e).signals }

    /**
     * Every signal some file defines, which a `$name` is checked against, entries that are no names
     * among them, as [collectSignalDefinitions] has them. The same set, not only an equal one, until
     * what some file defines changes: a caller may know by that whether it has read it before.
     */
    public fun definitions(): Set<String> =
        definitionsUnion
            ?: entries.flatMapTo(HashSet<String>()) { (path, e) -> namesOf(path, e).definitions }.also { definitionsUnion = it }

    /** The constants of the project's Kotlin, as [projectConstants] has them. The same set until what a file declares changes. */
    public fun constants(): Set<String> =
        constantsUnion ?: projectConstants(entries.values.mapNotNull { it.declared }).also { constantsUnion = it }

    /** The ids and classes every file declares. */
    public fun selectors(): Selectors {
        val ids = LinkedHashSet<String>()
        val classes = LinkedHashSet<String>()
        for (e in entries.values) {
            ids += e.selectors.ids
            classes += e.selectors.classes
        }
        return Selectors(ids, classes)
    }

    /** The paths of the files held now: a later [put] or [remove] does not change the set handed out. */
    public fun paths(): Set<String> = entries.keys.toSet()

    /** The signals the file at [path] names. */
    public fun signalsOf(path: String): Set<String> = entries[path]?.let { namesOf(path, it).signals }.orEmpty()

    /**
     * Where the file at [path] defines signals, in source order, from [text], which is asked for only when
     * they are not worked out already: an editor's own reading of the file as it is now. Null for a file
     * not held or with no text. They are kept where the text is the one the file was put in with.
     */
    public fun declarations(
        path: String,
        text: () -> String?,
    ): List<SignalDeclaration>? {
        val e = entries[path] ?: return null
        var without = e.withoutDsl != null && hasDsl?.invoke(path) == false
        (if (without) e.declarationsWithoutDsl else e.declarations)?.let { return it }
        val current = text() ?: return null
        // A text that is not the one put in may call a short helper that one did not.
        if (!e.readFrom(current)) {
            without =
                e.language == SourceLanguage.KOTLIN && callsShortHelper(current) && hasDsl?.invoke(path) == false
        }
        val found = signalDeclarations(source(current, e.language).let { if (without) it.copy(modules = WITHOUT_DSL) else it })
        if (e.readFrom(current)) {
            if (without) {
                e.declarationsWithoutDsl = found
            } else {
                e.declarations = found
            }
        }
        return found
    }

    /**
     * Where the file at [path] reads signals, from [source], the file as the editor describes it now, asked
     * for and kept as [declarations] has it, for the modules and constants of the [source] first given. Calls to something other than Streamlord are among them.
     */
    public fun reads(
        path: String,
        source: () -> Source?,
    ): List<SignalReference>? {
        val e = entries[path] ?: return null
        e.reads?.let { return it }
        val current = source() ?: return null
        val found = ANALYZER.signalReferences(current).map { SignalReference(it.start, it.end, it.name) }
        if (e.readFrom(current.text)) e.reads = found
        return found
    }

    /** The ids and classes the file at [path] declares. */
    public fun selectorsOf(path: String): Selectors = entries[path]?.selectors ?: Selectors.EMPTY

    /**
     * What the project knows about the signals of the file at [path], for [projectSignalIssues]: what every
     * file defines, what the file itself says where it has been put in, and what every other file says.
     */
    public fun signalsFor(path: String): ProjectSignals =
        object : ProjectSignals {
            override fun defined() = definitions()

            override fun own() = entries[path]?.facts

            override fun others() = entries.entries.filter { it.key != path }.map { it.value.facts }
        }

    /** Whether the facts of the file at [path] changing from [a] to [b] can change how the server's reads are judged in another file. */
    private fun factsMatter(
        path: String,
        a: SignalFacts,
        b: SignalFacts,
    ): Boolean {
        if (a.signalTypes != b.signalTypes || a.encodedTypes != b.encodedTypes || a.patchedTypes != b.patchedTypes) return true
        // A naming strategy decides every class's names.
        if (a.renamesSignals != b.renamesSignals || a.writesAtRunTime != b.writesAtRunTime) return true
        // The package and the imports decide which class a name of the file is.
        if (a.packageName != b.packageName || a.imports != b.imports) return true
        val declared = (a.declared - b.declared) + (b.declared - a.declared)
        val shapes = { f: SignalFacts -> f.classes.mapTo(HashSet()) { (name, properties) -> name to properties.map { it.name } } }
        val classes =
            (shapes(a) - shapes(b) + (shapes(b) - shapes(a))).mapTo(HashSet()) { it.first } +
                (a.opaqueClasses - b.opaqueClasses) + (b.opaqueClasses - a.opaqueClasses)
        if (declared.isEmpty() && classes.isEmpty()) return false
        // A declared name counts where another file reads it, and a class where a file, this one too, reads
        // into it, patches it or declares a page from it: one more class of a name may leave a read naming two.
        return entries.any { (key, e) ->
            (key != path && e.serverReads.any { it in declared }) ||
                classes.any { it in e.facts.encodedTypes || it in e.facts.patchedTypes || it in e.facts.signalTypes }
        }
    }

    /** To be called when what [hasDsl] answers may have changed: [definitions] is then worked out again. */
    public fun modulesChanged() {
        definitionsUnion = null
    }

    private fun source(
        text: String,
        language: SourceLanguage,
    ) = Source(text, language, prefix, if (hasDsl == null) Modules.FROM_IMPORTS else Modules.UNKNOWN)

    private fun names(source: Source) = Names(collectSignals(source), collectSignalDefinitions(source))

    private fun namesOf(
        path: String,
        e: Entry,
    ): Names = if (e.withoutDsl != null && hasDsl?.invoke(path) == false) e.withoutDsl else e.names

    /** Whether [e] gives the other files anything that its going takes away. */
    private fun tells(e: Entry): Boolean =
        e.names.definitions.isNotEmpty() ||
            e.withoutDsl
                ?.definitions
                .orEmpty()
                .isNotEmpty() ||
            e.declared?.let { it.constants.isNotEmpty() || it.others.isNotEmpty() } == true

    private fun dropUnions() {
        definitionsUnion = null
        constantsUnion = null
    }

    /** Whether [text] calls one of the short helpers on no receiver: a file without such a call reads the same in every module. */
    private fun callsShortHelper(text: String): Boolean {
        val helpers = Catalog.default.signalNameHelpers.keys
        return CALL.findAll(text).any { m ->
            if (m.groupValues[1] !in helpers) return@any false
            var i = m.range.first - 1
            while (i >= 0 && text[i].isWhitespace()) i--
            i < 0 || text[i] != '.'
        }
    }

    private companion object {
        private val WITHOUT_DSL = Modules.known(core = true, htmlDsl = false)
        private val ANALYZER = Analyzer()
        private val NO_FACTS = SignalFacts(emptySet(), emptySet(), emptyMap(), emptyList())
        private val CALL = regex("""\b([A-Za-z_]\w*)\s*\(""")
    }
}
