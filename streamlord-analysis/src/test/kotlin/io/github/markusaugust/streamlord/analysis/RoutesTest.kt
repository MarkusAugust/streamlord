package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `routes.test.ts`. */
class RoutesTest {
    private fun routes(src: String) = findRoutes(src).map { "${it.method} ${it.path}" }

    @Test
    fun `a verb called on a receiver is not a route`() {
        val src =
            """
            fun Application.module() {
                routing {
                    get("/top") { }
                    val v = map.get("key")
                    val h = call.request.headers.get("X-Foo")
                    client.post("https://example.com/hook")
                    cache.delete("user")
                    client
                        .put("/chained") { }
                    val bare = get("key")
                }
            }
            """.trimIndent()
        assertEquals(listOf("GET /top"), routes(src))
    }

    @Test
    fun `ktor routes keep their shapes`() {
        val src =
            """
            fun Route.api() {
                get("/x") { }
                get("relative") { }
                get("/no-lambda")
                authenticate("jwt") { post("/secure") { } }
                route("/api") { delete { } }
            }
            """.trimIndent()
        assertEquals(listOf("GET /x", "GET /relative", "GET /no-lambda", "POST /secure", "DELETE /api"), routes(src))
    }

    @Test
    fun `a route with a method keeps its prefix`() {
        val src =
            """
            fun Route.api() {
                route("/api") {
                    route("/v1", HttpMethod.Get) { get("/deep") { } }
                    get("/after") { }
                }
            }
            """.trimIndent()
        assertEquals(listOf("GET /api/v1/deep", "GET /api/after"), routes(src))
    }

    @Test
    fun `a spring class mapping is a prefix whatever stands before class`() {
        val src =
            """
            @RestController
            @RequestMapping("/api")
            internal class A {
                @GetMapping("/x") fun x() {}
                @GetMapping fun root() {}
            }
            @RequestMapping(value = "/d")
            public open class D { @PutMapping(value = "/x") fun x() {} }
            @RequestMapping("/c")
            interface C {
                @GetMapping("/x") fun x()
            }
            """.trimIndent()
        assertEquals(listOf("GET /api/x", "GET /api", "PUT /d/x", "GET /c/x"), routes(src))
    }

    @Test
    fun `a spring class without a mapping has no prefix`() {
        val src =
            """
            @RestController
            @RequestMapping("/api")
            class A { @GetMapping("/x") fun x() {} }
            @RestController
            class B { @GetMapping("/y") fun y() {} }
            """.trimIndent()
        assertEquals(listOf("GET /api/x", "GET /y"), routes(src))
    }

    @Test
    fun `a spring method takes the prefix of the class it stands in`() {
        val src =
            """
            @RequestMapping("/outer")
            class O {
                @RestController @RequestMapping("/inner") class I { @GetMapping("/a") fun a() {} }
                @GetMapping("/b") fun b() {}
            }
            @GetMapping("/top") fun top() {}
            """.trimIndent()
        assertEquals(listOf("GET /inner/a", "GET /outer/b", "GET /top"), routes(src))
    }

    @Test
    fun `a mapping without its leading slash still makes a path that starts with one`() {
        // Spring and Ktor both take "api" for "/api". Without the slash the inspector's URL was
        // {{baseUrl}}api/hent/visning, and java.net.URI read 8080api as part of the host.
        val src =
            """
            @RestController
            @RequestMapping("api/hent")
            class A { @GetMapping(value = "visning") fun v() {} }
            fun Route.api() { route("api") { get("x") { } } }
            """.trimIndent()
        assertEquals(listOf("GET /api/hent/visning", "GET /api/x"), routes(src))
    }

    @Test
    fun `a route knows the query parameters and headers its handler reads`() {
        fun params(r: Route) =
            (r.query.map { "?${it.name}${if (it.required) "" else "?"}" } + r.headers.map { "H ${it.name}${if (it.required) "" else "?"}" })
        val src =
            """
            @RestController
            @RequestMapping("api/hent")
            class A(private val s: S) {
                @GetMapping(value = "/visning")
                open fun visning(
                    @RequestParam partsnummer: String,
                    @RequestParam instans: String,
                    @RequestParam(required = false) fokus: String?,
                    @RequestHeader headere: Map<String, String>,
                    request: HttpServletRequest,
                ): StreamingResponseBody { val x = mapOf(1 to 2) }

                @PostMapping("/b") fun b(
                    @RequestParam("q") query: String, @RequestParam(name = "p", defaultValue = "1") page: Int,
                    @RequestParam all: MultiValueMap<String, String>, @RequestHeader("X-Id") id: String,
                    @RequestHeader(value = "X-Opt", required = false) opt: String, @Valid @RequestParam n: List<Int>,
                ) {}

                @GetMapping("/none") fun none() {}
            }
            fun Route.api() {
                route("/k/{id}") {
                    get {
                        val q = call.request.queryParameters["q"] // read twice
                        call.request.queryParameters["q"]
                        call.parameters["id"]
                        call.parameters.get("page")
                        "queryParameters[\"inString\"]"
                    }
                }
                get("/l") { }
            }
            """.trimIndent()
        assertEquals(
            listOf(
                "GET /api/hent/visning" to listOf("?partsnummer", "?instans", "?fokus?"),
                "POST /api/hent/b" to listOf("?q", "?p?", "?n", "H X-Id", "H X-Opt?"),
                "GET /api/hent/none" to emptyList(),
                "GET /k/{id}" to listOf("?q?", "?page?"),
                "GET /l" to emptyList(),
            ),
            findRoutes(src).map { "${it.method} ${it.path}" to params(it) },
        )
    }

    @Test
    fun `a spring class mapping is not a route whatever annotates the class`() {
        val src =
            """
            @RequestMapping("/api")
            @PreAuthorize("hasRole('ADMIN')")
            // the admin endpoints
            @Tag(name = "admin", description = describe("x"))
            class A { @GetMapping("/x") fun x() {} }
            """.trimIndent()
        assertEquals(listOf("GET /api/x"), routes(src))
    }
}
