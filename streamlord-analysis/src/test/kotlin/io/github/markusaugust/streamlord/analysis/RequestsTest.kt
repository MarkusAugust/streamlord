package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.mergePatch
import java.net.URI
import java.net.http.HttpRequest
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
    fun `substitutes text where a name is not a variable`() {
        assertEquals("no vars", Requests.substitute("no vars", emptyMap()).text)
        assertEquals("http://h/x", Requests.substitute("{{baseUrl}}/x", mapOf("baseUrl" to "http://h")).text)
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
    fun `refuses a host or port java_net_URI cannot read, and says which`() {
        val cases =
            listOf(
                "http://localhost:8080api/hent/visning" to
                    "The port in localhost:8080api is not a number. Is a / missing between the port and the path?",
                "http://localhost:8080:/x" to "The port in localhost:8080: is not a number. Is a / missing between the port and the path?",
                "http://my_service:8080/x" to
                    "The host my_service cannot be sent: a host name holds only letters, digits, hyphens and dots, and its last part starts with a letter.",
            )
        for ((url, error) in cases) {
            assertEquals(Requests.SendableUrl(null, error), Requests.sendableUrl(url), url)
        }
    }

    @Test
    fun `points to the server log when an error came without a body`() {
        assertEquals(
            "The server sent no body with this 400, so the reason is in its log. " +
                "Spring Boot, for one, writes no error body for a request that accepts only text/event-stream.",
            Requests.emptyBodyHint(400, " \n"),
        )
        assertEquals(null, Requests.emptyBodyHint(400, "Required request parameter 'partsnummer' is not present"))
        assertEquals(null, Requests.emptyBodyHint(204, ""))
    }

    @Test
    fun `refuses exactly the hosts and ports the HTTP client refuses`() {
        // The rule is written out by hand so the VS Code extension can hold the same one; this
        // holds both to the JDK, which is the client that has to accept what we let through.
        val urls =
            listOf(
                "http://localhost:8080/x",
                "http://localhost:/x",
                "http://localhost",
                "http://h?x=1",
                "http://user:pw@h:1/x",
                "http://127.0.0.1:8080/x",
                "http://10.0.0.255/x",
                "http://[::1]:8080/x",
                "http://[fe80::1]/x",
                "http://example.com./x",
                "http://a-b.example.com/x",
                "http://x1/x",
                "http://1x/x",
                "http://123/x",
                "http://localhost:8080api/x",
                "http://localhost:8080:/x",
                "http://h:-1/x",
                "http://h:80a",
                "http://my_service:8080/x",
                "http://-h/x",
                "http://h-/x",
                "http://a..b/x",
                "http://a.1b/x",
                "http://1.2.3.999/x",
                "http://1.2.3/x",
                "http://a.b-/x",
                "http://h%41/x",
                "http://h!/x",
            )
        for (url in urls) {
            val ours = Requests.sendableUrl(url)
            val jdk = runCatching { HttpRequest.newBuilder(URI(url)).build() }.isSuccess
            assertEquals(jdk, ours.url != null, "$url: the JDK ${if (jdk) "sends" else "refuses"} it, we said ${ours.error ?: "send"}")
        }
    }

    private val invalid = ".streamlord/env.json is not valid"
    private val env =
        Requests.parseEnv(
            """
            {
              "baseUrl": "http://127.0.0.1:8081/",
              "signals": { "search" : "ash", "n": 1.0, "s": "\u00e9</p>" },
              "headers": { "Authorization": "Bearer x", "X-Csrf-Token": "abc" }
            }
            """.trimIndent(),
        )
    private val vars = Requests.mergeVariables("http://localhost:8080/", env)
    private val defaults = Requests.mergeVariables("http://localhost:8080/", Requests.parseEnv("{}"))

    @Test
    fun `variables read the three keys of the env file, signals as written`() {
        assertEquals(
            Requests.Env(
                baseUrl = "http://127.0.0.1:8081",
                signals = """{"search":"ash","n":1.0,"s":"\u00e9</p>"}""",
                headers = listOf("Authorization" to "Bearer x", "X-Csrf-Token" to "abc"),
                params = null,
                errors = emptyList(),
            ),
            env,
        )
    }

    @Test
    fun `variables say what makes the env file invalid`() {
        assertEquals(listOf(".streamlord/env.json is not valid JSON at line 1, column 1."), Requests.parseEnv("not json").errors)
        // The comma a hand-edited file most often ends up with, after its last value.
        assertEquals(
            listOf(".streamlord/env.json is not valid JSON at line 3, column 1."),
            Requests.parseEnv("{\n  \"baseUrl\": \"http://localhost:9102\",\n}\n").errors,
        )
        assertEquals(listOf("$invalid: it must be an object with baseUrl, signals, headers or params."), Requests.parseEnv("[]").errors)
        assertEquals(
            Requests.Env(
                baseUrl = null,
                signals = null,
                headers = null,
                params = null,
                errors =
                    listOf(
                        "$invalid: \"csrf\" is not a known key. Use baseUrl, signals, headers or params.",
                        "$invalid: baseUrl must be text, such as \"http://localhost:8080\".",
                        "$invalid: signals must be a JSON object, such as {\"search\": \"ash\"}.",
                        "$invalid: the value of the header \"X-A\" must be text.",
                    ),
            ),
            Requests.parseEnv("""{"csrf": "x", "baseUrl": 8080, "signals": [], "headers": {"X-A": 1}}"""),
        )
        assertEquals(
            listOf(
                "$invalid: baseUrl must start with http:// or https://.",
                "$invalid: headers must be an object of names and values, such as {\"Authorization\": \"Bearer token\"}.",
            ),
            Requests.parseEnv("""{"baseUrl": "localhost:8080", "headers": []}""").errors,
        )
    }

    @Test
    fun `variables say where each value comes from`() {
        assertEquals(listOf(Requests.Variable("baseUrl", "http://localhost:8080", Requests.VariableSource.DEFAULT)), defaults)
        assertEquals(
            "baseUrl = http://127.0.0.1:8081\n" +
                "signals = {\"search\":\"ash\",\"n\":1.0,\"s\":\"\\u00e9</p>\"}\n" +
                "headers = Authorization: Bearer x; X-Csrf-Token: abc",
            Requests.describeVariables(vars),
        )
        assertEquals(
            "baseUrl = http://h   (default)\nsignals = {}\nheaders = (none)",
            Requests.describeVariables(Requests.mergeVariables("http://h", Requests.parseEnv("""{"signals": {}, "headers": {}}"""))),
        )
    }

    @Test
    fun `variables fill a request, each in its own field`() {
        val r =
            Requests.resolveRequest(
                SavedRequest("", "{{baseUrl}}/x?q={{ baseUrl }}", "POST", "{{signals}}", "{{headers}}\nX-B: 1"),
                vars,
            )
        assertEquals(emptyList(), r.errors)
        assertEquals(emptyList(), r.unset)
        assertEquals("http://127.0.0.1:8081/x?q=http://127.0.0.1:8081", r.request.url)
        assertEquals("""{"search":"ash","n":1.0,"s":"\u00e9</p>"}""", r.request.signals)
        assertEquals("Authorization: Bearer x\nX-Csrf-Token: abc\nX-B: 1", r.request.headers)
        val bad =
            Requests.resolveRequest(
                SavedRequest(
                    name = "",
                    url = "{{baseUrl}}/{{signals}}",
                    method = "POST",
                    signals = "{\"a\": \"{{csrf}}\"} {{signals}}",
                    headers = "{{headers}}\nOrigin: {{baseUrl}}",
                ),
                defaults,
            )
        assertEquals(
            listOf(
                "{{signals}} belongs in the signals field.",
                "{{csrf}} cannot stand in the signals field. Use {{signals}} there; a param goes in the URL or the headers.",
                "{{signals}} is not set. Add \"signals\" to .streamlord/env.json.",
                "{{headers}} is not set. Add \"headers\" to .streamlord/env.json.",
                "{{baseUrl}} belongs in the URL field.",
            ),
            bad.errors,
        )
        assertEquals(listOf("signals", "headers"), bad.unset)
    }

    @Test
    fun `variables are added to the env file without touching what is there`() {
        assertEquals(
            "{\n  \"baseUrl\": \"http://localhost:8080\"\n}\n",
            Requests.withEnvVariables(null, emptyList(), "http://localhost:8080"),
        )
        assertEquals(
            "{\n  \"baseUrl\": \"http://h\",\n  \"headers\": {},\n  \"signals\": {}\n}\n",
            Requests.withEnvVariables(null, listOf("headers", "signals"), "http://h"),
        )
        assertEquals("{\n  \"signals\": {}\n}", Requests.withEnvVariables("{}", listOf("signals"), "http://h"))
        assertEquals(
            "{ \"baseUrl\": \"http://x\",\n  \"signals\": {}\n}\n",
            Requests.withEnvVariables("{ \"baseUrl\": \"http://x\" }\n", listOf("signals", "baseUrl"), "http://h"),
        )
        assertEquals("{\"signals\": {}}", Requests.withEnvVariables("{\"signals\": {}}", listOf("signals"), "http://h"))
        assertEquals(null, Requests.withEnvVariables("not json", listOf("signals"), "http://h"))
        assertEquals(null, Requests.withEnvVariables("[]", listOf("signals"), "http://h"))
    }

    @Test
    fun `variables explain an unreachable server by its baseUrl`() {
        assertEquals(
            "{{baseUrl}} is http://localhost:8080, the default. Set baseUrl in .streamlord/env.json if your server listens elsewhere.",
            Requests.unreachableHint("{{baseUrl}}/hendelser", defaults),
        )
        assertEquals("{{baseUrl}} is http://127.0.0.1:8081, from .streamlord/env.json.", Requests.unreachableHint("{{ baseUrl }}/x", vars))
        assertEquals(null, Requests.unreachableHint("http://127.0.0.1:8081/x", defaults))
    }

    private val motregning = RunningServer("http://localhost:9102", "Motregning back (dev)")

    @Test
    fun `a running server is the baseUrl when the env file sets none`() {
        val found = Requests.mergeVariables("http://localhost:8080/", Requests.parseEnv("{}"), motregning)
        assertEquals(
            listOf(Requests.Variable("baseUrl", "http://localhost:9102", Requests.VariableSource.RUNNING, "Motregning back (dev)")),
            found,
        )
        assertEquals("baseUrl = http://localhost:9102   (from Motregning back (dev))", Requests.describeVariables(found))
        assertEquals(
            "{{baseUrl}} is http://localhost:9102, where Motregning back (dev) said it started.",
            Requests.unreachableHint("{{baseUrl}}/x", found, motregning),
        )
        assertEquals(null, Requests.baseUrlSuggestion(found, motregning))
    }

    @Test
    fun `the env file wins over a running server, which is offered when they differ`() {
        val set = Requests.mergeVariables("http://localhost:8080/", env, motregning)
        assertEquals("http://127.0.0.1:8081", set.first().value)
        assertEquals("http://localhost:9102", Requests.baseUrlSuggestion(set, motregning))
        assertEquals(
            "{{baseUrl}} is http://127.0.0.1:8081, from .streamlord/env.json. Motregning back (dev) started on http://localhost:9102.",
            Requests.unreachableHint("{{baseUrl}}/x", set, motregning),
        )
        val agree = Requests.mergeVariables("http://h", Requests.parseEnv("""{"baseUrl": "http://localhost:9102"}"""), motregning)
        assertEquals(null, Requests.baseUrlSuggestion(agree, motregning))
        assertEquals(null, Requests.baseUrlSuggestion(set, null))
    }

    @Test
    fun `baseUrl is set in the env file without touching the rest`() {
        assertEquals(
            "{\n  \"baseUrl\": \"http://localhost:9102\",\n  \"params\": {\"a\": \"1\"}\n}\n",
            Requests.withBaseUrl(
                "{\n  \"baseUrl\": \"http://localhost:8080\",\n  \"params\": {\"a\": \"1\"}\n}\n",
                "http://localhost:9102",
            ),
        )
        assertEquals("{\"params\": {},\n  \"baseUrl\": \"http://x\"\n}", Requests.withBaseUrl("{\"params\": {}}", "http://x"))
        assertEquals("{\n  \"baseUrl\": \"http://x\"\n}\n", Requests.withBaseUrl(null, "http://x"))
        assertEquals(null, Requests.withBaseUrl("{\"baseUrl\": ", "http://x"))
    }

    @Test
    fun `the request list counts what it holds in its first entry`() {
        assertEquals(
            listOf("New request…", "New request… (2 saved, 5 recent)", "New request… (1 recent)"),
            listOf(Requests.newRequestLabel(0, 0), Requests.newRequestLabel(2, 5), Requests.newRequestLabel(0, 1)),
        )
    }

    @Test
    fun `variables complete a name after the braces with what the field takes`() {
        fun names(c: Requests.Completion?) = c?.let { Triple(it.from, it.to, it.items.map { v -> v.name }) }
        assertEquals(Triple(0, 2, listOf("baseUrl")), names(Requests.variableCompletions("{{", 2, Requests.Field.URL, vars)))
        assertEquals(Triple(2, 6, listOf("signals")), names(Requests.variableCompletions("a {{ s", 6, Requests.Field.SIGNALS, vars)))
        assertEquals(Triple(0, 4, listOf("headers")), names(Requests.variableCompletions("{{}}", 2, Requests.Field.HEADERS, vars)))
        assertEquals(Triple(3, 6, listOf("headers")), names(Requests.variableCompletions("X: {{H", 6, Requests.Field.HEADERS, vars)))
        assertEquals(null, Requests.variableCompletions("{{x", 3, Requests.Field.URL, vars))
        assertEquals(null, Requests.variableCompletions("{x", 2, Requests.Field.URL, vars))
        assertEquals(null, Requests.variableCompletions("{{", 2, Requests.Field.SIGNALS, defaults))
        assertEquals(null, Requests.variableCompletions("X: {{b", 6, Requests.Field.HEADERS, vars))
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
        assertEquals(
            listOf("{{constructor}} is not set. Add it to params in .streamlord/env.json."),
            Requests.resolveRequest(SavedRequest("", "{{baseUrl}}/{{constructor}}"), defaults).errors,
        )
        val loose =
            """{"requests":[{"name":"a","url":"/x","headers":{"A":"b","O":{"k":[1]},"T":true}},{"name":"b","url":"/y","headers":["x"]}]}"""
        assertEquals(listOf("A: b\nO: {\"k\":[1]}\nT: true", ""), Requests.parseRequestsFile(loose).requests.map { it.headers })
    }

    @Test
    fun `a route opens with a variable for each parameter it needs`() {
        val src =
            """
            @RestController
            @RequestMapping("api/hent")
            class Visning {
                @GetMapping(value = "/visning/{id}")
                fun visning(
                    @PathVariable id: String,
                    @RequestParam partsnummer: String,
                    @RequestParam instans: String,
                    @RequestParam(required = false) fokus: String?,
                    @RequestHeader("Nav-Call-Id") callId: String,
                    @RequestHeader headere: Map<String, String>,
                ) {}
            }
            fun Route.api() { get("/users/{id}/posts/{slug?}") { call.request.queryParameters["page"] } }
            """.trimIndent()
        val (spring, ktor) = findRoutes(src)
        assertEquals("{{baseUrl}}/api/hent/visning/{{id}}?partsnummer={{partsnummer}}&instans={{instans}}", Requests.routeUrl(spring))
        assertEquals("Nav-Call-Id: {{Nav-Call-Id}}", Requests.routeHeaders(spring))
        assertEquals(listOf("fokus"), Requests.routeOptional(spring))
        assertEquals("{{baseUrl}}/users/{{id}}/posts", Requests.routeUrl(ktor))
        assertEquals("", Requests.routeHeaders(ktor))
        assertEquals(listOf("slug", "page"), Requests.routeOptional(ktor))
        assertEquals("{{baseUrl}}/x/{{id}}", Requests.routeUrl(Route("GET", "/x/{id:\\d+}", 0, Framework.SPRING)))
    }

    private val withParams =
        Requests.mergeVariables(
            "http://localhost:8080",
            Requests.parseEnv(
                """{"baseUrl": "http://localhost:9102", "params": {"partsnummer": "3000 507", "instans": "m1", "fokus": ""}}""",
            ),
        )

    @Test
    fun `params fill the URL encoded and the headers as written`() {
        val r =
            Requests.resolveRequest(
                SavedRequest("", "{{baseUrl}}/v?partsnummer={{partsnummer}}&instans={{ instans }}", headers = "X-Part: {{partsnummer}}"),
                withParams,
            )
        assertEquals(emptyList(), r.errors)
        assertEquals("http://localhost:9102/v?partsnummer=3000%20507&instans=m1", r.request.url)
        assertEquals("X-Part: 3000 507", r.request.headers)
        assertEquals(
            "baseUrl = http://localhost:9102\npartsnummer = 3000 507\ninstans = m1\nfokus = (none)",
            Requests.describeVariables(withParams),
        )
    }

    @Test
    fun `a param that is missing or empty is added to the env file to fill in`() {
        val r =
            Requests.resolveRequest(
                SavedRequest("", "{{baseUrl}}/v?a={{fokus}}&b={{saksnummer}}&c={{baseurl}}", signals = "{{instans}}"),
                withParams,
            )
        assertEquals(
            listOf(
                "{{fokus}} is empty. Fill it in under params in .streamlord/env.json.",
                "{{saksnummer}} is not set. Add it to params in .streamlord/env.json.",
                "{{baseurl}} is not a variable. Did you mean {{baseUrl}}?",
                "{{instans}} cannot stand in the signals field. Use {{signals}} there; a param goes in the URL or the headers.",
            ),
            r.errors,
        )
        assertEquals(listOf("fokus", "saksnummer"), r.unset)
    }

    @Test
    fun `params are read with what is wrong with them named`() {
        assertEquals(
            listOf(
                "$invalid: \"headers\" cannot be a param; {{headers}} is a variable of its own.",
                "$invalid: \"a b\" cannot be a param name; {{a b}} would not be read.",
                "$invalid: the value of the param \"n\" must be text.",
            ),
            Requests.parseEnv("""{"params": {"headers": "x", "a b": "1", "n": 1, "ok": "1"}}""").errors,
        )
        assertEquals(
            listOf("$invalid: params must be an object of names and values, such as {\"partsnummer\": \"123\"}."),
            Requests.parseEnv("""{"params": []}""").errors,
        )
    }

    @Test
    fun `params are added to the env file without touching what is there`() {
        assertEquals(
            "{\n  \"baseUrl\": \"http://h\",\n  \"params\": {\n    \"a\": \"\",\n    \"b\": \"\"\n  }\n}\n",
            Requests.withEnvVariables(null, listOf("a", "b"), "http://h"),
        )
        assertEquals(
            "{\n  \"baseUrl\": \"http://x\",\n  \"signals\": {},\n  \"params\": {\n    \"a\": \"\"\n  }\n}\n",
            Requests.withEnvVariables("{\n  \"baseUrl\": \"http://x\"\n}\n", listOf("a", "signals"), "http://h"),
        )
        assertEquals(
            "{\n  \"params\": {\n    \"a\": \"1\",\n    \"b\": \"\"\n  }\n}\n",
            Requests.withEnvVariables("{\n  \"params\": {\n    \"a\": \"1\"\n  }\n}\n", listOf("a", "b"), "http://h"),
        )
        assertEquals(
            "{\"params\": {\"a\": \"1\", \"b\": \"\"}}",
            Requests.withEnvVariables("{\"params\": {\"a\": \"1\"}}", listOf("b"), "http://h"),
        )
        assertEquals("{\"params\": {\n    \"b\": \"\"\n  }}", Requests.withEnvVariables("{\"params\": {}}", listOf("b"), "http://h"))
        assertEquals("{\"params\": {\"a\": \"\"}}", Requests.withEnvVariables("{\"params\": {\"a\": \"\"}}", listOf("a"), "http://h"))
    }

    @Test
    fun `params complete in the URL and the headers, not in the signals`() {
        fun names(c: Requests.Completion?) = c?.let { it.items.map { v -> v.name } }
        assertEquals(
            listOf("baseUrl", "partsnummer", "instans", "fokus"),
            names(Requests.variableCompletions("{{", 2, Requests.Field.URL, withParams)),
        )
        assertEquals(listOf("instans"), names(Requests.variableCompletions("X: {{i", 6, Requests.Field.HEADERS, withParams)))
        assertEquals(null, names(Requests.variableCompletions("{{p", 3, Requests.Field.SIGNALS, withParams)))
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
