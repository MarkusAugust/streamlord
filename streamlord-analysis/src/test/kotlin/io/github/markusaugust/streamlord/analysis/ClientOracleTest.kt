package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds the analysis to the fixtures under `conformance/client/`, each the output of a function
 * of the Datastar 1.0.4 client run by `conformance/generate/client.mjs`: `casing.json`
 * (`modifyCasing` in utils/text.ts), `attribute-keys.json` (`parseAttributeKey` in
 * engine/engine.ts), `expressions.json` (the body `genRx` compiles), `tags.json` (`tagToMs` and
 * `modifyTiming`) and `signal-store.json` (`pathToObj` and `filtered`).
 */
class ClientOracleTest {
    private val analyzer = Analyzer()

    private fun fixture(name: String): JsonObject = JsonParser.parseObject(File("../conformance/client/$name").readText())

    private fun cases(name: String): List<JsonObject> = fixture(name).array("cases")!!.objects()

    private fun group(
        name: String,
        group: String,
    ): List<JsonObject> = fixture(name).obj("cases")!!.array(group)!!.objects()

    /** `casing.json`: `modifyCasing` with each `__case` against [clientKeyName], and its camel default against [toCamel]. */
    @Test
    fun `reads the case of a key as modifyCasing does`() {
        val cases = cases("casing.json")
        assertEquals(68, cases.size)
        for (case in cases) {
            val key = case.string("key")!!
            val table = case.obj("modifyCasing")!!
            // A key holding __ is cut there by parseAttributeKey, so modifyCasing never sees it whole.
            if ("__" in key) continue
            assertEquals(table.string("default camel"), toCamel(key), key)
            assertEquals(table.string("default camel"), clientKeyName(key), key)
            // A key ending in _ runs into the __ of a modifier after it, and the client reads a shorter key.
            if (key.endsWith("_")) continue
            for ((column, value) in table) {
                if (!column.startsWith("case.")) continue
                // The client throws on `__case.pascal` of an empty key, which no attribute hands it: parseAttributeKey needs a character after the colon.
                val expected = (value as? JsonString)?.value ?: continue
                assertEquals(expected, clientKeyName("${key}__$column"), "$key with __$column")
            }
        }
    }

    /**
     * `casing.json`: [wireKey] writes a key and a `__case` for a name typed with capitals, so that
     * the client reads the name back; the fixture says what it reads.
     */
    @Test
    fun `writes a key the client reads back as the name typed`() {
        val casing = cases("casing.json").associateBy { it.string("key")!! }
        val misread = ArrayList<String>()
        for (name in casing.keys) {
            if (name.none { it.isUpperCase() } || name.any { it.isWhitespace() } || "__" in name) continue
            for (keyCase in listOf(KeyCase.CAMEL, KeyCase.KEBAB)) {
                val wire = wireKey(name, keyCase, null)
                val base = (wire ?: kebab(name)).substringBefore("__case.").lowercase()
                val table = casing[base]?.obj("modifyCasing") ?: fail("$base, the key written for $name, is not in casing.json: add it")
                if (wire == null) {
                    // No key gives the name back: the client's reading under the case the capitals ask for differs.
                    val wanted = if (name.first().isUpperCase()) "pascal" else "camel"
                    assertNotEquals(name, table.string("case.$wanted"), "$name refused as a ${keyCase.name.lowercase()} key")
                    assertEquals(table.string("case.$wanted"), wireKeyReads(name), name)
                    continue
                }
                val column = if ("__case." in wire) "case." + wire.substringAfter("__case.") else "default ${keyCase.name.lowercase()}"
                val read = table.string(column)
                // A signal key in markup is read as camelCase, as toCamel reads it; an event or class comes back as typed.
                val expected = if (keyCase == KeyCase.CAMEL) toCamel(name) else name
                if (read !=
                    expected
                ) {
                    misread +=
                        "$name written as $wire for ${keyCase.name.lowercase()} keys, which the client reads as $read, not $expected"
                }
            }
        }
        assertEquals(emptyList(), misread)
    }

    /** `attribute-keys.json`: `parseAttributeKey` against [Catalog.parseAttributeName], and the key's casing against [clientKeyName]. */
    @Test
    fun `splits an attribute name as parseAttributeKey does`() {
        val cases = cases("attribute-keys.json")
        assertEquals(54, cases.size)
        for (case in cases) {
            val raw = case.string("raw")!!
            val parsed = Catalog.default.parseAttributeName("data-$raw", "data-")!!
            val pluginName = case.string("pluginName")!!
            if (pluginName.endsWith(":") && case.string("key") == null) {
                // The client's key pattern needs a character after the colon, so `data-signals:` is the plugin
                // `signals:`, which it does not have. The parse keeps the colon as an empty key, and the markup
                // check says in words what the client does with it.
                assertEquals(pluginName.dropLast(1) to "", parsed.base to parsed.key, raw)
                val issue = analyzer.analyzeHtml("<div data-$raw=\"1\"></div>").single()
                assertContains(issue.message, "Datastar reads $pluginName as an attribute it does not have")
                continue
            }
            assertEquals(pluginName, parsed.base, raw)
            assertEquals(case.string("key"), parsed.key, raw)
            // The client keeps the last modifier of each label, its arguments as a set; the parse keeps every one as written.
            val modifiers = LinkedHashMap<String, List<String>>()
            for (m in parsed.modifiers) modifiers[m.name] = m.args.distinct()
            val expected = case.obj("modifiers")!!.mapValues { (_, v) -> (v as JsonArray).strings() }
            assertEquals(expected, modifiers, raw)
            val held = case.string("signalCamel") ?: continue
            assertEquals(held, clientKeyName(raw.substringAfter(':')), raw)
        }
    }

    /** `expressions.json`: the body `genRx` compiles, against [clientScript] and then [clientRewrite], in the client's order. */
    @Test
    fun `writes the function body genRx compiles`() {
        val cases = group("expressions.json", "rewrites")
        assertEquals(276, cases.size)
        for (case in cases) {
            val expression = case.string("expression")!!
            val returnsValue = case.boolean("returnsValue")!!
            val js = clientRewrite(clientScript(expression, returnsValue).js).js
            assertEquals(case.string("js"), js, "$expression with returnsValue $returnsValue")
        }
    }

    private val readsMs = Regex("""as (\d+) ms""")
    private val timerSet = Regex("""timer \d+ set (\S+)ms""")

    /** What the markup check says about the duration of `data-on:click__[modifiers]`, or null when it says nothing. */
    private fun durationMessage(modifiers: String): String? =
        analyzer
            .analyzeHtml("<div data-on:click__$modifiers=\"\$a = 1\"></div>")
            .map { it.message }
            .firstOrNull { "needs a duration" in it || "reads none and does not wait" in it || readsMs.containsMatchIn(it) }

    /** Tags the client reads through JavaScript's number syntax and the check refuses on purpose. */
    private val jsNumberForms = setOf("debounce.1e3ms")

    /** Holds what the check says about a wait to [wait], the client's, written `NaN` where the client read none. */
    private fun assertWait(
        written: String,
        message: String?,
        wait: String,
    ) {
        when {
            message == null -> {
                assertTrue(wait != "NaN", "$written: the check says nothing, but the client waits ${wait}ms")
            }

            "reads none and does not wait" in message -> {
                assertEquals("NaN", wait, written)
            }

            readsMs.containsMatchIn(message) -> {
                assertEquals(readsMs.find(message)!!.groupValues[1], wait, written)
            }

            // "needs a duration" names no number: it is right where the client reads none or no wait, and for
            // JavaScript's own number syntax, 1e3ms, which the check refuses on purpose (DURATION takes digits).
            else -> {
                assertContains(message, "needs a duration", message = written)
                val read = wait.toDoubleOrNull()
                assertTrue(
                    read == null || read.isNaN() || read <= 0.0 || written in jsNumberForms,
                    "$written: the client waits ${wait}ms, which the check does not say",
                )
            }
        }
    }

    /** `tags.json`: the timers `modifyTiming` sets for one timing modifier, against what the markup check says the client reads. */
    @Test
    fun `says what the client waits for a timing modifier`() {
        val timing = group("tags.json", "timing")
        assertEquals(23, timing.size)
        for (case in timing) {
            val written = case.string("modifiers")!!
            // One wrapper at a time: a trace of several does not say which timer is whose.
            if ("__" in written) continue
            val waits =
                case
                    .array("trace")!!
                    .strings()
                    .mapNotNull { timerSet.find(it)?.groupValues?.get(1) }
                    .distinct()
            val wait = waits.singleOrNull() ?: fail("$written: one wait expected in the trace, found $waits")
            assertWait(written, durationMessage(written), wait)
        }
    }

    /** `tags.json`: `tagToMs` over a tag set written as the arguments of `__debounce`, against what the markup check says. */
    @Test
    fun `reads a duration tag as tagToMs does`() {
        val tags = group("tags.json", "tags")
        assertEquals(30, tags.size)
        for (case in tags) {
            val set = case.array("tags")!!.strings()
            // Only what the browser hands over as a modifier's arguments: lowercase, and a dot or a space would split it.
            if (set.any { it != it.lowercase() || '.' in it || ' ' in it }) continue
            val ms = case["tagToMs"]
            val wait =
                when (ms) {
                    is JsonNumber -> ms.toBigDecimal().stripTrailingZeros().toPlainString()
                    else -> "NaN"
                }
            val written = (listOf("debounce") + set).joinToString(".")
            assertWait(written, durationMessage(written), wait)
        }
    }

    /** Whether [obj] holds [value] at the own keys [parts]. */
    private fun holds(
        obj: JsonValue,
        parts: List<String>,
        value: JsonValue,
    ): Boolean {
        var node: JsonValue = obj
        for (part in parts) node = (node as? JsonObject)?.get(part) ?: return false
        return node == value
    }

    /** `signal-store.json`: `pathToObj` on one path, against [signalPathProblem]. */
    @Test
    fun `knows which paths pathToObj cannot hold`() {
        val cases = group("signal-store.json", "pathToObj")
        assertEquals(34, cases.size)
        for (case in cases) {
            val single = case.array("paths")!!.singleOrNull() as? JsonArray ?: continue
            val path = (single[0] as JsonString).value
            val value = single[1]
            val held = case.string("result")?.let { holds(JsonParser.parseObject(it), path.split('.'), value) } ?: false
            val harmless = held && case.array("pollutes")!!.isEmpty()
            val problem = signalPathProblem(path)
            if (path.split('.').any { it.isEmpty() }) {
                // Held, but no $ reference reaches it: the problem is the reading, as the KDoc says.
                assertNotNull(problem, path)
            } else if (harmless) {
                assertNull(problem, path)
            } else {
                assertNotNull(
                    problem,
                    "$path: the client ${case.string("error")?.let { "throws $it" } ?: "writes ${case.array("pollutes")}"}",
                )
            }
        }
    }

    /** `signal-store.json`: `filtered` over a store, against [signalPathProblem] with an object under the key. */
    @Test
    fun `knows which keys the next request's filtered loses`() {
        val cases = group("signal-store.json", "filtered")
        assertEquals(26, cases.size)
        for (case in cases) {
            val store = JsonParser.parseObject(case.string("store")!!)
            val lost = case.string("error") != null || case.array("pollutes")!!.isNotEmpty()
            // A key holding a dot is the object form's own problem, in objectKeys; the fetch default drops a leading _ by choice.
            val flagged = store.any { (key, value) -> '.' !in key && signalPathProblem(key, objectUnder = value is JsonObject) != null }
            assertEquals(lost, flagged, store.toJson())
        }
    }
}
