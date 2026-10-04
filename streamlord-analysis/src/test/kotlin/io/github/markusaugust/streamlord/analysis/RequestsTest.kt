package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.mergePatch
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

    // The expected text of the next five tests is the same, character for character, in the VS Code
    // extension's `requests.test.ts`: both editors write one shared file and export one curl line.

    @Test
    fun `curl export takes the url as written and never throws`() {
        val tail = " -H 'Accept: text/event-stream' -H 'Datastar-Request: true'"
        val get = { url: String -> Requests.toCurl(req(url = url)) }
        assertEquals("curl -N -X GET 'http://localhost:8080?datastar=%7B%7D'$tail", get("http://localhost:8080"))
        assertEquals("curl -N -X GET '/x?datastar=%7B%7D'$tail", get("/x"))
        assertEquals("curl -N -X GET 'http://x/{id}?datastar=%7B%7D'$tail", get("http://x/{id}"))
        assertEquals("curl -N -X GET 'http://h/a b?datastar=%7B%7D'$tail", get("http://h/a b"))
        assertEquals("curl -N -X GET '{{base}}/x?datastar=%7B%7D'$tail", get("{{base}}/x"))
        assertEquals("curl -N -X GET 'http://h/x?y=1&z=a b&datastar=%7B%7D#frag'$tail", get("http://h/x?y=1&datastar=old&z=a b#frag"))
        assertEquals(
            "curl -N -X POST 'http://localhost:8080'$tail -H 'Content-Type: application/json' --data '{}'",
            Requests.toCurl(req(url = "http://localhost:8080", method = "POST")),
        )
    }

    @Test
    fun `curl export compacts signals without respelling them`() {
        val tail = " -H 'Accept: text/event-stream' -H 'Datastar-Request: true'"
        val spaced = " { \"n\" : 1.0,\n\t\"s\": \"a  \\\" b\\u00e9</p>\", \"l\": [ 1, 2 ] }\r\n"
        assertEquals(
            "curl -N -X POST 'http://h/x'$tail -H 'Content-Type: application/json' " +
                "--data '{\"n\":1.0,\"s\":\"a  \\\" b\\u00e9</p>\",\"l\":[1,2]}'",
            Requests.toCurl(req(url = "http://h/x", method = "POST", signals = spaced)),
        )
        assertEquals(
            "curl -N -X GET 'http://h/x?datastar=%7B%22k%22%3A%22%7E*%27%28%29%21%C3%A9+%EF%BF%BD%22%7D'$tail",
            Requests.toCurl(req(url = "http://h/x", signals = "{\"k\":\"~*'()!é \uD83D\"}")),
        )
        assertEquals(
            "curl -N -X PUT 'http://h/x'$tail -H 'X-A: 2' -H 'B: 3' -H 'Content-Type: application/json' --data 'not json'",
            Requests.toCurl(req(url = "http://h/x", method = "PUT", signals = "  not json ", headers = "X-A: 1\nBad\n B : 3 \r\nX-A: 2")),
        )
    }

    @Test
    fun `sends the url of the curl line or says why not`() {
        val cases =
            listOf(
                Triple(
                    "http://h:8080/api/x?flag&q=a%20b+c&s='x'&a%5B%5D=1#frag",
                    "http://h:8080/api/x?flag&q=a%20b+c&s='x'&a%5B%5D=1",
                    null,
                ),
                Triple("http://h/x?filter[name]=1", null, "The URL holds [, which cannot be sent as written. Write it as %5B."),
                Triple("HTTPS://h/søk/\uD83D\uDE00", "HTTPS://h/s%C3%B8k/%F0%9F%98%80", null),
                Triple("http://[::1]:8080/x", "http://[::1]:8080/x", null),
                Triple("http://h/x\uD83D", "http://h/x%EF%BF%BD", null),
                Triple("/api/x", null, "Not an absolute URL: /api/x. Start it with http:// or https://, or with {{baseUrl}}."),
                Triple("{{base}}/x", null, "Not an absolute URL: {{base}}/x. Start it with http:// or https://, or with {{baseUrl}}."),
                Triple(
                    "localhost:8080/x",
                    null,
                    "Not an absolute URL: localhost:8080/x. Start it with http:// or https://, or with {{baseUrl}}.",
                ),
                Triple("ftp://h/x", null, "Only http:// and https:// URLs can be sent: ftp://h/x"),
                Triple("http:///x", null, "No host in http:///x."),
                Triple("http://h/a b", null, "The URL holds a space or a control character, which cannot be sent. Write a space as %20."),
                Triple("http://h/users/{id}/x", null, "The URL still holds {id}. Fill in the path parameter before sending."),
                Triple("http://h/p\"q", null, "The URL holds \", which cannot be sent as written. Write it as %22."),
                Triple("http://h/p|q", null, "The URL holds |, which cannot be sent as written. Write it as %7C."),
                Triple("http://h/x/[y]", null, "The URL holds [, which cannot be sent as written. Write it as %5B."),
                Triple("http://h/p%zz", null, "The URL holds a % that starts no escape. Write it as %25."),
                Triple("http://bø.no/x", null, "The host holds ø, which cannot be sent as written. Write the host in its xn-- form."),
            )
        for ((url, sent, error) in cases) {
            assertEquals(Requests.SendableUrl(sent, error), Requests.sendableUrl(url), url)
        }
    }

    @Test
    fun `saved names sort by lowercase code units, not by locale`() {
        val names = listOf("b", "a", "B", "_x", "Zeta", "éa", "zz", "a-b", "ab", "A", "10", "9", "ΌΣ", "όσ2")
        val file = names.fold(RequestsFile.EMPTY) { acc, name -> Requests.upsert(acc, req(name = name)) }
        assertEquals(listOf("10", "9", "_x", "A", "a-b", "ab", "B", "Zeta", "zz", "éa", "ΌΣ", "όσ2"), file.requests.map { it.name })
        val last = { removed: String -> Requests.remove(file, removed).requests.last() }
        assertEquals("ΌΣ", last("ΌΣ2").name)
        assertEquals("όσ2", last("όσ").name)
    }

    @Test
    fun `the file is written the same byte for byte`() {
        assertEquals("{\n  \"version\": 1,\n  \"requests\": []\n}\n", Requests.serializeRequestsFile(RequestsFile.EMPTY))
        val odd = "q\" b\\ t\t n\n \u0001 \u007F é \uD83D\uDE00 \u2028 \u2029 </p> / <"
        val written = "\"q\\\" b\\\\ t\\t n\\n \\u0001 \u007F é \uD83D\uDE00 \\u2028 \\u2029 <\\/p> / <\""
        val requests = listOf(req(name = odd, url = "/x", signals = odd), req(name = "b", url = "/y", method = "POST", headers = "A: 1"))
        assertEquals(
            "{\n  \"version\": 1,\n  \"requests\": [\n    {\n      \"name\": $written,\n      \"url\": \"/x\",\n" +
                "      \"method\": \"GET\",\n      \"signals\": $written,\n      \"headers\": \"\"\n    },\n" +
                "    {\n      \"name\": \"b\",\n      \"url\": \"/y\",\n      \"method\": \"POST\",\n      \"signals\": \"\",\n" +
                "      \"headers\": \"A: 1\"\n    }\n  ]\n}\n",
            Requests.serializeRequestsFile(RequestsFile(requests)),
        )
    }

    @Test
    fun `a name every object has is not a variable or a path value`() {
        val r = Requests.substitute("{{a}} {{constructor}} {{toString}} {{__proto__}}", mapOf("a" to "1"))
        assertEquals("1 {{constructor}} {{toString}} {{__proto__}}", r.text)
        assertEquals(listOf("constructor", "toString", "__proto__"), r.missing)
        assertEquals("/7/", Requests.fillPath("/{id}/{constructor}/{valueOf?}{toString?}", mapOf("id" to "7")))
        val loose =
            """{"requests":[{"name":"a","url":"/x","headers":{"A":"b","O":{"k":[1]},"T":true}},{"name":"b","url":"/y","headers":["x"]}]}"""
        assertEquals(listOf("A: b\nO: {\"k\":[1]}\nT: true", ""), Requests.parseRequestsFile(loose).requests.map { it.headers })
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
