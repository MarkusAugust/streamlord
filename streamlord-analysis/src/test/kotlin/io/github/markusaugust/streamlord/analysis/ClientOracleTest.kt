package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.clientRewrite
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.fixtures.fixtureText
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
 * of the Datastar client run by `conformance/generate/client.mjs`: `casing.json`
 * (`modifyCasing` in utils/text.ts), `attribute-keys.json` (`parseAttributeKey` in
 * engine/engine.ts), `expressions.json` (the body `genRx` compiles), `tags.json` (`tagToMs` and
 * `modifyTiming`) and `signal-store.json` (`pathToObj` and `filtered`).
 */
class ClientOracleTest {
    private val analyzer = Analyzer()

    private fun fixture(name: String): JsonObject = JsonParser.parseObject(fixtureText("conformance/client/$name"))

    private fun cases(name: String): List<JsonObject> = fixture(name).array("cases")!!.objects()

    private fun group(
        name: String,
        group: String,
    ): List<JsonObject> = fixture(name).obj("cases")!!.array(group)!!.objects()

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
                val base = (wire ?: ClientCasing.kebab(name)).substringBefore("__case.").lowercase()
                val table = casing[base]?.obj("modifyCasing") ?: fail("$base, the key written for $name, is not in casing.json: add it")
                if (wire == null) {
                    // No key gives the name back: the client's reading under the case the capitals ask for differs.
                    val wanted = if (name.first().isUpperCase()) "pascal" else "camel"
                    assertNotEquals(name, table.string("case.$wanted"), "$name refused as a ${keyCase.name.lowercase()} key")
                    assertEquals(table.string("case.$wanted"), ClientCasing.readsAs(name), name)
                    continue
                }
                val column = if ("__case." in wire) "case." + wire.substringAfter("__case.") else "default ${keyCase.name.lowercase()}"
                val read = table.string(column)
                // A signal key in markup is read as camelCase; an event or class comes back as typed.
                val expected = if (keyCase == KeyCase.CAMEL) ClientCasing.camel(name) else name
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

    /** `attribute-keys.json`: `parseAttributeKey` against [Catalog.parseAttributeName], and the key's casing against core's reading. */
    @Test
    fun `splits an attribute name as parseAttributeKey does`() {
        val cases = cases("attribute-keys.json")
        assertEquals(55, cases.size)
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
            // The fixture holds the function's answer for the text as given; the browser lowercases it first.
            if (raw == raw.lowercase()) assertEquals(held, ClientCasing.readKey(raw.substringAfter(':')), raw)
        }
    }

    /** `expressions.json`: the names `genRx` compiles every expression with, and a plugin's `argNames` after them. */
    @Test
    fun `knows the names an expression is compiled with`() {
        val fixture = fixture("expressions.json")
        assertEquals(fixture.array("parameters")!!.strings(), Catalog.default.compiledParameters)
        val with = fixture.obj("parametersWithArgNames")!!
        assertEquals(with.array("parameters")!!.strings(), Catalog.default.compiledParameters + with.array("argNames")!!.strings())
        assertEquals(setOf("el", "evt", "patch"), Catalog.default.scopeNames)
    }

    /** `expressions.json`: the body `genRx` compiles, against [clientScript] and then [clientRewrite], in the client's order. */
    @Test
    fun `writes the function body genRx compiles`() {
        val cases = group("expressions.json", "rewrites")
        assertEquals(364, cases.size)
        for (case in cases) {
            val expression = case.string("expression")!!
            val returnsValue = case.boolean("returnsValue")!!
            val js = clientRewrite(clientScript(expression, returnsValue).js).js
            assertEquals(case.string("js"), js, "$expression with returnsValue $returnsValue")
        }
    }

    /** The issues the markup check gives [expression] where its value is used, `data-text`, or where it is only run, `data-on:click`. */
    private fun judged(
        expression: String,
        returnsValue: Boolean,
    ): Pair<String, List<Issue>> {
        val attribute = if (returnsValue) "data-text" else "data-on:click"
        val html = "<div $attribute=\"${expression.replace("&", "&amp;").replace("\"", "&quot;")}\"></div>"
        return html to analyzer.analyzeHtml(html)
    }

    /** What the check reports under another code than `expression-syntax`, each with the one it uses. */
    private val brokenUnderAnotherCode = mapOf("@get ('/x')" to "action-space")

    /**
     * `expressions.json`: `compiles` says whether JavaScript took the body `genRx` wrote. The check
     * calls an expression broken exactly there, so an action after a template literal, which the
     * client rewrites from 1.0.5 on, is not an error.
     */
    @Test
    fun `calls an expression broken where the body genRx writes does not compile, and nowhere else`() {
        val wrong = ArrayList<String>()
        for (case in group("expressions.json", "rewrites")) {
            val expression = case.string("expression")!!
            val returnsValue = case.boolean("returnsValue")!!
            val codes = judged(expression, returnsValue).second.map { it.code }
            val compiles = case.boolean("compiles")!!
            val other = brokenUnderAnotherCode[expression]
            if (other != null) {
                assertEquals(false, compiles, expression)
                assertContains(codes, other, expression)
                continue
            }
            if (("expression-syntax" in codes) == compiles) {
                val verdict = if (compiles) "compiles" else "does not compile"
                wrong += "$expression with returnsValue $returnsValue: the client's body $verdict: ${case.string("js")}"
            }
        }
        assertEquals(emptyList(), wrong)
    }

    /** `expressions.json`: a reference the check says the client does not read stands in the body as it was written. */
    @Test
    fun `says a signal is not read only where genRx leaves it as written`() {
        val wrong = ArrayList<String>()
        var said = 0
        for (case in group("expressions.json", "rewrites")) {
            val expression = case.string("expression")!!
            val (html, issues) = judged(expression, case.boolean("returnsValue")!!)
            for (issue in issues.filter { it.code == "signal-not-read" }) {
                said++
                val reference = html.substring(issue.start, issue.end)
                if (reference !in case.string("js")!!) wrong += "$expression: the client rewrote $reference: ${case.string("js")}"
            }
        }
        assertEquals(emptyList(), wrong)
        assertTrue(said >= 6, "only $said references said not to be read")
    }

    /**
     * `expressions.json`: where the client compiles the same body whether the value is used or not, it
     * split nothing, as for an expression that starts with `return` from 1.0.5 on, and the check has
     * no split to warn of.
     */
    @Test
    fun `warns of a value lost at a semicolon only where genRx splits there`() {
        val cases = group("expressions.json", "rewrites")
        val run = cases.filter { it.boolean("returnsValue") == false }.associate { it.string("expression")!! to it.string("js")!! }
        val wrong = ArrayList<String>()
        var warned = 0
        for (case in cases.filter { it.boolean("returnsValue") == true }) {
            val expression = case.string("expression")!!
            if (judged(expression, true).second.none { it.code == "statement-split" }) continue
            warned++
            if (case.string("js") == run.getValue(expression)) wrong += "$expression: the client compiles it whole: ${case.string("js")}"
        }
        assertEquals(emptyList(), wrong)
        assertTrue(warned >= 2, "only $warned expressions warned of")
    }

    private val dslCases: Map<String, JsonObject> =
        JsonParser
            .parseObject(fixtureText("conformance/dsl/free.json"))
            .array("cases")!!
            .objects()
            .associateBy { it.string("name")!! }

    /**
     * `fetch-options.json`: the catalog lists the options a backend action reads from its
     * arguments, and no other. A name the client only has in its type is an option that does nothing.
     */
    @Test
    fun `lists the fetch options the client reads and no other`() {
        val listed = Catalog.default.fetchOptions.map { it.name }
        assertEquals(listed.distinct(), listed)
        val wrong = ArrayList<String>()
        for (case in cases("fetch-options.json")) {
            val action = case.string("action")!!
            val read = case.array("options")!!.strings()
            wrong += (listed - read.toSet()).map { "$it is in the catalog, and @$action never reads it" }
            wrong += (read - listed.toSet()).map { "@$action reads $it, and the catalog does not list it" }
        }
        assertEquals(emptyList(), wrong)
    }

    /**
     * The kind the catalog gives a value of the client's TypeScript type [type], with the strings an
     * enum takes: an `AbortController` comes only from an expression, which no check judges.
     */
    private fun kindOf(type: String): Pair<String, List<String>> {
        val quoted = type.split("|").map { it.trim() }.filter { it != "AbortController" }
        if (quoted.all { it.length > 1 && it.startsWith("'") && it.endsWith("'") }) return "string" to quoted.map { it.trim('\'') }
        val kinds =
            mapOf(
                "string" to "string",
                "number" to "number",
                "boolean" to "boolean",
                "any" to "any",
                "Record<string, string>" to "object",
                "SignalFilterOptions" to "filter",
                "FetchArgs" to "options",
                "() => any" to "function",
                "RegExp | string" to "pattern",
            )
        return (kinds[type] ?: fail("no kind for the client's type $type")) to emptyList()
    }

    private val rawCatalog = JsonParser.parseObject(fixtureText("catalog/datastar-1.0.5.json"))

    private fun JsonObject.kinded(): Pair<String?, List<String>> = string("kind") to (array("values")?.strings() ?: emptyList())

    /**
     * `actions.json`: the kind and the values the catalog gives each fetch option and each key of a
     * signal filter are those of the client's FetchArgs and SignalFilterOptions types.
     */
    @Test
    fun `gives each option the kind the client's type declares`() {
        val actions = fixture("actions.json")
        val wrong = ArrayList<String>()
        for ((table, types) in listOf("fetchOptions" to actions.obj("fetchArgs")!!, "signalFilter" to actions.obj("signalFilter")!!)) {
            val listed = rawCatalog.array(table)?.objects() ?: fail("the catalog has no $table")
            for (field in listed) {
                val name = field.string("name")!!
                val type = types.string(name) ?: fail("$table: the client's type has no $name")
                if (field.kinded() != kindOf(type)) wrong += "$table $name: the catalog says ${field.kinded()}, the client's type is $type"
            }
            if (table == "signalFilter") assertEquals(types.keys.toList(), listed.map { it.string("name") })
        }
        assertEquals(emptyList(), wrong)
    }

    /**
     * `actions.json`: each free action of the catalog takes as many arguments as its apply declares,
     * of the kinds its types say, and those with a default are optional in its signature. A filter
     * is optional too: `filtered` in engine/signals.ts gives it a default of its own.
     */
    @Test
    fun `gives each free action the parameters its source declares`() {
        val declared = cases("actions.json").associateBy { it.string("action")!! }
        val wrong = ArrayList<String>()
        for (action in rawCatalog.array("actions")!!.objects().filter { it.boolean("pro") != true }) {
            val name = action.string("name")!!
            val parameters = declared[name]?.array("parameters")?.objects() ?: fail("the client has no action $name")
            val kinds = action.array("kinds")?.strings() ?: fail("@$name has no kinds in the catalog")
            val signature =
                Catalog.default.actionsByName
                    .getValue(name)
                    .parameters
            val wanted = parameters.map { kindOf(it.string("type")!!).first }
            if (kinds != wanted) wrong += "@$name: the catalog says $kinds, the client declares $wanted"
            val optional = parameters.map { it.boolean("optional") == true || kindOf(it.string("type")!!).first == "filter" }
            if (signature.map { it.endsWith("?") } !=
                optional
            ) {
                wrong += "@$name: the signature $signature, and the client's defaults $optional"
            }
        }
        assertEquals(emptyList(), wrong)
    }

    /** The reading of the case in `conformance/dsl/free.json` whose name starts with [name]. */
    private fun reading(name: String): JsonObject =
        dslCases.entries
            .singleOrNull { it.key.startsWith(name) }
            ?.value
            ?.obj("read") ?: fail("no one case in free.json starts with: $name")

    /**
     * `conformance/dsl/free.json`: what the client did with `retry`, `retryInterval` and
     * `retryMaxCount`, against what the catalog tells a reader of a hover about them. Each claim
     * here was true of 1.0.4 and the reading named beside it shows 1.0.5 doing otherwise.
     */
    @Test
    fun `describes the retry options as the client acts on them`() {
        val doc = Catalog.default.fetchOptions.associate { it.name to it.doc }

        // retry: 'never' sends one request when it fails on the network, and one when its stream breaks.
        assertEquals(1, reading("get never retried gives up on a request that fails").array("requests")!!.size)
        assertEquals(1, reading("get never retried gives up on a stream that breaks").array("requests")!!.size)
        for (claim in listOf("is retried whatever this says", "'auto' and 'never' act alike")) {
            assertTrue(claim !in doc.getValue("retry"), "retry: the catalog still says \"$claim\"")
        }

        // retryMaxCount 2 allows two retries over the whole action, though every answer was a 200.
        for (case in listOf("get retried always stops at retryMaxCount", "get whose stream breaks counts every retry")) {
            assertEquals(3, reading(case).array("requests")!!.size, case)
        }
        for (claim in listOf("in a row", "a 200 answer starts the count again")) {
            assertTrue(claim !in doc.getValue("retryMaxCount"), "retryMaxCount: the catalog still says \"$claim\"")
        }

        // The wait grows from 100 to 200 across a 200 answer whose stream breaks, and is 100 again only after a stream that ended.
        assertEquals(
            """[["setTimeout",100],["setTimeout",200]]""",
            reading("get whose stream breaks counts every retry")["timers"]!!.toJson(),
        )
        assertEquals(
            """[["setTimeout",100],["setTimeout",100]]""",
            reading("get retried always stops at retryMaxCount")["timers"]!!.toJson(),
        )
        assertTrue(
            "set back by a 200 answer" !in doc.getValue("retryInterval"),
            "retryInterval: the catalog still says a 200 answer sets it back",
        )
    }

    /** The catalog is the one for the client the fixtures were read from, and none of its text names another version. */
    @Test
    fun `carries the catalog of the client the fixtures were read from`() {
        val read = fixture("expressions.json").string("version")!!
        assertEquals(read, JsonParser.parseObject(fixtureText("conformance/dsl/free.json")).string("version"))
        assertEquals(read, CatalogText.VERSION)
        val named = Regex("""\b\d+\.\d+\.\d+\b""").findAll(CatalogText.JSON).map { it.value }.toSet()
        assertEquals(setOf(read), named, "the Datastar versions the catalog's text names")
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
                    is JsonNumber -> (ms.toLongOrNull() ?: ms.toDouble()).toString()
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

    /**
     * The fixtures under `conformance/dsl/`: for each DSL call there with a literal key that has a capital, the hint's [wireKey]
     * is the key and `__case` the DSL wrote, and the name the hint says the client reads back is
     * the one the call means, which DslAsTheClientReadsItTest holds to what the client did.
     */
    @Test
    fun `the wire hint names the key the dsl writes and the name the client reads`() {
        var checked = 0
        for (file in listOf("free.json", "pro.json")) {
            val cases = JsonParser.parseObject(fixtureText("conformance/dsl/$file")).array("cases")!!.objects()
            for (case in cases) {
                val call = case.obj("call")!!
                // The hint is shown for a key with a capital, the one the DSL rewrites.
                val key = call.string("key")?.takeIf { k -> k.any { it.isUpperCase() } } ?: continue
                val spec = Catalog.default.attributesByKotlin[call.string("fn")] ?: continue
                if (!spec.keyed || spec.keyCase == null) continue
                val prefix = case.string("prefix")!! + spec.name + ":"
                val names =
                    case.obj("read")!!.array("attributes")!!.flatMap { el ->
                        (el as JsonArray).map { (it as JsonArray)[0].toKotlin() as String }
                    }
                val written = names.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: continue
                val parts = written.split("__")
                val wire = parts[0] + parts.drop(1).filter { it.startsWith("case.") }.joinToString("") { "__$it" }
                assertEquals(wire, wireKey(key, spec.keyCase, call.string("case")), case.string("name"))
                if (call.string("case") == null && spec.keyCase != KeyCase.RAW) {
                    val read = if (spec.keyCase == KeyCase.CAMEL || "__case." in wire) ClientCasing.readKey(wire) else wire
                    assertEquals(key, read, case.string("name"))
                }
                checked++
            }
        }
        assertTrue(checked >= 15, "only $checked cases checked")
    }
}
