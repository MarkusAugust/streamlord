package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The cases of a rule both twins hold, from `editors/vscode/test/fixtures/twins/<file>`: this
 * module's tests and the VS Code extension's read the same file, through `twins.ts` there, so a
 * case is written once and the two analyses cannot hold different ones.
 */
internal fun twinCases(file: String): List<JsonObject> =
    JsonParser
        .parseObject(fixtureText("editors/vscode/test/fixtures/twins/$file"))
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
