package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Holds the answers that are not a stream to `conformance/dom/response-cases.json`: for each, the
 * headers a server sent and the `argsRaw` the client's fetch (plugins/actions/fetch.ts) read from
 * them, recorded by `conformance/generate/dom.mjs`. A header the client read is in `argsRaw` under
 * its camelCase name; one it did not read is not there.
 */
class ResponseAsTheClientReadsTest {
    private val cases: List<JsonObject> =
        (JsonParser.parse(File("../conformance/dom/response-cases.json").readText()) as List<*>).map { it as JsonObject }

    private fun camel(header: String): String =
        header
            .removePrefix("datastar-")
            .split('-')
            .mapIndexed { i, part ->
                if (i ==
                    0
                ) {
                    part
                } else {
                    part.replaceFirstChar { it.uppercase() }
                }
            }.joinToString("")

    /** The headers of [case] the client read an option from. */
    private fun read(case: JsonObject): Map<String, String> {
        val args = case.obj("argsRaw")!!
        return case.obj("headers")!!.filterKeys { args[camel(it)] != null }.mapValues { (_, value) -> (value as JsonString).value }
    }

    private val named: Set<String> =
        DatastarProtocol.Headers::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

    @Test
    fun `names every header the client reads from an answer`() {
        assertEquals(9, cases.size)
        val unnamed = cases.flatMap { read(it).keys }.distinct().filter { it !in named }
        assertEquals(emptyList(), unnamed, "headers the client reads that DatastarProtocol.Headers does not name")
    }

    /** The options the client read, given to the response type, come back as the headers it read them from. */
    @Test
    fun `writes each option as the header the client read it from`() {
        var held = 0
        for (case in cases) {
            val args = case.obj("argsRaw")!!
            val response =
                when (case.string("contentType")) {
                    "text/html" -> {
                        // The view transition's selector has a test of its own: ElementsResponseViewTransitionSelectorTest.
                        if (args.string("viewTransitionSelector") != null) continue
                        ElementsResponse(
                            args.string("elements")!!,
                            selector = args.string("selector"),
                            mode =
                                args.string("mode")?.let { wire -> ElementPatchMode.entries.single { it.wire == wire } }
                                    ?: ElementPatchMode.DEFAULT,
                            namespace =
                                args.string("namespace")?.let { wire -> ElementNamespace.entries.single { it.wire == wire } }
                                    ?: ElementNamespace.DEFAULT,
                            useViewTransition = args.string("useViewTransition") == "true",
                        )
                    }

                    else -> {
                        SignalsResponse(args.string("signals")!!, onlyIfMissing = args.string("onlyIfMissing") == "true")
                    }
                }
            assertEquals(read(case), response.headers, case.string("name"))
            assertEquals(case.string("body"), response.body, case.string("name"))
            held++
        }
        assertEquals(7, held)
    }
}
