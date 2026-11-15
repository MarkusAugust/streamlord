package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cases of `cases/requests.json`, which the extension's `inspectorPage.test.ts` reads for the inspector page's own `previewUrl`. */
class RequestsTest {
    // cases/request-urls.json, which the extension's `requestUrls.test.ts` and core's ClientRequestTest read too.
    @Test
    fun `opens the url the client opens`() {
        val cases = sharedCases("request-urls.json")
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

    private fun variables(c: JsonObject) = Requests.environment(c.string("env")!!, c.string("defaultUrl")!!, running(c)).vars

    /** A requests file holding [requests], as saving each in turn writes it. */
    private fun written(requests: List<SavedRequest>): String =
        requests.fold(null as String?) { text, r -> Requests.saveRequest(text, r).text } ?: Requests.saveRequest(null, request(null)).let {
            Requests.removeRequest(it.text, "counter").text!!
        }

    /** What [url] is sent to, as the stream client sends a request whose signals go in its body. */
    private fun sent(url: String) = Requests.streamRequest(url, "POST", "{}", emptyMap())

    private fun edit(e: RequestsEdit) = mapOf("text" to e.text, "error" to e.error)

    private fun pairs(list: List<Pair<String, String>>?) = list?.map { listOf(it.first, it.second) }

    // The projection of each call to the fields a case holds, so that a case means the same on the JVM and on Node.
    private val calls: Map<String, (JsonObject) -> Any?> =
        mapOf(
            "parseRequestsFile" to { c ->
                val file =
                    c.string("path")?.let { Requests.parseRequestsFile(c.string("text")!!, it) }
                        ?: Requests.parseRequestsFile(c.string("text")!!)
                // Saving each request read back into the file's own text leaves what it reads.
                val saved = file.requests.fold(c.string("text")!!) { text, r -> Requests.saveRequest(text, r).text ?: text }
                val again = Requests.parseRequestsFile(saved).requests
                val expected = c.obj("expected")!!
                buildMap {
                    put("requests", file.requests.map(::shown))
                    put("invalid", file.error != null)
                    put("roundTrips", again == file.requests)
                    // The text of an error that names a position is each reader's own, so a case states these two only when it pins them.
                    if ("error" in expected) put("error", file.error)
                    if ("skipped" in expected) put("skipped", file.skipped)
                }
            },
            "saveRequest" to { c ->
                val request = request(c.obj("request"))
                edit(
                    c.string("path")?.let { Requests.saveRequest(c.string("text"), request, it) }
                        ?: Requests.saveRequest(c.string("text"), request),
                )
            },
            "removeRequest" to { c ->
                val name = c.string("remove")!!
                edit(
                    c.string("path")?.let { Requests.removeRequest(c.string("text"), name, it) }
                        ?: Requests.removeRequest(c.string("text"), name),
                )
            },
            "serializeRequestsFile" to { c ->
                written(c.array("requests")!!.objects().map(::request))
            },
            "upsert" to { c ->
                // Through the edits of the file's text, as a store saves and deletes.
                var text: String? = null
                for (r in c.array("upsert")!!.objects()) text = Requests.saveRequest(text, request(r)).text
                c.string("remove")?.let { text = Requests.removeRequest(text, it).text }
                Requests.parseRequestsFile(text!!).requests.map { listOf(it.name, it.url) }
            },
            "pushRecent" to { c ->
                c
                    .array("push")!!
                    .objects()
                    .fold(emptyList<SavedRequest>()) { recent, r -> Requests.pushRecent(recent, request(r)) }
                    .map { listOf(it.name, it.url) }
            },
            "substitute" to { c ->
                // As a request's address is filled in.
                val vars = c.obj("vars")!!.map { (k, v) -> Requests.Variable(k, (v as JsonString).value, Requests.VariableSource.ENV) }
                val r = Requests.resolveRequest(SavedRequest("x", c.string("text")!!), vars)
                mapOf("text" to r.request.url, "missing" to r.unset)
            },
            "toCurl" to { c -> Requests.toCurl(request(c.obj("request"))) },
            "sendableUrl" to { c ->
                val r = sent(c.string("url")!!)
                mapOf("url" to (r as? Requests.StreamRequest.Sent)?.url, "error" to (r as? Requests.StreamRequest.Refused)?.error)
            },
            "sends" to { c -> sent(c.string("url")!!) is Requests.StreamRequest.Sent },
            "signalsRefused" to { c -> Requests.signalsProblem(c.string("signals")!!) != null },
            "readsAsStream" to { c -> Requests.readsAsStream(c.int("status")!!, c.string("contentType")) },
            "emptyBodyHint" to { c -> Requests.emptyBodyHint(c.int("status")!!, c.string("body")!!) },
            "requestHeaders" to { c ->
                val headers = c.array("headers")!!.map { (it as JsonArray).strings().let { (k, v) -> k to v } }.toMap(LinkedHashMap())
                Requests.requestHeaders(c.string("method")!!, headers).map { listOf(it.key, it.value) }
            },
            "requestsPathProblem" to { c -> Requests.requestsPathProblem(c.string("path")!!) != null },
            "requestsPathMessage" to { c -> Requests.requestsPathProblem(c.string("path")!!) },
            // The two older messages say "project" here and "workspace" in the extension, so a case names the part they share.
            "requestsPathProblemSays" to { c -> Requests.requestsPathProblem(c.string("path")!!)?.contains(c.string("says")!!) ?: false },
            "newRequestLabel" to { c -> Requests.newRequestLabel(c.int("saved")!!, c.int("recent")!!) },
            "parseEnv" to { c ->
                // As the variables a request may name: the file's own, each by its key.
                val env = Requests.environment(c.string("text")!!, "http://default", null)
                val own = env.vars.filter { it.source == Requests.VariableSource.ENV }

                fun named(key: String) = own.firstOrNull { it.name == key }?.value
                val headers = named("headers")?.lines()?.map { listOf(it.substringBefore(": "), it.substringAfter(": ")) }
                val params = own.filter { it.name !in setOf("baseUrl", "signals", "headers") }.map { listOf(it.name, it.value) }
                mapOf(
                    "baseUrl" to named("baseUrl"),
                    "signals" to named("signals"),
                    "headers" to headers,
                    "params" to params.ifEmpty { null },
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
            "previewUrl" to { c -> Requests.previewUrl(c.string("url")!!, variables(c)) },
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

    @Test
    fun `inspector requests`() =
        eachCase(sharedCases("requests.json"), { it.string("name")!! }) { c ->
            val name = c.string("name")!!
            val call = calls[c.string("call")] ?: error("$name: no call ${c.string("call")}")
            assertEquals(c["expected"]!!.toKotlin(), call(c), name)
        }

    @Test
    fun `a path that leaves the folder is named by what the editor calls that folder`() {
        assertEquals(
            "The requests file ../r.json leaves the workspace: use a path without .. in it.",
            Requests.requestsPathProblem("../r.json", "workspace"),
        )
        assertEquals(
            "The requests file /r.json is not inside the workspace: use a workspace-relative path.",
            Requests.requestsPathProblem("/r.json", "workspace"),
        )
        assertEquals(Requests.requestsPathProblem("../r.json"), Requests.requestsPathProblem("../r.json", "project"))
        assertEquals(null, Requests.requestsPathProblem("a/r.json", "workspace"))
    }

    @Test
    fun `text that is not JSON is named with where it stops`() {
        assertEquals(
            RequestsFile(emptyList(), ".streamlord/inspector.json is not valid JSON at line 1, column 1."),
            Requests.parseRequestsFile("not json"),
        )
        assertEquals(
            listOf(".streamlord/env.json is not valid JSON at line 1, column 1."),
            Requests.environment("not json", "http://default", null).errors,
        )
        val broken = Requests.parseRequestsFile("{\"requests\":[{\"name\":\"a\",\"url\":\"/a\"},]}", "req.json")
        assertTrue(broken.error!!.startsWith("req.json is not valid JSON"), broken.error)
    }

    @Test
    fun `a file that is not JSON is not changed, and the edit says where it stops`() {
        val refused = RequestsEdit(null, ".streamlord/inspector.json is not valid JSON at line 1, column 1.")
        assertEquals(refused, Requests.saveRequest("not json", SavedRequest("a", "/a")))
        assertEquals(refused, Requests.removeRequest("not json", "a"))
        val broken = Requests.saveRequest("{\"requests\":[{\"name\":\"a\",\"url\":\"/a\"},]}", SavedRequest("b", "/b"), "req.json")
        assertEquals(null, broken.text)
        assertTrue(broken.error!!.startsWith("req.json is not valid JSON"), broken.error)
    }

    // A text field hands back the signals it was given, line endings and all.
    @Test
    fun `a file with CRLF line endings keeps every line break a single CRLF through save after save`() {
        val lines =
            listOf(
                "{",
                "  \"version\": 1,",
                "  \"requests\": [",
                "    {",
                "      \"name\": \"a\",",
                "      \"url\": \"/a\",",
                "      \"method\": \"GET\",",
                "      \"signals\": {",
                "        \"q\": 1",
                "      },",
                "      \"headers\": \"\",",
                "      \"note\": {",
                "        \"k\": 1",
                "      }",
                "    }",
                "  ]",
                "}",
                "",
            )
        var text = lines.joinToString("\r\n")
        for (url in listOf("/one", "/two")) {
            val read = Requests.parseRequestsFile(text).requests.single()
            assertTrue("\r\n" in read.signals, read.signals)
            val saved = Requests.saveRequest(text, read.copy(url = url))
            assertEquals(null, saved.error)
            text = saved.text!!
            assertTrue("\r\r" !in text, text)
            assertEquals(text.split("\n").size, text.split("\r\n").size, text)
            assertEquals(lines.joinToString("\r\n").replace("\"/a\"", "\"$url\""), text)
        }
        val read = Requests.parseRequestsFile(text).requests.single()
        assertEquals(text, Requests.saveRequest(text, read).text)
    }

    @Test
    fun `the entries that are not read are part of the file, none by default`() {
        val file = Requests.parseRequestsFile("{\"requests\":[7]}")
        assertEquals(
            RequestsFile(emptyList(), null, listOf("Request 1 in .streamlord/inspector.json is not read: it is not an object.")),
            file,
        )
        assertEquals(emptyList<String>(), RequestsFile(emptyList()).skipped)
    }

    // Measured in headless Chrome for Testing 153 on the 1.0.5 bundle: the characters that may stand on both sides
    // of `true` in onlyIfMissing and still make it true.
    @Test
    fun `onlyIfMissing is read as the client reads it, true after JavaScript trims it`() {
        val measured =
            "0009 000a 000b 000c 000d 0020 00a0 1680 2000 2001 2002 2003 2004 2005 2006 2007 2008 2009 200a 2028 2029 202f 205f 3000 feff"
        val store = JsonParser.parseObject("""{"a":0}""")

        fun onlyIfMissing(value: String?) = Requests.patchedStore(store, """{"a":1}""", value)!!.store == store
        val kept = (0..0xFFFF).map { it.toChar() }.filter { onlyIfMissing("${it}true$it") }
        assertEquals(measured, kept.joinToString(" ") { it.code.toString(16).padStart(4, '0') })
        assertFalse(onlyIfMissing("TRUE"))
        assertFalse(onlyIfMissing(null))
    }

    // The client takes a patch however deep, and a number however long; the inspector says it does not fold one past its limits.
    @Test
    fun `a patch too deep or too long to fold leaves the store as it was and says so`() {
        val store = JsonParser.parseObject("""{"b":2}""")
        for (patch in listOf("{\"a\":".repeat(200_000) + "1" + "}".repeat(200_000), "{\"a\":1" + "0".repeat(1000) + "}")) {
            val patched = Requests.patchedStore(store, patch, null)
            assertEquals(store, patched?.store)
            // The client takes it, and the store shown is the one before it: so the inspector says, and nothing else.
            val said = patched?.message.orEmpty()
            assertTrue("the client takes" in said && "left as it was" in said && "keeps the keys" !in said, said)
        }
        assertEquals(null, Requests.patchedStore(store, "{a:1}", null), "no JSON, which the frame shows as it is")
    }

    // Each refusal is one sentence the analysis writes, which both inspectors show as it is.
    @Test
    fun `a patch the client fails on, or Streamlord refuses, says so as the inspector shows it`() {
        val store = JsonParser.parseObject("""{"a":[1,2]}""")
        val refused = Requests.patchedStore(store, """{"o":{"__proto__":{"x":1}}}""", null)!!.message.orEmpty()
        assertTrue(refused.startsWith("Streamlord refuses this signal patch on purpose: ") && refused.endsWith("The store shown keeps the keys before it."), refused)
        val throws = Requests.patchedStore(store, """{"a":{"0":7,"length":-1}}""", null)!!.message.orEmpty()
        assertTrue(throws.startsWith("The signal store cannot take this patch: ") && "Datastar's client throws on it too" in throws, throws)
        assertEquals(null, Requests.patchedStore(store, """{"b":1}""", null)!!.message)
    }
}
