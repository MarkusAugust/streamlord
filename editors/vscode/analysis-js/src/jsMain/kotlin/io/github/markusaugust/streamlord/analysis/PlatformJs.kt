package io.github.markusaugust.streamlord.analysis

// What the analysis asks of its platform, in JavaScript: the declarations of PlatformJvm.kt in streamlord-analysis.

/** The editor hands the analysis text, never a folder. */
public actual class SourceFolder private constructor()

public actual class ProjectIssue private constructor() {
    public actual val fails: Boolean get() = false
}

@Suppress("UNUSED_PARAMETER")
internal actual fun sourcesUnder(
    root: SourceFolder,
    skipped: Set<String>,
): List<Pair<SourceFolder, String>> = emptyList()

@Suppress("UNUSED_PARAMETER")
internal actual fun projectIssue(
    file: SourceFolder,
    line: Int,
    column: Int,
    issue: Issue,
): ProjectIssue = throw UnsupportedOperationException()

internal actual fun <K : Any, V : Any> sharedMap(): MutableMap<K, V> = HashMap()

/** What V8 throws when the stack is spent. */
internal external class RangeError : Throwable

internal actual typealias StackExhausted = RangeError

internal actual abstract class Traceless actual constructor() : RuntimeException()

internal actual fun currentTimeMillis(): Long =
    kotlin.js.Date
        .now()
        .toLong()

private val ID_START: dynamic = js("/^\\p{ID_Start}$/u")
private val ID_CONTINUE: dynamic = js("/^\\p{ID_Continue}$/u")

internal actual fun startsName(codePoint: Int): Boolean = ID_START.test(js("String").fromCodePoint(codePoint)) as Boolean

internal actual fun continuesName(codePoint: Int): Boolean = ID_CONTINUE.test(js("String").fromCodePoint(codePoint)) as Boolean
