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
});
