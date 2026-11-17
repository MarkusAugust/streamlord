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

/** A folder on disk as a [ProjectTree]. */
private class FolderTree(
    private val root: File,
) : ProjectTree {
    override fun list(folder: String): List<String> =
        root
            .resolve(folder)
            .listFiles()
            .orEmpty()
            .map { if (it.isDirectory) it.name + "/" else it.name }

    override fun read(file: String): String? = runCatching { root.resolve(file).readText() }.getOrNull()
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
