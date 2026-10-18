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
    // field and of the value, as the client's TextDecoder drops it from each (fetch.ts:407 and 409).
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
  // Joined as the client joins them, which drops empty lines before the first that has text, and
  // split again (fetch.ts:412-416, :114), so a message with no data still has one line.
  const joined = msg.data.reduce((acc, line) => (acc ? `${acc}\n${line}` : line), "");
  for (const line of joined.split("\n")) {
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


/**
 * Raised by `mergePatchAsClient` partway through a patch the client fails on, or one Streamlord
 * refuses. The client has by then applied the keys before the one that failed and keeps them, so
 * `store` is the store as it reads afterwards, and the next patch folds into that. `refused` is
 * true where Streamlord refuses the patch on purpose, whatever the client does with it, and the
 * message then says what the client does; false where the client throws on it too. The same as
 * `SignalPatchException` in streamlord-core.
 */
export class SignalPatchError extends Error {
  constructor(
    message: string,
    readonly store: Record<string, unknown>,
    readonly refused: boolean,
  ) {
    super(message);
    this.name = "SignalPatchError";
  }
}

/**
 * The store as the Datastar 1.0.4 client reads it after a signal patch: `mergePatch` and
 * `mergeInner` in the client's `signals.ts`, which differ from RFC 7386 in four ways. `onlyIfMissing`
 * is honoured leaf by leaf, so a nested object is still walked and only the values already there
 * are kept. An object patched onto an array keeps the array and sets its indices: an index past the
 * end extends it, a key that is not an index is dropped. The client walks whatever the patch is
 * with `for ... in`, so an array at the top level sets the keys `0`, `1`, ..., a string sets one key
 * per UTF-16 unit, and a number, a boolean or `null` patches nothing. And the store never hands back
 * `null`: a `null` inside a stored array, and the hole a deleted index leaves, read as `""` and are
 * not missing to the next `onlyIfMissing`. The same rules as `mergePatchAsClient` in streamlord-core.
 *
 * Keys are walked and kept in JavaScript's order, array indices ascending first. A key `length` on
 * an array resizes it, as the client's store does: the value is turned into a number as `Number()`
 * does it (`[]`, `false` and `""` are 0, `"2"` and `[2]` are 2), shorter drops the tail and longer
 * adds `""`; one that does not come out a whole number of zero or more throws, as the browser's
 * `RangeError` does, also under `onlyIfMissing` when the value is an object. This is the store as it
 * reads once a binding has touched it: the client fills a hole with `""` on the first read, so a
 * hole is missing to `onlyIfMissing` only until then.
 *
 * Objects are made without a prototype, so `constructor` is a signal name and nothing else. A
 * patch that throws, here or in the client, has its earlier keys applied: the error is a
 * `SignalPatchError` that carries the store as the client keeps it. Two kinds of patch are refused
 * on purpose, with `refused` set, whatever the client does with them. More than `holeLimit` indices
 * past the end of an array, which would fill memory with the holes the client never allocates.
 * And a key named `__proto__` anywhere in the patch, at any depth and inside array values too,
 * whatever it holds. Where its value is an object with keys, the client mostly throws at that key,
 * and the bindings on the page stop redrawing. Otherwise it gives the object holding the key a
 * foreign prototype, after which it throws on some later keys, replaces the object on a later
 * patch, or, with a second `__proto__` under the first, writes into `String.prototype`; only a
 * `null` in a patch object changes nothing there, and it is refused as well, so that one rule
 * holds. The store in the error is the patch cut before the key that holds the `__proto__`, at the level where it stands, or before the key
 * whose value is the array it is inside: the store the client keeps where it throws on that key.
 * Where the client does not throw, Streamlord refuses on purpose rather than model a store the
 * client no longer holds as plain JSON. A store passed in with a key `__proto__`, which no fold
 * leaves and the client never holds, is refused the same way once a patch reaches the object that
 * has it.
 *
 * The store passed in is never changed. Each object and array on the patch's path is copied, and
 * the rest of the result is the very same values.
 */
export function mergePatchAsClient(store: Record<string, unknown>, patch: unknown, ifMissing = false, holeLimit = 10_000): Record<string, unknown> {
  // `Object.entries` walks an array's indices and a string's UTF-16 units, as `for ... in` does.
  const root = copyObject(store);
  if (patch === null || (typeof patch !== "object" && typeof patch !== "string")) return root;
  try {
    if (Object.hasOwn(store, PROTO)) throw new Refusal(STORE_PROTO_REFUSED);
    new ClientMerge(ifMissing, holeLimit).fold(Object.entries(patch), root);
  } catch (e) {
    throw new SignalPatchError((e as Error).message, root, e instanceof Refusal);
  }
  return root;
}

type Node = Record<string, unknown> | unknown[];

/** `mergePatch` and `mergeInner` in signals.ts, on copies of the nodes a patch reaches. */
class ClientMerge {
  constructor(
    private readonly ifMissing: boolean,
    private readonly holeLimit: number,
  ) {}

  fold(patch: [string, unknown][], node: Node): void {
    for (const [key, value] of patch) {
      if (key === PROTO) throw new Refusal(PROTO_REFUSED);
      if (value === null) {
        if (!this.ifMissing) this.delete(node, key);
      } else {
        this.mergeInner(value, key, node);
      }
    }
  }

  private mergeInner(patch: unknown, key: string, parent: Node): void {
    if (isPojo(patch)) {
      const current = this.get(parent, key);
      if (isPojo(current) && Object.hasOwn(current, PROTO)) throw new Refusal(STORE_PROTO_REFUSED);
      const node: Node = Array.isArray(current) ? [...current] : isPojo(current) ? copyObject(current) : emptyObject();
      this.set(parent, key, node, node);
      this.fold(Object.entries(patch), node);
    } else {
      if (holdsProto(patch)) throw new Refusal(PROTO_REFUSED);
      if (!(this.ifMissing && this.has(parent, key))) this.set(parent, key, asRead(patch), patch);
    }
  }

  private has(parent: Node, key: string): boolean {
    if (!Array.isArray(parent)) return Object.hasOwn(parent, key);
    const index = arrayIndex(key);
    return key === "length" || (index !== null && index < parent.length);
  }

  private get(parent: Node, key: string): unknown {
    if (!Array.isArray(parent)) return Object.hasOwn(parent, key) ? parent[key] : undefined;
    const index = arrayIndex(key);
    return index === null ? undefined : parent[index];
  }

  /** Sets `value`; `raw` is what the client assigns, which an array's `length` reads as a number. */
  private set(parent: Node, key: string, value: unknown, raw: unknown): void {
    if (!Array.isArray(parent)) {
      parent[key] = value;
      return;
    }
    if (key === "length") {
      const length = jsNumber(raw);
      if (!(Number.isInteger(length) && length >= 0 && length <= 4294967295)) throw new Error(`Invalid array length ${JSON.stringify(raw)} in a signal patch`);
      if (length > parent.length + this.holeLimit) throw new Refusal(`Signal patch length ${length} is more than ${this.holeLimit} past the end of an array of ${parent.length}${HOLES}`);
      const before = parent.length;
      parent.length = length;
      if (length > before) parent.fill("", before);
      return;
    }
    // A key that is not an index is a property JSON.stringify leaves out.
    const index = arrayIndex(key);
    if (index === null) return;
    if (index > parent.length + this.holeLimit) throw new Refusal(`Signal patch index ${index} is more than ${this.holeLimit} past the end of an array of ${parent.length}${HOLES}`);
    while (parent.length < index) parent.push("");
    parent[index] = value;
  }

  // `delete` leaves a hole, read as ""; deleting an array's length is a TypeError in the client's strict-mode code.
  private delete(parent: Node, key: string): void {
    if (!Array.isArray(parent)) {
      delete parent[key];
      return;
    }
    if (key === "length") throw new Error("The client cannot delete the length of an array");
    const index = arrayIndex(key);
    if (index !== null && index < parent.length) parent[index] = "";
  }
}

function isPojo(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function emptyObject(): Record<string, unknown> {
  return Object.create(null) as Record<string, unknown>;
}

function copyObject(source: Record<string, unknown>): Record<string, unknown> {
  const out = emptyObject();
  for (const [k, v] of Object.entries(source)) out[k] = v;
  return out;
}

function arrayIndex(key: string): number | null {
  const index = Number(key);
  return Number.isInteger(index) && index >= 0 && index < 4294967295 && String(index) === key ? index : null;
}

/** `Number(value)`, or `NaN` for an object without a prototype, which has no number to give. */
function jsNumber(value: unknown): number {
  try {
    return Number(value);
  } catch {
    return Number.NaN;
  }
}

/** A patch Streamlord refuses on purpose, which the client may take. */
class Refusal extends Error {}

const PROTO = "__proto__";

/** Why a patch with a key `__proto__` is refused; `PROTO_REFUSED` in streamlord-core's JsonMerge.kt gives the same text. */
export const PROTO_REFUSED =
  "A key named __proto__ whose value is an object with keys mostly makes Datastar's client throw at that key, and the bindings on the page then stop redrawing. Otherwise the client gives the object holding the key a foreign prototype, which then throws on some later keys, replaces the object on a later patch, or with a second __proto__ writes into String.prototype. Streamlord does not model it and refuses the patch";

export const STORE_PROTO_REFUSED = "The store holds a key named __proto__, which Datastar's client never holds; Streamlord does not fold a patch into the object that has it";

const HOLES = ": the client takes it, and Streamlord does not fill memory with the holes";

/** Whether a key `__proto__` stands anywhere in `value`, as `JSON.parse` leaves it, an own key. */
function holdsProto(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(holdsProto);
  if (value === null || typeof value !== "object") return false;
  return Object.hasOwn(value, PROTO) || Object.values(value).some(holdsProto);
}

/** A value as the client's store reads it back: every `null` inside it is `""`. */
function asRead(value: unknown): unknown {
  if (value === null) return "";
  if (Array.isArray(value)) return value.map(asRead);
  if (typeof value === "object") {
    const out = emptyObject();
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) out[k] = asRead(v);
    return out;
  }
  return value;
}
