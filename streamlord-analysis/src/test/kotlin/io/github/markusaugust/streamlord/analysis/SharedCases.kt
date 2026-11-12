package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The cases of a rule, written once in `editors/vscode/test/fixtures/cases/<file>`. The analysis's
 * tests read them on the JVM and on Node, and a test of the extension reads one (through `cases.ts`)
 * where it holds something of its own to it.
 */
internal fun sharedCases(file: String): List<JsonObject> =
    JsonParser
        .parseObject(fixtureText("editors/vscode/test/fixtures/cases/$file"))
        .array("cases")!!
        .objects()

/**
 * Runs [check] on each of [cases] and fails once for all that failed, each under its [name], so one
 * case that fails does not hide the next.
 */
internal fun <T> eachCase(
    cases: List<T>,
    name: (T) -> String,
    check: (T) -> Unit,
) {
    assertTrue(cases.isNotEmpty())
    val failed = cases.mapNotNull { case -> runCatching { check(case) }.exceptionOrNull()?.let { "${name(case)}: ${it.message}" } }
    if (failed.isNotEmpty()) fail("${failed.size} of ${cases.size} cases failed\n" + failed.joinToString("\n"))
}
