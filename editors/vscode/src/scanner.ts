import { readKotlinStringAt, type KotlinString } from "./kotlinStrings.ts";

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

export function findCallSites(src: string, names: ReadonlySet<string>): CallSite[] {
  const sites: CallSite[] = [];
  IDENT.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = IDENT.exec(src)) !== null) {
    const name = m[1] ?? "";
    if (!names.has(name)) continue;
    const nameStart = m.index;
    if (/\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>.,?* ]*\.)?$/.test(src.slice(Math.max(0, nameStart - 80), nameStart))) continue;
    if (isInsideStringOrComment(src, nameStart)) continue;
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
    const text = src.slice(argStart, end);
    if (text.trim().length === 0 && args.length === 0 && end === argStart) return;
    if (text.trim().length === 0) return;
    args.push(makeArg(src, argStart, end));
  };
  while (i < src.length) {
    const c = src[i] ?? "";
    if (c === '"') {
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
  let start = from;
  while (start < to && /\s/.test(src[start] ?? "")) start++;
  let end = to;
  while (end > start && /\s/.test(src[end - 1] ?? "")) end--;
  let named: string | null = null;
  const nm = /^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)/.exec(src.slice(start, end));
  if (nm) {
    named = nm[1] ?? null;
    start += nm[0].length;
    while (start < end && /\s/.test(src[start] ?? "")) start++;
  }
  let string: KotlinString | null = null;
  if (src[start] === '"') {
    const s = readKotlinStringAt(src, start);
    if (s && s.end === end) string = s;
  }
  return { start, end, named, text: src.slice(start, end), string };
}

/** Cheap check: is this offset inside a string literal or comment? Scans from the start of the line. */
function isInsideStringOrComment(src: string, offset: number): boolean {
  // Block comments and raw strings can span lines; check for an enclosing one first.
  const before = src.slice(0, offset);
  const lastRawOpen = before.lastIndexOf('"""');
  if (lastRawOpen >= 0) {
    const s = readKotlinStringAt(src, lastRawOpen);
    if (s && s.end > offset && !s.unterminated) return true;
  }
  const lastBlockOpen = before.lastIndexOf("/*");
  if (lastBlockOpen >= 0 && before.lastIndexOf("*/") < lastBlockOpen) return true;
  const lineStart = before.lastIndexOf("\n") + 1;
  let inString = false;
  for (let i = lineStart; i < offset; i++) {
    const c = src[i];
    if (!inString && c === "/" && src[i + 1] === "/") return true;
    if (c === '"') inString = !inString;
    if (c === "\\" && inString) i++;
  }
  return inString;
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
