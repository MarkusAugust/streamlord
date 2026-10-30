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
  return (branches(expr) ?? [expr]).every((v) => LITERAL.test(v.trim()) || STRING_LITERAL.test(v.trim()));
}

const LITERAL = /^(?:-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false|null)$/;
const STRING_LITERAL = /^"(?:[^"\\$]|\\.)*"$/;
const WORD_CHAR_U = /[\p{L}\p{Nd}_]/u;

const wordAt = (e: string, at: number, w: string) => e.startsWith(w, at) && !WORD_CHAR_U.test(e[at - 1] ?? "") && !WORD_CHAR_U.test(e[at + w.length] ?? "");

/** Just past the string or character literal that opens at `at`, templates and all. */
function literalEnd(e: string, at: number): number {
  if (e[at] === "'") {
    const close = e.indexOf("'", at + (e[at + 1] === "\\" ? 3 : 2));
    return close < 0 ? e.length : close + 1;
  }
  const raw = e.startsWith('"""', at);
  let j = at + (raw ? 3 : 1);
  while (j < e.length) {
    if (!raw && e[j] === "\\") j += 2;
    else if (e.startsWith("${", j)) {
      let depth = 1;
      j += 2;
      while (j < e.length && depth > 0) {
        if (e[j] === '"' || e[j] === "'") {
          j = literalEnd(e, j);
          continue;
        }
        if (e[j] === "{") depth++;
        else if (e[j] === "}") depth--;
        j++;
      }
    } else if (raw ? e.startsWith('"""', j) : e[j] === '"') return j + (raw ? 3 : 1);
    else j++;
  }
  return e.length;
}

/** How deep in brackets each character of `e` stands, a bracket counting as outside itself; -1 inside a literal. */
function depths(e: string): number[] {
  const out = new Array<number>(e.length).fill(-1);
  let depth = 0;
  for (let i = 0; i < e.length; ) {
    const c = e[i];
    if (c === '"' || c === "'") {
      i = literalEnd(e, i);
      continue;
    }
    if (c === ")" || c === "]" || c === "}") depth--;
    out[i] = depth;
    if (c === "(" || c === "[" || c === "{") depth++;
    i++;
  }
  return out;
}

/** Just past the bracket that closes the one at `open`, or -1. */
function closing(e: string, d: readonly number[], open: number): number {
  for (let k = open + 1; k < e.length; k++) if (d[k] === d[open] && ")]}".includes(e[k] as string)) return k + 1;
  return -1;
}

/** A branch as the value it gives: a block of one expression is that expression. */
function unblock(v: string): string {
  const t = v.trim();
  return t.startsWith("{") && closing(t, depths(t), 0) === t.length ? t.slice(1, -1).trim() : t;
}

/**
 * The values an `if` or a `when` can result in, as written, a branch that is itself one read in
 * turn: `if (b) "x" else q` gives `"x"` and `q`. Null when the expression is neither, or one this
 * reading cannot take apart.
 */
export function branches(expr: string): string[] | null {
  const e = expr.trim();
  const d = depths(e);
  const expand = (v: string) => {
    const t = unblock(v);
    return branches(t) ?? [t];
  };
  const skipSpace = (from: number) => {
    let k = from;
    while (/\s/.test(e[k] ?? "")) k++;
    return k;
  };
  if (wordAt(e, 0, "if")) {
    const open = skipSpace(2);
    if (e[open] !== "(") return null;
    const close = closing(e, d, open);
    if (close < 0) return null;
    for (let k = close; k < e.length; k++) if (d[k] === 0 && wordAt(e, k, "else")) return [...expand(e.slice(close, k)), ...expand(e.slice(k + 4))];
    return null;
  }
  if (wordAt(e, 0, "when")) {
    let open = skipSpace(4);
    if (e[open] === "(") {
      open = closing(e, d, open);
      if (open < 0) return null;
      open = skipSpace(open);
    }
    if (e[open] !== "{" || closing(e, d, open) !== e.length) return null;
    const values: string[] = [];
    let entry = open + 1;
    let arrow = -1;
    for (let k = open + 1; k < e.length; k++) {
      if (d[k] === 1 && arrow < 0 && e.startsWith("->", k)) {
        arrow = k++;
        continue;
      }
      if (k === e.length - 1 || (d[k] === 1 && (e[k] === ";" || e[k] === "\n"))) {
        if (arrow >= 0) values.push(...expand(e.slice(arrow + 2, k)));
        else if (e.slice(entry, k).trim() !== "") return null;
        arrow = -1;
        entry = k + 1;
      }
    }
    return values.length > 0 ? values : null;
  }
  return null;
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

/** The nearest declaration of `name` before `at` in the one that encloses it: the type it is given, or the literal. */
function declarationOf(src: string, name: string, at: number): { type?: string; literal?: string } | null {
  const scope = src.slice(scopeStart(src, at), at);
  const n = name.replace(/[$]/g, "\\$&");
  const declarations = [
    ...scope.matchAll(new RegExp(`(?:[(,]\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:val|var)\\s+)?|\\b(?:val|var)\\s+)${n}\\s*(?::\\s*([\\p{L}_][\\p{L}\\p{Nd}_.]*\\??)|=\\s*(-?\\d[\\d_]*(?:\\.\\d+)?[LlFfDd]?|true|false)\\b|=|->|,)`, "gu")),
  ];
  const last = declarations.pop();
  return last ? { type: last[1], literal: last[2] } : null;
}

/** What a declaration or a value shows: the facts of HoleFact the text alone can give. */
export type TextKind = "number" | "boolean" | "nullable" | "string" | "markup";

/**
 * The type the enclosing declaration gives `name`: a number or a boolean that cannot be null
 * (`count: Int`, `val n = 3`), one that can (`count: Int?`), a string (`name: String`), which is
 * what may come from outside, or the markup `interpolate` returns (`page: Html`).
 * The nearest declaration before `at` wins, as Kotlin's scoping does; anything else is not known.
 */
export function declaredKind(src: string, name: string, at: number): TextKind | null {
  const d = declarationOf(src, name, at);
  if (!d) return null;
  if (d.literal !== undefined) return d.literal === "true" || d.literal === "false" ? "boolean" : "number";
  if (d.type === undefined) return null;
  const type = d.type.replace(/\?$/, "");
  const scalar = /^(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|UInt|ULong|UShort|UByte)$/.test(type) ? "number" : /^(?:kotlin\.)?Boolean$/.test(type) ? "boolean" : null;
  // Digits, true or false, or null: safe where anything is, and not where only a number or a boolean is.
  if (scalar) return type === d.type ? scalar : "nullable";
  if (/^(?:kotlin\.)?(?:String|CharSequence)$/.test(type)) return "string";
  // What interpolate returns: markup whose values have been through it.
  if (/^(?:[\p{L}_][\p{L}\p{Nd}_]*\.)*Html$/u.test(type)) return "markup";
  return null;
}

/**
 * Whether a value can be null as far as the text shows: `null` itself, a name declared with a
 * `?` type, or a safe call with no `?:` after it. A template writes null as `null`, and
 * `interpolate` writes nothing.
 */
export function mayBeNull(src: string, value: string, at: number): boolean {
  return (branches(value) ?? [value]).some((v) => {
    const t = v.trim();
    if (t === "null") return true;
    if (IDENT.test(t)) return declarationOf(src, t, at)?.type?.endsWith("?") ?? false;
    return t.includes("?.") && !t.includes("?:");
  });
}

/**
 * What the text shows of one value, a branch of an `if` or a `when` or a whole hole: a literal or
 * a constant, the author's own, or what the name's declaration says. Null where it shows nothing.
 */
export function valueKind(src: string, value: string, at: number, constants?: ReadonlySet<string>): TextKind | "constant" | null {
  const t = value.trim();
  if (LITERAL.test(t) || STRING_LITERAL.test(t)) return "constant";
  if (!IDENT.test(t)) return null;
  if (constants?.has(t) && !shadowed(src, t, at)) return "constant";
  return declaredKind(src, t, at);
}

/** Of the kinds a hole's values have, the one safe in the fewest places; one not known stands between a String and markup. */
export function weakest(kinds: readonly (TextKind | "constant" | null)[]): TextKind | "constant" | null {
  const order: (TextKind | "constant" | null)[] = ["string", null, "markup", "nullable", "boolean", "number", "constant"];
  let worst = order.length - 1;
  for (const k of kinds) worst = Math.min(worst, order.indexOf(k));
  return order[worst] ?? null;
}

/** Whether `name` is a parameter or value marked `@Language("HTML")` in the declaration that encloses `at`. */
export function htmlNamed(src: string, name: string, at: number): boolean {
  const scope = src.slice(scopeStart(src, at), at);
  const n = name.replace(/[$]/g, "\\$&");
  return new RegExp(`@Language\\(\\s*"html"\\s*\\)\\s*(?:(?:val|var)\\s+)?${n}\\b`, "iu").test(scope);
}

const MARKUP_CALL = /(?:^|[^\p{L}\p{Nd}_.])(?:interpolate|Trusted)\s*\(/u;
const HTML_TEXT = /^([\p{L}_][\p{L}\p{Nd}_]*)\.text$/u;

/**
 * Whether a hole holds markup the author built: a template with an HTML literal inside it, a
 * call to `interpolate` or `Trusted`, the text of an `Html`, or a name marked `@Language("HTML")`.
 * Escaping it would print the tags.
 */
export function isMarkup(src: string, s: KotlinString, ip: Interpolation): boolean {
  if (ip.kind === "braced" || ip.kind === "operand") {
    const inner = (s.nested ?? []).filter((n) => n.start >= ip.start && n.end <= ip.end);
    if (inner.some((n) => HTML_START.test(n.text))) return true;
    if (MARKUP_CALL.test(ip.text)) return true;
  }
  return (branches(ip.text) ?? [ip.text]).some((v) => {
    const t = v.trim();
    const text = HTML_TEXT.exec(t);
    if (text) return declaredKind(src, text[1] as string, s.start) === "markup";
    return IDENT.test(t) && htmlNamed(src, t, s.start);
  });
}

/**
 * Whether markup in a hole is a value `interpolate` writes as it stands in element text, an
 * `Html` or a `Trusted`, rather than a String it would escape.
 */
export function markupValued(src: string, s: KotlinString, ip: Interpolation): boolean {
  return (branches(ip.text) ?? [ip.text]).every((v) => {
    const t = v.trim();
    if (IDENT.test(t)) return declaredKind(src, t, s.start) === "markup";
    const call = /^(?:interpolate|Trusted)\s*\(/.exec(t);
    return call !== null && closing(t, depths(t), call[0].length - 1) === t.length;
  });
}

/**
 * The fix for a value written into an attribute value without quotes: quotes around the whole
 * value, which the browser reads the same, so a space in the value no longer starts an attribute.
 * Null when the value holds a quote of its own, or spans literals joined with `+`.
 */
export function quoteFix(s: KotlinString, view: string, ip: Interpolation): Fix | null {
  if (s.interpolations.some((o) => o.kind === "operand")) return null;
  const m = /=[ \t\n\f\r]*([^ \t\n\f\r"'=<>`]*)$/.exec(view.slice(0, ip.decodedStart));
  if (!m) return null;
  const from = ip.decodedStart - (m[1] ?? "").length;
  let to = ip.decodedStart;
  while (to < view.length && !" \t\n\f\r>".includes(view[to] as string)) {
    if ("\"'=<`".includes(view[to] as string)) return null;
    to++;
  }
  const quote = s.raw ? '"' : '\\"';
  const start = s.map[from] as number;
  const end = s.map[to] as number;
  return { title: "Quote the value", start, end: start, text: quote, edits: [{ start: end, end, text: quote }] };
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
        // A comment ends the operand; a string inside it is part of it.
        if (depth === 0 && (src.startsWith("//", k) || src.startsWith("/*", k))) break;
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
  // `.text`, the String the literal was: interpolate returns Html, and a fix that changed the type
  // would break whatever took the String.
  const fix: Fix = { title: "Build it with interpolate(...), which escapes the values", start: s.start, end: s.end, text: `interpolate(${[format, ...args].join(", ")}).text` };
  const imported = new RegExp(`^\\s*import\\s+(?:${INTERPOLATE_IMPORT.replace(/\./g, "\\.")}|io\\.github\\.markusaugust\\.streamlord\\.core\\.domain\\.\\*)\\s*$`, "m").test(src);
  if (!imported) {
    const imports = [...src.matchAll(/^import\s+[^\n]*$/gm)].pop();
    const pkg = /^package\s+[^\n]*$/m.exec(src);
    const anchor = imports ? (imports.index ?? 0) + imports[0].length : pkg ? pkg.index + pkg[0].length : -1;
    fix.edits = anchor < 0 ? [{ start: 0, end: 0, text: `import ${INTERPOLATE_IMPORT}\n\n` }] : [{ start: anchor, end: anchor, text: `\n${imports ? "" : "\n"}import ${INTERPOLATE_IMPORT}` }];
  }
  return fix;
}

/** Where each `%s` of `interpolate`'s format starts in `text`, as core counts them: `%%` is one per cent sign and never a hole. */
export function percentHoles(text: string): number[] {
  const found: number[] = [];
  let i = 0;
  while (i < text.length - 1) {
    if (text[i] === "%" && (text[i + 1] === "s" || text[i + 1] === "%")) {
      if (text[i + 1] === "s") found.push(i);
      i += 2;
      continue;
    }
    i++;
  }
  return found;
}

/**
 * The format with each `%s` read as a hole like a Kotlin template, `__kt__`, mapped back to the
 * source, and where each `%s` now starts. `<%s` is then a tag whose name is a hole, not a
 * template engine's `<%`.
 */
export function withPercentHoles(s: KotlinString, holes: readonly number[]): { format: KotlinString; at: number[] } {
  let text = "";
  const map: number[] = [];
  const at: number[] = [];
  const set = new Set(holes);
  for (let i = 0; i < s.text.length; ) {
    if (set.has(i)) {
      at.push(text.length);
      text += PLACEHOLDER;
      for (let c = 0; c < PLACEHOLDER.length; c++) map.push(s.map[i] as number);
      i += 2;
      continue;
    }
    text += s.text[i];
    map.push(s.map[i] as number);
    i++;
  }
  map.push(s.map[s.text.length] as number);
  const shift = (d: number) => d + (PLACEHOLDER.length - 2) * holes.filter((h) => h < d).length;
  return { format: { ...s, text, map, interpolations: s.interpolations.map((ip) => ({ ...ip, decodedStart: shift(ip.decodedStart) })) }, at };
}

/** What a value handed to `interpolate` is, as far as the text shows: what core's `admits` decides by. */
export type ArgKind = "string" | "number" | "boolean" | "null" | "char" | "trusted" | "markup";

/** Whether `text` is one call to `name` whose argument list closes at its end: `Trusted(x)`, not `Trusted(x).html`. */
function wholeCall(text: string, name: string): boolean {
  if (!new RegExp(`^${name}\\s*\\(`).test(text) || !text.endsWith(")")) return false;
  const { mask } = lexKotlin(text);
  let depth = 0;
  for (let i = text.indexOf("("); i < text.length; i++) {
    if (mask[i] === 0) continue;
    if (text[i] === "(") depth++;
    else if (text[i] === ")" && --depth === 0) return i === text.length - 1;
  }
  return false;
}

/**
 * The kind of a value handed to `interpolate`: a literal, `Trusted(...)`, a call to
 * `interpolate` (its Html), or a name the enclosing declaration gives a type. Null when the text
 * does not show it.
 */
export function argKind(src: string, text: string, literal: boolean, at: number): ArgKind | null {
  const t = text.trim();
  if (literal) return "string";
  if (/^-?(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[LlFfDdUu]*$/.test(t)) return "number";
  if (t === "true" || t === "false") return "boolean";
  if (t === "null") return "null";
  if (/^'(?:[^'\\]|\\.[^']*)'$/.test(t)) return "char";
  if (/^if\s*\(/.test(t) && literalOnly(t)) return t.includes('"') ? "string" : null;
  if (wholeCall(t, "Trusted")) return "trusted";
  if (wholeCall(t, "interpolate")) return "markup";
  // The String of the Html: interpolate(...).text.
  const html = /^([\s\S]*?)\s*\.\s*text$/.exec(t)?.[1];
  if (html !== undefined && wholeCall(html, "interpolate")) return "string";
  const declared = IDENT.test(t) ? declaredKind(src, t, at) : null;
  // An Int? may be null, which interpolate admits only where anything is: not known here.
  return declared === "nullable" ? null : declared;
}
