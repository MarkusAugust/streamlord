/**
 * Saved requests for the Stream Inspector: the file format, variable substitution, the recent
 * list and the curl export. Editor-independent.
 */

import type { Route } from "./routes.ts";
import type { RunningServer } from "./serverLog.ts";

export interface SavedRequest {
  name: string;
  url: string;
  method: string;
  /** Signals as JSON text (may contain variables). */
  signals: string;
  /** Headers as `Name: value` lines (may contain variables). */
  headers: string;
}

export interface RequestsFile {
  version: 1;
  requests: SavedRequest[];
}

export const REQUESTS_FILE = ".streamlord/inspector.json";
export const ENV_FILE = ".streamlord/env.json";
export const RECENT_LIMIT = 10;
export const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "QUERY"];

export function emptyRequestsFile(): RequestsFile {
  return { version: 1, requests: [] };
}

/** Parse the requests file leniently: bad entries are dropped, never fatal. */
export function parseRequestsFile(text: string): RequestsFile {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return emptyRequestsFile();
  }
  const list = (raw as { requests?: unknown })?.requests;
  if (!Array.isArray(list)) return emptyRequestsFile();
  const requests: SavedRequest[] = [];
  for (const item of list as Record<string, unknown>[]) {
    if (!item || typeof item.name !== "string" || typeof item.url !== "string") continue;
    requests.push({
      name: item.name,
      url: item.url,
      method: METHODS.includes(String(item.method ?? "GET").toUpperCase()) ? String(item.method ?? "GET").toUpperCase() : "GET",
      signals: typeof item.signals === "string" ? item.signals : item.signals && typeof item.signals === "object" ? JSON.stringify(item.signals, null, 2) : "",
      headers: typeof item.headers === "string" ? item.headers : item.headers && typeof item.headers === "object" && !Array.isArray(item.headers) ? Object.entries(item.headers as Record<string, unknown>).map(([k, v]) => `${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`).join("\n") : "",
    });
  }
  return { version: 1, requests };
}

/**
 * Written by hand, not by JSON.stringify: the file is shared with the IntelliJ plugin, and the
 * two must produce the same bytes or each editor rewrites what the other saved.
 */
export function serializeRequestsFile(file: RequestsFile): string {
  const fields = ["name", "url", "method", "signals", "headers"] as const;
  const requests = file.requests.map((r) => `    {\n${fields.map((f) => `      "${f}": ${jsonString(r[f])}`).join(",\n")}\n    }`);
  return `{\n  "version": 1,\n  "requests": [${requests.length ? `\n${requests.join(",\n")}\n  ` : ""}]\n}\n`;
}

const JSON_ESCAPES: Record<string, string> = { '"': '\\"', "\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t", "\b": "\\b", "\f": "\\f", "\u2028": "\\u2028", "\u2029": "\\u2029" };

/** A JSON string as the Kotlin SDK's writer spells it: U+2028 and U+2029 escaped, and the slash of `</`. */
function jsonString(s: string): string {
  const body = s.replace(/["\\\u0000-\u001f\u2028\u2029]/g, (c) => JSON_ESCAPES[c] ?? `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`).replace(/<\//g, "<\\/");
  return `"${body}"`;
}

/**
 * Insert or replace by name (case-insensitive), keeping the list sorted by name.
 *
 * Names are matched and ordered by `nameKey`, never by locale: the IntelliJ plugin applies the
 * same rule, so the shared file keeps one order whichever editor saves it.
 */
export function upsert(file: RequestsFile, request: SavedRequest): RequestsFile {
  const key = nameKey(request.name);
  const others = file.requests.filter((r) => nameKey(r.name) !== key);
  return { version: 1, requests: [...others, request].sort((a, b) => compareText(nameKey(a.name), nameKey(b.name))) };
}

/**
 * A name in lowercase, one character at a time, compared code unit by code unit. Lowercasing the
 * whole string would depend on the runtime: the JVM and V8 disagree on where a sigma is final.
 */
function nameKey(name: string): string {
  return [...name].map((c) => c.toLowerCase()).join("");
}

function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function remove(file: RequestsFile, name: string): RequestsFile {
  const key = nameKey(name);
  return { version: 1, requests: file.requests.filter((r) => nameKey(r.name) !== key) };
}

/** The first entry of the request list, saying what else is in it, so the list reads as the place to find them. */
export function newRequestLabel(saved: number, recent: number): string {
  const counts = [saved > 0 ? `${saved} saved` : "", recent > 0 ? `${recent} recent` : ""].filter((c) => c !== "").join(", ");
  return counts ? `New request… (${counts})` : "New request…";
}

/** The variables with a field of their own, in the order the inspector lists them. Every other name is a param. */
export const ENV_KEYS = ["baseUrl", "signals", "headers"] as const;
export type EnvKey = (typeof ENV_KEYS)[number];

/** The key in `.streamlord/env.json` that holds the values of the parameters a route reads. */
export const PARAMS = "params";

/** `.streamlord/env.json` as read: each key that is set and valid, and what is wrong with the rest. */
export interface Env {
  baseUrl: string | null;
  /** Compact JSON, spelled as in the file. */
  signals: string | null;
  headers: [string, string][] | null;
  /** The values of `{{name}}` in the URL and headers, such as a request parameter of a route. */
  params: [string, string][] | null;
  errors: string[];
}

const INVALID = `${ENV_FILE} is not valid`;

/**
 * Read `.streamlord/env.json`. Only `baseUrl` (text), `signals` (an object), `headers` and
 * `params` (objects of texts) are allowed, and anything else is reported rather than ignored, so
 * a typo does not quietly leave a value unset.
 */
export function parseEnv(text: string): Env {
  const env: Env = { baseUrl: null, signals: null, headers: null, params: null, errors: [] };
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (e) {
    const position = /position (\d+)/.exec((e as Error).message)?.[1];
    env.errors.push(position !== undefined ? `${ENV_FILE} is not valid JSON ${lineAndColumn(text, Number(position))}.` : `${ENV_FILE} is not valid JSON.`);
    return env;
  }
  if (!isObject(raw)) {
    env.errors.push(`${INVALID}: it must be an object with baseUrl, signals, headers or params.`);
    return env;
  }
  for (const [key, value] of Object.entries(raw)) {
    if (key === "baseUrl") {
      if (typeof value !== "string") env.errors.push(`${INVALID}: baseUrl must be text, such as "http://localhost:8080".`);
      else if (!/^https?:\/\//i.test(value)) env.errors.push(`${INVALID}: baseUrl must start with http:// or https://.`);
      else env.baseUrl = value.replace(/\/+$/, "");
    } else if (key === "signals") {
      if (!isObject(value)) env.errors.push(`${INVALID}: signals must be a JSON object, such as {"search": "ash"}.`);
      // From the text, not written anew, so 1.0 stays 1.0 and the IntelliJ plugin arrives at the same signals.
      else env.signals = compactSignals(topLevelValues(text).get("signals") ?? "{}");
    } else if (key === "headers") {
      if (!isObject(value)) {
        env.errors.push(`${INVALID}: headers must be an object of names and values, such as {"Authorization": "Bearer token"}.`);
        continue;
      }
      const notText = Object.entries(value).filter(([, v]) => typeof v !== "string");
      for (const [name] of notText) env.errors.push(`${INVALID}: the value of the header "${name}" must be text.`);
      if (notText.length === 0) env.headers = Object.entries(value as Record<string, string>);
    } else if (key === PARAMS) {
      if (!isObject(value)) {
        env.errors.push(`${INVALID}: params must be an object of names and values, such as {"partsnummer": "123"}.`);
        continue;
      }
      const before = env.errors.length;
      for (const [name, v] of Object.entries(value)) {
        if (isEnvKey(name)) env.errors.push(`${INVALID}: "${name}" cannot be a param; {{${name}}} is a variable of its own.`);
        else if (!PARAM_NAME.test(name)) env.errors.push(`${INVALID}: "${name}" cannot be a param name; {{${name}}} would not be read.`);
        else if (typeof v !== "string") env.errors.push(`${INVALID}: the value of the param "${name}" must be text.`);
      }
      if (env.errors.length === before) env.params = Object.entries(value as Record<string, string>);
    } else {
      env.errors.push(`${INVALID}: "${key}" is not a known key. Use baseUrl, signals, headers or params.`);
    }
  }
  return env;
}

const PARAM_NAME = /^[A-Za-z_][A-Za-z0-9_.-]*$/;

/** Where `position` falls in `text`, as an editor counts it: "at line 3, column 1". */
export function lineAndColumn(text: string, position: number): string {
  const before = text.slice(0, Math.max(0, Math.min(position, text.length)));
  return `at line ${before.split("\n").length}, column ${position - before.lastIndexOf("\n")}`;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

/** The text of each value of the top-level object, by key as written; `text` is known to parse. */
function topLevelValues(text: string): Map<string, string> {
  return new Map([...topLevelRanges(text)].map(([k, [from, to]]) => [k, text.slice(from, to)]));
}

/** Where each value of the top-level object stands in `text`, from and to, by key as written; `text` is known to parse. */
function topLevelRanges(text: string): Map<string, [number, number]> {
  const values = new Map<string, [number, number]>();
  let i = text.indexOf("{") + 1;
  const space = () => {
    while (i < text.length && " \t\n\r".includes(text[i] ?? "")) i++;
  };
  while (i < text.length) {
    space();
    if (text[i] !== '"') break;
    const keyEnd = valueEnd(text, i);
    const key = text.slice(i + 1, keyEnd - 1);
    i = keyEnd;
    space();
    i++;
    space();
    const end = valueEnd(text, i);
    values.set(key, [i, end]);
    i = end;
    space();
    if (text[i] === ",") i++;
  }
  return values;
}

/** The end of the JSON value at `i`. */
function valueEnd(text: string, i: number): number {
  if (text[i] === '"') {
    i++;
    while (i < text.length && text[i] !== '"') i += text[i] === "\\" ? 2 : 1;
    return i + 1;
  }
  if (text[i] === "{" || text[i] === "[") {
    let depth = 0;
    for (; i < text.length; i++) {
      const c = text[i];
      if (c === '"') i = valueEnd(text, i) - 1;
      else if (c === "{" || c === "[") depth++;
      else if ((c === "}" || c === "]") && --depth === 0) return i + 1;
    }
    return text.length;
  }
  while (i < text.length && !",}] \t\n\r".includes(text[i] ?? "")) i++;
  return i;
}

const VARIABLE = /\{\{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*\}\}/g;

/** Where a value came from: the settings, the env file, or the log of a server the editor started. */
export type VariableSource = "default" | "env" | "running";

/** A variable that is set, as text, and where it was set, so the inspector can say both. */
export interface Variable {
  name: string;
  value: string;
  source: VariableSource;
  /** For a value from a running server, the debug session it was started from. */
  origin?: string;
}

/**
 * `baseUrl` from the env file, or else where `running` said it started, or else the default; then
 * `signals`, `headers` and each param when the file sets them. The file comes first because it is
 * what the user wrote down; a server's log only fills in what they did not.
 */
export function mergeVariables(defaultUrl: string, env: Env, running: RunningServer | null = null): Variable[] {
  const baseUrl: Variable =
    env.baseUrl !== null
      ? { name: "baseUrl", value: env.baseUrl, source: "env" }
      : running !== null
        ? { name: "baseUrl", value: running.url, source: "running", origin: running.name }
        : { name: "baseUrl", value: defaultUrl.replace(/\/+$/, ""), source: "default" };
  const out: Variable[] = [baseUrl];
  if (env.signals !== null) out.push({ name: "signals", value: env.signals, source: "env" });
  if (env.headers !== null) out.push({ name: "headers", value: env.headers.map(([k, v]) => `${k}: ${v}`).join("\n"), source: "env" });
  for (const [k, v] of env.params ?? []) out.push({ name: k, value: v, source: "env" });
  return out;
}

/** The values by name, for substitution. */
export function variableValues(vars: Variable[]): Record<string, string> {
  return Object.fromEntries(vars.map((v) => [v.name, v.value]));
}

/** One line per variable; only a value the env file does not set is marked, as the default. */
export function describeVariables(vars: Variable[]): string {
  const from = (v: Variable) => (v.source === "default" ? "   (default)" : v.source === "running" ? `   (from ${v.origin})` : "");
  return vars.map((v) => `${v.name} = ${v.value === "" ? "(none)" : v.value.split("\n").join("; ")}${from(v)}`).join("\n");
}

/** Replace `{{name}}` with values; unknown names are left in place and reported. */
export function substitute(text: string, vars: Record<string, string>): { text: string; missing: string[] } {
  const missing = new Set<string>();
  const out = text.replace(VARIABLE, (whole, name: string) => {
    // An own key only: `{{constructor}}` is not a variable because every object has a constructor.
    if (Object.hasOwn(vars, name)) return vars[name] ?? "";
    missing.add(name);
    return whole;
  });
  return { text: out, missing: [...missing] };
}

export type Field = "url" | "signals" | "headers";

/** The one field each of the three stands in. A param stands in the URL or the headers. */
const HOME: Record<EnvKey, Field> = { baseUrl: "url", signals: "signals", headers: "headers" };
const FIELD_NAME: Record<Field, string> = { url: "URL", signals: "signals", headers: "headers" };

function belongsIn(name: string, field: Field): boolean {
  return isEnvKey(name) ? HOME[name] === field : field !== "signals";
}

function isEnvKey(name: string): name is EnvKey {
  return (ENV_KEYS as readonly string[]).includes(name);
}

/**
 * Fill the variables into a request. Anything that cannot be filled is reported, and `unset`
 * names the keys a request needs that the env file does not set, for the inspector to add.
 */
export function resolveRequest(r: SavedRequest, vars: Variable[]): { request: SavedRequest; errors: string[]; unset: string[] } {
  const values = new Map(vars.map((v) => [v.name, v.value]));
  const errors: string[] = [];
  const unset: string[] = [];
  const report = (message: string) => {
    if (!errors.includes(message)) errors.push(message);
  };
  const fill = (text: string, field: Field) =>
    text.replace(VARIABLE, (whole, name: string) => {
      const param = !isEnvKey(name);
      const meant = ENV_KEYS.find((k) => k.toLowerCase() === name.toLowerCase());
      if (param && meant !== undefined) {
        report(`{{${name}}} is not a variable. Did you mean {{${meant}}}?`);
        return whole;
      }
      if (param && field === "signals") {
        report(`{{${name}}} cannot stand in the signals field. Use {{signals}} there; a param goes in the URL or the headers.`);
        return whole;
      }
      if (!param && !belongsIn(name, field)) {
        report(`{{${name}}} belongs in the ${FIELD_NAME[HOME[name]]} field.`);
        return whole;
      }
      const value = values.get(name);
      if (value === undefined) {
        report(param ? `{{${name}}} is not set. Add it to ${PARAMS} in ${ENV_FILE}.` : `{{${name}}} is not set. Add "${name}" to ${ENV_FILE}.`);
        if (!unset.includes(name)) unset.push(name);
        return whole;
      }
      // An empty param is one the inspector added for the user to fill in.
      if (param && value === "") {
        report(`{{${name}}} is empty. Fill it in under ${PARAMS} in ${ENV_FILE}.`);
        if (!unset.includes(name)) unset.push(name);
        return whole;
      }
      return param && field === "url" ? encodeSegment(value) : value;
    });
  const request = { ...r, url: fill(r.url, "url"), signals: fill(r.signals, "signals"), headers: fill(r.headers, "headers") };
  return { request, errors, unset };
}

/**
 * The text of `.streamlord/env.json` with `keys` added after what is there: `baseUrl` with its
 * current value, `signals` and `headers` as empty objects to fill in, and any other name as an
 * empty entry in `params`. A missing file is written with `baseUrl` first. The text is extended
 * rather than written anew, so the user's own layout and values stay as they were; null when it
 * is not a JSON object and cannot be extended safely.
 */
export function withEnvVariables(text: string | null, keys: string[], baseUrl: string): string | null {
  const distinct = [...new Set(keys)];
  const own = distinct.filter(isEnvKey);
  const params = distinct.filter((k) => !isEnvKey(k));
  const newParams = `{\n${params.map((k) => `    ${jsonString(k)}: ""`).join(",\n")}\n  }`;
  const valueOf = (key: string) => (key === "baseUrl" ? jsonString(baseUrl) : key === PARAMS ? newParams : "{}");
  const wanted: string[] = [...own, ...(params.length > 0 ? [PARAMS] : [])];
  if (text === null) {
    const entries = ["baseUrl", ...wanted.filter((k) => k !== "baseUrl")];
    return `{\n${entries.map((k) => `  ${jsonString(k)}: ${valueOf(k)}`).join(",\n")}\n}\n`;
  }
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isObject(raw)) return null;
  const existing = raw[PARAMS];
  const withParams = isObject(existing) ? addParams(text, existing, params) : text;
  const missing = wanted.filter((k) => !Object.hasOwn(raw as object, k));
  if (missing.length === 0) return withParams;
  const close = withParams.lastIndexOf("}");
  const before = withParams.slice(0, close).replace(/[ \t\n\r]+$/, "");
  const added = missing.map((k) => `  ${jsonString(k)}: ${valueOf(k)}`).join(",\n");
  return `${before}${before.endsWith("{") ? "\n" : ",\n"}${added}\n${withParams.slice(close)}`;
}

/** `text` with each of `names` that `existing`, its `params` object, lacks added as an empty entry at its end. */
function addParams(text: string, existing: Record<string, unknown>, names: string[]): string {
  const missing = names.filter((n) => !Object.hasOwn(existing, n));
  const range = topLevelRanges(text).get(PARAMS);
  if (range === undefined || missing.length === 0) return text;
  const close = range[1] - 1;
  const before = text.slice(0, close).replace(/[ \t\n\r]+$/, "");
  const multiline = text.slice(range[0], close).includes("\n") || Object.keys(existing).length === 0;
  const entries = missing.map((n) => `${jsonString(n)}: ""`);
  const added = multiline
    ? `${before.endsWith("{") ? "\n" : ",\n"}${entries.map((e) => `    ${e}`).join(",\n")}\n  `
    : `${before.endsWith("{") ? "" : ", "}${entries.join(", ")}`;
  return before + added + text.slice(close);
}

/**
 * Where the `{{baseUrl}}` of a URL came from, for a server that could not be reached: a value
 * left at its default is the usual reason, and nothing on screen said so.
 */
export function unreachableHint(url: string, vars: Variable[], running: RunningServer | null = null): string | null {
  const baseUrl = vars.find((v) => v.name === "baseUrl");
  if (!baseUrl || !/\{\{\s*baseUrl\s*\}\}/.test(url)) return null;
  if (baseUrl.source === "default") return `{{baseUrl}} is ${baseUrl.value}, the default. Set baseUrl in ${ENV_FILE} if your server listens elsewhere.`;
  if (baseUrl.source === "running") return `{{baseUrl}} is ${baseUrl.value}, where ${baseUrl.origin} said it started.`;
  const elsewhere = baseUrlSuggestion(vars, running);
  return `{{baseUrl}} is ${baseUrl.value}, from ${ENV_FILE}.${elsewhere !== null ? ` ${running?.name} started on ${elsewhere}.` : ""}`;
}

/**
 * The URL a running server announced when the env file sets another one, for the inspector to
 * offer as the file's new `baseUrl`; null when they agree or nothing is running. The file is never
 * changed without being asked.
 */
export function baseUrlSuggestion(vars: Variable[], running: RunningServer | null): string | null {
  const baseUrl = vars.find((v) => v.name === "baseUrl");
  if (!baseUrl || running === null || baseUrl.source !== "env" || baseUrl.value === running.url) return null;
  return running.url;
}

/**
 * The text of `.streamlord/env.json` with `baseUrl` set to `url`: its value replaced where it
 * stands and everything else as written, or added when the file has none. Null when the text is
 * not a JSON object and cannot be changed safely.
 */
export function withBaseUrl(text: string | null, url: string): string | null {
  let range: [number, number] | undefined;
  if (text !== null) {
    try {
      if (isObject(JSON.parse(text))) range = topLevelRanges(text).get("baseUrl");
    } catch {
      return null;
    }
  }
  if (text === null || range === undefined) return withEnvVariables(text, ["baseUrl"], url);
  return text.slice(0, range[0]) + jsonString(url) + text.slice(range[1]);
}

/**
 * The variables to offer after `{{` at `caret`, those the field takes and the file sets, and
 * the range to replace with `{{name}}`, closing braces already typed included.
 */
export function variableCompletions(text: string, caret: number, field: Field, vars: Variable[]): { from: number; to: number; items: Variable[] } | null {
  const m = /\{\{\s*([A-Za-z]*)$/.exec(text.slice(0, caret));
  if (!m) return null;
  const prefix = (m[1] ?? "").toLowerCase();
  const items = vars.filter((v) => belongsIn(v.name, field) && v.name.toLowerCase().startsWith(prefix));
  if (items.length === 0) return null;
  return { from: m.index, to: text.startsWith("}}", caret) ? caret + 2 : caret, items };
}

/** The request as the Datastar client would send it, one identity for the recent list. */
export function requestKey(r: SavedRequest): string {
  return `${r.method} ${r.url}\n${r.signals.trim()}\n${r.headers.trim()}`;
}

export function pushRecent(recent: SavedRequest[], r: SavedRequest): SavedRequest[] {
  const key = requestKey(r);
  const entry: SavedRequest = { ...r, name: r.name || `${r.method} ${r.url}` };
  return [entry, ...recent.filter((x) => requestKey(x) !== key)].slice(0, RECENT_LIMIT);
}

function shellQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

/**
 * The signals as compact JSON; text that is not JSON is passed through trimmed.
 *
 * Valid JSON is compacted by dropping the whitespace between its tokens, not by writing it out
 * again: JSON.stringify would respell numbers and escapes, and the IntelliJ plugin has to arrive
 * at the same text.
 */
export function compactSignals(signals: string): string {
  const text = signals.replace(/^[ \t\n\r]+|[ \t\n\r]+$/g, "");
  if (text.length === 0) return "{}";
  try {
    JSON.parse(text);
  } catch {
    return text;
  }
  return text.replace(/"(?:[^"\\]|\\.)*"|[ \t\n\r]+/g, (m) => (m.startsWith('"') ? m : ""));
}

/** The URL the Datastar client would open: GET and DELETE carry the signals in the query string. */
export function requestUrl(url: string, method: string, signalsJson: string): string {
  if (method !== "GET" && method !== "DELETE") return url;
  // Taken apart as text: a relative URL, or one that still holds an unresolved variable, is one
  // `new URL` refuses, and the rest of it is passed on exactly as it was written.
  const hash = url.indexOf("#");
  const fragment = hash < 0 ? "" : url.slice(hash);
  const beforeFragment = hash < 0 ? url : url.slice(0, hash);
  const mark = beforeFragment.indexOf("?");
  const kept = (mark < 0 ? "" : beforeFragment.slice(mark + 1)).split("&").filter((p) => p.length > 0 && !p.startsWith("datastar="));
  // URLSearchParams is the form encoding, and unlike encodeURIComponent it never throws.
  const query = [...kept, new URLSearchParams({ datastar: signalsJson }).toString()].join("&");
  return `${mark < 0 ? beforeFragment : beforeFragment.slice(0, mark)}?${query}${fragment}`;
}

// What Kotlin's `trim()` strips, which is not quite what JavaScript's does: the two disagree on
// U+FEFF and on U+001C to U+001F, and the curl line has to come out the same from both editors.
const HEADER_SPACE = "[\\t-\\r \\u001c-\\u001f\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+";
const HEADER_TRIM = new RegExp(`^${HEADER_SPACE}|${HEADER_SPACE}$`, "g");

/** Parse `Name: value` lines into a header map. A name given twice is sent once, with its last value. */
export function parseHeaderLines(text: string): Record<string, string> {
  const out = new Map<string, string>();
  for (const line of text.split("\n")) {
    const idx = line.indexOf(":");
    if (idx > 0) out.set(line.slice(0, idx).replace(HEADER_TRIM, ""), line.slice(idx + 1).replace(HEADER_TRIM, ""));
  }
  // Own properties even for a name such as `__proto__`, which an assignment would take as the prototype.
  return Object.fromEntries(out);
}

/** A curl command equivalent to what the inspector sends. Variables must already be resolved. */
export function toCurl(r: SavedRequest): string {
  const bodyless = r.method === "GET" || r.method === "DELETE";
  const signalsJson = compactSignals(r.signals);
  const parts = ["curl", "-N", "-X", r.method];
  parts.push(shellQuote(requestUrl(r.url, r.method, signalsJson)));
  parts.push("-H", shellQuote("Accept: text/event-stream"), "-H", shellQuote("Datastar-Request: true"));
  for (const [k, v] of Object.entries(parseHeaderLines(r.headers))) parts.push("-H", shellQuote(`${k}: ${v}`));
  if (!bodyless) parts.push("-H", shellQuote("Content-Type: application/json"), "--data", shellQuote(signalsJson));
  return parts.join(" ");
}

const NOT_SENDABLE = `"<>\\^\`|`;
const PATH_PARAM_TEXT = /\{[^{}]*\}/;

/**
 * The URL of the curl line as the inspector sends it, or why it cannot be sent as written. The
 * text goes out unchanged but for two things every client does alike: the fragment is dropped,
 * and a character beyond ASCII is sent as UTF-8 escapes. Anything curl refuses, globs away or the
 * IntelliJ client's java.net.URI rejects is refused here, so both inspectors refuse the same URLs.
 */
export function sendableUrl(url: string): { url: string } | { error: string } {
  const scheme = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//.exec(url)?.[1];
  if (scheme === undefined) return { error: `Not an absolute URL: ${url}. Start it with http:// or https://, or with {{baseUrl}}.` };
  if (scheme.toLowerCase() !== "http" && scheme.toLowerCase() !== "https") return { error: `Only http:// and https:// URLs can be sent: ${url}` };
  const hash = url.indexOf("#");
  const target = hash < 0 ? url : url.slice(0, hash);
  const start = scheme.length + 3;
  const slash = target.slice(start).search(/[/?]/);
  const authorityEnd = slash < 0 ? target.length : start + slash;
  if (authorityEnd === start) return { error: `No host in ${url}.` };
  let out = target.slice(0, start);
  for (let i = start; i < target.length; i++) {
    const c = target[i] ?? "";
    const code = c.charCodeAt(0);
    if (code <= 0x20 || code === 0x7f) return { error: "The URL holds a space or a control character, which cannot be sent. Write a space as %20." };
    if (c === "{" || c === "}") return { error: `The URL still holds ${PATH_PARAM_TEXT.exec(target)?.[0] ?? c}. Fill in the path parameter before sending.` };
    // Brackets belong to an IPv6 host. Elsewhere curl reads them as a range to expand.
    if (NOT_SENDABLE.includes(c) || ((c === "[" || c === "]") && i >= authorityEnd)) {
      return { error: `The URL holds ${c}, which cannot be sent as written. Write it as ${escape(c)}.` };
    }
    if (c === "%" && !/^[0-9A-Fa-f]{2}$/.test(target.slice(i + 1, i + 3))) return { error: "The URL holds a % that starts no escape. Write it as %25." };
    if (code > 0x7f) {
      const cp = target.codePointAt(i) ?? code;
      // A surrogate without its partner goes out as U+FFFD, as TextEncoder sends it.
      const char = cp >= 0xd800 && cp <= 0xdfff ? "�" : String.fromCodePoint(cp);
      if (i < authorityEnd) return { error: `The host holds ${char}, which cannot be sent as written. Write the host in its xn-- form.` };
      out += escape(char);
      i += char.length - 1;
      continue;
    }
    out += c;
  }
  const authority = authorityError(target.slice(start, authorityEnd));
  return authority !== null ? { error: authority } : { url: out };
}

/**
 * What to say under an error response that came without a body, or null when there is a body or
 * no error. The reason is then only in the server's log: a Spring Boot app missing a request
 * parameter answered the inspector with a bare 400, because its error page has no
 * text/event-stream form to write.
 */
export function emptyBodyHint(status: number, body: string): string | null {
  if (status < 400 || body.trim() !== "") return null;
  return `The server sent no body with this ${status}, so the reason is in its log. Spring Boot, for one, writes no error body for a request that accepts only text/event-stream.`;
}

const PORT = /^:[0-9]*$/;
const IPV4 = /^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$/;
const LABEL = /^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$/;

/**
 * Why java.net.URI would read no host in `authority`, or null when it reads one. The IntelliJ
 * client then refuses the request with "unsupported URI"; Node would send some of these, but both
 * inspectors refuse the same URLs. A route whose path lost its slash gave `localhost:8080api`.
 */
function authorityError(authority: string): string | null {
  const hostPort = authority.slice(authority.lastIndexOf("@") + 1);
  const bracketed = hostPort.startsWith("[");
  const colon = hostPort.indexOf(":");
  const hostEnd = bracketed ? hostPort.indexOf("]") + 1 : colon < 0 ? hostPort.length : colon;
  const host = hostPort.slice(0, hostEnd);
  const port = hostPort.slice(hostEnd);
  if (port !== "" && !PORT.test(port)) return `The port in ${hostPort} is not a number. Is a / missing between the port and the path?`;
  if (bracketed || IPV4.test(host)) return null;
  const labels = host.replace(/\.$/, "").split(".");
  if (labels.every((l) => LABEL.test(l)) && (labels.length === 1 || /^[A-Za-z]/.test(labels[labels.length - 1] ?? ""))) return null;
  return `The host ${host} cannot be sent: a host name holds only letters, digits, hyphens and dots, and its last part starts with a letter.`;
}

/** UTF-8 escapes in capitals; a surrogate without its partner is encoded as U+FFFD, as TextEncoder does. */
function escape(s: string): string {
  return [...new TextEncoder().encode(s)].map((b) => `%${b.toString(16).toUpperCase().padStart(2, "0")}`).join("");
}

const OPTIONAL_SEGMENT = /\/\{([A-Za-z_][A-Za-z0-9_]*)\?\}/g;

/**
 * The URL "Open in Stream Inspector" writes for `route`: each path parameter and each required
 * query parameter as a `{{name}}`, filled from `params` in the env file. `{id}`, Spring's
 * `{id:\d+}` and Ktor's `{id}` are required; Ktor's optional `{id?}` segment is left out, and
 * `routeOptional` names it instead.
 */
export function routeUrl(route: Route): string {
  const path = route.path.replace(OPTIONAL_SEGMENT, "").replace(/\{([A-Za-z_][A-Za-z0-9_]*)(?::[^{}]*)?\}/g, (_, n: string) => `{{${n}}}`);
  const query = route.query
    .filter((q) => q.required && PARAM_NAME.test(q.name))
    .map((q) => `${encodeSegment(q.name)}={{${q.name}}}`)
    .join("&");
  return `{{baseUrl}}${path}${query ? `?${query}` : ""}`;
}

/** The headers field for `route`: one `Name: {{Name}}` line per header it requires. */
export function routeHeaders(route: Route): string {
  return route.headers
    .filter((h) => h.required && PARAM_NAME.test(h.name))
    .map((h) => `${h.name}: {{${h.name}}}`)
    .join("\n");
}

/** What `routeUrl` and `routeHeaders` leave out because the route can do without it, by name. */
export function routeOptional(route: Route): string[] {
  return [
    ...[...route.path.matchAll(OPTIONAL_SEGMENT)].map((m) => m[1] ?? ""),
    ...route.query.filter((q) => !q.required).map((q) => q.name),
    ...route.headers.filter((h) => !h.required).map((h) => h.name),
  ];
}

/** `encodeURIComponent`, sending a surrogate without its partner as U+FFFD instead of throwing. */
function encodeSegment(s: string): string {
  return encodeURIComponent(s.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "\uFFFD"));
}
