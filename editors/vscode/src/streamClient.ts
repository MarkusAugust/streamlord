import { decodeDatastar, SseParser, type DatastarFrame } from "./sse.ts";

/**
 * The network half of the Stream Inspector, free of any editor API so it can be tested against a
 * real server. Opens a Datastar request exactly as the browser client would (signals in the
 * query string for GET and DELETE, in the body otherwise, `Datastar-Request: true`) and reports
 * what comes back.
 */

export interface StreamRequest {
  url: string;
  method: string;
  /** Signals as a JSON object; `undefined` sends `{}`. */
  signals?: unknown;
  headers?: Record<string, string>;
}

export interface StreamHandlers {
  onStatus?: (status: "connecting" | "open" | "closed", detail?: { http?: string; contentType?: string }) => void;
  onFrame?: (frame: DatastarFrame) => void;
  onComment?: (text: string) => void;
  onNonSse?: (response: { contentType: string; body: string; headers: Record<string, string> }) => void;
  onError?: (message: string) => void;
}

export function buildRequest(req: StreamRequest): { url: URL; init: RequestInit } {
  const headers: Record<string, string> = { Accept: "text/event-stream", "Datastar-Request": "true", ...(req.headers ?? {}) };
  const url = new URL(req.url);
  const signals = req.signals ?? {};
  const bodyless = req.method === "GET" || req.method === "DELETE";
  let body: string | undefined;
  if (bodyless) {
    url.searchParams.set("datastar", JSON.stringify(signals));
  } else {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(signals);
  }
  return { url, init: { method: req.method, headers, body } };
}

/** Parse `Name: value` lines into a header map. */
export function parseHeaderLines(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split("\n")) {
    const idx = line.indexOf(":");
    if (idx > 0) out[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
  }
  return out;
}

/** Open the stream and pump events to the handlers until it closes, errors or is aborted. */
export async function openStream(req: StreamRequest, handlers: StreamHandlers, signal: AbortSignal): Promise<void> {
  let built: { url: URL; init: RequestInit };
  try {
    built = buildRequest(req);
  } catch {
    handlers.onError?.(`Not a valid URL: ${req.url}`);
    return;
  }
  handlers.onStatus?.("connecting");
  try {
    const response = await fetch(built.url, { ...built.init, signal });
    const ct = response.headers.get("content-type") ?? "";
    handlers.onStatus?.("open", { http: `${response.status} ${response.statusText}`, contentType: ct });
    if (!ct.includes("text/event-stream")) {
      const text = await response.text();
      const datastarHeaders: Record<string, string> = {};
      response.headers.forEach((v, k) => {
        if (k.startsWith("datastar-")) datastarHeaders[k] = v;
      });
      handlers.onNonSse?.({ contentType: ct, body: text, headers: datastarHeaders });
      handlers.onStatus?.("closed");
      return;
    }
    if (!response.body) {
      handlers.onStatus?.("closed");
      return;
    }
    const parser = new SseParser();
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      for (const msg of parser.feed(decoder.decode(value, { stream: true }))) {
        if (msg.comments.length && msg.data.length === 0 && msg.event === "message") {
          handlers.onComment?.(msg.comments.join("\n"));
          continue;
        }
        handlers.onFrame?.(decodeDatastar(msg));
      }
    }
    handlers.onStatus?.("closed");
  } catch (e) {
    if ((e as Error).name === "AbortError") {
      handlers.onStatus?.("closed");
      return;
    }
    handlers.onError?.(describeError(e));
  }
}

/** `fetch failed` says nothing; the cause underneath usually says everything. */
export function describeError(e: unknown): string {
  const err = e as Error & { cause?: { code?: string; message?: string; address?: string; port?: number } };
  const cause = err.cause;
  if (cause?.code === "ECONNREFUSED") return `Connection refused at ${cause.address ?? "host"}:${cause.port ?? "?"}. Is the server running?`;
  if (cause?.code === "ENOTFOUND") return `Host not found: ${cause.address ?? err.message}.`;
  if (cause?.code) return `${cause.code}: ${cause.message ?? err.message}`;
  if (cause?.message) return `${err.message}: ${cause.message}`;
  return err.message;
}
