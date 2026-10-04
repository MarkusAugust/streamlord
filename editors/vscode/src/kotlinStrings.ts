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
  /** Source offset where the literal starts: the opening quote, or the first `$` of a multi-dollar prefix. */
  start: number;
  /** Source offset just past the closing quote(s). */
  end: number;
  raw: boolean;
  /**
   * How many dollars open a template: 1 in an ordinary literal, 2 or more in a multi-dollar
   * literal (`$$"..."`, Kotlin 2.2+), where shorter runs are plain text and `$count` reaches
   * the browser untouched.
   */
  dollars: number;
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

/** A Kotlin identifier may use any letter: `$år` is a template as much as `$year` is. */
function isIdentStart(c: string): boolean {
  return /[\p{L}_]/u.test(c);
}
function isIdentPart(c: string): boolean {
  return /[\p{L}\p{Nd}_]/u.test(c);
}

/** The offset just past the character literal that opens at `at`: `'a'`, `'\n'`, `'\u0041'`. */
export function charLiteralEnd(src: string, at: number): number {
  let j = at + 1;
  if (src[j] === "\\") j += src[j + 1] === "u" ? 6 : 2;
  else j += 1;
  if (src[j] === "'") j++;
  return j;
}

/** Does a string literal start at `at`: a quote, or a run of dollars followed by a quote? */
export function isStringStart(src: string, at: number): boolean {
  let i = at;
  while (src[i] === "$") i++;
  return src[i] === '"';
}

/**
 * Read the string literal that starts at `at`: at its opening quote, or at the first `$` of a
 * multi-dollar prefix. Returns null if no literal starts there.
 */
export function readKotlinStringAt(src: string, at: number): KotlinString | null {
  let quote = at;
  while (src[quote] === "$") quote++;
  if (src[quote] !== '"') return null;
  const dollars = Math.max(1, quote - at);
  const raw = src.startsWith('"""', quote);
  const contentStart = raw ? quote + 3 : quote + 1;
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
        // The end of the text is where the closing quotes start, as in an ordinary literal.
        map.push(j - 3);
        return { start: at, end: j, raw, dollars, contentStart, contentEnd: j - 3, text: out, map, interpolations, unterminated: false };
      }
    } else {
      if (c === '"') {
        map.push(i);
        return { start: at, end: i + 1, raw, dollars, contentStart, contentEnd: i, text: out, map, interpolations, unterminated: false };
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
      // A template opens with exactly `dollars` dollars; a shorter run is text. In a longer
      // run the first ones are text and the last `dollars` open the template.
      let run = 0;
      while (src[i + run] === "$") run++;
      if (run < dollars) {
        for (let k = 0; k < run; k++) push("$", i + k);
        i += run;
        continue;
      }
      for (let k = 0; k < run - dollars; k++) push("$", i + k);
      i += run - dollars;
      const n = src[i + dollars] ?? "";
      if (n === "{") {
        // ${'$'} is the idiom for a literal dollar; anything else is an opaque template.
        if (dollars === 1 && src.startsWith("${'$'}", i)) {
          push("$", i);
          i += 6;
          continue;
        }
        let depth = 1;
        let j = i + dollars + 1;
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
          } else if (cj === "'") {
            // A brace in a character literal ('}') is not the end of the template.
            j = charLiteralEnd(src, j);
            continue;
          }
          j++;
        }
        interpolations.push({ start: i, end: j, kind: "braced", text: src.slice(i + dollars + 1, j - 1), decodedStart: out.length });
        push(PLACEHOLDER, i);
        i = j;
        continue;
      }
      if (isIdentStart(n)) {
        let j = i + dollars;
        while (j < src.length && isIdentPart(src[j] ?? "")) j++;
        interpolations.push({ start: i, end: j, kind: "simple", text: src.slice(i + dollars, j), decodedStart: out.length });
        push(PLACEHOLDER, i);
        i = j;
        continue;
      }
    }
    push(c, i);
    i++;
  }
  map.push(i);
  return { start: at, end: i, raw, dollars, contentStart, contentEnd: i, text: out, map, interpolations, unterminated: true };
}

/** Map a decoded range back to source offsets. */
export function toSource(s: KotlinString, decodedStart: number, decodedEnd: number): { start: number; end: number } {
  const start = s.map[Math.min(decodedStart, s.map.length - 1)] ?? s.contentStart;
  const endIdx = Math.min(Math.max(decodedEnd, decodedStart + 1), s.map.length - 1);
  const end = Math.max(s.map[endIdx] ?? start + 1, start + 1);
  return { start, end };
}
