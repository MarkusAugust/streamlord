import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { fillPath, parseEnvFile, parseRequestsFile, pathParams, pushRecent, remove, resolveRequest, serializeRequestsFile, substitute, toCurl, upsert, type SavedRequest } from "../src/requests.ts";
import { findRoutes } from "../src/routes.ts";

const req = (over: Partial<SavedRequest> = {}): SavedRequest => ({ name: "counter", url: "{{baseUrl}}/api/counter-stream", method: "GET", signals: "", headers: "", ...over });

describe("saved requests", () => {
  it("round-trips the file and tolerates loose input", () => {
    const parsed = parseRequestsFile(`{"requests":[{"name":"a","url":"/x","method":"post","signals":{"q":1},"headers":{"X-A":"1"}},{"bad":true},{"name":"b","url":"/y"}]}`);
    assert.deepEqual(parsed.requests.map((r) => [r.name, r.method, r.signals.replace(/\s/g, ""), r.headers]), [["a", "POST", '{"q":1}', "X-A: 1"], ["b", "GET", "", ""]]);
    assert.deepEqual(parseRequestsFile("not json").requests, []);
    const text = serializeRequestsFile(parsed);
    assert.deepEqual(parseRequestsFile(text), parsed);
  });

  it("upserts by name case-insensitively and keeps names sorted", () => {
    let file = parseRequestsFile("{}");
    file = upsert(file, req({ name: "Zeta" }));
    file = upsert(file, req({ name: "alpha" }));
    file = upsert(file, req({ name: "ZETA", url: "/new" }));
    assert.deepEqual(file.requests.map((r) => [r.name, r.url]), [["alpha", "{{baseUrl}}/api/counter-stream"], ["ZETA", "/new"]]);
    assert.deepEqual(remove(file, "zeta").requests.map((r) => r.name), ["alpha"]);
  });

  it("substitutes variables and reports the missing ones", () => {
    const vars = { baseUrl: "http://localhost:8080", token: "t1" };
    const r = resolveRequest(req({ headers: "X-Csrf-Token: {{token}}\nX-Other: {{ nope }}", signals: '{"u":"{{user}}"}' }), vars);
    assert.equal(r.request.url, "http://localhost:8080/api/counter-stream");
    assert.equal(r.request.headers, "X-Csrf-Token: t1\nX-Other: {{ nope }}");
    assert.deepEqual(r.missing, ["user", "nope"]);
    assert.equal(substitute("no vars", {}).text, "no vars");
    assert.deepEqual(parseEnvFile('{"baseUrl":"http://x","port":8080,"nested":{"a":1}}'), { baseUrl: "http://x", port: "8080" });
    assert.deepEqual(parseEnvFile("["), {});
  });

  it("keeps a bounded, de-duplicated recent list", () => {
    let recent: SavedRequest[] = [];
    for (let i = 0; i < 12; i++) recent = pushRecent(recent, req({ name: "", url: `/r${i}` }));
    recent = pushRecent(recent, req({ name: "", url: "/r11" }));
    assert.equal(recent.length, 10);
    assert.equal(recent[0]?.url, "/r11");
    assert.equal(recent[0]?.name, "GET /r11");
    assert.equal(recent.filter((r) => r.url === "/r11").length, 1);
  });

  it("exports curl for query-string and body methods", () => {
    assert.equal(
      toCurl(req({ url: "http://localhost:8080/api/x", signals: '{"q": "a b"}', headers: "X-A: 1" })),
      `curl -N -X GET 'http://localhost:8080/api/x?datastar=%7B%22q%22%3A%22a+b%22%7D' -H 'Accept: text/event-stream' -H 'Datastar-Request: true' -H 'X-A: 1'`,
    );
    assert.equal(
      toCurl(req({ url: "http://h/x", method: "POST", signals: `{"s":"it's"}` })),
      `curl -N -X POST 'http://h/x' -H 'Accept: text/event-stream' -H 'Datastar-Request: true' -H 'Content-Type: application/json' --data '{"s":"it'\\''s"}'`,
    );
  });

  it("handles path parameters", () => {
    assert.deepEqual(pathParams("/users/{id}/posts/{slug?}"), [{ name: "id", optional: false }, { name: "slug", optional: true }]);
    assert.equal(fillPath("/users/{id}/posts/{slug?}", { id: "4 2", slug: "" }), "/users/4%202/posts");
    assert.equal(fillPath("/users/{id}/posts/{slug?}", { id: "7", slug: "x" }), "/users/7/posts/x");
  });
});

describe("routes", () => {
  it("finds ktor routes with nested prefixes and path-less verbs", () => {
    const src = `fun Route.api() {
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
  val s = "get(\\"/string\\")"
}`;
    assert.deepEqual(
      findRoutes(src).map((r) => `${r.method} ${r.path}`),
      ["GET /api/counter", "GET /api/coffee/tracker", "POST /api/coffee/add", "DELETE /api/users/{id}", "GET /top"],
    );
  });

  it("finds spring routes with a class prefix", () => {
    const src = `@RestController
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
}`;
    assert.deepEqual(
      findRoutes(src).map((r) => `${r.method} ${r.path} ${r.framework}`),
      ["GET /api/feed spring", "POST /api/search spring", "PUT /api/legacy spring", "DELETE /api spring"],
    );
  });
});
