package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.html.DatastarAttributes
import io.github.markusaugust.streamlord.html.elements
import kotlinx.html.TagConsumer
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A call to the DSL and what the user means by it, in the client's own terms: the listener it
 * adds, the signal it stores, the class it sets. [expect] is written by hand as literals and never
 * computed with the DSL's own code, so a case holds the DSL to the client rather than to itself.
 */
internal class DslCase(
    val name: String,
    val call: Map<String, Any?>,
    val prefix: String,
    val probe: Map<String, Any?>?,
    val html: String,
    val expect: Expect,
)

internal fun dslCase(
    name: String,
    fn: String,
    key: String? = null,
    case: String? = null,
    prefix: String = "data-",
    probe: Map<String, Any?>? = null,
    patch: (TagConsumer<*>.() -> Unit)? = null,
    expect: Expect.() -> Unit = {},
    render: TagConsumer<*>.() -> Unit,
): DslCase {
    val previous = DatastarAttributes.prefix
    DatastarAttributes.prefix = prefix
    try {
        val html = elements(render)
        // The patch is the DSL's too: the server renders what it sends with the same calls.
        val probed = if (patch == null) probe else (probe ?: emptyMap()) + ("patch" to elements(patch))
        return DslCase(name, mapOf("fn" to fn, "key" to key, "case" to case), prefix, probed, html, Expect().apply(expect))
    } finally {
        DatastarAttributes.prefix = previous
    }
}

/** What the client should do with a case's HTML. Every list is compared whole, so a missing or an extra entry fails. */
internal class Expect {
    val listeners = mutableListOf<List<Any?>>()
    val timers = mutableListOf<List<Any?>>()
    val observers = mutableListOf<Map<String, Any?>>()
    val requests = mutableListOf<Map<String, Any?>>()
    val errors = mutableListOf<String>()
    val fired = mutableListOf<Map<String, Any?>>()
    val attrs = mutableListOf<Triple<String, String, String?>>()
    val texts = mutableListOf<Pair<String, String>>()

    /** The store once the attributes are applied, as JSON. */
    var store: String = "{}"

    /** The store at the end, after the probe's events; the same as [store] unless set. */
    var storeAfter: String? = null

    /** The arguments of each probe() an expression ran, when the case states them. */
    var probes: List<List<Any?>>? = null
    var viewTransitions: Int = 0

    /** A listener added with an options object, as `on` and its kin add theirs. */
    fun listens(
        at: String,
        type: String,
        capture: Boolean = false,
        passive: Boolean = false,
        once: Boolean = false,
    ) {
        listeners += listOf(at, type, mapOf("capture" to capture, "passive" to passive, "once" to once))
    }

    /** A listener added with no options. */
    fun listensPlain(
        at: String,
        type: String,
    ) {
        listeners += listOf(at, type, null)
    }

    fun timer(
        kind: String,
        ms: Number?,
    ) {
        timers += listOf(kind, ms)
    }

    /** Each round of an event the probe sent, with how many expressions ran by the end of it. */
    fun heard(
        at: String,
        type: String,
        vararg runs: Int,
        prevented: Boolean = false,
        bubbled: Boolean = at != "window" && at != "document",
        sync: List<Int>? = null,
    ) {
        runs.forEachIndexed { i, total ->
            fired +=
                mapOf(
                    "at" to at,
                    "type" to type,
                    "round" to i + 1,
                    "sync" to (sync?.get(i) ?: total),
                    "total" to total,
                    "defaultPrevented" to prevented,
                    "bubbled" to bubbled,
                )
        }
    }

    /** How many expressions ran as the element entered the viewport, left it, and entered again. */
    fun intersects(
        enter: Int,
        exit: Int,
        again: Int = enter,
    ) {
        fired += mapOf("at" to "intersection", "type" to "enter", "total" to enter)
        fired += mapOf("at" to "intersection", "type" to "exit", "total" to exit)
        fired += mapOf("at" to "intersection", "type" to "enter", "total" to again)
    }

    fun observes(
        options: Map<String, Any?>?,
        vararg targets: String,
    ) {
        observers += mapOf("options" to options, "targets" to targets.toList())
    }

    /**
     * A request the client's fetch sent, and whether its signal was cancelled by the end: the
     * client cancels it once it is done with the request (dispose in fetch.ts), so only one still
     * waiting for its answer is not.
     */
    fun request(
        method: String,
        url: String,
        body: Any? = null,
        headers: Map<String, Any?> = emptyMap(),
        aborted: Boolean = true,
    ) {
        requests += mapOf("url" to url, "method" to method, "headers" to headers, "body" to body, "aborted" to aborted)
    }

    /** An attribute the element holds at the end; a null [value] says it holds none of that name. */
    fun attr(
        at: String,
        name: String,
        value: String?,
    ) {
        attrs += Triple(at, name, value)
    }

    fun text(
        at: String,
        text: String,
    ) {
        texts += at to text
    }
}

/** The headers the client's fetch sends every request, before its content type and the user's. */
internal val ACCEPT = mapOf("Accept" to "text/event-stream, text/html, application/json", "Datastar-Request" to true)
internal val JSON_BODY = ACCEPT + ("Content-Type" to "application/json")

/**
 * The fixture under conformance/dsl that a test's cases are written to and read back from: the
 * Kotlin side writes each case's name, call, prefix, probe and html under
 * `./gradlew :streamlord-html-pro:updateClientReadings`, and `npm run dsl` in conformance/generate
 * fills in what the client read.
 */
internal class ClientReadings(
    private val file: String,
    private val comment: String,
    private val proAttributes: List<String>? = null,
    private val proActions: List<String>? = null,
) {
    private val path = File(System.getProperty("streamlord.dslDir") ?: "../conformance/dsl", file)

    // The script that reads the cases and what it shares with the other generators, as dsl.mjs hashes them.
    private val generator = listOf("dsl.mjs", "common.mjs").map { File(path.parentFile.parentFile, "generate/$it") }
    private val updating = System.getProperty("streamlord.updateClientReadings") == "true"

    fun check(cases: List<DslCase>) {
        val names =
            cases
                .groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        check(names.isEmpty()) { "Case names must be unique: $names" }
        val written = if (path.exists()) JsonParser.parseObject(path.readText()) else null
        val reads = written?.array("cases")?.objects()?.associate { it.string("name")!! to it } ?: emptyMap()
        if (updating) return write(cases, written, reads)

        assertNotNull(written, "$path is missing: run ./gradlew :streamlord-html-pro:updateClientReadings, then npm run dsl")
        assertEquals(
            cases.map { it.name },
            written.array("cases")!!.objects().map { it.string("name") },
            "$file holds other cases than the test: run ./gradlew :streamlord-html-pro:updateClientReadings, then npm run dsl",
        )
        val failures = mutableListOf<String>()
        if (written.string("generator") != sha256(generator.fold(ByteArray(0)) { bytes, script -> bytes + script.readBytes() })) {
            failures += "$file was read by another dsl.mjs or common.mjs: run npm run dsl in conformance/generate"
        }
        if (canon(written["proAttributes"]) != canon(proAttributes) || canon(written["proActions"]) != canon(proActions)) {
            failures += "$file names other Pro attributes or actions than the test: run updateClientReadings, then npm run dsl"
        }
        for (case in cases) {
            val stored = reads.getValue(case.name)
            if (stored.string("html") != case.html || stored.string("prefix") != case.prefix ||
                canon(stored["probe"]) != canon(case.probe) || canon(stored["call"]) != canon(case.call)
            ) {
                failures += "${case.name}: the case is not the one $file holds, whose html is ${stored.string("html")}: " +
                    "run ./gradlew :streamlord-html-pro:updateClientReadings, then npm run dsl"
                continue
            }
            val read = stored.obj("read")
            if (read == null) {
                failures += "${case.name}: no reading in $file: run npm run dsl in conformance/generate"
                continue
            }
            failures += compare(case, read)
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    /** Every case in the file with its reading, for the coverage the client parsed. */
    fun written(): List<JsonObject> =
        JsonParser
            .parseObject(path.readText())
            .array("cases")!!
            .objects()
            .onEach { if (it.obj("read") == null) fail("${it.string("name")}: no reading in $file: run npm run dsl") }

    /**
     * Fails unless the cases reach every point of the catalog but [exempt], each with its reason,
     * and the client parsed no modifier the catalog does not list.
     */
    fun checkCoverage(
        pro: Boolean,
        exempt: Map<String, String>,
    ) {
        if (updating) return
        val coverage = CatalogCoverage(pro)
        val points = coverage.points()
        val reached = coverage.reached(written())
        assertEquals(emptySet(), exempt.keys - points, "exemptions the catalog does not name")
        assertEquals(emptySet(), exempt.keys.intersect(reached), "exemptions a case reaches after all")
        assertEquals(emptySet(), points - reached - exempt.keys, "catalog entries no case in $file reaches")
        val modifiers = reached.filter { it.contains(" __") && !it.substringAfter(" __").contains('.') }.toSet()
        assertEquals(emptySet(), modifiers - points, "modifiers the client parsed that the catalog does not list")
    }

    private fun write(
        cases: List<DslCase>,
        written: JsonObject?,
        reads: Map<String, JsonObject>,
    ) {
        val lines =
            cases.map { case ->
                val stored = reads[case.name]
                val same =
                    stored != null && stored.string("html") == case.html && stored.string("prefix") == case.prefix &&
                        canon(stored["probe"]) == canon(case.probe)
                val entry =
                    linkedMapOf<String, Any?>(
                        "name" to case.name,
                        "call" to case.call,
                        "prefix" to case.prefix,
                    )
                case.probe?.let { entry["probe"] = it }
                entry["html"] = case.html
                entry["read"] = if (same) stored!!["read"]?.toKotlin() else null
                "    " + JsonWriter.write(entry)
            }
        val header = linkedMapOf<String, Any?>("\$comment" to comment)
        written?.let { w -> listOf("version", "bundles", "generator").forEach { k -> w[k]?.let { header[k] = it.toKotlin() } } }
        proAttributes?.let { header["proAttributes"] = it }
        proActions?.let { header["proActions"] = it }
        val head = header.entries.joinToString(",\n") { (k, v) -> "  ${JsonWriter.writeString(k)}: ${JsonWriter.write(v)}" }
        path.parentFile.mkdirs()
        path.writeText("{\n$head,\n  \"cases\": [\n${lines.joinToString(",\n")}\n  ]\n}\n")
    }

    private fun compare(
        case: DslCase,
        read: JsonObject,
    ): List<String> {
        val e = case.expect
        val out = mutableListOf<String>()

        fun same(
            what: String,
            expected: Any?,
            actual: Any?,
        ) {
            if (canon(expected) !=
                canon(actual)
            ) {
                out += "${case.name}: $what\n  expected ${canon(expected)}\n  but the client did ${canon(actual)}"
            }
        }
        same("errors", e.errors, read["errors"]?.toKotlin())
        same("listeners", e.listeners, read["listeners"]?.toKotlin())
        same("timers", e.timers, read["timers"]?.toKotlin())
        same("observers", e.observers, read["observers"]?.toKotlin())
        same("requests", e.requests, read["requests"]?.toKotlin())
        same("events", e.fired, read["fired"]?.toKotlin())
        same("store", JsonParser.parse(e.store).toKotlin(), JsonParser.parse(read.string("storeBefore")!!).toKotlin())
        same("store at the end", JsonParser.parse(e.storeAfter ?: e.store).toKotlin(), JsonParser.parse(read.string("store")!!).toKotlin())
        same("view transitions", e.viewTransitions, read["viewTransitions"]?.toKotlin())
        same("timers the clock never reached", 0, read["leftTimers"]?.toKotlin())
        e.probes?.let { same("probes", it, read["probes"]?.toKotlin()) }
        val after = read.array("after")!!.associate { el -> (el as JsonArray).let { it[0].toKotlin() as String to it } }
        for ((at, name, value) in e.attrs) {
            val element =
                after[at] ?: run {
                    out += "${case.name}: no element $at"
                    null
                } ?: continue
            val held = (element[1] as JsonArray).map { it.toKotlin() as List<*> }.firstOrNull { it[0] == name }?.get(1)
            same("attribute $name of $at", value, held)
        }
        for ((at, text) in e.texts) same("text of $at", text, after[at]?.get(2)?.toKotlin())
        return out
    }
}

private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

/** [value] as JSON with the keys of every object sorted, so two values compare by content. */
internal fun canon(value: Any?): String =
    JsonWriter.write(
        when (value) {
            is JsonValue -> sorted(value.toKotlin())
            else -> sorted(value)
        },
    )

private fun sorted(value: Any?): Any? =
    when (value) {
        is Map<*, *> -> {
            value.entries.sortedBy { it.key as String }.associate { it.key to sorted(it.value) }
        }

        is List<*> -> {
            value.map { sorted(it) }
        }

        is Array<*> -> {
            value.map { sorted(it) }
        }

        is Number -> {
            value.toDouble().let {
                if (it == Math.floor(it) && !it.isInfinite() &&
                    kotlin.math.abs(it) < 1e15
                ) {
                    it.toLong()
                } else {
                    it
                }
            }
        }

        else -> {
            value
        }
    }

/**
 * Which entries of `catalog/datastar-1.0.4.json` the cases reach, counted from the attributes the
 * client parsed out of each case's HTML and the action calls in their values, never from a
 * case's name: an attribute in each of its forms, each modifier of it, each tag a duration
 * modifier takes, each value of an enum modifier, each action and each fetch option, and each
 * Kotlin function, which must have written an attribute the catalog gives it.
 */
internal class CatalogCoverage(
    private val pro: Boolean,
) {
    private val catalog = JsonParser.parseObject(File("../catalog/datastar-${DatastarProtocol.VERSION}.json").readText())
    private val groups = catalog.obj("modifierGroups")!!
    private val attributes = catalog.array("attributes")!!.objects().filter { (it.boolean("pro") ?: false) == pro }
    private val actions = catalog.array("actions")!!.objects().filter { (it.boolean("pro") ?: false) == pro }

    private fun modifiers(attribute: JsonObject): List<JsonObject> =
        attribute.array("modifiers")!!.objects().flatMap { m -> m.string("\$ref")?.let { groups.array(it)!!.objects() } ?: listOf(m) }

    /** Every point the catalog names: `attribute keyed`, `attribute __modifier`, `attribute __modifier.tag`, `@action`, `option x`, `fn()`. */
    fun points(): Set<String> =
        buildSet {
            for (a in attributes) {
                val name = a.string("name")!!
                val forms = a.array("forms")!!.strings()
                if (forms.any { it.startsWith("data-$name:") }) add("$name keyed")
                if (forms.any { !it.startsWith("data-$name:") }) add("$name plain")
                for (m in modifiers(a)) {
                    val mod = m.string("name")!!
                    add("$name __$mod")
                    for (tag in m.array("values")?.strings().orEmpty() + m.array("flags")?.strings().orEmpty()) add("$name __$mod.$tag")
                }
                for (fn in a.array("kotlin")!!.strings()) add("$fn()")
            }
            for (action in actions) {
                add("@${action.string("name")}")
                add("${action.string("kotlin")}()")
            }
            if (!pro) for (option in catalog.array("fetchOptions")!!.objects()) add("option ${option.string("name")}")
        }

    /** The points the fixture's cases reach. */
    fun reached(cases: List<JsonObject>): Set<String> =
        buildSet {
            val byName = attributes.associateBy { it.string("name")!! }
            for (case in cases) {
                val prefix = case.string("prefix")!!
                val fn = case.obj("call")!!.string("fn")!!
                val read = case.obj("read") ?: continue
                for (element in read.array("attributes")!!) {
                    for (attribute in element as JsonArray) {
                        val (rawName, value) = (attribute as JsonArray).map { it.toKotlin() as String }
                        for (action in actions) {
                            val call = "@${action.string("name")}("
                            if (call in value) {
                                add("@${action.string("name")}")
                                if (fn == action.string("kotlin")) add("$fn()")
                            }
                        }
                        if (!pro && Regex("@(get|post|put|patch|delete|query)\\(").containsMatchIn(value)) {
                            for (option in catalog.array("fetchOptions")!!.objects()) {
                                if ("${option.string("name")}: " in value) add("option ${option.string("name")}")
                            }
                        }
                        if (!rawName.startsWith(prefix)) continue
                        // As the client's parseAttributeKey splits it.
                        val parts = rawName.removePrefix(prefix).split("__")
                        val plugin = parts[0].substringBefore(':')
                        val a = byName[plugin] ?: continue
                        add(if (':' in parts[0]) "$plugin keyed" else "$plugin plain")
                        if (fn in a.array("kotlin")!!.strings()) add("$fn()")
                        for (raw in parts.drop(1)) {
                            val label = raw.substringBefore('.')
                            add("$plugin __$label")
                            for (tag in raw.split('.').drop(1)) add("$plugin __$label.$tag")
                        }
                    }
                }
            }
        }
}
