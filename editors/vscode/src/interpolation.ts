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
import { catalog } from "./catalog.ts";
import { dslHelpers, EXPRESSION_HELPERS } from "./signalNameHyphens.ts";

const HTML_START = /^\s*<(?:[A-Za-z]|__kt__|!--|!doctype)/i;
const IDENT = /^[\p{L}_][\p{L}\p{Nd}_]*$/u;

/** An expression as a message quotes it: on one line, and cut short past 30 characters. */
export function shortened(text: string): string {
  const e = text.trim().replace(/\s+/g, " ");
  return e.length <= 30 ? e : `${e.slice(0, 29)}…`;
}

/** How a hole is named in a message: `$name`, `${expr}` shortened, or an operand of `+` as the value it is. */
export function holeDisplay(ip: Interpolation, dollars: number): string {
  if (ip.kind === "simple") return "$".repeat(dollars) + ip.text;
  if (ip.kind === "operand") return `the value \`${shortened(ip.text)}\``;
  return `${"$".repeat(dollars)}{${shortened(ip.text)}}`;
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

/** A name the file declares, and the stretch of it where the name means that declaration. */
interface Declaration {
  /** Where the name is written. */
  at: number;
  /** Where the declaration can first be seen: the name for a local, the scope's opening bracket otherwise. */
  start: number;
  /** Just past where it can last be seen. */
  end: number;
  type?: string;
  /** The number or boolean literal it is set to. */
  literal?: string;
  /** The string literal a `const val` is set to, as written, when it holds no template. */
  text?: string;
  constant: boolean;
  html: boolean;
}

/**
 * What the analysis needs of a whole file, read once: where the code is (template code included),
 * the names it declares with their scopes, and whether `interpolate` is imported.
 */
interface FileFacts {
  src: string;
  /** 1 where the character is code: outside literals and comments, or in a template's code. */
  code: Uint8Array;
  names: Map<string, Declaration[]>;
  importEdits?: Fix["edits"] | null;
  helpers?: ReadonlySet<string>;
}

let lastFacts: FileFacts | null = null;

/** The facts of `src`, kept for the last file read: every hole of a file asks for them. */
export function fileFacts(src: string, lex?: { strings: KotlinString[]; mask: Uint8Array }): FileFacts {
  if (lastFacts !== null && lastFacts.src === src) return lastFacts;
  const { strings, mask } = lex ?? lexKotlin(src);
  const code = codeMask(src, strings, mask);
  lastFacts = { src, code, names: declarations(src, code) };
  return lastFacts;
}

/** The lexer's mask with the code of every `${...}` template marked as code, its braces too. */
function codeMask(src: string, strings: readonly KotlinString[], mask: Uint8Array): Uint8Array {
  const code = mask.slice();
  // Outermost first, so a literal inside a template is blanked before its own templates are marked.
  for (const s of withNested(strings)) {
    for (const ip of s.interpolations) {
      if (ip.kind !== "braced" || src[ip.end - 1] !== "}") continue;
      const open = src.indexOf("{", ip.start);
      const inner = lexKotlin(src.slice(open + 1, ip.end - 1)).mask;
      code.set(inner, open + 1);
      code[open] = 1;
      code[ip.end - 1] = 1;
    }
  }
  return code;
}

const NAME = String.raw`[\p{L}_][\p{L}\p{Nd}_]*`;
const TYPE = String.raw`[\p{L}_][\p{L}\p{Nd}_.]*\??`;
const VAL_AFTER = new RegExp(
  String.raw`\s*(?:<[^<>]*>\s*)?((?:${NAME}\s*\.\s*)*)(${NAME})(?:\s*:\s*(${TYPE}))?(?:\s*=\s*(?:(-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false)|("(?:[^"\\$\n]|\\[^$])*"))(?=[ \t]*(?:[\n\r;})\],]|//|$)))?`,
  "uy",
);
const DESTRUCTURED = new RegExp(String.raw`\s*\(([^()]*)\)`, "y");
const PARAMETER = new RegExp(String.raw`^\s*(?:@[\p{L}_][\p{L}\p{Nd}_.]*(?:\s*\([^)]*\))?\s*)*((?:[\p{L}]+\s+)*?)(${NAME})\s*:\s*(${TYPE})?`, "u");
const LAMBDA_PARAMETERS = new RegExp(String.raw`\s*((?:\([^()]*\)|${NAME})(?:\s*:\s*[\p{L}\p{Nd}_.<>?]+)?(?:\s*,\s*(?:\([^()]*\)|${NAME})(?:\s*:\s*[\p{L}\p{Nd}_.<>?]+)?)*)\s*->`, "uy");
const FOR_VARIABLE = new RegExp(String.raw`\s*(\([^()]*\)|${NAME})(?:\s*:\s*(${TYPE}))?\s+in\b`, "uy");
const CONST_BEFORE = /\bconst[ \t]+(?:\p{L}+[ \t]+)*$/u;
const HTML_BEFORE = /@Language\(\s*"html"\s*\)\s*(?:\p{L}+\s+)*$/iu;
const HTML_ANNOTATION = /@Language\(\s*"html"\s*\)/i;
const WORD_START = /[\p{L}_]/u;
const WORD_PART = /[\p{L}\p{Nd}_]/u;

/** Whether `c` can start a name, or with `rest` go on with one: ASCII at once, the rest of Unicode by its class. */
function wordChar(c: string | undefined, rest: boolean): boolean {
  if (c === undefined) return false;
  const k = c.charCodeAt(0);
  if ((k >= 97 && k <= 122) || (k >= 65 && k <= 90) || k === 95) return true;
  if (k >= 48 && k <= 57) return rest;
  return k > 127 && (rest ? WORD_PART : WORD_START).test(c);
}
/** The words that start a declaration of their own, ending a function's expression body. */
const DECLARATION_WORDS: ReadonlySet<string> = new Set(["fun", "val", "var", "class", "interface", "object", "init", "constructor", "typealias"]);

/** Names whose declarations wait for their scope's end: a function's parameters until its body is found. */
interface Pending {
  decls: Declaration[];
  kind: "fun" | "for";
  /** `after`: the parameters closed; `expr`: an expression body; `stmt`: a loop body without braces. */
  state: "after" | "expr" | "stmt";
  /** Whether an expression body has begun: `=` then a line break goes on with the next line. */
  started?: boolean;
}

/** Whether the line break at `at` ends an expression: nothing before it or after it carries the expression on. */
function lineEnds(src: string, code: Uint8Array, at: number): boolean {
  let k = at - 1;
  while (k >= 0 && (code[k] !== 1 || /\s/.test(src[k] ?? ""))) k--;
  if (/[-=+*/%&|?:.,(<>!]/.test(src[k] ?? "")) return false;
  k = at + 1;
  while (k < src.length && (code[k] !== 1 || /\s/.test(src[k] ?? ""))) k++;
  return !/^(?:[-.?+*/%&|:]|else\b)/.test(src.slice(k, k + 5));
}

interface Frame {
  open: number;
  kind: "top" | "block" | "class" | "lambda" | "when" | "params" | "ctor" | "for" | "paren";
  /** Declarations whose scope ends where this frame closes. */
  ends: Declaration[];
  commas: number[];
  pending: Pending | null;
  fun: boolean;
  cls: boolean;
  when: boolean;
  loop: boolean;
  /** The parameters of a class's constructor, waiting for its body. */
  ctor: Declaration[] | null;
}

/**
 * Every name the file declares, read from the code alone: a `val` or `var` (destructured too), a
 * parameter of a function, a constructor or a lambda, and a loop variable, each with the stretch
 * of the file where it is the one the name means. A named argument, `list(page = 1)`, declares
 * nothing. Sorted by where each scope starts.
 */
function declarations(src: string, code: Uint8Array): Map<string, Declaration[]> {
  const names = new Map<string, Declaration[]>();
  const add = (name: string, d: Declaration) => {
    if (name === "_") return;
    const list = names.get(name);
    if (list) list.push(d);
    else names.set(name, [d]);
  };
  const frame = (open: number, kind: Frame["kind"]): Frame => ({ open, kind, ends: [], commas: [], pending: null, fun: false, cls: false, when: false, loop: false, ctor: null });
  const stack: Frame[] = [frame(-1, "top")];
  const top = () => stack[stack.length - 1] as Frame;
  const settle = (p: Pending, end: number) => {
    for (const d of p.decls) d.end = end;
  };
  const endPending = (f: Frame, at: number) => {
    if (f.pending) settle(f.pending, at);
    f.pending = null;
    if (f.ctor) for (const d of f.ctor) d.end = at;
    f.ctor = null;
  };
  /** Each parameter between the brackets of a function or a constructor. */
  const parameters = (f: Frame, close: number): { name: string; d: Declaration }[] => {
    const out: { name: string; d: Declaration }[] = [];
    const cuts = [f.open, ...f.commas, close];
    for (let k = 0; k + 1 < cuts.length; k++) {
      const text = src.slice((cuts[k] as number) + 1, cuts[k + 1]);
      const m = PARAMETER.exec(text);
      if (!m) continue;
      const vararg = /\bvararg\b/.test(m[1] ?? "");
      out.push({ name: m[2] as string, d: { at: (cuts[k] as number) + 1 + (m.index ?? 0), start: f.open, end: src.length, type: vararg ? undefined : m[3], constant: false, html: HTML_ANNOTATION.test(text) } });
    }
    return out;
  };
  const names_ = (list: string) => [...list.matchAll(new RegExp(String.raw`(${NAME})(?:\s*:\s*(${TYPE}))?`, "gu"))];
  for (let i = 0; i < src.length; i++) {
    if (code[i] !== 1) continue;
    const c = src[i] as string;
    const f = top();
    if (wordChar(c, false) && !wordChar(src[i - 1], true)) {
      let end = i + 1;
      while (wordChar(src[end], true)) end++;
      const word = src.slice(i, end);
      // `Row::class` names a class and declares none.
      const reference = src.startsWith("::", i - 2);
      const ctorOfClass = word === "constructor" && f.cls;
      if (DECLARATION_WORDS.has(word) && !reference && !ctorOfClass) {
        // A new declaration ends a function's expression body, a loop body without braces, or a class without one.
        endPending(f, i);
        f.cls = false;
      }
      if (reference) {
        // A class literal.
      } else if (word === "fun" || (word === "constructor" && !ctorOfClass)) f.fun = true;
      else if (word === "class" || word === "interface" || word === "object") f.cls = true;
      else if (word === "when") f.when = true;
      else if (word === "for") f.loop = true;
      else if ((word === "val" || word === "var") && f.kind !== "params" && f.kind !== "ctor") {
        const constant = CONST_BEFORE.test(src.slice(Math.max(0, i - 80), i));
        const html = HTML_BEFORE.test(src.slice(Math.max(0, i - 160), i));
        const local = f.kind !== "top" && f.kind !== "class";
        const scope = (at: number): Declaration => ({ at, start: local ? at : f.kind === "top" ? 0 : f.open, end: src.length, constant, html });
        // `when (val x = ...)`: the scope is the one the brackets stand in.
        const owner = f.kind === "paren" || f.kind === "for" ? ((stack[stack.length - 2] as Frame | undefined) ?? f) : f;
        DESTRUCTURED.lastIndex = end;
        const destructured = DESTRUCTURED.exec(src);
        if (destructured) {
          for (const n of names_(destructured[1] as string)) {
            const d = scope(end + (destructured[0].indexOf("(") + 1) + (n.index ?? 0));
            add(n[1] as string, d);
            if (owner.kind !== "top") owner.ends.push(d);
          }
        } else {
          VAL_AFTER.lastIndex = end;
          const m = VAL_AFTER.exec(src);
          // `val String.shout`: an extension, read on a receiver and never by its name alone.
          if (m && !m[1]) {
            const at = end + m[0].indexOf(m[2] as string, (m[0].length - m[0].trimStart().length));
            const d = { ...scope(at), type: m[3], literal: m[4], text: constant ? m[5] : undefined };
            add(m[2] as string, d);
            if (owner.kind !== "top") owner.ends.push(d);
          }
        }
      }
      i = end - 1;
      continue;
    }
    if (c === "(" || c === "[" || c === "{") {
      let kind: Frame["kind"] = c === "{" ? "block" : "paren";
      const g = frame(i, kind);
      if (c === "(" && f.fun) {
        kind = "params";
        f.fun = false;
      } else if (c === "(" && f.cls && !f.ctor) {
        kind = "ctor";
      } else if (c === "(" && f.loop) {
        kind = "for";
        f.loop = false;
        FOR_VARIABLE.lastIndex = i + 1;
        const m = FOR_VARIABLE.exec(src);
        if (m) {
          const decls: Declaration[] = [];
          const list = (m[1] as string).startsWith("(") ? names_((m[1] as string).slice(1, -1)).map((n) => [n[1] as string, undefined] as const) : [[m[1] as string, m[2]] as const];
          for (const [name, type] of list) {
            const d: Declaration = { at: i + 1, start: i, end: src.length, type, constant: false, html: false };
            add(name, d);
            decls.push(d);
          }
          g.pending = { decls, kind: "for", state: "after" };
        }
      } else if (c === "{") {
        f.fun = false;
        f.loop = false;
        if (f.pending && f.pending.state === "after") {
          // The body of the function or the loop whose parameters just closed.
          g.ends.push(...f.pending.decls);
          f.pending = null;
        } else if (f.cls) {
          kind = "class";
          f.cls = false;
          if (f.ctor) g.ends.push(...f.ctor);
          f.ctor = null;
        } else if (f.when) {
          kind = "when";
          f.when = false;
        } else {
          LAMBDA_PARAMETERS.lastIndex = i + 1;
          const m = LAMBDA_PARAMETERS.exec(src);
          if (m && !code.subarray(i + 1, i + 1 + m[0].length).includes(0)) {
            kind = "lambda";
            for (const part of splitTopLevel(m[1] as string)) {
              const p = part.trim();
              const list = p.startsWith("(") ? names_(p.slice(1, -1)).map((n) => [n[1] as string, undefined] as const) : [[/^\S+?(?=\s*(?::|$))/u.exec(p)?.[0] ?? p, /:\s*(.+)$/.exec(p)?.[1]?.trim()] as const];
              for (const [name, type] of list) {
                const d: Declaration = { at: i + 1, start: i, end: src.length, type: type && new RegExp(`^${TYPE}$`, "u").test(type) ? type : undefined, constant: false, html: false };
                add(name, d);
                g.ends.push(d);
              }
            }
          }
        }
      }
      g.kind = kind;
      stack.push(g);
      continue;
    }
    if (c === ")" || c === "]" || c === "}") {
      if (stack.length === 1) continue;
      const g = stack.pop() as Frame;
      const outer = top();
      const handOver = (p: Pending) => {
        if (outer.pending) settle(outer.pending, i + 1);
        outer.pending = p;
      };
      if (g.kind === "for" && g.pending) {
        handOver(g.pending);
        g.pending = null;
      }
      endPending(g, i + 1);
      for (const d of g.ends) d.end = i + 1;
      if (g.kind === "params") handOver({ decls: parameters(g, i).map(({ name, d }) => (add(name, d), d)), kind: "fun", state: "after" });
      else if (g.kind === "ctor") outer.ctor = parameters(g, i).map(({ name, d }) => (add(name, d), d));
      continue;
    }
    if (c === ",") {
      f.commas.push(i);
      continue;
    }
    const p = f.pending;
    if (p === null) continue;
    if (p.state === "after") {
      if (c === "=" && src[i + 1] !== "=" && p.kind === "fun") p.state = "expr";
      else if (p.kind === "for" && !/\s/.test(c)) p.state = "stmt";
    } else if (p.state === "stmt" && (c === "\n" || c === ";")) {
      endPending(f, i);
    } else if (p.state === "expr") {
      if (c === ";" || (c === "\n" && p.started && lineEnds(src, code, i))) endPending(f, i);
      else if (!/\s/.test(c)) p.started = true;
    }
  }
  for (const f of stack) endPending(f, src.length);
  for (const list of names.values()) list.sort((a, b) => a.start - b.start || a.at - b.at);
  return names;
}

/** `text` cut at each comma outside brackets. */
function splitTopLevel(text: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let from = 0;
  for (let k = 0; k < text.length; k++) {
    const c = text[k];
    if (c === "(" || c === "<") depth++;
    else if (c === ")" || c === ">") depth--;
    else if (c === "," && depth === 0) {
      out.push(text.slice(from, k));
      from = k + 1;
    }
  }
  out.push(text.slice(from));
  return out;
}

/**
 * The declaration `name` means at `at`: of those whose scope holds `at`, the innermost, and of
 * two in one scope the later. Null when the file declares no such name there.
 */
function declarationOf(src: string, name: string, at: number): Declaration | null {
  const list = fileFacts(src).names.get(name);
  if (!list) return null;
  let lo = 0;
  let hi = list.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if ((list[mid] as Declaration).start <= at) lo = mid + 1;
    else hi = mid;
  }
  for (let k = lo - 1; k >= 0; k--) {
    const d = list[k] as Declaration;
    if (d.end > at) return d;
  }
  return null;
}

/**
 * Whether `name` is a constant the author wrote, as far as this file tells: one of the
 * workspace's `const val` names that the file declares as nothing else, or a `const val` of its own.
 */
export function constantAt(src: string, name: string, at: number, constants?: ReadonlySet<string>): boolean {
  const d = declarationOf(src, name, at);
  if (d) return d.constant;
  if (!constants?.has(name)) return false;
  return (fileFacts(src).names.get(name) ?? []).every((x) => x.constant);
}

/** What a declaration or a value shows: the facts of HoleFact the text alone can give. */
export type TextKind = "number" | "boolean" | "nullable" | "string" | "markup";

/**
 * The type the declaration `name` means at `at` gives it: a number or a boolean that cannot be
 * null (`count: Int`, `val n = 3`), one that can (`count: Int?`), a string (`name: String`),
 * which is what may come from outside, or the markup `interpolate` returns (`page: Html`).
 * Anything else is not known. `nonNull` reads the value as the left side of `?:` gives it.
 */
export function declaredKind(src: string, name: string, at: number, nonNull = false): TextKind | null {
  const d = declarationOf(src, name, at);
  if (!d) return null;
  if (d.literal !== undefined) return d.literal === "true" || d.literal === "false" ? "boolean" : "number";
  if (d.type === undefined) return null;
  const type = d.type.replace(/\?$/, "");
  const scalar = /^(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|UInt|ULong|UShort|UByte)$/.test(type) ? "number" : /^(?:kotlin\.)?Boolean$/.test(type) ? "boolean" : null;
  // Digits, true or false, or null: safe where anything is, and not where only a number or a boolean is.
  if (scalar) return type === d.type || nonNull ? scalar : "nullable";
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

/** One value a hole can result in, and whether it stands left of `?:`, which never gives null. */
export interface HoleValue {
  text: string;
  nonNull: boolean;
}

/** The values a hole can result in: each branch of an `if` or a `when`, each side of `?:`. */
export function holeValues(expr: string): HoleValue[] {
  const t = unparen(expr);
  const b = branches(t);
  if (b) return b.flatMap((v) => holeValues(v));
  const sides = splitTop(t, "?:");
  if (sides.length > 1) return sides.flatMap((v, k) => holeValues(v).map((h) => ({ text: h.text, nonNull: h.nonNull || k < sides.length - 1 })));
  return [{ text: t, nonNull: false }];
}

/** The expression without the brackets around all of it. */
function unparen(expr: string): string {
  let t = expr.trim();
  while (t.startsWith("(") && closing(t, depths(t), 0) === t.length) t = t.slice(1, -1).trim();
  return t;
}

/** `e` cut at each `op` outside brackets and literals; a `+` that is part of `++` or `+=`, or a sign, does not cut. */
function splitTop(expr: string, op: string): string[] {
  const e = expr;
  const d = depths(e);
  const out: string[] = [];
  let from = 0;
  for (let k = 0; k < e.length; k++) {
    if (d[k] !== 0 || !e.startsWith(op, k)) continue;
    if (op === "+") {
      if (e[k + 1] === "+" || e[k + 1] === "=" || e[k - 1] === "+") continue;
      const before = e.slice(from, k).trim();
      if (before === "" || /[-+*/%=<>!&|?:(,]$/.test(before)) continue;
    }
    out.push(e.slice(from, k));
    from = k + op.length;
    k += op.length - 1;
  }
  out.push(e.slice(from));
  return out;
}

const CHAR_LITERAL = /^'(?:[^'\\]|\\.[^']*)'$/;

/** The calls of Streamlord's DSL that a hole may hold: `signal("count")`, `post("/x")`, each returning an expression the author wrote. */
const HOLE_HELPERS: ReadonlySet<string> = new Set([...EXPRESSION_HELPERS, ...catalog.actions.map((a) => a.kotlin).filter((k) => k !== ""), "statements", "js"]);

/** Whether `t` is one call to a helper of Streamlord's DSL, in a file that uses the DSL and imports no other function of that name. */
function helperCall(src: string, t: string): boolean {
  const m = /^([\p{L}_][\p{L}\p{Nd}_]*)\s*\(/u.exec(t);
  if (!m) return false;
  const facts = fileFacts(src);
  facts.helpers ??= dslHelpers(src, HOLE_HELPERS);
  return facts.helpers.has(m[1] as string) && wholeCall(t, m[1] as string);
}

/**
 * What the text shows of one value, a branch of an `if` or a `when` or a whole hole: a literal or
 * a constant, the author's own, a call to one of Streamlord's expression helpers, which is the
 * author's code too, or what the name's declaration says. Null where it shows nothing.
 */
export function valueKind(src: string, value: string, at: number, constants?: ReadonlySet<string>, nonNull = false): TextKind | "constant" | null {
  const t = value.trim();
  if (LITERAL.test(t) || STRING_LITERAL.test(t) || CHAR_LITERAL.test(t)) return "constant";
  if (helperCall(src, t)) return "constant";
  if (!IDENT.test(t)) return null;
  if (constantAt(src, t, at, constants)) return "constant";
  return declaredKind(src, t, at, nonNull);
}

/** Of the kinds a hole's values have, the one safe in the fewest places; one not known stands between a String and markup. */
export function weakest(kinds: readonly (TextKind | "constant" | null)[]): TextKind | "constant" | null {
  const order: (TextKind | "constant" | null)[] = ["string", null, "markup", "nullable", "boolean", "number", "constant"];
  let worst = order.length - 1;
  for (const k of kinds) worst = Math.min(worst, order.indexOf(k));
  return order[worst] ?? null;
}

/** Whether `name` means, at `at`, a parameter or value marked `@Language("HTML")`. */
export function htmlNamed(src: string, name: string, at: number): boolean {
  return declarationOf(src, name, at)?.html ?? false;
}

const TRIM_CALL = /^\s*\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)\s*$/;
const USES_HTML_DSL = /^\s*(?:import\s+io\.github\.markusaugust\.streamlord\.html\.(?:elements|\*)\s*$|package\s+io\.github\.markusaugust\.streamlord\.html\b)/m;

/** Whether `t` is one string literal of HTML, or one with a trim call after it. */
function htmlLiteral(t: string): boolean {
  const quote = /^\$*"/.exec(t);
  if (!quote) return false;
  const at = quote[0].length - 1;
  const end = literalEnd(t, at);
  const rest = t.slice(end);
  if (rest.trim() !== "" && !TRIM_CALL.test(rest)) return false;
  const content = t.slice(at + (t.startsWith('"""', at) ? 3 : 1), end);
  return HTML_START.test(/trimMargin/.test(rest) ? content.replace(/^\s*\|/, "") : content);
}

/** The call that ends `t`, `receiver.name(args) { lambda }`, or null when `t` does not end in one. */
function lastCall(t: string): { name: string; receiver: string | null; args: string; lambda: string | null } | null {
  const e = t.trim();
  const d = depths(e);
  const opening = (close: number) => {
    for (let k = close - 1; k >= 0; k--) if (d[k] === d[close] && "([{".includes(e[k] as string)) return k;
    return -1;
  };
  let end = e.length;
  let lambda: string | null = null;
  if (e.endsWith("}") && d[end - 1] !== -1) {
    const open = opening(end - 1);
    if (open < 0) return null;
    lambda = e.slice(open + 1, end - 1);
    end = open;
    while (end > 0 && /\s/.test(e[end - 1] ?? "")) end--;
  }
  let args = "";
  if (e[end - 1] === ")" && d[end - 1] !== -1) {
    const open = opening(end - 1);
    if (open < 0) return null;
    args = e.slice(open + 1, end - 1);
    end = open;
  } else if (lambda === null) return null;
  const name = /([\p{L}_][\p{L}\p{Nd}_]*)\s*$/u.exec(e.slice(0, end));
  if (!name) return null;
  const before = e.slice(0, end - name[0].length).trimEnd();
  if (before === "") return { name: name[1] as string, receiver: null, args, lambda };
  const dot = /\??\.$/.exec(before);
  return dot ? { name: name[1] as string, receiver: before.slice(0, -dot[0].length), args, lambda } : null;
}

/** What a lambda gives: its last expression, without its parameters. */
function lambdaResult(body: string): string {
  const b = body.replace(/^\s*(?:\([^()]*\)|[\p{L}_][\p{L}\p{Nd}_]*)(?:\s*:\s*[\p{L}\p{Nd}_.<>?]+)?(?:\s*,\s*(?:\([^()]*\)|[\p{L}_][\p{L}\p{Nd}_]*)(?:\s*:\s*[\p{L}\p{Nd}_.<>?]+)?)*\s*->/u, "");
  const d = depths(b);
  let from = 0;
  for (let k = 0; k < b.length; k++) {
    if (d[k] !== 0 || (b[k] !== "\n" && b[k] !== ";")) continue;
    const before = b.slice(from, k).trim();
    const after = b.slice(k + 1).trimStart();
    // A line that ends in an operator, or one that starts with one, goes on with the next.
    if (b[k] === "\n" && (/[-+*/%=<>&|?:.,(]$/.test(before) || /^(?:[-+*/%&|?:.]|else\b)/.test(after) || before === "")) continue;
    from = k + 1;
  }
  return b.slice(from).trim();
}

/** The argument given as `name = { ... }`, as its lambda's body. */
function namedLambda(args: string, name: string): string | null {
  for (const a of splitTop(args, ",")) {
    const m = new RegExp(`^\\s*${name}\\s*=\\s*\\{([\\s\\S]*)\\}\\s*$`).exec(a);
    if (m) return m[1] as string;
  }
  return null;
}

/**
 * Whether a hole's whole result is markup the author built, which escaping would print as tags:
 * an HTML literal, trimmed or not, or a `+` chain that opens with one; a call to `interpolate` or
 * `Trusted`, or the text of an `Html`; a name declared `Html` or marked `@Language("HTML")`;
 * kotlinx.html's `createHTML()`, `appendHTML()` in `buildString`, or Streamlord's `elements { }`;
 * `joinToString` of markup; or an `if`, a `when` or an `?:` every one of whose results is markup.
 */
export function markupValue(src: string, value: string, at: number): boolean {
  const values = holeValues(value);
  // `if (admin) "<b>admin</b>" else ""`: every result is markup or a literal the author wrote.
  if (values.length > 1) {
    const markup = values.map((v) => markupValue(src, v.text, at));
    return markup.some((m) => m) && values.every((v, k) => markup[k] || LITERAL.test(v.text) || STRING_LITERAL.test(v.text) || CHAR_LITERAL.test(v.text));
  }
  const t = (values[0] as HoleValue).text;
  if (htmlLiteral(t)) return true;
  const sum = splitTop(t, "+");
  if (sum.length > 1) return htmlLiteral((sum[0] as string).trim());
  if (wholeCall(t, "interpolate") || wholeCall(t, "Trusted")) return true;
  const textOf = /^([\s\S]*?)\s*\.\s*text$/.exec(t)?.[1];
  if (textOf !== undefined && (wholeCall(textOf, "interpolate") || (IDENT.test(textOf) && declaredKind(src, textOf, at) === "markup"))) return true;
  if (IDENT.test(t)) return declaredKind(src, t, at) === "markup" || htmlNamed(src, t, at);
  if (/^createHTML\s*\(/.test(t)) return true;
  const call = lastCall(t);
  if (!call) return false;
  if (call.name === "buildString" && call.receiver === null && call.lambda !== null) return /\bappendHTML\s*\(/.test(call.lambda);
  if (call.name === "elements" && call.receiver === null && call.lambda !== null) return USES_HTML_DSL.test(src);
  if (call.name !== "joinToString") return false;
  const transform = call.lambda ?? namedLambda(call.args, "transform");
  if (transform !== null) return markupValue(src, lambdaResult(transform), at);
  return call.receiver !== null && markupElements(src, call.receiver, at);
}

/** Whether every element of a collection is markup: `listOf(a, b)` of markup, or a `map { }` to markup. */
function markupElements(src: string, receiver: string, at: number): boolean {
  const r = lastCall(receiver);
  if (!r) return false;
  if ((r.name === "listOf" || r.name === "arrayOf") && r.receiver === null && r.lambda === null) return r.args.trim() !== "" && splitTop(r.args, ",").every((a) => markupValue(src, a, at));
  if ((r.name === "map" || r.name === "mapIndexed" || r.name === "mapNotNull") && r.lambda !== null) return markupValue(src, lambdaResult(r.lambda), at);
  return false;
}

/** Whether a hole's value is markup the author built; see [markupValue]. */
export function isMarkup(src: string, s: KotlinString, ip: Interpolation): boolean {
  return markupValue(src, ip.text, s.start);
}

/** The calls whose result is the text they were given, changed but no more markup than it was. */
const TEXT_TRANSFORMS: ReadonlySet<string> = new Set([
  "trim", "trimStart", "trimEnd", "lowercase", "uppercase", "toLowerCase", "toUpperCase", "capitalize", "decapitalize", "replaceFirstChar",
  "take", "takeLast", "drop", "dropLast", "substring", "substringBefore", "substringAfter", "substringBeforeLast", "substringAfterLast",
  "removePrefix", "removeSuffix", "removeSurrounding", "padStart", "padEnd", "orEmpty", "toString", "reversed", "repeat",
]);

/** Whether a string literal in `e` holds `<` or `&`, which markup is made of. */
function markupInLiteral(e: string): boolean {
  for (let k = 0; k < e.length; k++) {
    if (e[k] !== '"') continue;
    const end = literalEnd(e, k);
    if (/[<&]/.test(e.slice(k, end))) return true;
    k = end - 1;
  }
  return false;
}

/**
 * Whether a value in element text may hold markup that `interpolate` would escape: a constant
 * whose value this file does not show or shows with a tag, a literal with one, or the result of a
 * call that is not a plain change to text (`items(xs)`, a `joinToString` with tags in it). A
 * rewrite to `interpolate` would then print the tags.
 */
export function mayHoldMarkup(src: string, value: string, at: number, constants?: ReadonlySet<string>): boolean {
  return holeValues(value).some(({ text: t }) => {
    if (LITERAL.test(t) || CHAR_LITERAL.test(t)) return false;
    if (IDENT.test(t)) {
      if (!constantAt(src, t, at, constants)) return false;
      const text = declarationOf(src, t, at)?.text;
      return text === undefined || /[<&]/.test(text);
    }
    if (markupInLiteral(t)) return true;
    if (/^"/.test(t)) return false;
    if (/^[\p{L}_][\p{L}\p{Nd}_]*(?:\s*\??\.\s*[\p{L}_][\p{L}\p{Nd}_]*)*(?:!!)?$/u.test(t)) return false;
    const sum = splitTop(t, "+");
    if (sum.length > 1) return sum.some((p) => mayHoldMarkup(src, p, at, constants));
    const call = lastCall(t);
    if (!call) return true;
    const plain = TEXT_TRANSFORMS.has(call.name) || (call.name === "joinToString" && call.lambda === null && namedLambda(call.args, "transform") === null);
    return !plain || call.receiver === null || mayHoldMarkup(src, call.receiver, at, constants);
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
  const facts = fileFacts(src);
  if (facts.importEdits === undefined) facts.importEdits = importEdits(src);
  if (facts.importEdits) fix.edits = facts.importEdits.map((e) => ({ ...e }));
  return fix;
}

const INTERPOLATE_IMPORTED = new RegExp(`^\\s*import\\s+(?:${INTERPOLATE_IMPORT.replace(/\./g, "\\.")}|io\\.github\\.markusaugust\\.streamlord\\.core\\.domain\\.\\*)\\s*$`, "m");

/** The edit that imports `interpolate`, after the last import or the package, or null when the file has the import. */
function importEdits(src: string): Fix["edits"] | null {
  if (INTERPOLATE_IMPORTED.test(src)) return null;
  const imports = [...src.matchAll(/^import\s+[^\n]*$/gm)].pop();
  const pkg = /^package\s+[^\n]*$/m.exec(src);
  const anchor = imports ? (imports.index ?? 0) + imports[0].length : pkg ? pkg.index + pkg[0].length : -1;
  return anchor < 0 ? [{ start: 0, end: 0, text: `import ${INTERPOLATE_IMPORT}\n\n` }] : [{ start: anchor, end: anchor, text: `\n${imports ? "" : "\n"}import ${INTERPOLATE_IMPORT}` }];
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
