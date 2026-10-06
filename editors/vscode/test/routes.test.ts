import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { findRoutes } from "../src/routes.ts";

/** The cases of the analysis module's `RoutesTest.kt`. */
const routes = (src: string): string[] => findRoutes(src).map((r) => `${r.method} ${r.path}`);

describe("route detection", () => {
  it("a verb called on a receiver is not a route", () => {
    const src = `fun Application.module() {
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
}`;
    assert.deepEqual(routes(src), ["GET /top"]);
  });

  it("ktor routes keep their shapes", () => {
    const src = `fun Route.api() {
    get("/x") { }
    get("relative") { }
    get("/no-lambda")
    authenticate("jwt") { post("/secure") { } }
    route("/api") { delete { } }
}`;
    assert.deepEqual(routes(src), ["GET /x", "GET /relative", "GET /no-lambda", "POST /secure", "DELETE /api"]);
  });

  it("a route with a method keeps its prefix", () => {
    const src = `fun Route.api() {
    route("/api") {
        route("/v1", HttpMethod.Get) { get("/deep") { } }
        get("/after") { }
    }
}`;
    assert.deepEqual(routes(src), ["GET /api/v1/deep", "GET /api/after"]);
  });

  it("a spring class mapping is a prefix whatever stands before class", () => {
    const src = `@RestController
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
}`;
    assert.deepEqual(routes(src), ["GET /api/x", "GET /api", "PUT /d/x", "GET /c/x"]);
  });

  it("a spring class without a mapping has no prefix", () => {
    const src = `@RestController
@RequestMapping("/api")
class A { @GetMapping("/x") fun x() {} }
@RestController
class B { @GetMapping("/y") fun y() {} }`;
    assert.deepEqual(routes(src), ["GET /api/x", "GET /y"]);
  });

  it("a spring method takes the prefix of the class it stands in", () => {
    const src = `@RequestMapping("/outer")
class O {
    @RestController @RequestMapping("/inner") class I { @GetMapping("/a") fun a() {} }
    @GetMapping("/b") fun b() {}
}
@GetMapping("/top") fun top() {}`;
    assert.deepEqual(routes(src), ["GET /inner/a", "GET /outer/b", "GET /top"]);
  });

  it("a mapping without its leading slash still makes a path that starts with one", () => {
    // Spring and Ktor both take "api" for "/api". Without the slash the inspector's URL was
    // {{baseUrl}}api/hent/visning, and java.net.URI read 8080api as part of the host.
    const src = `@RestController
@RequestMapping("api/hent")
class A { @GetMapping(value = "visning") fun v() {} }
fun Route.api() { route("api") { get("x") { } } }`;
    assert.deepEqual(routes(src), ["GET /api/hent/visning", "GET /api/x"]);
  });

  it("a spring class mapping is not a route whatever annotates the class", () => {
    const src = `@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
// the admin endpoints
@Tag(name = "admin", description = describe("x"))
class A { @GetMapping("/x") fun x() {} }`;
    assert.deepEqual(routes(src), ["GET /api/x"]);
  });
});
