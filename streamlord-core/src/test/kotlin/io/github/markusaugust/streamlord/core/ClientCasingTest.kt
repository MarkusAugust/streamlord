package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail

/**
 * Holds [ClientCasing] to `conformance/client/casing.json` and `attribute-keys.json`, the output
 * of the Datastar 1.0.4 client's `modifyCasing` and `camel` (utils/text.ts) and
 * `parseAttributeKey` (engine/engine.ts) run by `conformance/generate/client.mjs`.
 */
class ClientCasingTest {
    private fun cases(name: String): List<JsonObject> =
        JsonParser.parseObject(File("../conformance/client/$name").readText()).array("cases")!!.objects()

    private val casing = cases("casing.json").associateBy { it.string("key")!! }

    @Test
    fun `recases a key as the client's caseFns and modifyCasing do`() {
        assertEquals(70, casing.size)
        for ((key, case) in casing) {
            val table = case.obj("modifyCasing")!!
            assertEquals(table.string("default camel"), ClientCasing.camel(key), key)
            assertEquals(table.string("default camel"), ClientCasing.modifyCasing(key, null), key)
            assertEquals(table.string("default kebab"), ClientCasing.modifyCasing(key, null, "kebab"), key)
            for ((column, value) in table) {
                if (!column.startsWith("case.")) continue
                // The client throws on `__case.pascal` of an empty key, which no attribute hands it.
                val expected = (value as? JsonString)?.value ?: continue
                assertEquals(expected, ClientCasing.modifyCasing(key, column.removePrefix("case.").split('.')), "$key with __$column")
            }
        }
    }

    @Test
    fun `camel-cases as text's camel does`() {
        for ((key, case) in casing) assertEquals(case.string("camel"), ClientCasing.textCamel(key), key)
    }

    @Test
    fun `reads the name a written key declares as parseAttributeKey and modifyCasing do`() {
        val cases = cases("attribute-keys.json")
        assertEquals(55, cases.size)
        for (case in cases) {
            val raw = case.string("raw")!!
            val held = case.string("signalCamel") ?: continue
            // The fixture holds the function's answer for the text as given; the browser lowercases it first.
            if (raw != raw.lowercase()) continue
            assertEquals(held, ClientCasing.readKey(raw.substringAfter(':')), raw)
        }
        for ((key, case) in casing) {
            if ("__" in key || key.endsWith("_") || key != key.lowercase()) continue
            val table = case.obj("modifyCasing")!!
            for (c in ClientCasing.CASES) {
                val expected = table.string("case.$c") ?: continue
                assertEquals(expected, ClientCasing.readKey("${key}__case.$c"), "$key with __case.$c")
            }
        }
    }

    @Test
    fun `chooses a key the client reads back as the name`() {
        val misread = ArrayList<String>()
        for (name in casing.keys) {
            if (name.none { it in 'A'..'Z' } || name.any { it.isWhitespace() } || "__" in name) continue
            for (default in listOf("camel", "kebab")) {
                val choice = ClientCasing.keyFor(name, null, default)
                val base = (choice?.key ?: ClientCasing.kebab(name)).lowercase()
                val table = casing[base]?.obj("modifyCasing") ?: fail("$base, the key written for $name, is not in casing.json: add it")
                if (choice == null) {
                    // No key gives the name back: what the client reads under the case the capitals ask for differs.
                    val wanted = ClientCasing.wantedCase(name)
                    assertNotEquals(name, table.string("case.$wanted"), "$name refused as a $default key")
                    assertEquals(table.string("case.$wanted"), ClientCasing.readsAs(name), name)
                    continue
                }
                val column = choice.case?.let { "case.$it" } ?: "default $default"
                val read = table.string(column)
                // A key with a hyphen is camel-cased by a signal attribute, so only a name without one reads back as written there.
                val expected = if (default == "camel") ClientCasing.camel(name) else name
                if (read !=
                    expected
                ) {
                    misread +=
                        "$name written as ${choice.key} with $column for a $default default, which the client reads as $read"
                }
            }
        }
        assertEquals(emptyList(), misread)
    }
}
