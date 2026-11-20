package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames

/*
 * A whole project judged in one call: which files are read, what each is told of the others, and
 * what comes back.
 */

/**
 * The files of a project, as the analysis asks for them: the driven port of [Analyzer.analyzeProject].
 * A path is counted from the project's root, with `/` between its folders and none at either end;
 * the root itself is the empty string. For a project of `README.md` and `src/main/Page.kt`:
 * `list("")` gives `["src/", "README.md"]`, `list("src")` gives `["main/"]`, and the file is read
 * as `read("src/main/Page.kt")`.
 */
public interface ProjectTree {
    /** The names in [folder], in any order, a folder's own name ending in `/`. Empty where there is no such folder. */
    public fun list(folder: String): List<String>

    /** The text of [file], or null where it cannot be read. */
    public fun read(file: String): String?
}

/**
 * An [Issue] of a project's sources, as [Analyzer.analyzeProject] finds them: the [path] of its file, as the
 * [ProjectTree] names it or, where a folder was given, with that folder before it, and the [line] and
 * [column] where it starts.
 */
public data class ProjectIssue(
    val path: String,
    val line: Int,
    val column: Int,
    val issue: Issue,
) {
    /** Whether the issue should fail a build: an error, or an `unsafe-interpolation` warning, which escaping cannot save. */
    public val fails: Boolean
        get() = issue.severity == Severity.ERROR || (issue.code == IssueCode.UNSAFE_INTERPOLATION && issue.severity == Severity.WARNING)

    /**
     * Whether the issue fails a build that asks for no value written without its mark: what [fails], and every other
     * warning of a Kotlin value in an HTML string, in a block or a format of `interpolate` or in a string of its own.
     */
    public val failsStrict: Boolean
        get() = fails || (issue.code in UNMARKED && issue.severity == Severity.WARNING)

    /** The issue as one line in the form compilers write, as [Issue.format] has it: `src/main/kotlin/Page.kt:12:5: error: ... [code]`. */
    public fun format(): String = issue.format(path, SourcePosition(line, column))
}

/**
 * The codes of a warning of a value in an HTML string that [ProjectIssue.fails] lets through: one not escaped where
 * escaping is enough, markup that will be, and a bare dollar in a Datastar expression, a value or a swallowed signal.
 */
private val UNMARKED = setOf(IssueCode.INTERPOLATE_TEMPLATE, IssueCode.UNESCAPED_INTERPOLATION, IssueCode.KOTLIN_INTERPOLATION)

/** The issues that should fail a build: each error, and each `unsafe-interpolation` warning. A hint never does. */
public fun List<ProjectIssue>.failures(): List<ProjectIssue> = filter { it.fails }

/**
 * The issues that should fail a build, and with [strict] each warning of a value written without its mark too: one left
 * unmarked in the block of `interpolate`, a template in its format, markup held as a `String` that `interpolate` would
 * print as text, and a `String` or markup outside element text in an HTML string that is not `interpolate`'s. A value
 * that is meant to stand so is declared `Html` or wrapped in `Trusted`. A hint never fails.
 */
public fun List<ProjectIssue>.failures(strict: Boolean): List<ProjectIssue> = filter { if (strict) it.failsStrict else it.fails }

/**
 * Every issue of the project in [tree], read with [prefix]: its Kotlin files, and its HTML and
 * template files, each judged as an editor judges that kind of file, and each told what the
 * others declare: their constants, and the signals they define and read. Which files are read is
 * [ProjectFiles]' to say. The issues come in path order, then by offset; [failures] keeps those
 * that should fail a build.
 */
public fun Analyzer.analyzeProject(
    tree: ProjectTree,
    prefix: String = CatalogNames.PREFIX,
): List<ProjectIssue> {
    val sources =
        ProjectFiles.under(tree).mapNotNull { path ->
            val language = ProjectFiles.languageOf(path) ?: return@mapNotNull null
            tree.read(path)?.let { path to Source(it, language, prefix, engine = ProjectFiles.engineOf(path)) }
        }
    val index = ProjectIndex(prefix)
    for ((path, source) in sources) index.put(path, source.text, source.language)
    val constants = index.constants()
    return sources.flatMap { (path, file) ->
        val source = file.copy(constants = constants)
        val own =
            when (source.language) {
                SourceLanguage.KOTLIN -> analyzeKotlin(source)
                SourceLanguage.HTML -> analyzeHtml(source)
                SourceLanguage.SETTINGS -> emptyList()
            }
        val told = projectSignalIssues(source.language, signalReferences(source), index.signalsFor(path))
        (own + told).sortedWith(compareBy({ it.start }, { it.end })).map { issue ->
            val (line, column) = issue.position(source.text)
            ProjectIssue(path, line, column, issue)
        }
    }
}
