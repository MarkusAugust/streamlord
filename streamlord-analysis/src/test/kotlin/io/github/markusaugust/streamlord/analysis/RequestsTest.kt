package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `requests.test.ts` and the SSE tests. */
class RequestsTest {
    private fun req(
        name: String = "counter",
        url: String = "{{baseUrl}}/api/counter-stream",
        method: String = "GET",
        signals: String = "",
        headers: String = "",
    ) = SavedRequest(name, url, method, signals, headers)

    @Test
    fun `round-trips the file and tolerates loose input`() {
        val parsed =
            Requests.parseRequestsFile(
                """{"requests":[{"name":"a","url":"/x","method":"post","signals":{"q":1},"headers":{"X-A":"1"}},{"bad":true},{"name":"b","url":"/y"}]}""",
            )
        assertEquals(
            listOf(listOf("a", "POST", "{\"q\":1}", "X-A: 1"), listOf("b", "GET", "", "")),
            parsed.requests.map { listOf(it.name, it.method, it.signals.replace(Regex("""\s"""), ""), it.headers) },
        )
        assertEquals(emptyList(), Requests.parseRequestsFile("not json").requests)
        val text = Requests.serializeRequestsFile(parsed)
        assertEquals(parsed, Requests.parseRequestsFile(text))
        assertEquals(1, JsonParser.parseObject(text).int("version"))
    }

    @Test
    fun `upserts by name case-insensitively and keeps names sorted`() {
        var file = Requests.parseRequestsFile("{}")
        file = Requests.upsert(file, req(name = "Zeta"))
        file = Requests.upsert(file, req(name = "alpha"))
        file = Requests.upsert(file, req(name = "ZETA", url = "/new"))
        assertEquals(listOf("alpha" to "{{baseUrl}}/api/counter-stream", "ZETA" to "/new"), file.requests.map { it.name to it.url })
        assertEquals(listOf("alpha"), Requests.remove(file, "zeta").requests.map { it.name })
    }

    @Test
    fun `substitutes variables and reports the missing ones`() {
        val vars = mapOf("baseUrl" to "http://localhost:8080", "token" to "t1")
        val r =
            Requests.resolveRequest(
                req(headers = "X-Csrf-Token: {{token}}\nX-Other: {{ nope }}", signals = "{\"u\":\"{{user}}\"}"),
                vars,
            )
        assertEquals("http://localhost:8080/api/counter-stream", r.request.url)
        assertEquals("X-Csrf-Token: t1\nX-Other: {{ nope }}", r.request.headers)
        assertEquals(listOf("user", "nope"), r.missing)
        assertEquals("no vars", Requests.substitute("no vars", emptyMap()).text)
        assertEquals(
            mapOf("baseUrl" to "http://x", "port" to "8080"),
            Requests.parseEnvFile("""{"baseUrl":"http://x","port":8080,"nested":{"a":1}}"""),
        )
        assertEquals(emptyMap(), Requests.parseEnvFile("["))
    }

    @Test
    fun `keeps a bounded de-duplicated recent list`() {
        var recent: List<SavedRequest> = emptyList()
        for (i in 0 until 12) recent = Requests.pushRecent(recent, req(name = "", url = "/r$i"))
        recent = Requests.pushRecent(recent, req(name = "", url = "/r11"))
        assertEquals(10, recent.size)
        assertEquals("/r11", recent[0].url)
        assertEquals("GET /r11", recent[0].name)
        assertEquals(1, recent.count { it.url == "/r11" })
    }

    @Test
    fun `exports curl for query-string and body methods`() {
        assertEquals(
            "curl -N -X GET 'http://localhost:8080/api/x?datastar=%7B%22q%22%3A%22a+b%22%7D' -H 'Accept: text/event-stream' " +
                "-H 'Datastar-Request: true' -H 'X-A: 1'",
            Requests.toCurl(req(url = "http://localhost:8080/api/x", signals = "{\"q\": \"a b\"}", headers = "X-A: 1")),
        )
        assertEquals(
            "curl -N -X POST 'http://h/x' -H 'Accept: text/event-stream' -H 'Datastar-Request: true' -H 'Content-Type: application/json' " +
                "--data '{\"s\":\"it'\\''s\"}'",
            Requests.toCurl(req(url = "http://h/x", method = "POST", signals = "{\"s\":\"it's\"}")),
        )
    }

    @Test
    fun `handles path parameters`() {
        assertEquals(
            listOf(Requests.PathParam("id", false), Requests.PathParam("slug", true)),
            Requests.pathParams("/users/{id}/posts/{slug?}"),
        )
        assertEquals("/users/4%202/posts", Requests.fillPath("/users/{id}/posts/{slug?}", mapOf("id" to "4 2", "slug" to "")))
        assertEquals("/users/7/posts/x", Requests.fillPath("/users/{id}/posts/{slug?}", mapOf("id" to "7", "slug" to "x")))
    }

    @Test
    fun `finds ktor routes with nested prefixes and path-less verbs`() {
        val src =
            """
            fun Route.api() {
              route("/api") {
                get("/counter") { }
                route("/coffee") {
                  get("/tracker") { call.respond("x") }
                  route("/add") { withCSRFProtection { post { } } }
                }
                delete("/users/{id}") { }
              }
              get("/top") { }
              post { }  // no path, no prefix: ignored
              // get("/comment") { }
              val s = "get(\"/string\")"
            }
            """.trimIndent()
        assertEquals(
            listOf("GET /api/counter", "GET /api/coffee/tracker", "POST /api/coffee/add", "DELETE /api/users/{id}", "GET /top"),
            findRoutes(src).map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun `finds spring routes with a class prefix`() {
        val src =
            """
            @RestController
            @RequestMapping("/api")
            class FeedController {
              @GetMapping("/feed")
              fun feed() = 1
              @PostMapping(value = ["/search"], produces = ["text/event-stream"])
              fun search() = 2
              @RequestMapping("/legacy", method = [RequestMethod.PUT])
              fun legacy() = 3
              @DeleteMapping
              fun all() = 4
            }
            """.trimIndent()
        assertEquals(
            listOf("GET /api/feed SPRING", "POST /api/search SPRING", "PUT /api/legacy SPRING", "DELETE /api SPRING"),
            findRoutes(src).map { "${it.method} ${it.path} ${it.framework}" },
        )
    }

    @Test
    fun `parses sse frames across chunks and decodes datastar events`() {
        val p = SseParser()
        val a = p.feed("event: datastar-patch-elements\nid: 7\ndata: selector #a\ndata: elements <div>\ndata: elements ")
        assertEquals(emptyList(), a)
        val b = p.feed("</div>\n\n: ping\n\nevent: datastar-patch-signals\r\ndata: signals {\"n\":1}\r\n\r\n")
        assertEquals(3, b.size)
        val f = decodeDatastar(b[0])
        assertEquals(mapOf("selector" to "#a", "elements" to "<div>\n</div>"), f.args)
        assertEquals("7", f.id)
        assertEquals(listOf("ping"), b[1].comments)
        assertEquals("{\"n\":1}", decodeDatastar(b[2]).args["signals"])
    }

    @Test
    fun `merge-patches signals`() {
        val target = JsonParser.parse("""{"a":1,"u":{"n":"x","e":"y"}}""")
        val patch = JsonParser.parse("""{"a":null,"u":{"e":null,"t":1},"l":[1]}""")
        assertEquals(JsonParser.parse("""{"u":{"n":"x","t":1},"l":[1]}"""), mergePatch(target, patch))
    }
}
