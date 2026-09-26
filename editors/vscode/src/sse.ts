/**
 * A Server-Sent Events parser and a Datastar event decoder, for the Stream Inspector.
 * Editor-independent so it can be tested.
 */

export interface SseMessage {
  event: string;
  id: string | null;
  retry: number | null;
  data: string[];
  comments: string[];
}

export interface DatastarFrame {
  event: string;
  id: string | null;
  retry: number | null;
  /** Data lines grouped by their first word, joined with newlines, as the client does it. */
  args: Record<string, string>;
  raw: string;
  receivedAt: number;
}

export class SseParser {
  private buffer = "";
  private current: SseMessage = empty();

  /** Feed a chunk; returns every complete message it finished. */
  feed(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const out: SseMessage[] = [];
    let idx: number;
    while ((idx = this.buffer.search(/\r\n|\r|\n/)) >= 0) {
      const line = this.buffer.slice(0, idx);
      const sepLen = this.buffer.startsWith("\r\n", idx) ? 2 : 1;
      this.buffer = this.buffer.slice(idx + sepLen);
      if (line.length === 0) {
        if (this.current.data.length > 0 || this.current.event !== "message" || this.current.id !== null || this.current.comments.length > 0) {
          out.push(this.current);
        }
        this.current = empty();
        continue;
      }
      if (line.startsWith(":")) {
        this.current.comments.push(line.slice(1).replace(/^ /, ""));
        continue;
      }
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
      switch (field) {
        case "event":
          this.current.event = value;
          break;
        case "data":
          this.current.data.push(value);
          break;
        case "id":
          this.current.id = value;
          break;
        case "retry":
          if (/^\d+$/.test(value)) this.current.retry = Number(value);
          break;
        default:
          break;
      }
    }
    return out;
  }
}

function empty(): SseMessage {
  return { event: "message", id: null, retry: null, data: [], comments: [] };
}

export function decodeDatastar(msg: SseMessage): DatastarFrame {
  const groups: Record<string, string[]> = {};
  for (const line of msg.data) {
    const sp = line.indexOf(" ");
    const key = sp < 0 ? line : line.slice(0, sp);
    const value = sp < 0 ? "" : line.slice(sp + 1);
    (groups[key] ??= []).push(value);
  }
  const args: Record<string, string> = {};
  for (const [k, v] of Object.entries(groups)) args[k] = v.join("\n");
  const raw = [`event: ${msg.event}`, ...(msg.id !== null ? [`id: ${msg.id}`] : []), ...(msg.retry !== null ? [`retry: ${msg.retry}`] : []), ...msg.data.map((d) => `data: ${d}`)].join("\n");
  return { event: msg.event, id: msg.id, retry: msg.retry, args, raw, receivedAt: Date.now() };
}

/** RFC 7386 JSON merge patch, as the Datastar client applies signal patches. */
export function mergePatch(target: unknown, patch: unknown): unknown {
  if (patch === null || typeof patch !== "object" || Array.isArray(patch)) return patch;
  const result: Record<string, unknown> = target !== null && typeof target === "object" && !Array.isArray(target) ? { ...(target as Record<string, unknown>) } : {};
  for (const [k, v] of Object.entries(patch as Record<string, unknown>)) {
    if (v === null) delete result[k];
    else result[k] = mergePatch(result[k], v);
  }
  return result;
}
