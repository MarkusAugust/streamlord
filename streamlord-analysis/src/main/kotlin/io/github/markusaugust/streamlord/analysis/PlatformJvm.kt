package io.github.markusaugust.streamlord.analysis

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * What the analysis asks of the JVM. The JavaScript build leaves this file out and brings its own,
 * editors/vscode/analysis-js, with the same declarations.
 */

/** An [Issue] of a project's sources, as [Analyzer.analyzeProject] finds them: the [file], and the [line] and [column] where it starts. */
public data class ProjectIssue(
    val file: File,
    val line: Int,
    val column: Int,
    val issue: Issue,
) {
    /** Whether the issue should fail a build: an error, or an `unsafe-interpolation` warning, which escaping cannot save. */
    public val fails: Boolean
        get() = issue.severity == Severity.ERROR || (issue.code == "unsafe-interpolation" && issue.severity == Severity.WARNING)

    /** The issue as one line in the form compilers write, as [Issue.format] has it: `src/main/kotlin/Page.kt:12:5: error: ... [code]`. */
    public fun format(): String = issue.format(file.path, SourcePosition(line, column))
}

/** What [Analyzer.analyzeProject] answers: the `.kt` files under [root], less the folders named in [skipped] and the hidden ones, in path order. */
internal fun Analyzer.issuesUnder(
    root: File,
    prefix: String,
    skipped: Set<String>,
): List<ProjectIssue> {
    val files =
        root
            .walkTopDown()
            .onEnter { it == root || (it.name !in skipped && !it.name.startsWith(".")) }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .map { it to it.readText() }
            .toList()
    val constants = projectConstants(files.map { it.second })
    return files.flatMap { (file, source) ->
        analyzeKotlin(
            Source(source, SourceLanguage.KOTLIN, prefix, constants = constants),
        ).sortedWith(compareBy({ it.start }, { it.end })).map { issue ->
            val (line, column) = issue.position(source)
            ProjectIssue(file, line, column, issue)
        }
    }
}

/** A map two threads may fill at once. */
internal fun <K : Any, V : Any> sharedMap(): MutableMap<K, V> = ConcurrentHashMap()

/** What a parser too deep in its own recursion is stopped by. */
internal typealias StackExhausted = StackOverflowError

/** An exception that carries no stack trace, for one thrown and caught as control flow. */
internal abstract class Traceless : RuntimeException(null, null, false, false)

internal fun currentTimeMillis(): Long = System.currentTimeMillis()

/** Past ASCII: whether [codePoint] may start a JavaScript name, `ID_Start`. */
internal fun startsName(codePoint: Int): Boolean = Character.isUnicodeIdentifierStart(codePoint)

/** Past ASCII: whether [codePoint] may continue a JavaScript name, `ID_Continue`; the two joiners are counted by the caller. */
internal fun continuesName(codePoint: Int): Boolean =
    Character.isUnicodeIdentifierPart(codePoint) && !Character.isIdentifierIgnorable(codePoint)
