package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureNames
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The catalog's `events` are every call of core, Ktor's and Spring's public API that builds an SSE
 * event or an answer that is no stream, read from the API dumps the build checks the modules
 * against: a call added to the API without the catalog, or one the catalog keeps after it is gone,
 * is red here.
 */
class EventCallsJvmTest {
    private val stream = "io/github/markusaugust/streamlord/core/port/driving/DatastarStream"

    // The calls that send an event or an answer built elsewhere, or none at all.
    private val takesAnEvent = setOf("send", "sendAll", "sendLatest", "comment", "getCodec", "respondDatastar", "toResponseEntity")

    /** The classes of [api] and the names of their functions, without Kotlin's mangling and `$default`. */
    private fun classes(api: String): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        for (line in api.lines()) {
            val head = Regex("""^public .*class (\S+)""").find(line)
            if (head != null) {
                current = out.getOrPut(head.groupValues[1]) { LinkedHashSet() }
                if (line.contains(": io/github/markusaugust/streamlord/core/domain/Datastar")) current += ":event"
                continue
            }
            val fn = Regex("""^\s+public .*fun ([A-Za-z_][A-Za-z0-9_]*)""").find(line) ?: continue
            if ("synthetic" in line && "\$default" !in line) continue
            current?.add(fn.groupValues[1])
        }
        return out
    }

    private fun api(module: String) = classes(fixtureText("$module/api/$module.api"))

    @Test
    fun `the catalog lists every call of the public API that builds an event or an answer, and no other`() {
        val core = api("streamlord-core")
        val events = core.filterValues { ":event" in it }.keys.map { it.substringAfterLast('/') }
        val companions =
            events.flatMap { event ->
                core["io/github/markusaugust/streamlord/core/domain/$event\$Companion"].orEmpty().map { "$event.$it" }
            }
        val adapters =
            api("streamlord-ktor")["io/github/markusaugust/streamlord/ktor/ResponsesKt"].orEmpty() +
                api("streamlord-spring")["io/github/markusaugust/streamlord/spring/ResponsesKt"].orEmpty()
        val public = (core.getValue(stream) + events + companions + adapters).filter { it != ":event" && it !in takesAnEvent }.toSet()

        val catalog = JsonParser.parseObject(fixtureText("catalog/" + fixtureNames("catalog").single { it.startsWith("datastar-") }))
        val listed =
            catalog
                .obj("kotlinCallSites")
                ?.obj("events")
                ?.keys
                ?.filter { !it.startsWith("$") }
                .orEmpty()
                .toSet()
        assertEquals(public.sorted(), listed.sorted())
    }
}
