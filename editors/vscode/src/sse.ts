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

/**
 * Feeds chunks of an SSE stream, hands back every complete message.
 *
 * It reads as Datastar's client does, the same way as the core's SseReader that the IntelliJ
 * inspector uses: a line without a colon is skipped, a byte order mark before a field or a value
 * is dropped, and an id is kept as written. A message carrying only a retry is handed back, since
 * the client acts on it. The inspector judges nothing, so a retry that is not a whole number of
 * milliseconds that fits a 32-bit int is shown as none.
 */
export class SseParser {
  private line = "";
  // A chunk may end between the CR and the LF of one line ending. The CR ends its line at once,
  // so nothing waits on a stream that closes there, and the LF that opens the next chunk is dropped.
  private afterCr = false;
  private event: string | null = null;
  private id: string | null = null;
  private retry: string | null = null;
  private data: string[] = [];
  private comments: string[] = [];

  /** Feed a chunk; returns every complete message it finished. */
  feed(chunk: string): SseMessage[] {
    const out: SseMessage[] = [];
    // Each character is looked at once, and a line is cut from the chunk rather than built a
    // character at a time, so a long line in small pieces costs no more than in one.
    let start = 0;
    for (let i = 0; i < chunk.length; i++) {
      const ch = chunk[i];
      if (this.afterCr) {
        this.afterCr = false;
        if (ch === "\n") {
          start = i + 1;
          continue;
        }
      }
      if (ch === "\r" || ch === "\n") {
        const text = this.line + chunk.slice(start, i);
        this.line = "";
        start = i + 1;
        if (ch === "\r") this.afterCr = true;
        this.endLine(text, out);
      }
    }
    this.line += chunk.slice(start);
    return out;
  }

  /**
   * The stream has ended: the comments read since the last message, if any, as one message.
   * An unfinished message is dropped, as the client never dispatches one.
   */
  finish(): SseMessage | null {
    if (this.line.startsWith(":")) this.comments.push(this.line.slice(1).replace(/^ /, ""));
    this.line = "";
    const unfinished = this.event !== null || this.id !== null || this.retry !== null || this.data.length > 0;
    const trailing = !unfinished && this.comments.length > 0 ? message(null, null, null, [], this.comments) : null;
    this.reset();
    return trailing;
  }

  private endLine(text: string, out: SseMessage[]): void {
    if (text.length === 0) {
      if (this.event !== null || this.id !== null || this.retry !== null || this.data.length > 0 || this.comments.length > 0) {
        out.push(message(this.event, this.id, this.retry, this.data, this.comments));
      }
      this.reset();
      return;
    }
    if (text.startsWith(":")) {
      this.comments.push(text.slice(1).replace(/^ /, ""));
      return;
    }
    // A line without a colon is skipped, and a byte order mark is dropped from the start of the
    // field and of the value, as the client's TextDecoder drops it from each (fetch.ts:403-408).
    const colon = text.indexOf(":");
    if (colon < 0) return;
    const field = text.slice(0, colon).replace(/^\uFEFF/, "");
    const value = text.slice(colon + 1).replace(/^ /, "").replace(/^\uFEFF/, "");
    switch (field) {
      case "event":
        this.event = value;
        break;
      case "id":
        this.id = value;
        break;
      case "retry":
        this.retry = value;
        break;
      case "data":
        this.data.push(value);
        break;
      default:
        break;
    }
  }

  private reset(): void {
    this.event = null;
    this.id = null;
    this.retry = null;
    this.data = [];
    this.comments = [];
  }
}

function message(event: string | null, id: string | null, retry: string | null, data: string[], comments: string[]): SseMessage {
  // Past a 32-bit int the retry is shown as none, as the Kotlin parser's Int shows it.
  const shown = retry !== null && /^\d+$/.test(retry) && Number(retry) <= 0x7fffffff ? Number(retry) : null;
  return { event: event ?? "message", id, retry: shown, data: [...data], comments: [...comments] };
}

export function decodeDatastar(msg: SseMessage): DatastarFrame {
  // A Map, and Object.fromEntries after it: a data line may open with `constructor` or `__proto__`,
  // which a plain object answers from its prototype.
  const groups = new Map<string, string[]>();
  for (const line of msg.data) {
    // As the client splits it: without a space the key is the line minus its last character and
    // the value is the whole line (fetch.ts:115-117).
    const sp = line.indexOf(" ");
    const key = line.slice(0, sp);
    const value = line.slice(sp + 1);
    const group = groups.get(key);
    if (group) group.push(value);
    else groups.set(key, [value]);
  }
  const args: Record<string, string> = Object.fromEntries([...groups].map(([k, v]) => [k, v.join("\n")]));
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
