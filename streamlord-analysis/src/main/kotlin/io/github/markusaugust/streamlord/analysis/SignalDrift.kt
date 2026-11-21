package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

/*
 * Signals a handler reads, against signals the pages declare.
 *
 * A signal nobody reads is harmless: it sits in the browser's store and comes back untouched.
 * A signal a handler reads that no page declares is the other way round, and it is always a
 * mistake: a rename that reached the markup and not the data class, a typo in a lookup, a
 * handler kept after its page went. The reader gets a default and nobody is told.
 *
 * Neither half of that is visible in one file, so this works over a set of them.
 */

/** Where a name was read, in the file it was read in. */
public data class SignalRead(
    val name: String,
    val start: Int,
    val end: Int,
)

/**
 * A property of a `@Serializable` class, and where its name sits.
 *
 * The name is the one the signal goes by: under `@SerialName("page_no") val page`, it is `page_no`,
 * and the offsets are those of the annotation's text.
 */
public data class SignalProperty(
    val name: String,
    val start: Int,
    val end: Int,
)

/**
 * What one source file says about signals.
 *
 * @property declared Signal names the file puts on a page, from markup and from the DSL.
 * @property signalTypes Types the file reads signals into, by their simple name.
 * @property classes The classes the file declares whose signals the check can name, by simple name: each
 *   `@Serializable` one, and each Jackson one whose names its constructor holds.
 * @property lookups Names read by a `Signals` accessor, such as `signals.string("query")`.
 * @property encodedTypes Classes a page declares its signals from, `dataSignals(codec.toJsonValue(Form()))`,
 *   by simple name, with `*` for a value whose class the file does not name.
 * @property patchedTypes Classes a server patch sets the signals of, `patchSignals(Form())`, by simple
 *   name. Unlike a page's, a patch of a class the check cannot read declares nothing: the server
 *   sends what it sends, and a read only it could set is still worth a look.
 * @property packageName The file's package, and [imports] what it imports, `app.forms.Form` or
 *   `app.forms.*`: a simple name above is the class they make it, as Kotlin resolves it.
 * @property renamesSignals Whether the file sets a naming strategy for its codec, in code or in
 *   Spring's settings, so that no class's signals go by the names the check reads off it.
 */
public data class SignalFacts(
    val declared: Set<String>,
    val signalTypes: Set<String>,
    val classes: Map<String, List<SignalProperty>>,
    val lookups: List<SignalRead>,
    val encodedTypes: Set<String> = emptySet(),
    val patchedTypes: Set<String> = emptySet(),
    val packageName: String = "",
    val imports: Set<String> = emptySet(),
    val renamesSignals: Boolean = false,
)

private val SIGNAL_TYPE_PARAMETER = regex("""\breadSignals\s*<\s*([A-Za-z_][A-Za-z0-9_]*)\s*>""")
private val SIGNAL_TYPE_DEFAULT = regex("""\breadSignalsOr\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

/** The `Signals` accessors that take a signal name. `path` takes several; its first is the signal. */
private val LOOKUPS = setOf("string", "boolean", "int", "long", "double", "decimal", "obj", "array", "has", "path")

// A JSON object, a map and a result set have accessors of the same names, so the name of a call is
// not enough. A lookup counts when it is made on an untyped `readSignals()` call, on a name the
// file binds to one or declares as `Signals`, or on a name that is plainly `signals`.
private val SIGNALS_BINDING =
    regex(
        """\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*Signals\??\s*)?=[^\n;=]*\breadSignals\s*\([^()\n]*\)\s*$""",
        RegexOption.MULTILINE,
    )
private val SIGNALS_TYPED = regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*Signals\b""")
private val READ_SIGNALS_BEFORE = regex("""\breadSignals\s*$""")

/**
 * Reads [src] for everything the drift check needs.
 *
 * Declarations are collected the way completion collects them, which is deliberately generous:
 * a name offered twice costs nothing, and a name missed here would become a false warning.
 */
public fun collectSignalFacts(source: Source): SignalFacts {
    val src = source.text
    val language = source.language
    if (language == SourceLanguage.SETTINGS) {
        return SignalFacts(emptySet(), emptySet(), emptyMap(), emptyList(), renamesSignals = settingsRenameSignals(src))
    }
    if (language == SourceLanguage.HTML) {
        return SignalFacts(collectDeclaredSignals(src, language, source.prefix), emptySet(), emptyMap(), emptyList())
    }

    val lex = lexKotlin(src)

    // A read written in a comment or a string is none.
    val types = LinkedHashSet<String>()
    for (re in listOf(SIGNAL_TYPE_PARAMETER, SIGNAL_TYPE_DEFAULT)) {
        for (m in re.findAll(src)) if (lex.mask[m.range.first]) types += m.groupValues[1]
    }

    val bound = hashSetOf("signals")
    for (re in listOf(SIGNALS_BINDING, SIGNALS_TYPED)) {
        for (m in re.findAll(src)) if (lex.mask[m.range.first]) bound += m.groupValues[1]
    }

    val lookups = ArrayList<SignalRead>()
    for (site in findCallSites(src, LOOKUPS, lex)) {
        if (!onSignals(src, site.nameStart, bound)) continue
        val first = site.positional.firstOrNull()?.string ?: continue
        lookups += SignalRead(first.text, first.contentStart, first.contentEnd)
    }

    val packageName =
        PACKAGE
            .findAll(src)
            .firstOrNull { lex.mask[it.range.first] }
            ?.groupValues
            ?.get(1)
            .orEmpty()
    val imports = IMPORT.findAll(src).filter { lex.mask[it.range.first] }.mapTo(LinkedHashSet()) { it.groupValues[1] }
    return SignalFacts(
        collectDeclaredSignals(src, language, source.prefix),
        types,
        signalClasses(src, lex).filter { it.serializable || (it.readable && it.properties.isNotEmpty()) }.associate {
            it.name to
                it.properties
        },
        lookups,
        encodedSignalTypes(src, lex = lex),
        patchedSignalTypes(src, lex),
        packageName,
        imports,
        codeRenamesSignals(src, lex),
    )
}

private val PACKAGE = regex("""^[ \t]*package\s+([A-Za-z_][A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

// An import under another name, `import a.Form as F`, is left out: the class is then read by no name the check knows.
private val IMPORT = regex("""^[ \t]*import\s+([A-Za-z_][A-Za-z0-9_.]*(?:\.\*)?)[ \t]*(?:;|//|/\*|$)""", RegexOption.MULTILINE)

/** A class of the project, by the file that declares it and its package. */
private class DeclaredClass(
    val file: String,
    val packageName: String,
    val properties: List<SignalProperty>,
)

/**
 * The classes of [byName] that the simple name [type] means in [reader], as Kotlin looks it up: an
 * explicit import first, then the reader's own package, then a star import. More than one is a
 * name the check cannot settle.
 */
private fun resolve(
    reader: SignalFacts,
    type: String,
    byName: Map<String, List<DeclaredClass>>,
): List<DeclaredClass> {
    val all = byName[type].orEmpty()
    // An explicit import of the name decides, even of a class outside the project.
    if (reader.imports.any { it == type || it.endsWith(".$type") }) return all.filter { "${it.packageName}.$type" in reader.imports }
    all.filter { it.packageName == reader.packageName }.let { if (it.isNotEmpty()) return it }
    return all.filter { it.packageName.isNotEmpty() && "${it.packageName}.*" in reader.imports }
}

/** Whether the accessor whose name starts at [nameStart] is called on something that holds signals. */
private fun onSignals(
    src: String,
    nameStart: Int,
    bound: Set<String>,
): Boolean {
    var i = nameStart - 1
    while (i >= 0 && src[i].isWhitespace()) i--
    if (i < 0 || src[i] != '.') return false
    i--
    if (i >= 0 && src[i] == '?') i--
    while (i >= 0 && src[i] == '!') i--
    while (i >= 0 && src[i].isWhitespace()) i--
    if (i < 0) return false

    if (src[i] == ')') {
        var depth = 0
        while (i >= 0) {
            if (src[i] == ')') depth++
            if (src[i] == '(' && --depth == 0) break
            i--
        }
        return i >= 0 && READ_SIGNALS_BEFORE.containsMatchIn(src.substring(maxOf(0, i - 40), i))
    }

    val end = i + 1
    while (i >= 0 && src[i].isIdentPart()) i--
    return src.substring(i + 1, end) in bound
}

/** Every `@Serializable` class in the file, with the names and offsets of its constructor properties. */
internal fun serializableProperties(
    src: String,
    lex: KotlinLex = lexKotlin(src),
): Map<String, List<SignalProperty>> = signalClasses(src, lex).filter { it.serializable }.associate { it.name to it.properties }

// A naming strategy handed to a mapper, Jackson's or kotlinx.serialization's.
private val NAMING_STRATEGY =
    regex("""\bPropertyNamingStrateg(?:y|ies)\b|\bpropertyNamingStrategy\b|\bJsonNamingStrategy\b""")
private val JSON_NAMING = regex("""@(?:[A-Za-z_][\w.]*\.)?JsonNaming\s*\(""")
private val IMPORT_LINE = regex("""^[ \t]*import\b[^\n]*""", RegexOption.MULTILINE)

/** Whether the code of [src] sets a naming strategy for a codec: one named in `@JsonNaming` is that class's alone, and an import sets none. */
internal fun codeRenamesSignals(
    src: String,
    lex: KotlinLex,
): Boolean {
    val own = JSON_NAMING.findAll(src).map { it.range.first..maxOf(it.range.last, matchingParen(src, it.range.last)) }.toList()
    val imports = IMPORT_LINE.findAll(src).map { it.range }.toList()
    return NAMING_STRATEGY.findAll(src).any { m ->
        lex.mask[m.range.first] && own.none { m.range.first in it } && imports.none { m.range.first in it }
    }
}

// Spring's `spring.jackson.property-naming-strategy`, as its relaxed binding lets it be written.
private val SETTINGS_NAMING = regex("""(?i)property[-_]?naming[-_]?strategy""")

/** Whether Spring's settings in [text] set a naming strategy, on a line that is no comment. */
internal fun settingsRenameSignals(text: String): Boolean =
    text.lineSequence().any { line ->
        val start = line.trimStart()
        !start.startsWith("#") && !start.startsWith("!") && SETTINGS_NAMING.containsMatchIn(line)
    }

/**
 * The classes the simple name [type] may mean in [src], as Kotlin looks it up: an explicit import
 * alone, or else the file's own package before each star import, written `pkg.Name`.
 */
internal fun candidateClasses(
    src: String,
    lex: KotlinLex,
    type: String,
): List<String> {
    val imports =
        IMPORT
            .findAll(src)
            .filter { lex.mask[it.range.first] }
            .map { it.groupValues[1] }
            .toList()
    imports.firstOrNull { it == type || it.endsWith(".$type") }?.let { return listOf(it) }
    val own =
        PACKAGE
            .findAll(src)
            .firstOrNull { lex.mask[it.range.first] }
            ?.groupValues
            ?.get(1)
            .orEmpty()
    return listOf(if (own.isEmpty()) type else "$own.$type") + imports.filter { it.endsWith(".*") }.map { it.removeSuffix("*") + type }
}

/** The package [src] declares, or the empty one. */
internal fun packageOf(
    src: String,
    lex: KotlinLex,
): String =
    PACKAGE
        .findAll(src)
        .firstOrNull { lex.mask[it.range.first] }
        ?.groupValues
        ?.get(1)
        .orEmpty()

/** The offset of the `)` that closes the `(` at [open], or -1. Nesting only; a parenthesis in a string is rare here. */
internal fun matchingParen(
    src: String,
    open: Int,
): Int {
    var depth = 0
    for (i in open until src.length) {
        when (src[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return -1
}

/**
 * What the drift check saw, and what it found.
 *
 * [issues] being empty is the good answer only when [read] is not. A check that recognised no
 * reads at all reports exactly the same empty map as a project in perfect order, and the two are
 * worth telling apart: the collectors are patterns over source text, so a signals class written
 * in a shape they do not match contributes nothing and takes the finding with it. That is not a
 * hypothetical. The first version of this check called a 45-file corpus clean because its pattern
 * for a signals class did not allow a modifier before `class`, and every `@Serializable public
 * data class` in it was invisible.
 *
 * So assert on both. `report.read` tells you the check had something to judge.
 *
 * @property issues Findings by file, empty when nothing drifted.
 * @property declared Every signal name the files declare, from markup and from the DSL.
 * @property read Every signal name the files read, through a signals class or a lookup.
 * @property declaredByCodec What a page is declared from, `dataSignals(codec.toJsonValue(...))`,
 *   whose names only the codec knows: a class the check cannot read, or `*` for a value of no named
 *   class, and with a naming strategy set every class it would judge. While it is not empty every
 *   read counts as declared, and [issues] stays empty.
 */
public data class SignalDriftReport(
    val issues: Map<String, List<Issue>>,
    val declared: Set<String>,
    val read: Set<String>,
    val declaredByCodec: Set<String> = emptySet(),
)

/**
 * The signals read in the file whose facts are [own] that neither it nor any of [others] declares:
 * that file's part of [signalDrift]. A file with no signals class and no lookup can report
 * nothing, so [others] is read only for one that has.
 */
internal fun signalDriftIn(
    own: SignalFacts,
    others: () -> Iterable<SignalFacts>,
): List<Issue> {
    if (own.classes.isEmpty() && own.lookups.isEmpty()) return emptyList()
    val facts = HashMap<String, SignalFacts>()
    // Only this file's issues are asked for, so another file's lookups play no part.
    others().forEachIndexed { i, o -> facts["$i"] = o.copy(lookups = emptyList()) }
    facts[OWN_FILE] = own
    return signalDrift(facts).issues[OWN_FILE].orEmpty()
}

/** The key of the file [signalDriftIn] judges, which no index of the others is. */
private const val OWN_FILE = ""

/**
 * Signals read in one file that nothing in [facts] declares.
 *
 * [facts] is every file of the project, keyed by whatever the caller calls a file: the union of
 * their declarations is what a read is judged against, because the page that declares a signal is
 * rarely the file that reads it.
 *
 * A class is only looked at when some file reads signals into it. A project's other classes are
 * its own business, and a request body that happens to be one is not a signal.
 */
public fun signalDrift(facts: Map<String, SignalFacts>): SignalDriftReport {
    val declared = facts.values.flatMapTo(LinkedHashSet()) { it.declared }
    val read = LinkedHashSet<String>()

    val byName = HashMap<String, MutableList<DeclaredClass>>()
    for ((file, one) in facts) {
        for ((type, properties) in one.classes) byName.getOrPut(type) { ArrayList() } += DeclaredClass(file, one.packageName, properties)
    }

    // A class is judged where one class alone is what a read names: two the read may mean are left alone.
    val judged = HashSet<Pair<String, String>>()
    // A page that declares its signals from an encoded class declares that class's properties. A
    // class this check cannot read, a Jackson one or none named, may declare any name.
    val byCodec = LinkedHashSet<String>()
    for (one in facts.values) {
        for (type in one.signalTypes) resolve(one, type, byName).singleOrNull()?.let { judged += it.file to type }
        for (type in one.encodedTypes) {
            val found = if (type == ANY_SIGNAL) emptyList() else resolve(one, type, byName)
            if (found.isEmpty()) byCodec += type
            for (c in found) c.properties.forEach { declared += it.name }
        }
        for (type in one.patchedTypes) for (c in resolve(one, type, byName)) c.properties.forEach { declared += it.name }
    }
    // A naming strategy renames every class's signals, so none is read off its properties.
    if (facts.values.any { it.renamesSignals }) {
        for ((_, type) in judged) byCodec += type
        judged.clear()
        for (one in facts.values) byCodec += one.encodedTypes
    }
    val anyDeclared = byCodec.isNotEmpty()

    val out = LinkedHashMap<String, List<Issue>>()
    for ((file, one) in facts) {
        val issues = ArrayList<Issue>()

        for ((type, properties) in one.classes) {
            if (file to type !in judged) continue
            for (property in properties) {
                read += property.name
                if (property.name in declared || anyDeclared) continue
                issues +=
                    Issue(
                        start = property.start,
                        end = property.end,
                        message =
                            "No markup declares the signal '${property.name}', so $type.${property.name} will " +
                                "always be its default. Check the name against the page, or drop the property.",
                        severity = Severity.WARNING,
                        code = IssueCode.SIGNAL_NEVER_DECLARED,
                        link = Docs.SIGNALS,
                    )
            }
        }

        for (lookup in one.lookups) {
            read += lookup.name
            if (lookup.name in declared || anyDeclared) continue
            issues +=
                Issue(
                    start = lookup.start,
                    end = lookup.end,
                    message =
                        "No markup declares the signal '${lookup.name}', so this read will always be null. " +
                            "Check the name against the page.",
                    severity = Severity.WARNING,
                    code = IssueCode.SIGNAL_NEVER_DECLARED,
                    link = Docs.SIGNALS,
                )
        }

        if (issues.isNotEmpty()) out[file] = issues
    }
    return SignalDriftReport(out, declared, read, byCodec)
}

/**
 * What the project knows about signals, which an editor's index holds: the driven port of
 * [projectSignalIssues], which asks each question only where its answer is needed.
 */
public interface ProjectSignals {
    /** Every signal the project defines, or null where that is not known. */
    public fun defined(): Set<String>?

    /** What the file asked about says about signals, or null where that is not known. */
    public fun own(): SignalFacts?

    /** What every other file says about signals. */
    public fun others(): Iterable<SignalFacts>
}

/**
 * What the [project] says of the signals one file in [language] reads, the one entry both editors
 * ask: each of [refs] that reads a signal the project does not define, and in a Kotlin file each
 * signal it reads through a signals class or a lookup that no file declares.
 */
public fun projectSignalIssues(
    language: SourceLanguage,
    refs: List<SignalReference>,
    project: ProjectSignals,
): List<Issue> {
    val defined = if (refs.isEmpty()) null else project.defined()
    val own = if (language == SourceLanguage.KOTLIN) project.own() else null
    return defined?.let { unknownSignalIssues(refs, it) }.orEmpty() + own?.let { signalDriftIn(it, project::others) }.orEmpty()
}
