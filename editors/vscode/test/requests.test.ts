import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { baseUrlSuggestion, describeVariables, emptyBodyHint, emptyRequestsFile, lineAndColumn, mergeVariables, newRequestLabel, parseEnv, parseRequestsFile, pushRecent, readsAsStream, remove, requestHeaders, requestsPathProblem, requestUrl, resolveRequest, routeHeaders, routeOptional, routeUrl, sendableUrl, serializeRequestsFile, substitute, toCurl, unreachableHint, upsert, variableCompletions, withBaseUrl, withEnvVariables, type SavedRequest } from "../src/requests.ts";
import { findRoutes } from "../src/routes.ts";
import { twinCases } from "./twins.ts";

// twins/request-urls.json, which RequestsTest.kt in streamlord-analysis and ClientRequestTest.kt in streamlord-core read too.
type UrlCase = { url: string; method: string; signals: string; expected: string };
describe("the url the client opens", () => {
  it("opens the url the client opens", () => {
    const cases = twinCases<UrlCase>("request-urls.json");
    for (const c of cases) assert.equal(requestUrl(c.url, c.method, c.signals), c.expected, JSON.stringify(c));
    assert.ok(cases.length > 0);
  });

  it("holds the cases to the browser's own URLSearchParams.set", () => {
    const cases = twinCases<UrlCase>("request-urls.json");
    for (const c of cases) {
      if (!["GET", "DELETE"].includes(c.method.toUpperCase())) continue;
      // The client re-serialises the rest of the query and the inspector keeps it as written, so the
      // two are compared as the parameters a server reads.
      const client = new URL(c.url, "http://h/");
      client.searchParams.set("datastar", c.signals);
      const ours = new URL(c.expected, "http://h/");
      assert.deepEqual([...ours.searchParams], [...client.searchParams], JSON.stringify(c));
      assert.equal(ours.hash, client.hash, JSON.stringify(c));
    }
  });
});

/** A case of `fixtures/twins/requests.json`: the function in `call`, its inputs, and what it gives. */
type Case = { name: string; call: string; expected: unknown } & Record<string, any>;

const request = (r: Partial<SavedRequest> = {}): SavedRequest => ({ name: "counter", url: "{{baseUrl}}/api/counter-stream", method: "GET", signals: "", headers: "", ...r });
const shown = (r: SavedRequest) => ({ name: r.name, url: r.url, method: r.method, signals: r.signals, headers: r.headers });
const variables = (c: Case) => mergeVariables(c.defaultUrl, parseEnv(c.env), c.running ?? null);

// The projections of RequestsTest.kt, so that a case means the same in both twins.
const calls: Record<string, (c: Case) => unknown> = {
  parseRequestsFile: (c) => {
    const file = parseRequestsFile(c.text, c.path);
    const again = parseRequestsFile(serializeRequestsFile(file)).requests.map(shown);
    return { requests: file.requests.map(shown), invalid: file.error !== undefined, roundTrips: JSON.stringify(again) === JSON.stringify(file.requests.map(shown)) };
  },
  serializeRequestsFile: (c) => serializeRequestsFile({ version: 1, requests: c.requests.map(request) }),
  upsert: (c) => {
    let file = emptyRequestsFile();
    for (const r of c.upsert) file = upsert(file, request(r));
    if (c.remove !== undefined) file = remove(file, c.remove);
    return file.requests.map((r) => [r.name, r.url]);
  },
  pushRecent: (c) => (c.push as Partial<SavedRequest>[]).reduce((recent: SavedRequest[], r) => pushRecent(recent, request(r)), []).map((r) => [r.name, r.url]),
  substitute: (c) => substitute(c.text, c.vars),
  toCurl: (c) => toCurl(request(c.request)),
  // Exactly one of the two is set, as in the Kotlin twin's data class.
  sendableUrl: (c) => {
    const r = sendableUrl(c.url) as { url?: string; error?: string };
    assert.equal(Object.keys(r).length, 1, c.name);
    return { url: r.url ?? null, error: r.error ?? null };
  },
  sends: (c) => "url" in sendableUrl(c.url),
  readsAsStream: (c) => readsAsStream(c.status, c.contentType),
  emptyBodyHint: (c) => emptyBodyHint(c.status, c.body),
  requestHeaders: (c) => Object.entries(requestHeaders(c.method, Object.fromEntries(c.headers))),
  requestsPathProblem: (c) => requestsPathProblem(c.path) !== null,
  newRequestLabel: (c) => newRequestLabel(c.saved, c.recent),
  lineAndColumn: (c) => lineAndColumn(c.text, c.position),
  parseEnv: (c) => parseEnv(c.text),
  variables: (c) => {
    const vars = variables(c);
    return { variables: vars.map((v) => ({ name: v.name, value: v.value, source: v.source, origin: v.origin ?? null })), described: describeVariables(vars) };
  },
  resolveRequest: (c) => {
    const r = resolveRequest(request(c.request), variables(c));
    return { url: r.request.url, signals: r.request.signals, headers: r.request.headers, errors: r.errors, unset: r.unset };
  },
  unreachableHint: (c) => unreachableHint(c.url, variables(c), c.running ?? null),
  baseUrlSuggestion: (c) => baseUrlSuggestion(variables(c), c.running ?? null),
  variableCompletions: (c) => {
    const m = variableCompletions(c.text, c.caret, c.field, variables(c));
    return m && { from: m.from, to: m.to, names: m.items.map((v) => v.name) };
  },
  withEnvVariables: (c) => withEnvVariables(c.text, c.keys, c.baseUrl),
  withBaseUrl: (c) => withBaseUrl(c.text, c.url),
  routeRequest: (c) => findRoutes(c.source.join("\n")).map((r) => ({ url: routeUrl(r), headers: routeHeaders(r), optional: routeOptional(r) })),
};

describe("inspector requests", () => {
  for (const c of twinCases<Case>("requests.json")) {
    it(c.name, () => {
      const call = calls[c.call];
      assert.ok(call, `${c.name}: no call ${c.call}`);
      assert.deepEqual(call(c), c.expected, c.name);
    });
  }
});

// Where a JSON reader stops is its own: V8 gives no position for an unexpected token, so this
// says less than RequestsTest.kt's.
describe("text that is not JSON", () => {
  it("is named without a position where V8 gives none", () => {
    assert.deepEqual(parseRequestsFile("not json"), { version: 1, requests: [], error: ".streamlord/inspector.json is not valid JSON." });
    assert.deepEqual(parseEnv("not json").errors, [".streamlord/env.json is not valid JSON."]);
    const broken = parseRequestsFile('{"requests":[{"name":"a","url":"/a"},]}', "req.json");
    assert.ok(broken.error?.startsWith("req.json is not valid JSON"), broken.error);
  });
});
