package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File

/**
 * The cases of a rule both twins hold, from `editors/vscode/test/fixtures/twins/<file>`: this
 * module's tests and the VS Code extension's read the same file, through `twins.ts` there, so a
 * case is written once and the two analyses cannot hold different ones.
 */
internal fun twinCases(file: String): List<JsonObject> =
    JsonParser
        .parseObject(File("../editors/vscode/test/fixtures/twins/$file").readText())
        .array("cases")!!
        .objects()
