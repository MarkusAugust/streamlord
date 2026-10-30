/**
 * The pieces of the interpolation judgement that are about Kotlin rather than about Datastar:
 * which values are joined into an HTML string, which of them the analysis can see are safe or
 * are markup themselves, what the text looks like once `trimMargin` has run, and the rewrite to
 * `interpolate`. The twin of Interpolations.kt in streamlord-analysis; where a hole lands is
 * holes.ts, the twin of core.
 */
import { lexKotlin } from "./scanner.ts";
import { PLACEHOLDER, readKotlinStringAt, type Interpolation, type KotlinString } from "./kotlinStrings.ts";
import type { Fix } from "./expression.ts";

const HTML_START = /^\s*<(?:[A-Za-z]|!--|!doctype)/i;
const IDENT = /^[\p{L}_][\p{L}\p{Nd}_]*$/u;

/** How a hole is named in a message: `$name`, `${expr}` shortened, or the operand as written. */
export function holeDisplay(ip: Interpolation, dollars: number): string {
  if (ip.kind === "simple") return "$".repeat(dollars) + ip.text;
  if (ip.kind === "operand") return ip.text;
  const e = ip.text.trim();
  return `${"$".repeat(dollars)}{${e.length <= 30 ? e : `${e.slice(0, 29)}…`}}`;
}

/**
 * The literal's text as `trimMargin` leaves it for the markup checks: each margin marker at the
 * start of a line becomes a space, so nothing moves and the map still holds.
 */
export function markupView(src: string, s: KotlinString): string {
  const m = /^\s*\.\s*trimMargin\s*\(\s*(?:"([^"\\\n]*)")?\s*\)/.exec(src.slice(s.end, s.end + 64));
  if (!m) return s.text;
  const marker = m[1] ?? "|";
  if (marker.length === 0) return s.text;
  let out = "";
  let lineStart = true;
  for (let i = 0; i < s.text.length; i++) {
    const c = s.text[i] ?? "";
    if (lineStart && c !== " " && c !== "\t") {
      if (s.text.startsWith(marker, i)) {
        out += " ".repeat(marker.length);
        i += marker.length - 1;
        lineStart = false;
        continue;
      }
      lineStart = false;
    }
    if (c === "\n") lineStart = true;
    out += c;
  }
  return out;
}

/** Whether a trim call follows the literal, which `interpolate`'s rewrite cannot move without changing the output. */
export function trimmed(src: string, s: KotlinString): boolean {
  return /^\s*\.\s*trim(?:Indent|Margin)\s*\(/.test(src.slice(s.end, s.end + 64));
}

/** The `const val` names a Kotlin file declares, outside strings and comments: what the workspace index gathers. */
export function constantNames(src: string): Set<string> {
  const { mask } = lexKotlin(src);
  const out = new Set<string>();
  for (const m of src.matchAll(/\bconst\s+val\s+([\p{L}_][\p{L}\p{Nd}_]*)/gu)) if (mask[m.index ?? 0] === 1) out.add(m[1] as string);
  return out;
}

/** Every literal the lexer found, and those inside their templates. */
export function withNested(strings: readonly KotlinString[]): KotlinString[] {
  const out: KotlinString[] = [];
  const visit = (s: KotlinString) => {
    out.push(s);
    for (const n of s.nested ?? []) visit(n);
  };
  for (const s of strings) visit(s);
  return out;
}

/**
 * A braced template whose every result is a literal the author wrote: `${if (n == 1) "error"
 * else "errors"}`, a number, `true`. Its value is markup the source spells out.
 */
export function literalOnly(expr: string): boolean {
  const e = expr.trim();
  if (/^(?:-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false|null)$/.test(e)) return true;
  if (/^"(?:[^"\\$]|\\.)*"$/.test(e)) return true;
  const branches = /^if\s*\(.*?\)\s*("(?:[^"\\$]|\\.)*"|-?\d+|true|false)\s*else\s*("(?:[^"\\$]|\\.)*"|-?\d+|true|false)$/s.exec(e);
  return branches !== null;
}

/** The name a hole reads, when it is one plain name: `$x`, `${x}`. */
export function plainName(ip: Interpolation): string | null {
  const t = ip.text.trim();
  return IDENT.test(t) ? t : null;
}

/** Where the declaration that encloses `at` starts: the nearest `fun` before it, or the file. */
function scopeStart(src: string, at: number): number {
  const before = src.slice(0, at);
  const m = [...before.matchAll(/\bfun\b/g)].pop();
  return m?.index ?? 0;
}

/**
 * Whether `name` is declared between the enclosing function and `at`: a parameter, a local, a
 * lambda parameter. Such a name is not the workspace's constant of the same name.
 */
export function shadowed(src: string, name: string, at: number): boolean {
  const scope = src.slice(scopeStart(src, at), at);
  const n = name.replace(/[$]/g, "\\$&");
  return new RegExp(`\\b(?:val|var)\\s+${n}\\b|[(,]\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*${n}\\s*:|\\b${n}\\s*(?:,\\s*[\\p{L}_][\\p{L}\\p{Nd}_]*\\s*)*->|\\bfor\\s*\\(\\s*${n}\\b`, "u").test(scope);
}

/**
 * The type the enclosing declaration gives `name`: a number or a boolean that cannot be null
 * (`count: Int`, `val n = 3`), a string (`name: String`), which is what may come from outside, or
 * the markup `interpolate` returns (`page: Html`).
 * The nearest declaration before `at` wins, as Kotlin's scoping does; anything else is not known.
 */
export function declaredKind(src: string, name: string, at: number): "number" | "boolean" | "string" | "markup" | null {
  const scope = src.slice(scopeStart(src, at), at);
  const n = name.replace(/[$]/g, "\\$&");
  const declarations = [
    ...scope.matchAll(new RegExp(`(?:[(,]\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:val|var)\\s+)?|\\b(?:val|var)\\s+)${n}\\s*(?::\\s*([\\p{L}_][\\p{L}\\p{Nd}_.]*\\??)|=\\s*(-?\\d[\\d_]*(?:\\.\\d+)?[LlFfDd]?|true|false)\\b|=|->|,)`, "gu")),
  ];
  const last = declarations.pop();
  if (!last) return null;
  const type = last[1];
  const literal = last[2];
  if (literal !== undefined) return literal === "true" || literal === "false" ? "boolean" : "number";
  if (type === undefined) return null;
  if (/^(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|UInt|ULong|UShort|UByte)$/.test(type)) return "number";
  if (/^(?:kotlin\.)?Boolean$/.test(type)) return "boolean";
  if (/^(?:kotlin\.)?(?:String|CharSequence)\??$/.test(type)) return "string";
  // What interpolate returns: markup whose values have been through it.
  if (/^(?:[\p{L}_][\p{L}\p{Nd}_]*\.)*Html\??$/u.test(type)) return "markup";
  return null;
}

/** Whether `name` is a parameter or value marked `@Language("HTML")` in the declaration that encloses `at`. */
export function htmlNamed(src: string, name: string, at: number): boolean {
  const scope = src.slice(scopeStart(src, at), at);
  const n = name.replace(/[$]/g, "\\$&");
  return new RegExp(`@Language\\(\\s*"html"\\s*\\)\\s*(?:(?:val|var)\\s+)?${n}\\b`, "iu").test(scope);
}

/**
 * Whether a hole holds markup the author built: a template with an HTML literal inside it, a
 * call to `interpolate`, or a name marked `@Language("HTML")`. Escaping it would print the tags.
 */
export function isMarkup(src: string, s: KotlinString, ip: Interpolation): boolean {
  if (ip.kind === "braced" || ip.kind === "operand") {
    const inner = (s.nested ?? []).filter((n) => n.start >= ip.start && n.end <= ip.end);
    if (inner.some((n) => HTML_START.test(n.text))) return true;
    if (/(?:^|[^\p{L}\p{Nd}_.])interpolate\s*\(/u.test(ip.text)) return true;
  }
  const name = plainName(ip);
  return name !== null && htmlNamed(src, name, s.start);
}

/** What may end an operand of `+` at the top level: an argument or statement boundary, or an operator that binds less tightly. */
const OPERAND_END = /^(?:[,)\]};]|\?:|&&|\|\||==|!=|<=|>=|\.\.)/;

/** The words that end an operand, as words of their own: `photo` is a name, not `pho` and `to`. */
const OPERAND_END_WORDS = ["to", "else"];
const WORD_CHAR = /[\p{L}\p{Nd}_]/u;

function operandEndWordAt(src: string, at: number): boolean {
  return OPERAND_END_WORDS.some((w) => src.startsWith(w, at) && !WORD_CHAR.test(src[at - 1] ?? "") && !WORD_CHAR.test(src[at + w.length] ?? ""));
}

/**
 * The `+` chain that starts at the literal `first`, read as one string: each literal's text, and
 * a hole for each value between them, mapped back to the source. Null when no `+` follows.
 * `mask` is the lexer's: 0 inside literals and comments.
 */
export function chainFrom(src: string, first: KotlinString, mask: Uint8Array): KotlinString | null {
  const parts: (KotlinString | { start: number; end: number })[] = [first];
  let i = first.end;
  const spaces = (from: number, newlines: boolean) => {
    let k = from;
    while (k < src.length && (src[k] === " " || src[k] === "\t" || (newlines && (src[k] === "\n" || src[k] === "\r")))) k++;
    return k;
  };
  for (;;) {
    // A literal followed by a call (`"…".trimIndent()`) is the receiver of that call: the chain ends.
    const plus = spaces(i, false);
    if (src[plus] !== "+" || src[plus + 1] === "+" || src[plus + 1] === "=") break;
    let k = spaces(plus + 1, true);
    if (src[k] === '"' || (src[k] === "$" && /^\$+"/.test(src.slice(k, k + 8)))) {
      const lit = readKotlinStringAt(src, k);
      if (!lit || lit.unterminated) break;
      parts.push(lit);
      i = lit.end;
      continue;
    }
    const start = k;
    let depth = 0;
    while (k < src.length) {
      if (mask[k] === 0) {
        k++;
        continue;
      }
      const c = src[k] ?? "";
      if (c === "(" || c === "[" || c === "{") depth++;
      else if (c === ")" || c === "]" || c === "}") {
        if (depth === 0) break;
        depth--;
      } else if (depth === 0) {
        if (c === "\n" || c === "\r") break;
        if (c === "+" && src[k + 1] !== "+" && src[k + 1] !== "=") break;
        if (OPERAND_END.test(src.slice(k, k + 4)) || operandEndWordAt(src, k)) break;
      }
      k++;
    }
    let end = k;
    while (end > start && /\s/.test(src[end - 1] ?? "")) end--;
    if (end <= start) break;
    parts.push({ start, end });
    i = end;
  }
  if (parts.length < 2) return null;
  // Only a chain whose last part is followed by a + that was refused, or by nothing more, is whole.
  let text = "";
  const map: number[] = [];
  const interpolations: Interpolation[] = [];
  const nested: KotlinString[] = [];
  for (const p of parts) {
    if ("text" in p) {
      for (const ip of p.interpolations) interpolations.push({ ...ip, decodedStart: ip.decodedStart + text.length });
      nested.push(...(p.nested ?? []));
      text += p.text;
      map.push(...p.map.slice(0, p.text.length));
    } else {
      interpolations.push({ start: p.start, end: p.end, kind: "operand", text: src.slice(p.start, p.end), decodedStart: text.length });
      for (let c = 0; c < PLACEHOLDER.length; c++) map.push(p.start);
      text += PLACEHOLDER;
    }
  }
  const last = parts[parts.length - 1] as KotlinString | { start: number; end: number };
  const end = last.end;
  map.push("text" in last ? last.contentEnd : last.end);
  return {
    start: first.start,
    end,
    raw: first.raw,
    dollars: 1,
    contentStart: first.contentStart,
    contentEnd: "text" in last ? last.contentEnd : last.end,
    text,
    map,
    interpolations,
    unterminated: false,
    nested,
  };
}

const INTERPOLATE_IMPORT = "io.github.markusaugust.streamlord.core.domain.interpolate";

/**
 * The literal rewritten as a call to `interpolate`: each template becomes `%s` and its value an
 * argument, each `%` of the markup `%%`. With the import, when the file has none.
 */
export function interpolateFix(src: string, s: KotlinString): Fix {
  let format = "";
  let at = s.start;
  for (const ip of s.interpolations) {
    format += src.slice(at, ip.start).replace(/%/g, "%%");
    format += "%s";
    at = ip.end;
  }
  format += src.slice(at, s.end).replace(/%/g, "%%");
  const args = s.interpolations.map((ip) => (ip.kind === "simple" ? ip.text : ip.text.trim()));
  const fix: Fix = { title: "Build it with interpolate(...), which escapes the values", start: s.start, end: s.end, text: `interpolate(${[format, ...args].join(", ")})` };
  const imported = new RegExp(`^\\s*import\\s+(?:${INTERPOLATE_IMPORT.replace(/\./g, "\\.")}|io\\.github\\.markusaugust\\.streamlord\\.core\\.domain\\.\\*)\\s*$`, "m").test(src);
  if (!imported) {
    const imports = [...src.matchAll(/^import\s+[^\n]*$/gm)].pop();
    const pkg = /^package\s+[^\n]*$/m.exec(src);
    const anchor = imports ? (imports.index ?? 0) + imports[0].length : pkg ? pkg.index + pkg[0].length : -1;
    fix.edits = anchor < 0 ? [{ start: 0, end: 0, text: `import ${INTERPOLATE_IMPORT}\n\n` }] : [{ start: anchor, end: anchor, text: `\n${imports ? "" : "\n"}import ${INTERPOLATE_IMPORT}` }];
  }
  return fix;
}
