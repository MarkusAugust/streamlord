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
      headers: typeof item.headers === "string" ? item.headers : item.headers && typeof item.headers === "object" ? Object.entries(item.headers as Record<string, string>).map(([k, v]) => `${k}: ${v}`).join("\n") : "",
    });
  }
  return { version: 1, requests };
}

export function serializeRequestsFile(file: RequestsFile): string {
  return JSON.stringify({ version: 1, requests: file.requests.map(({ name, url, method, signals, headers }) => ({ name, url, method, signals, headers })) }, null, 2) + "\n";
}

/** Insert or replace by name (case-insensitive), keeping the list sorted by name. */
export function upsert(file: RequestsFile, request: SavedRequest): RequestsFile {
  const others = file.requests.filter((r) => r.name.toLowerCase() !== request.name.toLowerCase());
  return { version: 1, requests: [...others, request].sort((a, b) => a.name.localeCompare(b.name)) };
}

export function remove(file: RequestsFile, name: string): RequestsFile {
  return { version: 1, requests: file.requests.filter((r) => r.name.toLowerCase() !== name.toLowerCase()) };
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
    if (name in vars) return vars[name] ?? "";
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

/** A curl command equivalent to what the inspector sends. Variables must already be resolved. */
export function toCurl(r: SavedRequest): string {
  const bodyless = r.method === "GET" || r.method === "DELETE";
  let signalsJson = "{}";
  if (r.signals.trim()) {
    try {
      signalsJson = JSON.stringify(JSON.parse(r.signals));
    } catch {
      signalsJson = r.signals.trim();
    }
  }
  const url = new URL(r.url);
  const parts = ["curl", "-N", "-X", r.method];
  if (bodyless) url.searchParams.set("datastar", signalsJson);
  parts.push(shellQuote(url.toString()));
  parts.push("-H", shellQuote("Accept: text/event-stream"), "-H", shellQuote("Datastar-Request: true"));
  for (const line of r.headers.split("\n")) {
    const idx = line.indexOf(":");
    if (idx > 0) parts.push("-H", shellQuote(`${line.slice(0, idx).trim()}: ${line.slice(idx + 1).trim()}`));
  }
  if (!bodyless) parts.push("-H", shellQuote("Content-Type: application/json"), "--data", shellQuote(signalsJson));
  return parts.join(" ");
}

/** Path parameters such as `{id}` or Ktor's optional `{id?}`. */
export function pathParams(path: string): { name: string; optional: boolean }[] {
  return [...path.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)(\?)?\}/g)].map((m) => ({ name: m[1] ?? "", optional: m[2] === "?" }));
}

/** Fill path parameters; an empty value for an optional parameter removes its segment. */
export function fillPath(path: string, values: Record<string, string>): string {
  return path
    .replace(/\/\{([A-Za-z_][A-Za-z0-9_]*)\?\}/g, (_, n: string) => (values[n] ? `/${encodeURIComponent(values[n] ?? "")}` : ""))
    .replace(/\{([A-Za-z_][A-Za-z0-9_]*)\??\}/g, (_, n: string) => encodeURIComponent(values[n] ?? ""));
}
