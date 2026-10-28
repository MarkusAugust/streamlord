package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.domain.SignalNames
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Holds [Casing] to `conformance/client/casing.json` and `conformance/client/attribute-keys.json`,
 * the output of the Datastar 1.0.4 client's `modifyCasing` (utils/text.ts) and `parseAttributeKey`
 * (engine/engine.ts) run by `conformance/generate/client.mjs`.
 */
class ClientOracleTest {
    private fun cases(name: String): List<JsonObject> =
        JsonParser.parseObject(File("../conformance/client/$name").readText()).array("cases")!!.objects()

    private val casing = cases("casing.json").associateBy { it.string("key")!! }

    /** `casing.json`: [Casing.camel] and [Casing.clientCase] against `modifyCasing` with each `__case`. */
    @Test
    fun `recases a key as the client's caseFns do`() {
        assertEquals(68, casing.size)
        for ((key, case) in casing) {
            val table = case.obj("modifyCasing")!!
            assertEquals(table.string("case.camel"), Casing.camel(key), key)
            for (c in Case.entries) {
                // The client throws on `__case.pascal` of an empty key; validate refuses a blank name before clientCase sees one.
                val expected = table.string("case.${c.wire}") ?: continue
                assertEquals(expected, Casing.clientCase(key, c), "$key as ${c.wire}")
            }
        }
    }

    /**
     * `casing.json`: [Casing.key] writes a key and a `__case` for a name typed with capitals, so
     * that the client derives the name; the fixture says what it derives. A name it refuses is
     * refused with what the client would read instead.
     */
    @Test
    fun `writes a key the client derives the name from`() {
        val misread = ArrayList<String>()
        for (name in casing.keys) {
            if (name.none { it.isUpperCase() } || runCatching { Casing.validate(name) }.isFailure) continue
            // A signal name holds no hyphen, so only an event or class name, kebab by default, may.
            for (default in if ('-' in name) listOf(Case.KEBAB) else listOf(Case.CAMEL, Case.KEBAB)) {
                val result = runCatching { Casing.key(name, null, default) }
                val wire = (result.getOrNull()?.first ?: Casing.kebab(name)).lowercase()
                val table = casing[wire]?.obj("modifyCasing") ?: fail("$wire, the key written for $name, is not in casing.json: add it")
                result
                    .onSuccess { (_, case) ->
                        val column = if (case == null) "default ${default.wire}" else "case.${case.wire}"
                        val read = table.string(column)
                        if (read != name) {
                            misread +=
                                "$name written as $wire with $column for a ${default.wire} default, which the client reads as $read"
                        }
                    }.onFailure { e ->
                        val read = Regex("""would read (\S+)\.""").find(e.message!!)?.groupValues?.get(1) ?: throw e
                        val wanted = if (name.first().isUpperCase()) "pascal" else "camel"
                        assertEquals(table.string("case.$wanted"), read, "$name refused for what the client reads from $wire")
                    }
            }
        }
        assertEquals(emptyList(), misread)
    }

    /** `casing.json`: [Casing.clientCamel], which `bindProp` reads a `__prop` back with, is text.ts's `camel`. */
    @Test
    fun `camel-cases as the client's camel does`() {
        for ((key, case) in casing) assertEquals(case.string("camel"), Casing.clientCamel(key), key)
    }

    /**
     * `attribute-keys.json`: [Casing.heldKey] reads the signal a key with modifiers declares as
     * `parseAttributeKey` and `modifyCasing` do, and refuses one the client cannot hold.
     */
    @Test
    fun `reads the signal a written key declares as the client does`() {
        val cases = cases("attribute-keys.json")
        assertEquals(54, cases.size)
        for (case in cases) {
            val raw = case.string("raw")!!
            val held = case.string("signalCamel") ?: continue
            // The browser lowercases an attribute name; the fixture holds the function's answer for the text as given.
            if (raw != raw.lowercase()) continue
            val written = raw.substringAfter(':')
            assertEquals(held, Casing.readKey(written), raw)
            if (SignalNames.pathProblem(held) == null) {
                assertEquals(written, Casing.heldKey(held, written), raw)
            } else {
                val e = assertFailsWith<InvalidSignalNameException>(raw) { Casing.heldKey(held, written) }
                assertContains(e.message!!, "is read by Datastar's client as $held")
            }
        }
    }
}
