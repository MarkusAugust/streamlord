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

  // The expected text of the next five tests is the same, character for character, in the analysis
  // module's `RequestsTest.kt`: both editors write one shared file and export one curl line.

  it("curl export takes the url as written and never throws", () => {
    const tail = " -H 'Accept: text/event-stream' -H 'Datastar-Request: true'";
    const get = (url: string): string => toCurl(req({ url }));
    assert.equal(get("http://localhost:8080"), `curl -N -X GET 'http://localhost:8080?datastar=%7B%7D'${tail}`);
    assert.equal(get("/x"), `curl -N -X GET '/x?datastar=%7B%7D'${tail}`);
    assert.equal(get("http://x/{id}"), `curl -N -X GET 'http://x/{id}?datastar=%7B%7D'${tail}`);
    assert.equal(get("http://h/a b"), `curl -N -X GET 'http://h/a b?datastar=%7B%7D'${tail}`);
    assert.equal(get("{{base}}/x"), `curl -N -X GET '{{base}}/x?datastar=%7B%7D'${tail}`);
    assert.equal(get("http://h/x?y=1&datastar=old&z=a b#frag"), `curl -N -X GET 'http://h/x?y=1&z=a b&datastar=%7B%7D#frag'${tail}`);
    assert.equal(toCurl(req({ url: "http://localhost:8080", method: "POST" })), `curl -N -X POST 'http://localhost:8080'${tail} -H 'Content-Type: application/json' --data '{}'`);
  });

  it("curl export compacts signals without respelling them", () => {
    const tail = " -H 'Accept: text/event-stream' -H 'Datastar-Request: true'";
    assert.equal(
      toCurl(req({ url: "http://h/x", method: "POST", signals: ' { "n" : 1.0,\n\t"s": "a  \\" b\\u00e9</p>", "l": [ 1, 2 ] }\r\n' })),
      `curl -N -X POST 'http://h/x'${tail} -H 'Content-Type: application/json' --data '{"n":1.0,"s":"a  \\" b\\u00e9</p>","l":[1,2]}'`,
    );
    assert.equal(
      toCurl(req({ url: "http://h/x", signals: `{"k":"~*'()!é \uD83D"}` })),
      `curl -N -X GET 'http://h/x?datastar=%7B%22k%22%3A%22%7E*%27%28%29%21%C3%A9+%EF%BF%BD%22%7D'${tail}`,
    );
    assert.equal(
      toCurl(req({ url: "http://h/x", method: "PUT", signals: "  not json ", headers: "X-A: 1\nBad\n B : 3 \r\nX-A: 2" })),
      `curl -N -X PUT 'http://h/x'${tail} -H 'X-A: 2' -H 'B: 3' -H 'Content-Type: application/json' --data 'not json'`,
    );
  });

  it("saved names sort by lowercase code units, not by locale", () => {
    const names = ["b", "a", "B", "_x", "Zeta", "éa", "zz", "a-b", "ab", "A", "10", "9", "ΌΣ", "όσ2"];
    const file = names.reduce((acc, name) => upsert(acc, req({ name })), parseRequestsFile("{}"));
    assert.deepEqual(file.requests.map((r) => r.name), ["10", "9", "_x", "A", "a-b", "ab", "B", "Zeta", "zz", "éa", "ΌΣ", "όσ2"]);
    assert.equal(remove(file, "ΌΣ2").requests.at(-1)?.name, "ΌΣ");
    assert.equal(remove(file, "όσ").requests.at(-1)?.name, "όσ2");
  });

  it("the file is written the same byte for byte", () => {
    assert.equal(serializeRequestsFile(parseRequestsFile("{}")), '{\n  "version": 1,\n  "requests": []\n}\n');
    const odd = 'q" b\\ t\t n\n \u0001 \u007F é \uD83D\uDE00 \u2028 \u2029 </p> / <';
    const written = '"q\\" b\\\\ t\\t n\\n \\u0001 \u007F é \uD83D\uDE00 \\u2028 \\u2029 <\\/p> / <"';
    assert.equal(
      serializeRequestsFile({ version: 1, requests: [req({ name: odd, url: "/x", signals: odd }), req({ name: "b", url: "/y", method: "POST", headers: "A: 1" })] }),
      `{\n  "version": 1,\n  "requests": [\n    {\n      "name": ${written},\n      "url": "/x",\n      "method": "GET",\n` +
        `      "signals": ${written},\n      "headers": ""\n    },\n    {\n      "name": "b",\n      "url": "/y",\n` +
        `      "method": "POST",\n      "signals": "",\n      "headers": "A: 1"\n    }\n  ]\n}\n`,
    );
  });

  it("a name every object has is not a variable or a path value", () => {
    const r = substitute("{{a}} {{constructor}} {{toString}} {{__proto__}}", { a: "1" });
    assert.equal(r.text, "1 {{constructor}} {{toString}} {{__proto__}}");
    assert.deepEqual(r.missing, ["constructor", "toString", "__proto__"]);
    assert.equal(fillPath("/{id}/{constructor}/{valueOf?}{toString?}", { id: "7" }), "/7/");
    const loose = `{"requests":[{"name":"a","url":"/x","headers":{"A":"b","O":{"k":[1]},"T":true}},{"name":"b","url":"/y","headers":["x"]}]}`;
    assert.deepEqual(parseRequestsFile(loose).requests.map((r) => r.headers), ['A: b\nO: {"k":[1]}\nT: true', ""]);
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
