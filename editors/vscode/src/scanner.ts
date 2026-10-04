import { charLiteralEnd, isStringStart, readKotlinStringAt, type KotlinString } from "./kotlinStrings.ts";

/**
 * Finds calls to known DSL functions in Kotlin source and splits their arguments, without a
 * Kotlin parser. The DSL names are distinctive enough that a call is `name(` not preceded by
 * `fun`; arguments are split on top-level commas while skipping strings, nested brackets,
 * lambdas and comments.
 */

export interface Arg {
  /** Source offset where the argument text starts (after `name =` if named). */
  start: number;
  end: number;
  named: string | null;
  text: string;
  /** Set when the argument is exactly one string literal. */
  string: KotlinString | null;
}

export interface CallSite {
  name: string;
  nameStart: number;
  openParen: number;
  closeParen: number;
  args: Arg[];
  /** `true` when a trailing lambda follows the closing parenthesis. */
  trailingLambda: boolean;
}

const IDENT = /\b([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;

/** `.trimIndent()` and `.trimMargin()` change the indentation of a literal, not what it says. */
const TRIM_CALL = /\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)/y;

/** The offset of the first character from `from` that is neither whitespace nor part of a comment. */
function skipTrivia(src: string, from: number, to: number): number {
  let i = from;
  while (i < to) {
    if (/\s/.test(src[i] ?? "")) i++;
    else if (src.startsWith("//", i)) {
      const nl = src.indexOf("\n", i);
      i = nl < 0 || nl > to ? to : nl;
    } else if (src.startsWith("/*", i)) {
      const close = src.indexOf("*/", i + 2);
      i = close < 0 || close + 2 > to ? to : close + 2;
    } else break;
  }
  return i;
}

export function findCallSites(src: string, names: ReadonlySet<string>, mask: Uint8Array = codeMask(src)): CallSite[] {
  const sites: CallSite[] = [];
  IDENT.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = IDENT.exec(src)) !== null) {
    const name = m[1] ?? "";
    if (!names.has(name)) continue;
    const nameStart = m.index;
    if (mask[nameStart] === 0) continue;
    if (/\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>.,?* ]*\.)?$/.test(src.slice(Math.max(0, nameStart - 80), nameStart))) continue;
    const openParen = m.index + m[0].length - 1;
    const parsed = parseArgs(src, openParen);
    if (!parsed) continue;
    sites.push({ name, nameStart, openParen, closeParen: parsed.closeParen, args: parsed.args, trailingLambda: parsed.trailingLambda });
    IDENT.lastIndex = openParen + 1;
  }
  return sites;
}

function parseArgs(src: string, openParen: number): { closeParen: number; args: Arg[]; trailingLambda: boolean } | null {
  const args: Arg[] = [];
  let depth = 0;
  let i = openParen + 1;
  let argStart = i;
  const finish = (end: number) => {
    // A comment after the last comma is not an argument.
    if (skipTrivia(src, argStart, end) === end) return;
    args.push(makeArg(src, argStart, end));
  };
  while (i < src.length) {
    const c = src[i] ?? "";
    if ((c === '"' || c === "$") && isStringStart(src, i)) {
      const s = readKotlinStringAt(src, i);
      i = s ? Math.max(s.end, i + 1) : i + 1;
      continue;
    }
    if (c === "'" ) {
      const close = src.indexOf("'", i + 2 + (src[i + 1] === "\\" ? 1 : 0));
      i = close > 0 ? close + 1 : i + 1;
      continue;
    }
    if (c === "/" && src[i + 1] === "/") {
      const nl = src.indexOf("\n", i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === "/" && src[i + 1] === "*") {
      const close = src.indexOf("*/", i + 2);
      i = close < 0 ? src.length : close + 2;
      continue;
    }
    if (c === "(" || c === "[" || c === "{") depth++;
    else if (c === ")" || c === "]" || c === "}") {
      if (depth === 0) {
        if (c !== ")") return null;
        finish(i);
        const after = src.slice(i + 1, i + 40);
        return { closeParen: i, args, trailingLambda: /^\s*\{/.test(after) };
      }
      depth--;
    } else if (c === "," && depth === 0) {
      finish(i);
      argStart = i + 1;
    }
    i++;
  }
  return null;
}

function makeArg(src: string, from: number, to: number): Arg {
  let end = to;
  while (end > from && /\s/.test(src[end - 1] ?? "")) end--;
  let start = skipTrivia(src, from, end);
  let named: string | null = null;
  const nm = /^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)/.exec(src.slice(start, end));
  if (nm) {
    named = nm[1] ?? null;
    start = skipTrivia(src, start + nm[0].length, end);
  }
  let string: KotlinString | null = null;
  if (isStringStart(src, start)) {
    const s = readKotlinStringAt(src, start);
    if (s) {
      // The argument is still one literal with a trim call or a comment after it.
      let after = skipTrivia(src, s.end, end);
      TRIM_CALL.lastIndex = after;
      const trim = TRIM_CALL.exec(src);
      if (trim) after = skipTrivia(src, after + trim[0].length, end);
      if (after === end) string = s;
    }
  }
  return { start, end, named, text: src.slice(start, end), string };
}

/**
 * A mask of which offsets are code, as opposed to string literals, character literals and
 * comments. Built once per scan; Kotlin block comments nest, raw strings span lines.
 */
export function codeMask(src: string): Uint8Array {
  return lexKotlin(src).mask;
}

/** Every string literal in the source that is not inside a comment, in order. */
export function findKotlinStrings(src: string): KotlinString[] {
  return lexKotlin(src).strings;
}

/**
 * One pass over Kotlin source: the string literals, and a mask of which offsets are code as
 * opposed to string literals, character literals and comments. Kotlin block comments nest,
 * raw strings span lines, and a character literal may hold an escape such as `'A'`.
 */
export function lexKotlin(src: string): { strings: KotlinString[]; mask: Uint8Array } {
  const strings: KotlinString[] = [];
  const mask = new Uint8Array(src.length).fill(1);
  const blank = (from: number, to: number) => mask.fill(0, from, Math.min(to, src.length));
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if ((c === '"' || c === "$") && isStringStart(src, i)) {
      const s = readKotlinStringAt(src, i);
      if (s) strings.push(s);
      const end = s ? Math.max(s.end, i + 1) : i + 1;
      blank(i, end);
      i = end;
      continue;
    }
    if (c === "'") {
      const j = charLiteralEnd(src, i);
      blank(i, j);
      i = j;
      continue;
    }
    if (c === "/" && src[i + 1] === "/") {
      const nl = src.indexOf("\n", i);
      const end = nl < 0 ? src.length : nl;
      blank(i, end);
      i = end;
      continue;
    }
    if (c === "/" && src[i + 1] === "*") {
      let depth = 1;
      let j = i + 2;
      while (j < src.length && depth > 0) {
        if (src.startsWith("/*", j)) {
          depth++;
          j += 2;
        } else if (src.startsWith("*/", j)) {
          depth--;
          j += 2;
        } else j++;
      }
      blank(i, j);
      i = j;
      continue;
    }
    i++;
  }
  return { strings, mask };
}

/**
 * The string literal that contains an offset, if any. Scans from the start of the file rather
 * than backwards from the offset: a quote inside a raw HTML string (`data-text="$count"`) is
 * not the start of a literal, and only a forward scan knows that.
 */
export function stringAt(src: string, offset: number, strings: KotlinString[] = findKotlinStrings(src)): KotlinString | null {
  for (const s of strings) {
    if (s.start >= offset) return null;
    if (offset <= s.end) return s;
  }
  return null;
}

const HTML_START = /^\s*<(?:[A-Za-z]|!--|!doctype)/i;
const HTML_MARKER = /@Language\(\s*"html"\s*\)|\/\/\s*language\s*=\s*html\b/gi;

/**
 * Does a string literal hold HTML? Yes when its text opens with a tag, a comment or a doctype,
 * or when the code just before it carries IntelliJ's injection marker: `@Language("HTML")` on
 * the function or property, or a `// language=HTML` comment. The marker reaches the next
 * string literal only.
 */
export function isHtmlString(src: string, s: KotlinString): boolean {
  if (HTML_START.test(s.text)) return true;
  const before = src.slice(Math.max(0, s.start - 240), s.start);
  const markers = [...before.matchAll(HTML_MARKER)];
  const last = markers[markers.length - 1];
  if (!last) return false;
  const after = before.slice(last.index + last[0].length);
  // `fun f(@Language("HTML") html: String? = null, ...)` annotates a parameter, not the next literal:
  // the marker is followed at once by a parameter declaration. A function's own parameters
  // further on (`fun greeting(name: String): String = """..."""`) do not count.
  if (/^\s*(?:va[lr]\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:\s*String\??\s*(?:=[^,)]*)?[,)]/.test(after)) return false;
  return !after.includes('"');
}

/** Pick the string argument a call-site spec points at. */
export function selectStringArg(site: CallSite, arg: number | "last", named?: string): KotlinString | null {
  if (named) {
    const byName = site.args.find((a) => a.named === named);
    if (byName) return byName.string;
  }
  const positional = site.args.filter((a) => a.named === null);
  if (arg === "last") {
    const strings = positional.filter((a) => a.string !== null);
    return strings.length > 0 ? (strings[strings.length - 1]?.string ?? null) : null;
  }
  return positional[arg]?.string ?? null;
}

export function namedArgText(site: CallSite, name: string): string | null {
  return site.args.find((a) => a.named === name)?.text ?? null;
}
