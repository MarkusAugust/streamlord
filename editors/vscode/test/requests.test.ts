import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { describeVariables, emptyBodyHint, fillPath, mergeVariables, newRequestLabel, parseEnv, parseRequestsFile, pathParams, pushRecent, remove, resolveRequest, sendableUrl, serializeRequestsFile, substitute, toCurl, unreachableHint, upsert, variableCompletions, withEnvVariables, type SavedRequest } from "../src/requests.ts";
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

  it("substitutes text where a name is not a variable", () => {
    assert.equal(substitute("no vars", {}).text, "no vars");
    assert.equal(substitute("{{baseUrl}}/x", { baseUrl: "http://h" }).text, "http://h/x");
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

// The same cases, character for character, as `sends the url of the curl line or says why not` in the
// analysis module's `RequestsTest.kt`: both inspectors refuse the same URLs with the same words.
describe("sendable url", () => {
  it("sends the url of the curl line or says why not", () => {
    const cases: [string, string | null, string | null][] = [
      ["http://h:8080/api/x?flag&q=a%20b+c&s='x'&a%5B%5D=1#frag", "http://h:8080/api/x?flag&q=a%20b+c&s='x'&a%5B%5D=1", null],
      ["http://h/x?filter[name]=1", null, "The URL holds [, which cannot be sent as written. Write it as %5B."],
      ["HTTPS://h/søk/\u{1F600}", "HTTPS://h/s%C3%B8k/%F0%9F%98%80", null],
      ["http://[::1]:8080/x", "http://[::1]:8080/x", null],
      ["http://h/x\uD83D", "http://h/x%EF%BF%BD", null],
      ["/api/x", null, "Not an absolute URL: /api/x. Start it with http:// or https://, or with {{baseUrl}}."],
      ["{{base}}/x", null, "Not an absolute URL: {{base}}/x. Start it with http:// or https://, or with {{baseUrl}}."],
      ["localhost:8080/x", null, "Not an absolute URL: localhost:8080/x. Start it with http:// or https://, or with {{baseUrl}}."],
      ["ftp://h/x", null, "Only http:// and https:// URLs can be sent: ftp://h/x"],
      ["http:///x", null, "No host in http:///x."],
      ["http://h/a b", null, "The URL holds a space or a control character, which cannot be sent. Write a space as %20."],
      ["http://h/users/{id}/x", null, "The URL still holds {id}. Fill in the path parameter before sending."],
      ["http://h/p\"q", null, "The URL holds \", which cannot be sent as written. Write it as %22."],
      ["http://h/p|q", null, "The URL holds |, which cannot be sent as written. Write it as %7C."],
      ["http://h/x/[y]", null, "The URL holds [, which cannot be sent as written. Write it as %5B."],
      ["http://h/p%zz", null, "The URL holds a % that starts no escape. Write it as %25."],
      ["http://bø.no/x", null, "The host holds ø, which cannot be sent as written. Write the host in its xn-- form."],
    ];
    for (const [url, sent, error] of cases) {
      assert.deepEqual(sendableUrl(url), sent !== null ? { url: sent } : { error }, url);
    }
  });

  it("refuses a host or port java.net.URI cannot read, and says which", () => {
    const cases: [string, string][] = [
      ["http://localhost:8080api/hent/visning", "The port in localhost:8080api is not a number. Is a / missing between the port and the path?"],
      ["http://localhost:8080:/x", "The port in localhost:8080: is not a number. Is a / missing between the port and the path?"],
      ["http://my_service:8080/x", "The host my_service cannot be sent: a host name holds only letters, digits, hyphens and dots, and its last part starts with a letter."],
    ];
    for (const [url, error] of cases) assert.deepEqual(sendableUrl(url), { error }, url);
  });

  it("points to the server log when an error came without a body", () => {
    assert.equal(emptyBodyHint(400, " \n"), "The server sent no body with this 400, so the reason is in its log. Spring Boot, for one, writes no error body for a request that accepts only text/event-stream.");
    assert.equal(emptyBodyHint(400, "Required request parameter 'partsnummer' is not present"), null);
    assert.equal(emptyBodyHint(204, ""), null);
  });

  it("refuses exactly the hosts and ports the IntelliJ inspector refuses", () => {
    // The same URLs as RequestsTest.kt, which checks each verdict against the JDK's HTTP client.
    const sent = ["http://localhost:8080/x", "http://localhost:/x", "http://localhost", "http://h?x=1", "http://user:pw@h:1/x", "http://127.0.0.1:8080/x", "http://10.0.0.255/x", "http://[::1]:8080/x", "http://[fe80::1]/x", "http://example.com./x", "http://a-b.example.com/x", "http://x1/x", "http://1x/x", "http://123/x"];
    const refused = ["http://localhost:8080api/x", "http://localhost:8080:/x", "http://h:-1/x", "http://h:80a", "http://my_service:8080/x", "http://-h/x", "http://h-/x", "http://a..b/x", "http://a.1b/x", "http://1.2.3.999/x", "http://1.2.3/x", "http://a.b-/x", "http://h%41/x", "http://h!/x"];
    for (const url of sent) assert.ok("url" in sendableUrl(url), url);
    for (const url of refused) assert.ok("error" in sendableUrl(url), url);
  });
});

// The same cases, character for character, as the variable tests in the analysis module's
// `RequestsTest.kt`: both inspectors read the env file, judge it and fill a request alike.
describe("inspector variables", () => {
  const ENV = ".streamlord/env.json is not valid";
  const env = parseEnv(`{
  "baseUrl": "http://127.0.0.1:8081/",
  "signals": { "search" : "ash", "n": 1.0, "s": "\\u00e9</p>" },
  "headers": { "Authorization": "Bearer x", "X-Csrf-Token": "abc" }
}`);
  const vars = mergeVariables("http://localhost:8080/", env);
  const defaults = mergeVariables("http://localhost:8080/", parseEnv("{}"));

  it("read the three keys of the env file, signals as written", () => {
    assert.deepEqual(env, {
      baseUrl: "http://127.0.0.1:8081",
      signals: '{"search":"ash","n":1.0,"s":"\\u00e9</p>"}',
      headers: [["Authorization", "Bearer x"], ["X-Csrf-Token", "abc"]],
      errors: [],
    });
  });

  it("say what makes the env file invalid", () => {
    assert.deepEqual(parseEnv("not json").errors, [".streamlord/env.json is not valid JSON."]);
    assert.deepEqual(parseEnv("[]").errors, [`${ENV}: it must be an object with baseUrl, signals or headers.`]);
    assert.deepEqual(parseEnv('{"csrf": "x", "baseUrl": 8080, "signals": [], "headers": {"X-A": 1}}'), {
      baseUrl: null,
      signals: null,
      headers: null,
      errors: [
        `${ENV}: "csrf" is not a known key. Use baseUrl, signals or headers.`,
        `${ENV}: baseUrl must be text, such as "http://localhost:8080".`,
        `${ENV}: signals must be a JSON object, such as {"search": "ash"}.`,
        `${ENV}: the value of the header "X-A" must be text.`,
      ],
    });
    assert.deepEqual(parseEnv('{"baseUrl": "localhost:8080", "headers": []}').errors, [
      `${ENV}: baseUrl must start with http:// or https://.`,
      `${ENV}: headers must be an object of names and values, such as {"Authorization": "Bearer token"}.`,
    ]);
  });

  it("say where each value comes from", () => {
    assert.deepEqual(defaults, [{ name: "baseUrl", value: "http://localhost:8080", source: "default" }]);
    assert.equal(
      describeVariables(vars),
      'baseUrl = http://127.0.0.1:8081\nsignals = {"search":"ash","n":1.0,"s":"\\u00e9</p>"}\nheaders = Authorization: Bearer x; X-Csrf-Token: abc',
    );
    assert.equal(describeVariables(mergeVariables("http://h", parseEnv('{"signals": {}, "headers": {}}'))), "baseUrl = http://h   (default)\nsignals = {}\nheaders = (none)");
  });

  it("fill a request, each variable in its own field", () => {
    const r = resolveRequest({ name: "", url: "{{baseUrl}}/x?q={{ baseUrl }}", method: "POST", signals: "{{signals}}", headers: "{{headers}}\nX-B: 1" }, vars);
    assert.deepEqual(r.errors, []);
    assert.deepEqual(r.unset, []);
    assert.equal(r.request.url, "http://127.0.0.1:8081/x?q=http://127.0.0.1:8081");
    assert.equal(r.request.signals, '{"search":"ash","n":1.0,"s":"\\u00e9</p>"}');
    assert.equal(r.request.headers, "Authorization: Bearer x\nX-Csrf-Token: abc\nX-B: 1");
    const bad = resolveRequest({ name: "", url: "{{baseUrl}}/{{signals}}", method: "POST", signals: '{"a": "{{csrf}}"} {{signals}}', headers: "{{headers}}\nOrigin: {{baseUrl}}" }, defaults);
    assert.deepEqual(bad.errors, [
      "{{signals}} belongs in the signals field.",
      "{{csrf}} is not a variable. Use {{baseUrl}}, {{signals}} or {{headers}}.",
      '{{signals}} is not set. Add "signals" to .streamlord/env.json.',
      '{{headers}} is not set. Add "headers" to .streamlord/env.json.',
      "{{baseUrl}} belongs in the URL field.",
    ]);
    assert.deepEqual(bad.unset, ["signals", "headers"]);
  });

  it("add keys to the env file without touching what is there", () => {
    assert.equal(withEnvVariables(null, [], "http://localhost:8080"), '{\n  "baseUrl": "http://localhost:8080"\n}\n');
    assert.equal(withEnvVariables(null, ["headers", "signals"], "http://h"), '{\n  "baseUrl": "http://h",\n  "headers": {},\n  "signals": {}\n}\n');
    assert.equal(withEnvVariables("{}", ["signals"], "http://h"), '{\n  "signals": {}\n}');
    assert.equal(withEnvVariables('{ "baseUrl": "http://x" }\n', ["signals", "baseUrl"], "http://h"), '{ "baseUrl": "http://x",\n  "signals": {}\n}\n');
    assert.equal(withEnvVariables('{"signals": {}}', ["signals"], "http://h"), '{"signals": {}}');
    assert.equal(withEnvVariables("not json", ["signals"], "http://h"), null);
    assert.equal(withEnvVariables("[]", ["signals"], "http://h"), null);
  });

  it("explain an unreachable server by its baseUrl", () => {
    assert.equal(
      unreachableHint("{{baseUrl}}/hendelser", defaults),
      "{{baseUrl}} is http://localhost:8080, the default. Set baseUrl in .streamlord/env.json if your server listens elsewhere.",
    );
    assert.equal(unreachableHint("{{ baseUrl }}/x", vars), "{{baseUrl}} is http://127.0.0.1:8081, from .streamlord/env.json.");
    assert.equal(unreachableHint("http://127.0.0.1:8081/x", defaults), null);
  });

  it("count what the request list holds in its first entry", () => {
    assert.deepEqual([newRequestLabel(0, 0), newRequestLabel(2, 5), newRequestLabel(0, 1)], ["New request…", "New request… (2 saved, 5 recent)", "New request… (1 recent)"]);
  });

  it("complete a name after {{ with what the field takes", () => {
    const names = (c: ReturnType<typeof variableCompletions>) => c && { from: c.from, to: c.to, names: c.items.map((v) => v.name) };
    assert.deepEqual(names(variableCompletions("{{", 2, "url", vars)), { from: 0, to: 2, names: ["baseUrl"] });
    assert.deepEqual(names(variableCompletions("a {{ s", 6, "signals", vars)), { from: 2, to: 6, names: ["signals"] });
    assert.deepEqual(names(variableCompletions("{{}}", 2, "headers", vars)), { from: 0, to: 4, names: ["headers"] });
    assert.deepEqual(names(variableCompletions("X: {{H", 6, "headers", vars)), { from: 3, to: 6, names: ["headers"] });
    assert.equal(variableCompletions("{{x", 3, "url", vars), null);
    assert.equal(variableCompletions("{x", 2, "url", vars), null);
    assert.equal(variableCompletions("{{", 2, "signals", defaults), null);
    assert.equal(variableCompletions("X: {{b", 6, "headers", vars), null);
  });
});
