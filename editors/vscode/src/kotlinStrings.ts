/**
 * Reading Kotlin string literals without a Kotlin parser.
 *
 * A literal is decoded to the text the JVM will see at runtime, with a map from every decoded
 * character back to its source offset, so diagnostics land on the right column. Kotlin string
 * templates are the one thing that cannot be decoded: `$name` and `${expr}` are recorded as
 * interpolations and replaced with a placeholder identifier, because that is what they are
 * to the Datastar expression around them: an opaque value.
 */

export interface Interpolation {
  /** Source offset of the `$`. */
  start: number;
  /** Source offset just past the template. */
  end: number;
  /** `simple` for `$name`, `braced` for `${...}`. */
  kind: "simple" | "braced";
  /** The identifier for `simple`, the inner text for `braced`. */
  text: string;
  /** Offset in the decoded text where the placeholder starts. */
  decodedStart: number;
}

export interface KotlinString {
  /** Source offset of the opening quote. */
  start: number;
  /** Source offset just past the closing quote(s). */
  end: number;
  raw: boolean;
  contentStart: number;
  contentEnd: number;
  /** The runtime text, with `__kt__` in place of each template. */
  text: string;
  /** Decoded index -> source offset. Has `text.length + 1` entries. */
  map: number[];
  interpolations: Interpolation[];
  /** `true` when the literal never closes. */
  unterminated: boolean;
}

export const PLACEHOLDER = "__kt__";

const SIMPLE_ESCAPES: Record<string, string> = { n: "\n", t: "\t", b: "\b", r: "\r", '"': '"', "'": "'", "\\": "\\", $: "$" };

function isIdentStart(c: string): boolean {
  return /[A-Za-z_]/.test(c);
}
function isIdentPart(c: string): boolean {
  return /[A-Za-z0-9_]/.test(c);
}

/** Read the string literal whose opening quote is at `at`. Returns null if there is no quote there. */
export function readKotlinStringAt(src: string, at: number): KotlinString | null {
  if (src[at] !== '"') return null;
  const raw = src.startsWith('"""', at);
  const contentStart = raw ? at + 3 : at + 1;
  let i = contentStart;
  let out = "";
  const map: number[] = [];
  const interpolations: Interpolation[] = [];
  const push = (ch: string, from: number) => {
    for (const c of ch) {
      out += c;
      map.push(from);
    }
  };

  while (i < src.length) {
    const c = src[i] ?? "";
    if (raw) {
      if (src.startsWith('"""', i)) {
        // A raw string ends at the last quote of a run of three or more.
        let j = i;
        while (src[j] === '"') j++;
        const extra = j - i - 3;
        for (let k = 0; k < extra; k++) push('"', i + k);
        map.push(j);
        return { start: at, end: j, raw, contentStart, contentEnd: j - 3, text: out, map, interpolations, unterminated: false };
      }
    } else {
      if (c === '"') {
        map.push(i);
        return { start: at, end: i + 1, raw, contentStart, contentEnd: i, text: out, map, interpolations, unterminated: false };
      }
      if (c === "\n") break;
      if (c === "\\") {
        const n = src[i + 1] ?? "";
        if (n === "u" && /^[0-9A-Fa-f]{4}$/.test(src.slice(i + 2, i + 6))) {
          push(String.fromCharCode(parseInt(src.slice(i + 2, i + 6), 16)), i);
          i += 6;
          continue;
        }
        push(SIMPLE_ESCAPES[n] ?? n, i);
        i += 2;
        continue;
      }
    }
    if (c === "$") {
      const n = src[i + 1] ?? "";
      if (n === "{") {
        // ${'$'} is the idiom for a literal dollar; anything else is an opaque template.
        if (src.startsWith("${'$'}", i)) {
          push("$", i);
          i += 6;
          continue;
        }
        let depth = 1;
        let j = i + 2;
        while (j < src.length && depth > 0) {
          const cj = src[j];
          if (cj === "{") depth++;
          else if (cj === "}") depth--;
          else if (cj === '"') {
            const inner = readKotlinStringAt(src, j);
            if (inner) {
              j = inner.end;
              continue;
            }
          }
          j++;
        }
        interpolations.push({ start: i, end: j, kind: "braced", text: src.slice(i + 2, j - 1), decodedStart: out.length });
        push(PLACEHOLDER, i);
        i = j;
        continue;
      }
      if (isIdentStart(n)) {
        let j = i + 1;
        while (j < src.length && isIdentPart(src[j] ?? "")) j++;
        interpolations.push({ start: i, end: j, kind: "simple", text: src.slice(i + 1, j), decodedStart: out.length });
        push(PLACEHOLDER, i);
        i = j;
        continue;
      }
    }
    push(c, i);
    i++;
  }
  map.push(i);
  return { start: at, end: i, raw, contentStart, contentEnd: i, text: out, map, interpolations, unterminated: true };
}

/** Map a decoded range back to source offsets. */
export function toSource(s: KotlinString, decodedStart: number, decodedEnd: number): { start: number; end: number } {
  const start = s.map[Math.min(decodedStart, s.map.length - 1)] ?? s.contentStart;
  const endIdx = Math.min(Math.max(decodedEnd, decodedStart + 1), s.map.length - 1);
  const end = Math.max(s.map[endIdx] ?? start + 1, start + 1);
  return { start, end };
}
