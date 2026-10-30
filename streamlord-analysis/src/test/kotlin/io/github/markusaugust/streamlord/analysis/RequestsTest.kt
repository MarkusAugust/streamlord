package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.mergePatch
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cases of `twins/requests.json`, which the VS Code extension's `requests.test.ts` reads too. */
class RequestsTest {
    // twins/request-urls.json, which requests.test.ts and core's ClientRequestTest read too.
    @Test
    fun `opens the url the client opens`() {
        val cases = twinCases("request-urls.json")
        for (c in cases) {
            assertEquals(
                c.string("expected"),
                Requests.requestUrl(c.string("url")!!, c.string("method")!!, c.string("signals")!!),
                c.toJson(),
            )
        }
        assertTrue(cases.isNotEmpty())
    }

    private fun request(r: JsonObject?) =
        SavedRequest(
            r?.string("name") ?: "counter",
            r?.string("url") ?: "{{baseUrl}}/api/counter-stream",
            r?.string("method") ?: "GET",
            r?.string("signals") ?: "",
            r?.string("headers") ?: "",
        )

    private fun shown(r: SavedRequest) =
        mapOf(
            "name" to r.name,
            "url" to r.url,
            "method" to r.method,
            "signals" to r.signals,
            "headers" to r.headers,
        )

    private fun running(c: JsonObject) = c.obj("running")?.let { RunningServer(it.string("url")!!, it.string("name")!!) }

    private fun variables(c: JsonObject) =
        Requests.mergeVariables(c.string("defaultUrl")!!, Requests.parseEnv(c.string("env")!!), running(c))

    private fun pairs(list: List<Pair<String, String>>?) = list?.map { listOf(it.first, it.second) }

    // The projections of requests.test.ts, so that a case means the same in both twins.
    private val calls: Map<String, (JsonObject) -> Any?> =
        mapOf(
            "parseRequestsFile" to { c ->
                val file =
                    c.string("path")?.let { Requests.parseRequestsFile(c.string("text")!!, it) }
                        ?: Requests.parseRequestsFile(c.string("text")!!)
                val again = Requests.parseRequestsFile(Requests.serializeRequestsFile(file)).requests
                mapOf("requests" to file.requests.map(::shown), "invalid" to (file.error != null), "roundTrips" to (again == file.requests))
            },
            "serializeRequestsFile" to { c ->
                Requests.serializeRequestsFile(RequestsFile(c.array("requests")!!.objects().map(::request)))
            },
            "upsert" to { c ->
                var file = RequestsFile.EMPTY
                for (r in c.array("upsert")!!.objects()) file = Requests.upsert(file, request(r))
                c.string("remove")?.let { file = Requests.remove(file, it) }
                file.requests.map { listOf(it.name, it.url) }
            },
            "pushRecent" to { c ->
                c
                    .array("push")!!
                    .objects()
                    .fold(emptyList<SavedRequest>()) { recent, r -> Requests.pushRecent(recent, request(r)) }
                    .map { listOf(it.name, it.url) }
            },
            "substitute" to { c ->
                val r = Requests.substitute(c.string("text")!!, c.obj("vars")!!.mapValues { (it.value as JsonString).value })
                mapOf("text" to r.text, "missing" to r.missing)
            },
            "toCurl" to { c -> Requests.toCurl(request(c.obj("request"))) },
            "sendableUrl" to { c ->
                val r = Requests.sendableUrl(c.string("url")!!)
                mapOf("url" to r.url, "error" to r.error)
            },
            "sends" to { c -> Requests.sendableUrl(c.string("url")!!).url != null },
            "readsAsStream" to { c -> Requests.readsAsStream(c.int("status")!!, c.string("contentType")) },
            "emptyBodyHint" to { c -> Requests.emptyBodyHint(c.int("status")!!, c.string("body")!!) },
            "requestHeaders" to { c ->
                val headers = c.array("headers")!!.map { (it as JsonArray).strings().let { (k, v) -> k to v } }.toMap(LinkedHashMap())
                Requests.requestHeaders(c.string("method")!!, headers).map { listOf(it.key, it.value) }
            },
            "requestsPathProblem" to { c -> Requests.requestsPathProblem(c.string("path")!!) != null },
            "newRequestLabel" to { c -> Requests.newRequestLabel(c.int("saved")!!, c.int("recent")!!) },
            "lineAndColumn" to { c -> Requests.lineAndColumn(c.string("text")!!, c.int("position")!!) },
            "parseEnv" to { c ->
                val env = Requests.parseEnv(c.string("text")!!)
                mapOf(
                    "baseUrl" to env.baseUrl,
                    "signals" to env.signals,
                    "headers" to pairs(env.headers),
                    "params" to pairs(env.params),
                    "errors" to env.errors,
                )
            },
            "variables" to { c ->
                val vars = variables(c)
                mapOf(
                    "variables" to
                        vars.map {
                            mapOf(
                                "name" to it.name,
                                "value" to it.value,
                                "source" to it.source.name.lowercase(),
                                "origin" to it.origin,
                            )
                        },
                    "described" to Requests.describeVariables(vars),
                )
            },
            "resolveRequest" to { c ->
                val r = Requests.resolveRequest(request(c.obj("request")), variables(c))
                mapOf(
                    "url" to r.request.url,
                    "signals" to r.request.signals,
                    "headers" to r.request.headers,
                    "errors" to r.errors,
                    "unset" to r.unset,
                )
            },
            "unreachableHint" to { c -> Requests.unreachableHint(c.string("url")!!, variables(c), running(c)) },
            "baseUrlSuggestion" to { c -> Requests.baseUrlSuggestion(variables(c), running(c)) },
            "variableCompletions" to { c ->
                val field = Requests.Field.valueOf(c.string("field")!!.uppercase())
                Requests.variableCompletions(c.string("text")!!, c.int("caret")!!, field, variables(c))?.let { m ->
                    mapOf("from" to m.from.toLong(), "to" to m.to.toLong(), "names" to m.items.map { it.name })
                }
            },
            "withEnvVariables" to { c -> Requests.withEnvVariables(c.string("text"), c.array("keys")!!.strings(), c.string("baseUrl")!!) },
            "withBaseUrl" to { c -> Requests.withBaseUrl(c.string("text"), c.string("url")!!) },
            "routeRequest" to { c ->
                findRoutes(c.array("source")!!.strings().joinToString("\n")).map {
                    mapOf("url" to Requests.routeUrl(it), "headers" to Requests.routeHeaders(it), "optional" to Requests.routeOptional(it))
                }
            },
        )

    @TestFactory
    fun `inspector requests`(): List<DynamicTest> =
        twinCases("requests.json").map { c ->
            val name = c.string("name")!!
            DynamicTest.dynamicTest(name) {
                val call = calls[c.string("call")] ?: error("$name: no call ${c.string("call")}")
                assertEquals(c["expected"]!!.toKotlin(), call(c), name)
            }
        }

    // The rule is written out by hand so the VS Code extension can hold the same one; this holds
    // the shared verdicts to the JDK, which is the client that has to accept what we let through.
    @Test
    fun `refuses exactly the hosts and ports the HTTP client refuses`() {
        val cases = twinCases("requests.json").filter { it.string("call") == "sends" }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val url = c.string("url")!!
            val jdk = runCatching { HttpRequest.newBuilder(URI(url)).build() }.isSuccess
            assertEquals(jdk, c.boolean("expected"), "$url: the JDK ${if (jdk) "sends" else "refuses"} it")
        }
    }

    // Where a JSON reader stops is its own, so requests.test.ts says less here.
    @Test
    fun `text that is not JSON is named with where it stops`() {
        assertEquals(
            RequestsFile(emptyList(), ".streamlord/inspector.json is not valid JSON at line 1, column 1."),
            Requests.parseRequestsFile("not json"),
        )
        assertEquals(listOf(".streamlord/env.json is not valid JSON at line 1, column 1."), Requests.parseEnv("not json").errors)
        val broken = Requests.parseRequestsFile("{\"requests\":[{\"name\":\"a\",\"url\":\"/a\"},]}", "req.json")
        assertTrue(broken.error!!.startsWith("req.json is not valid JSON"), broken.error)
    }
}
