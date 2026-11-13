import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as port from "../src/analysis.ts";

/**
 * The port writes each object it hands over by the names of its properties, as strings. This holds
 * every object it hands over to the types the compiler wrote for it: no property it has not
 * declared, and every one it declares and does not mark optional.
 */

const types = readFileSync(new URL("../analysis-js/build/port/streamlord-analysis-js.d.mts", import.meta.url), "utf8");

/** Each interface's properties, with whether it may be left out and the names of the interfaces its type names. */
const interfaces = new Map<string, Map<string, { optional: boolean; refers: string[] }>>();
for (const [, name = "", body = ""] of types.matchAll(/export declare interface (\w+) \{([^}]*)\}/g)) {
  const props = new Map<string, { optional: boolean; refers: string[] }>();
  for (const [, prop = "", optional, type = ""] of body.matchAll(/readonly (\w+)(\?)?: ([^;]+);/g)) {
    props.set(prop, { optional: optional === "?", refers: [...type.matchAll(/\b[A-Z]\w*\b/g)].map((r) => r[0]) });
  }
  interfaces.set(name, props);
}

/** What is wrong with `value` as the interface `type`, and with every object of a declared type in it. */
function problems(type: string, value: unknown, at: string): string[] {
  const props = interfaces.get(type);
  if (!props || value === null || value === undefined) return [];
  if (Array.isArray(value)) return value.flatMap((v, i) => problems(type, v, `${at}[${i}]`));
  const out: string[] = [];
  const own = value as Record<string, unknown>;
  for (const key of Object.keys(own)) if (!props.has(key)) out.push(`${at}.${key} is not declared on ${type}`);
  for (const [name, p] of props) {
    if (!(name in own)) {
      if (!p.optional) out.push(`${at}.${name}, which ${type} requires, is missing`);
      continue;
    }
    for (const r of p.refers) out.push(...problems(r, own[name], `${at}.${name}`));
  }
  return out;
}

const kotlin = 'import io.github.markusaugust.streamlord.html.*\nfun f(count: Int) = div { dataOnClick("@get(\'/x\'); $count++"); dataText("\\$count") }\nval p = "<p data-text=\\"$count\\">${count}</p>"\n@Serializable data class Form(val email: String = "")\nfun Route.r() { get("/a/{id}") { call.readSignals<Form>() } }\nconst val LABEL = "x"\n';
const html = '<div data-signals:count="0" data-on:click="@post(\'/x\', {headers: {}})" data-text="$count + $nope" id="a" class="b"></div>';

describe("what the port hands over has the shape its types declare", () => {
  it("in every result of the editor's half", () => {
    const found: string[] = [];
    const check = (type: string, value: unknown, at: string) => found.push(...problems(type, value, at));
    for (const [src, isKotlin] of [[kotlin, true], [html, false]] as const) {
      const signals = port.signalsOf(src, isKotlin, "data-");
      check("FileSignals", signals, "signalsOf");
      const drift = port.driftFactsOf(src, isKotlin, "data-");
      check("DriftFacts", drift, "driftFactsOf");
      check("Issue", port.diagnose(src, isKotlin, "data-", ["LABEL"], signals.definitions, [drift]), "diagnose");
      const places = port.signalPlaces(src, isKotlin, "data-");
      check("SignalPlaces", places, "signalPlaces");
      for (let at = 0; at <= src.length; at++) {
        check("CompletionItem", port.complete(src, at, isKotlin, "data-", ["count"], ["a"], ["b"], true), `complete@${at}`);
        check("HoverText", port.hover(src, at, isKotlin, "data-"), `hover@${at}`);
        check("Signature", port.signatureHelp(src, at, isKotlin, "data-"), `signatureHelp@${at}`);
        check("SignalSite", port.signalSiteAt(places, signals.definitions, at), `signalSiteAt@${at}`);
        if (isKotlin) check("Fix", port.rewriteAt(src, at, at, "data-", []), `rewriteAt@${at}`);
      }
      check("SignalSite", port.signalSitesNaming(places, signals.definitions, "count", true), "signalSitesNaming");
    }
    check("WorkspaceFiles", port.workspaceFiles(), "workspaceFiles");
    assert.deepEqual(found, []);
  });

  it("in every result of the inspector's half", () => {
    const found: string[] = [];
    const check = (type: string, value: unknown, at: string) => found.push(...problems(type, value, at));
    const routes = port.findRoutes(kotlin);
    assert.ok(routes.length > 0);
    check("Route", routes, "findRoutes");
    const request = routes[0]!.request;
    check("InspectorFiles", port.inspectorFiles(), "inspectorFiles");
    for (const text of [null, "", '{"requests": [{"name": "a", "url": "/x"}, 1]}', "{"]) check("RequestsFile", port.parseRequestsFile(text, ".streamlord/inspector.json"), "parseRequestsFile");
    check("RequestsEdit", port.saveRequest(null, request, ".streamlord/inspector.json"), "saveRequest");
    check("RequestsEdit", port.removeRequest("{", "a", ".streamlord/inspector.json"), "removeRequest");
    check("SavedRequest", port.pushRecent([request], request), "pushRecent");
    for (const text of [null, '{"baseUrl": "http://h", "signals": {"a": 1}, "params": {"id": "1"}}', '{"nope": 1}']) {
      const env = port.environment(text, "http://localhost:8080", "http://localhost:9000", "Run");
      check("Environment", env, "environment");
      check("ResolvedRequest", port.resolveRequest({ ...request, url: "{{baseUrl}}/a/{{id}}" }, env.vars), "resolveRequest");
      check("VariableCompletion", port.variableCompletions("{{", 2, "url", env.vars), "variableCompletions");
    }
    for (const signals of ["", "{", "[1]"]) check("StreamRequest", port.streamRequest("http://h/x", "GET", signals, {}), "streamRequest");
    check("StreamRequest", port.streamRequest("nope", "GET", "", {}), "streamRequest");
    for (const status of [200, 204, 500]) check("ResponseReading", port.responseReading(status, status === 200 ? "text/event-stream" : null), "responseReading");
    const reader = new port.StreamReader();
    check("StreamEvent", reader.feed(": hello\n\nevent: datastar-patch-signals\nid: 1\ndata: signals {\"a\":1}\n\nevent: datastar-patch-elements\ndata: elements <p>x</p>\n"), "feed");
    check("StreamEvent", reader.finish(), "finish");
    for (const patch of ['{"a": 1}', '{"a": {"__proto__": 1}}']) check("PatchedStore", port.applySignalPatch("{}", patch, false), "applySignalPatch");
    assert.deepEqual(found, []);
  });
});
