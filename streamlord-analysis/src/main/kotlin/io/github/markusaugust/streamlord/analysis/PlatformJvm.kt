package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * What the analysis asks of the JVM. The JavaScript build leaves this file out and brings its own,
 * editors/vscode/analysis-js, with the same declarations.
 */

/**
 * The project in the folder [root], as [analyzeProject] reads any [ProjectTree], each issue's path
 * with the root before it, so a build's output names the file from where the build runs.
 */
public fun Analyzer.analyzeProject(
    root: File,
    prefix: String = CatalogNames.PREFIX,
): List<ProjectIssue> = analyzeProject(FolderTree(root), prefix).map { it.copy(path = root.resolve(it.path).path) }

/**
 * The check a build runs, as one line in a test: `fun markup() = Analyzer().checkProject()`. It reads the
 * project in [root] as [analyzeProject] does and throws an [AssertionError] where a finding should fail a
 * build: a line that counts them, then each on a line of its own in the form compilers write, which an IDE
 * and CI make clickable. What fails is what [failures] keeps, with [strict] each value written without its
 * mark too. A folder that is not there fails as well, and so does a file that cannot be read: a check that
 * read nothing would pass for ever.
 */
public fun Analyzer.checkProject(
    root: File = File("src/main"),
    strict: Boolean = false,
    prefix: String = CatalogNames.PREFIX,
) {
    if (!root.isDirectory) throw AssertionError("${root.absolutePath} is no folder, so there is nothing to check")
    val failing = analyzeProject(root, prefix).failures(strict)
    if (failing.isEmpty()) return
    val count = if (failing.size == 1) "1 finding" else "${failing.size} findings"
    throw AssertionError("$count in ${root.path}\n" + failing.joinToString("\n") { it.format() })
}

/** A folder on disk as a [ProjectTree]. What is neither a file nor a folder is not listed, and a file that cannot be read throws. */
private class FolderTree(
    private val root: File,
) : ProjectTree {
    override fun list(folder: String): List<String> =
        root
            .resolve(folder)
            .listFiles()
            .orEmpty()
            .mapNotNull {
                when {
                    it.isDirectory -> it.name + "/"
                    it.isFile -> it.name
                    else -> null
                }
            }

    override fun read(file: String): String = root.resolve(file).readText()
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

/** Where group [group] of [match] stands in [input]; the JavaScript build asks its engine again. */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
internal fun Regex.groupRange(
    input: CharSequence,
    match: MatchResult,
    group: Int,
): IntRange? = match.groups[group]?.range
