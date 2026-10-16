import { ACCEPT, compactSignals, NON_STREAM_LIMIT, readsAsStream, requestUrl, sendableUrl } from "./requests.ts";
import { decodeDatastar, SseParser, type DatastarFrame, type SseMessage } from "./sse.ts";

/**
 * The network half of the Stream Inspector, free of any editor API so it can be tested against a
 * real server. Opens a Datastar request exactly as the browser client would (signals in the
 * query string for GET and DELETE, in the body otherwise, `Datastar-Request: true`) and reports
 * what comes back. The URL and the signals are the ones the curl export writes.
 */

export interface StreamRequest {
  url: string;
  method: string;
  /** Signals as JSON text, as the user wrote them; empty or `undefined` sends `{}`. */
  signals?: string;
  headers?: Record<string, string>;
}

export interface StreamHandlers {
  onStatus?: (status: "connecting" | "open" | "closed", detail?: { http?: string; contentType?: string }) => void;
  onFrame?: (frame: DatastarFrame) => void;
  onComment?: (text: string) => void;
  onNonSse?: (response: { http: string; contentType: string; body: string; headers: Record<string, string> }) => void;
  onError?: (message: string) => void;
}

export function buildRequest(req: StreamRequest): { url: string; init: RequestInit } | { error: string } {
  // Judged as written, so the message shows the user's URL and not the one with the signals added.
  const written = sendableUrl(req.url);
  if ("error" in written) return written;
  const signalsJson = compactSignals(req.signals ?? "");
  // Judged on the compacted text, which is what goes out: a lone no-break space is not blank to it.
  try {
    JSON.parse(signalsJson);
  } catch (e) {
    return { error: `Signals are not valid JSON: ${(e as Error).message}` };
  }
  const sendable = sendableUrl(requestUrl(req.url, req.method, signalsJson));
  if ("error" in sendable) return sendable;
  const headers: Record<string, string> = { Accept: ACCEPT, "Datastar-Request": "true", ...(req.headers ?? {}) };
  const bodyless = req.method === "GET" || req.method === "DELETE";
  if (!bodyless) headers["Content-Type"] = "application/json";
  return { url: sendable.url, init: { method: req.method, headers, body: bodyless ? undefined : signalsJson } };
}

/** Open the stream and pump events to the handlers until it closes, errors or is aborted. */
export async function openStream(req: StreamRequest, handlers: StreamHandlers, signal: AbortSignal): Promise<void> {
  const built = buildRequest(req);
  if ("error" in built) {
    handlers.onError?.(built.error);
    return;
  }
  handlers.onStatus?.("connecting");
  try {
    const response = await fetch(built.url, { ...built.init, signal });
    const ct = response.headers.get("content-type") ?? "";
    handlers.onStatus?.("open", { http: `${response.status} ${response.statusText}`, contentType: ct });
    if (!readsAsStream(response.status, response.headers.get("content-type"))) {
      const text = await readLimited(response);
      const datastarHeaders: Record<string, string> = {};
      response.headers.forEach((v, k) => {
        if (k.startsWith("datastar-")) datastarHeaders[k] = v;
      });
      handlers.onNonSse?.({ http: `${response.status} ${response.statusText}`, contentType: ct, body: text, headers: datastarHeaders });
      handlers.onStatus?.("closed");
      return;
    }
    if (!response.body) {
      handlers.onStatus?.("closed");
      return;
    }
    const parser = new SseParser();
    const reader = response.body.getReader();
    // The byte order mark is the parser's to drop, before each field as the client drops it.
    const decoder = new TextDecoder("utf-8", { ignoreBOM: true });
    const show = (msg: SseMessage) => {
      if (msg.comments.length && msg.data.length === 0 && msg.event === "message" && msg.id === null && msg.retry === null) {
        handlers.onComment?.(msg.comments.join("\n"));
      } else {
        handlers.onFrame?.(decodeDatastar(msg));
      }
    };
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      for (const msg of parser.feed(decoder.decode(value, { stream: true }))) show(msg);
    }
    for (const msg of parser.feed(decoder.decode())) show(msg);
    const trailing = parser.finish();
    if (trailing) show(trailing);
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
  type Cause = { code?: string; message?: string; address?: string; port?: number; errors?: Cause[] };
  const err = e as Error & { cause?: Cause };
  const cause = err.cause;
  // A refused connection to localhost is an AggregateError over ::1 and 127.0.0.1; the details sit in errors[].
  const first = cause?.errors?.[0] ?? cause;
  const where = first?.address ? `${first.address}:${first.port ?? "?"}` : new URL(err.message.startsWith("http") ? err.message : "http://unknown").host;
  if (cause?.code === "ECONNREFUSED") return `Connection refused at ${where}. Is the server running?`;
  if (cause?.code === "ENOTFOUND") return `Host not found: ${first?.address ?? cause.message ?? err.message}.`;
  if (cause?.code) return `${cause.code}: ${cause.message || err.message}`;
  if (cause?.message) return `${err.message}: ${cause.message}`;
  return err.message;
}

/** The body as text, read up to the limit, so a large response does not land in memory whole. */
async function readLimited(response: Response): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (size <= NON_STREAM_LIMIT) {
    const { value, done } = await reader.read();
    if (done) break;
    chunks.push(value);
    size += value.length;
  }
  const cut = size > NON_STREAM_LIMIT;
  if (cut) await reader.cancel();
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, at);
    at += chunk.length;
  }
  const text = new TextDecoder().decode(cut ? bytes.subarray(0, NON_STREAM_LIMIT) : bytes);
  return cut ? `${text}\n\n(cut at 1 MiB)` : text;
}
