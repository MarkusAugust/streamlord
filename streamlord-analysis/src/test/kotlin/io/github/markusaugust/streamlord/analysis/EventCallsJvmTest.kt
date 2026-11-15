package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonBoolean
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureNames
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The catalog's `events` are every call of core, streamlord-html, Ktor's and Spring's public API
 * that builds an SSE event or an answer that is no stream, read from the API dumps the build checks
 * the modules against, with the flags a dump shows: `stream`, a call that takes a `retry`, and
 * `response`, an answer. A dump cannot show the rest: every argument of these calls is a `String`
 * there, `Html` included, so `takes`, `names` and `json` are held by the cases the editors are
 * judged by; and it leaves out an inline function with a reified type, so `value` is held by
 * reflection in the tests of core, Ktor and Spring (`ValueOverloadsJvmTest`).
 */
class EventCallsJvmTest {
    private val stream = "io/github/markusaugust/streamlord/core/port/driving/DatastarStream"
    private val domain = "io/github/markusaugust/streamlord/core/domain/"
    private val classLine = Regex("""^public .*class (\S+)(.*)""")
    private val functionLine = Regex("""^\s+public .*fun ([A-Za-z_<][A-Za-z0-9_>]*)\S* \(([^)]*)\)""")

    // The calls that send an event or an answer built elsewhere, or none at all.
    private val takesAnEvent = setOf("send", "sendAll", "sendLatest", "comment", "getCodec", "respondDatastar", "toResponseEntity")

    /** A class of a dump: what it extends, and each function's parameter lists, by its name without Kotlin's mangling. */
    private class ApiClass(
        val supers: String,
        val functions: MutableMap<String, MutableList<String>> = LinkedHashMap(),
    )

    private fun api(module: String): Map<String, ApiClass> {
        val out = LinkedHashMap<String, ApiClass>()
        var current: ApiClass? = null
        for (line in fixtureText("$module/api/$module.api").lines()) {
            val head = classLine.find(line)
            if (head != null) {
                current = ApiClass(head.groupValues[2]).also { out[head.groupValues[1]] = it }
                continue
            }
            val fn = functionLine.find(line) ?: continue
            // A constructor that takes a value class is synthetic, with a marker; so is each function's `$default`.
            if ("synthetic" in line && "\$default" !in line && "DefaultConstructorMarker" !in line) continue
            current?.functions?.getOrPut(fn.groupValues[1]) { ArrayList() }?.add(fn.groupValues[2])
        }
        return out
    }

    @Test
    fun `the catalog lists every call of the public API that builds an event or an answer, with the flags the dumps show`() {
        val core = api("streamlord-core")
        // Each call by its name: the parameter lists of its overloads, and whether it is an answer.
        val calls = HashMap<String, Pair<MutableList<String>, Boolean>>()

        fun add(
            name: String,
            parameters: List<String>,
            response: Boolean,
        ) {
            if (name !in takesAnEvent) calls.getOrPut(name) { ArrayList<String>() to response }.first += parameters
        }
        for ((name, parameters) in core.getValue(stream).functions) add(name, parameters, response = false)
        for ((path, type) in core.filterValues { "${domain}Datastar" in it.supers }) {
            val event = path.substringAfterLast('/')
            val response = "${domain}DatastarResponse" in type.supers
            add(event, type.functions["<init>"].orEmpty(), response)
            for ((name, parameters) in core["$path\$Companion"]?.functions.orEmpty()) add("$event.$name", parameters, response)
        }
        // streamlord-html's patchElements of markup the DSL builds, on a stream.
        for ((name, parameters) in api("streamlord-html").getValue("io/github/markusaugust/streamlord/html/ElementsKt").functions) {
            if (parameters.any { it.startsWith("L$stream;") }) add(name, parameters, response = false)
        }
        for ((module, adapter) in listOf("streamlord-ktor" to "ktor", "streamlord-spring" to "spring")) {
            for ((name, parameters) in api(module).getValue("io/github/markusaugust/streamlord/$adapter/ResponsesKt").functions) {
                add(name, parameters, response = true)
            }
        }

        val catalog = JsonParser.parseObject(fixtureText("catalog/" + fixtureNames("catalog").single { it.startsWith("datastar-") }))
        val listed =
            catalog
                .obj("kotlinCallSites")!!
                .obj("events")!!
                .filterKeys { !it.startsWith("$") }
                .mapValues { it.value as JsonObject }

        fun flags(
            stream: Boolean,
            response: Boolean,
        ) = listOfNotNull("stream".takeIf { stream }, "response".takeIf { response })
        assertEquals(
            calls.mapValues { (_, call) -> flags(call.first.any { "Lkotlin/time/Duration;" in it }, call.second) }.toSortedMap(),
            listed.mapValues { (_, e) -> flags(e["stream"] == JsonBoolean.TRUE, e["response"] == JsonBoolean.TRUE) }.toSortedMap(),
        )
    }
}
