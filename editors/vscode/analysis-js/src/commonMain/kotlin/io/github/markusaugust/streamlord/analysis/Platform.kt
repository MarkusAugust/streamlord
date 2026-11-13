package io.github.markusaugust.streamlord.analysis

import java.io.File

// What the analysis asks of its platform. On the JVM these are the declarations of PlatformJvm.kt in streamlord-analysis.

public expect class ProjectIssue {
    public val fails: Boolean
}

internal expect fun Analyzer.issuesUnder(
    root: File,
    prefix: String,
    skipped: Set<String>,
): List<ProjectIssue>

internal expect fun <K : Any, V : Any> sharedMap(): MutableMap<K, V>

internal expect class StackExhausted : Throwable

internal expect abstract class Traceless() : RuntimeException

internal expect fun currentTimeMillis(): Long

internal expect fun startsName(codePoint: Int): Boolean

internal expect fun continuesName(codePoint: Int): Boolean
