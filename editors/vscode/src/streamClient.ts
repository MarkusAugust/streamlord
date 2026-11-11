import { responseReading, streamRequest, StreamReader, type DatastarFrame, type StreamEvent } from "./analysis.ts";

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
  const built = streamRequest(req.url, req.method, req.signals, req.headers ?? {});
  if (built.error || !built.url || !built.method) return { error: built.error ?? "" };
  return { url: built.url, init: { method: built.method, headers: built.headers, body: built.body ?? undefined } };
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
    const reading = responseReading(response.status, ct);
    if (!reading.stream) {
      const unread = reading.unread;
      if (unread) await response.body?.cancel();
      const text = unread ?? (await readLimited(response, reading.limit, reading.cut));
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
    const stream = new StreamReader();
    const reader = response.body.getReader();
    // The byte order mark is the stream reader's to drop, before each field as the client drops it.
    const decoder = new TextDecoder("utf-8", { ignoreBOM: true });
    const show = (events: StreamEvent[]) => {
      for (const event of events) {
        if (event.frame) handlers.onFrame?.(event.frame);
        else handlers.onComment?.(event.comment ?? "");
      }
    };
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      show(stream.feed(decoder.decode(value, { stream: true })));
    }
    show(stream.feed(decoder.decode()));
    show(stream.finish());
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
async function readLimited(response: Response, limit: number, cutMark: string): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (size <= limit) {
    const { value, done } = await reader.read();
    if (done) break;
    chunks.push(value);
    size += value.length;
  }
  const cut = size > limit;
  if (cut) await reader.cancel();
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, at);
    at += chunk.length;
  }
  const text = new TextDecoder().decode(cut ? bytes.subarray(0, limit) : bytes);
  return cut ? text + cutMark : text;
}
