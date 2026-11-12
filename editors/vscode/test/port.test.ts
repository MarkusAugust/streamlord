import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import {
  applySignalPatch,
  baseUrlSuggestion,
  complete,
  diagnose,
  environment,
  findRoutes,
  parseHeaderLines,
  signalPlaces,
  signalSiteAt,
  signatureHelp,
  signalsOf,
  StreamReader,
  streamRequest,
  unreachableHint,
  variableCompletions,
  type Fix,
  type Issue,
  type Variable,
} from "../src/analysis.ts";

/**
 * The port as the adapters meet it: the Kotlin analysis compiled to JavaScript, handing plain
 * objects over. The corpus in `fixtures/corpus`, which the analysis's own tests judge case by case
 * on the JVM and on Node, is run through it once more here, for what only this side of the
 * boundary can show.
 */

interface Expected {
  code: string;
  start?: number;
  end?: number;
  severity?: string;
  message?: string;
  fixes?: string[];
}
interface Case {
  name: string;
  source: string;
  prefix?: string;
  constants?: string[];
  /** What a host knows of the project beyond the file: whether it has streamlord-html, whether it has core, which calls are not Streamlord's. */
  htmlDsl?: boolean;
  core?: boolean;
  foreign?: string[];
  issues: Expected[];
}

/** The source with a fix applied: its own replacement and the edits it brings, the last first. */
function applied(source: string, fix: Fix): string {
  const edits = [{ start: fix.start, end: fix.end, text: fix.text }, ...(fix.edits ?? [])].sort((a, b) => b.start - a.start);
  return edits.reduce((text, e) => text.slice(0, e.start) + e.text + text.slice(e.end), source);
}

const byCode = (a: Issue, b: Issue) => ((a.code ?? "") < (b.code ?? "") ? -1 : (a.code ?? "") > (b.code ?? "") ? 1 : 0);
const dir = new URL("./fixtures/corpus/", import.meta.url);

/**
 * The files whose cases call the DSL without importing it, which the corpus reads as a host that
 * leaves the file's modules open. The port reads them from the imports, as the editor has to, so
 * those cases are the analysis's own suites' to judge.
 */
const MODULES_LEFT_OPEN = new Set(["kotlin.json", "kotlin-sdk.json"]);

describe("the corpus through the port", () => {
  let judged = 0;
  for (const file of readdirSync(dir).sort()) {
    const corpus = JSON.parse(readFileSync(new URL(file, dir), "utf8")) as { kind: string; cases: Case[] };
    // The checks no adapter asks for are not the port's to run.
    if ((corpus.kind !== "html" && corpus.kind !== "kotlin") || MODULES_LEFT_OPEN.has(file)) continue;
    it(`judges every case in ${file} as written`, () => {
      for (const c of corpus.cases) {
        // What a host knows beyond the text it cannot tell the port.
        if (c.htmlDsl !== undefined || c.core !== undefined || c.foreign !== undefined) continue;
        const found = diagnose(c.source, corpus.kind === "kotlin", c.prefix ?? "data-", c.constants ?? [], null);
        const actual = [...found].sort((a, b) => a.start - b.start || a.end - b.end || byCode(a, b));
        const seen = actual.map((i, n) => {
          const o: Expected = { code: i.code ?? "" };
          // An expected issue without a range is checked by code only; a severity or a message is checked where the case gives one.
          if (c.issues[n]?.start !== undefined || !c.issues[n]) Object.assign(o, { start: i.start, end: i.end });
          if (c.issues[n]?.severity !== undefined) o.severity = i.severity;
          if (c.issues[n]?.message !== undefined) o.message = i.message;
          if (i.fixes?.length) o.fixes = i.fixes.map((f) => applied(c.source, f));
          return o;
        });
        assert.deepEqual(seen, c.issues, `${file}: ${c.name}`);
        judged++;
      }
    });
  }
  it("reaches most of the corpus", () => assert.ok(judged > 500, `judged ${judged} cases`));
});

describe("what crosses the port", () => {
  const html = `<div data-signals:count="0" data-text="$cuont" data-on:click="@gett('/x')"></div>`;

  it("is plain: own properties, arrays and strings that survive JSON", () => {
    const issues = diagnose(html, false, "data-", [], ["count"]);
    assert.ok(Array.isArray(issues));
    const unknown = issues.find((i) => i.code === "unknown-signal");
    assert.ok(unknown, JSON.stringify(issues));
    assert.equal(Object.getPrototypeOf(unknown), Object.prototype);
    assert.deepEqual(JSON.parse(JSON.stringify(unknown)), unknown);
    assert.equal(typeof unknown.start, "number");
    assert.equal(unknown.severity, "warning");
  });

  it("leaves an optional property out where there is nothing to say", () => {
    const [issue] = diagnose(`<div data-text="1 +"></div>`, false, "data-", [], null);
    assert.ok(issue);
    assert.equal("fixes" in issue, false);
    assert.equal(issue.fixes ?? null, null);
  });

  it("makes a fix's text when it is read, and the same each time", () => {
    const fixes = diagnose(html, false, "data-", [], ["count"]).flatMap((i) => i.fixes ?? []);
    assert.ok(fixes.length > 0);
    for (const fix of fixes) {
      assert.equal(typeof fix.text, "string");
      assert.equal(fix.text, fix.text);
      assert.ok(Object.keys(fix).includes("text"));
    }
  });

  it("reports a signal no file defines only when told what the workspace defines", () => {
    const codes = (defined: string[] | null) => diagnose(html, false, "data-", [], defined).map((i) => i.code);
    assert.equal(codes(null).includes("unknown-signal"), false);
    assert.equal(codes(["count"]).includes("unknown-signal"), true);
    assert.equal(codes(["count", "cuont"]).includes("unknown-signal"), false);
  });

  it("names the prefix setting as VS Code names it", () => {
    const [issue] = diagnose(`<div data-star-on:click="x()"></div>`, false, "data-", [], null);
    assert.equal(issue?.code, "prefix-mismatch");
    assert.equal(issue?.message, "This is an aliased Datastar attribute, but the prefix is set to data-. Check streamlord.attributePrefix.");
  });

  it("reads a Kotlin file with the modules its imports show", () => {
    const call = `fun page() = div { dataText("1 +") }`;
    const withDsl = `import io.github.markusaugust.streamlord.html.dataText\n${call}`;
    assert.deepEqual(diagnose(call, true, "data-", [], null), []);
    assert.equal(diagnose(withDsl, true, "data-", [], null).length, 1);
  });

  it("takes back what it handed out", () => {
    const places = signalPlaces(html, false, "data-");
    const defined = signalsOf(html, false).definitions;
    assert.deepEqual(defined, ["count"]);
    const site = signalSiteAt(places, defined, html.indexOf("count") + 1);
    assert.deepEqual(site && { signal: site.signal, kind: site.declaration?.kind }, { signal: "count", kind: "key" });
  });

  it("hands completion over as data", () => {
    const [item] = complete("<div data-on:click__deb", 23, false, "data-", [], [], [], true).filter((i) => i.label === "debounce");
    assert.deepEqual({ ...item }, { label: "debounce", kind: "modifier", replaceFrom: 20, snippet: "debounce.${1:500ms}", detail: "__debounce.<duration>", sort: "debounce" });
    assert.deepEqual(signatureHelp("<div data-on:click=\"@get('/x', ", 30, false, "data-")?.parameters, ["uri", "options?"]);
  });

  it("opens a route as the request the inspector sends", () => {
    const [route] = findRoutes(`fun Route.x() { get("/items/{id}") { call.respondText("") } }`);
    assert.deepEqual(route && { method: route.method, path: route.path, url: route.request.url }, { method: "GET", path: "/items/{id}", url: "{{baseUrl}}/items/{{id}}" });
  });

  it("reads a stream chunk by chunk and folds a patch into the store", () => {
    const reader = new StreamReader();
    assert.deepEqual(reader.feed(": hello\n\nevent: datastar-patch-signals\ndata: signals {\"a\""), [{ comment: "hello" }]);
    const [event] = reader.feed(": 1}\n\n");
    assert.equal(event?.frame?.event, "datastar-patch-signals");
    assert.equal(event?.frame?.id, null);
    assert.equal(typeof event?.frame?.receivedAt, "number");
    const patched = applySignalPatch(`{"b":2}`, event?.frame?.args.signals, false);
    assert.deepEqual({ ...patched, store: JSON.parse(patched.store) }, { store: { b: 2, a: 1 }, refused: false });
    const refused = applySignalPatch(`{"b":2}`, `{"a":1,"__proto__":{"x":1}}`, false);
    assert.deepEqual([JSON.parse(refused.store), refused.refused, typeof refused.error], [{ b: 2, a: 1 }, true, "string"]);
  });
  it("keeps a header whatever it is named", () => {
    const headers = parseHeaderLines("__proto__: a\nconstructor: b\ntoString: c\nX-Token: d");
    assert.deepEqual(Object.entries(headers), [["__proto__", "a"], ["constructor", "b"], ["toString", "c"], ["X-Token", "d"]]);
    const sent = streamRequest("http://localhost:8080/x", "GET", "{}", headers).headers as Record<string, string>;
    assert.deepEqual([sent["__proto__"], sent["constructor"], sent["toString"], sent["X-Token"]], ["a", "b", "c", "d"]);
  });

  it("answers for a variable source and a field it does not know, as for none of those it knows", () => {
    const vars = environment(`{"baseUrl":"http://localhost:1","params":{"token":"t"}}`, "http://localhost:8080", undefined, undefined).vars;
    const odd: Variable[] = vars.map((v) => ({ ...v, source: "elsewhere" }) as unknown as Variable);
    // A field that is none of the three takes the params, and none of the variables with a field of their own.
    assert.deepEqual(variableCompletions("{{", 2, "nowhere", vars)?.items.map((v) => v.name), ["token"]);
    assert.equal(variableCompletions("{{b", 3, "nowhere", vars), null);
    // A source that is none of the three is no default and no running server, and not the file's either.
    assert.equal(unreachableHint("{{baseUrl}}/x", odd, undefined, undefined), unreachableHint("{{baseUrl}}/x", vars, undefined, undefined));
    assert.equal(baseUrlSuggestion(vars, "http://localhost:2", "demo"), "http://localhost:2");
    assert.equal(baseUrlSuggestion(odd, "http://localhost:2", "demo"), null);
  });
  it("folds a patch nested deeper than the SDK reads from a request, as the client does", () => {
    const deep = '{"a":'.repeat(200) + "1" + "}".repeat(200);
    const patched = applySignalPatch(`{"b":2}`, deep, false);
    assert.deepEqual([patched.store, patched.error, patched.refused], [`{"b":2,"a":${deep.slice(5, -1)}}`, undefined, false]);
    // And once more into the store that now holds it.
    assert.equal(applySignalPatch(patched.store, deep, false).store, patched.store);
  });
});
