/**
 * Saved requests for the Stream Inspector: the file format, variable substitution, the recent
 * list and the curl export. Editor-independent.
 */

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

/** Parse `{"baseUrl": "..."}`; anything that is not a flat object of strings is ignored. */
export function parseEnvFile(text: string): Record<string, string> {
  try {
    const raw = JSON.parse(text) as Record<string, unknown>;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {};
    return Object.fromEntries(Object.entries(raw).filter(([, v]) => typeof v === "string" || typeof v === "number").map(([k, v]) => [k, String(v)]));
  } catch {
    return {};
  }
}

const VARIABLE = /\{\{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*\}\}/g;

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

export function resolveRequest(r: SavedRequest, vars: Record<string, string>): { request: SavedRequest; missing: string[] } {
  const url = substitute(r.url, vars);
  const signals = substitute(r.signals, vars);
  const headers = substitute(r.headers, vars);
  return {
    request: { ...r, url: url.text, signals: signals.text, headers: headers.text },
    missing: [...new Set([...url.missing, ...signals.missing, ...headers.missing])],
  };
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
  const queryStart = target.indexOf("?") < 0 ? target.length : target.indexOf("?");
  let out = target.slice(0, start);
  for (let i = start; i < target.length; i++) {
    const c = target[i] ?? "";
    const code = c.charCodeAt(0);
    if (code <= 0x20 || code === 0x7f) return { error: "The URL holds a space or a control character, which cannot be sent. Write a space as %20." };
    if (c === "{" || c === "}") return { error: `The URL still holds ${PATH_PARAM_TEXT.exec(target)?.[0] ?? c}. Fill in the path parameter before sending.` };
    // Brackets are an IPv6 host, and java.net.URI takes them in a query but not in a path.
    if (NOT_SENDABLE.includes(c) || ((c === "[" || c === "]") && i >= authorityEnd && i < queryStart)) {
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
  return { url: out };
}

/** UTF-8 escapes in capitals; a surrogate without its partner is encoded as U+FFFD, as TextEncoder does. */
function escape(s: string): string {
  return [...new TextEncoder().encode(s)].map((b) => `%${b.toString(16).toUpperCase().padStart(2, "0")}`).join("");
}

/** Path parameters such as `{id}` or Ktor's optional `{id?}`. */
export function pathParams(path: string): { name: string; optional: boolean }[] {
  return [...path.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)(\?)?\}/g)].map((m) => ({ name: m[1] ?? "", optional: m[2] === "?" }));
}

/** Fill path parameters; an empty value for an optional parameter removes its segment. */
export function fillPath(path: string, values: Record<string, string>): string {
  return path
    .replace(/\/\{([A-Za-z_][A-Za-z0-9_]*)\?\}/g, (_, n: string) => (own(values, n) ? `/${encodeURIComponent(own(values, n))}` : ""))
    .replace(/\{([A-Za-z_][A-Za-z0-9_]*)\??\}/g, (_, n: string) => encodeURIComponent(own(values, n)));
}

/** The value under an own key: a parameter named `constructor` must not read the prototype. */
function own(values: Record<string, string>, name: string): string {
  return Object.hasOwn(values, name) ? (values[name] ?? "") : "";
}
