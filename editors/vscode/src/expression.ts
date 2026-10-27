import { Parser } from "acorn";
import { catalog } from "./catalog.ts";

/**
 * Validation of Datastar expressions. A Datastar expression is JavaScript in which `$name` is a
 * signal and `@name(` is an action. The client rewrites both with one pattern before it compiles
 * the text (see `clientRewrite`), so the syntax is judged on the rewritten text, and what the
 * pattern does differently from JavaScript, it is told about.
 */

export interface Issue {
  start: number;
  end: number;
  message: string;
  severity: "error" | "warning" | "info" | "hint";
  code?: string;
  /** Documentation the diagnostic links to. */
  link?: string;
  /** Quick fixes: replace [start, end) with text. Offsets are in the same space as the issue's. */
  fixes?: Fix[];
}

export interface Fix {
  title: string;
  start: number;
  end: number;
  text: string;
}

export const DOCS = {
  attributes: "https://data-star.dev/reference/attributes",
  actions: "https://data-star.dev/reference/actions",
  sse: "https://data-star.dev/reference/sse_events",
  expressions: "https://data-star.dev/guide/datastar_expressions",
  signals: "https://data-star.dev/guide/reactive_signals",
};

/** The quote of the single-, double- or backtick-quoted JavaScript string literal the offset is inside, or null. */
export function quoteAt(text: string, offset: number): string | null {
  let quote: string | null = null;
  for (let i = 0; i < offset; i++) {
    const c = text[i] ?? "";
    if (c === "\\") {
      i++;
      continue;
    }
    if (quote) {
      if (c === quote) quote = null;
    } else if (c === "'" || c === '"' || c === "`") quote = c;
  }
  return quote;
}

/** What a character of an expression is to JavaScript; see `jsRegions`. */
export const Region = { CODE: 0, STRING: 1, TEMPLATE: 2, REGEX: 3, COMMENT: 4 } as const;

/** A character JavaScript continues a name with: ASCII, `_`, `$`, or a letter or decimal digit beyond ASCII. */
export function isIdentPart(c: string | undefined): boolean {
  if (c === undefined || c.length === 0) return false;
  if (/[A-Za-z0-9_$]/.test(c)) return true;
  return c >= "\u0080" && /[\p{L}\p{Nd}]/u.test(c);
}

/** The words after which a `/` opens a regular expression rather than dividing. */
const REGEX_AFTER_WORD: ReadonlySet<string> = new Set(["return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await"]);

/** The words whose parenthesised head may be followed by a statement that opens with a regular expression. */
const HEAD_WORDS: ReadonlySet<string> = new Set(["if", "while", "for", "with"]);

/**
 * Where a regular expression literal ends when its scan reaches each offset, outside a class and
 * inside one (`2 * offset` and `2 * offset + 1`), or -1 when it does not close on its line: past
 * the closing `/` and its flags. Filled from the end in one pass, so that a text full of `/` that
 * never close is read in linear time rather than scanned to the end of the line once for each.
 */
function regexEnds(text: string): Int32Array {
  const n = text.length;
  const out = new Int32Array(2 * (n + 2)).fill(-1);
  for (let j = n - 1; j >= 0; j--) {
    const c = text[j];
    for (let inClass = 0; inClass <= 1; inClass++) {
      let end: number;
      if (c === "\n") end = -1;
      else if (c === "\\") end = out[2 * (j + 2) + inClass]!;
      else if (c === "[") end = out[2 * (j + 1) + 1]!;
      else if (c === "]") end = out[2 * (j + 1)]!;
      else if (c === "/" && inClass === 0) {
        end = j + 1;
        while (end < n && isIdentPart(text[end])) end++;
      } else end = out[2 * (j + 1) + inClass]!;
      out[2 * j + inClass] = end;
    }
  }
  return out;
}

/**
 * What each character of an expression is to JavaScript: code, a string, the text of a template
 * literal, a regular expression or a comment. The code of a `${ }` is code. Whether a `/` divides
 * or opens a regular expression is told by what precedes it, as a tokenizer tells it. Shared with
 * the analysis module (`jsRegions` in Expression.kt), which follows it step by step.
 */
export function jsRegions(text: string): Uint8Array {
  const kind = new Uint8Array(text.length);
  // One entry per open `${`, counting the braces opened inside it.
  const braces: number[] = [];
  // One entry per open parenthesis: whether it is the head of an `if`, `while`, `for` or `with`.
  const parens: boolean[] = [];
  let regexAllowed = true;
  let lastWord = "";
  let ends: Int32Array | null = null;
  /** The end of the regular expression literal that opens at `from`, or null when none closes on its line. */
  const regexEnd = (from: number) => {
    ends ??= regexEnds(text);
    const end = ends[2 * (from + 1)]!;
    return end < 0 ? null : end;
  };
  const mark = (from: number, to: number, k: number) => {
    const end = Math.min(to, text.length);
    kind.fill(k, from, end);
    return end;
  };
  /** From just inside a template literal to just past its closing backtick, or past the `${` that opens code. */
  const template = (from: number) => {
    let j = from;
    while (j < text.length) {
      if (text[j] === "\\") j = mark(j, j + 2, Region.TEMPLATE);
      else if (text[j] === "`") {
        regexAllowed = false;
        return mark(j, j + 1, Region.TEMPLATE);
      } else if (text.startsWith("${", j)) {
        braces.push(0);
        regexAllowed = true;
        return mark(j, j + 2, Region.TEMPLATE);
      } else j = mark(j, j + 1, Region.TEMPLATE);
    }
    return j;
  };
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    const word = lastWord;
    lastWord = "";
    if (c === "'" || c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== c) j += text[j] === "\\" ? 2 : 1;
      i = mark(i, j + 1, Region.STRING);
      regexAllowed = false;
    } else if (c === "`") {
      kind[i] = Region.TEMPLATE;
      i = template(i + 1);
    } else if (c === "/" && text[i + 1] === "/") {
      const nl = text.indexOf("\n", i);
      i = mark(i, nl < 0 ? text.length : nl, Region.COMMENT);
      lastWord = word;
    } else if (c === "/" && text[i + 1] === "*") {
      const close = text.indexOf("*/", i + 2);
      i = mark(i, close < 0 ? text.length : close + 2, Region.COMMENT);
      lastWord = word;
    } else if (c === "/" && regexAllowed && regexEnd(i) !== null) {
      i = mark(i, regexEnd(i)!, Region.REGEX);
      regexAllowed = false;
    } else if (c === "{") {
      if (braces.length > 0) braces[braces.length - 1]!++;
      i++;
      regexAllowed = true;
    } else if (c === "}") {
      if (braces.length > 0 && braces[braces.length - 1] === 0) {
        braces.pop();
        kind[i] = Region.TEMPLATE;
        i = template(i + 1);
      } else {
        if (braces.length > 0) braces[braces.length - 1]!--;
        i++;
        regexAllowed = false;
      }
    } else if (c === "(") {
      parens.push(HEAD_WORDS.has(word));
      i++;
      regexAllowed = true;
    } else if (c === ")") {
      regexAllowed = parens.pop() ?? false;
      i++;
    } else if (c === "]") {
      i++;
      regexAllowed = false;
    } else if ((c === "+" || c === "-") && text[i + 1] === c) {
      // A postfix `++` leaves a value, a prefix one expects one: what came before still decides.
      i += 2;
    } else if (c >= "0" && c <= "9") {
      let j = i + 1;
      while (j < text.length && /[A-Za-z0-9_.]/.test(text[j]!)) j++;
      i = j;
      regexAllowed = false;
    } else if (isIdentPart(c)) {
      let j = i + 1;
      while (j < text.length && isIdentPart(text[j])) j++;
      lastWord = text.slice(i, j);
      regexAllowed = REGEX_AFTER_WORD.has(lastWord);
      i = j;
    } else if (/\s/.test(c)) {
      lastWord = word;
      i++;
    } else {
      i++;
      regexAllowed = true;
    }
  }
  return kind;
}

export function attributeDoc(name: string): string {
  return `${DOCS.attributes}#${name.startsWith("data-") ? name : "data-" + name}`;
}

/** An action with space before its parenthesis, which the client does not rewrite. */
const SPACED_ACTION = /@([A-Za-z_$][A-Za-z0-9_$]*)(\s+)\(/g;

/**
 * The function body Datastar compiles is function code, where `new.target` is allowed; acorn has no
 * option for that, so the check is turned off.
 */
const FunctionBodyParser = Parser.extend(
  (Base) =>
    class extends (Base as unknown as new (...args: never[]) => object) {
      get allowNewDotTarget() {
        return true;
      }
    } as unknown as typeof Parser,
);

/** As the function body Datastar compiles with `Function()`: `return` is allowed at the top level, `await` is not. */
function parseScript(js: string): void {
  FunctionBodyParser.parse(js, {
    ecmaVersion: "latest",
    sourceType: "script",
    allowReturnOutsideFunction: true,
    allowAwaitOutsideFunction: false,
    allowHashBang: false,
  });
}

/**
 * With no attribute to say whether the value is read, an expression that opens with `{` may be an
 * object literal Datastar wraps in `return (...)`; to a script parser it is a block. When the
 * script parse fails, the text is tried once more inside parentheses.
 */
function objectLiteralParses(js: string): boolean {
  if (!js.trimStart().startsWith("{")) return false;
  try {
    parseScript(`(${js})`);
    return true;
  } catch {
    return false;
  }
}

/** Does `js` parse as one expression? A filter value is evaluated as `return (value)`. */
export function expressionError(js: string): { pos: number; raisedAt: number; message: string } | null {
  try {
    parseScript(`return (${js}\n)`);
    return null;
  } catch (e) {
    const err = e as { pos?: number; raisedAt?: number; message?: string };
    const shift = (n: number | undefined) => Math.min(Math.max((n ?? 8) - 8, 0), js.length);
    return { pos: shift(err.pos), raisedAt: shift(err.raisedAt), message: (err.message ?? "Syntax error").replace(/\s*\(\d+:\d+\)$/, "") };
  }
}

/**
 * One statement of a value-returning expression: `statementRe` of Datastar's `genRx` as the client
 * writes it (engine.ts:415-416 in 1.0.4). Everything up to a `;` that is not inside quotes, a regex
 * literal or a no-argument IIFE.
 */
const STATEMENT = /(?:\/(?:\\\/|[^/])*\/|"(?:\\"|[^"])*"|'(?:\\'|[^'])*'|`(?:\\`|[^`])*`|\(\s*(?:(?:function)\s*\(\s*\)|(?:\(\s*\))\s*=>)\s*(?:\{[\s\S]*?\}|[^;){]*)\s*\)\s*\(\s*\)|[^;])+/gm;

/** JavaScript as Datastar compiles an expression, with a map from each of its offsets back to the expression. */
interface ClientScript {
  js: string;
  origin: number[];
}

/** A `$name.path` or an `@name(` the client rewrites; `end` is the end of what it replaces. */
export interface Rewrite {
  kind: "signal" | "action";
  start: number;
  end: number;
  name: string;
}

/** The text as the client rewrites it, what it rewrote, and the quoted runs it stepped over and left as written. */
export interface ClientRewrite extends ClientScript {
  rewrites: Rewrite[];
  runs: { start: number; end: number }[];
}

/**
 * The client's rewrite of an expression, the pattern as the client writes it (engine.ts:448-449 in
 * 1.0.4): a quoted string or a template literal without `${` is stepped over and left as written, a
 * `${...}` with no brace inside has its signals and actions rewritten, and outside them a
 * `$name.path` becomes `$['name']['path']` and an `@name(` a call of the action dispatcher.
 */
const CLIENT_REWRITE = /(?:"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|`[^`\\$]*(?:(?:\\.|\$(?!\{))[^`\\$]*)*`)|\$\{([^{}]*)\}|\$(\w+(?:[.-]\w+)*)|@([A-Za-z_$][\w$]*)\(/g;

/** Inside a `${...}` the client rewrites signals first and actions after, each with its own pattern. */
const CLIENT_SIGNAL = /\$(\w+(?:[.-]\w+)*)/g;
const CLIENT_ACTION = /@([A-Za-z_$][\w$]*)\(/g;
/** The client's signal pattern, anchored where it is tried. */
const CLIENT_SIGNAL_HERE = /\$(\w+(?:[.-]\w+)*)/y;

const signalPath = (name: string) => "$" + name.split(".").map((part) => `['${part}']`).join("");
const actionCall = (name: string) => `__action("${name}",evt,`;

/**
 * The text as the client writes it before compiling, with what it rewrote. Every character of a
 * rewritten reference maps back to where the reference began.
 */
export function clientRewrite(text: string): ClientRewrite {
  let js = "";
  const origin: number[] = [];
  const rewrites: Rewrite[] = [];
  const runs: { start: number; end: number }[] = [];
  const copy = (from: number, to: number) => {
    for (let k = from; k < to; k++) {
      origin.push(k);
      js += text[k];
    }
  };
  const put = (r: Rewrite) => {
    const written = r.kind === "signal" ? signalPath(r.name) : actionCall(r.name);
    for (let k = 0; k < written.length; k++) origin.push(r.start);
    js += written;
    rewrites.push(r);
  };
  const interpolation = (from: number, to: number) => {
    const inner = text.slice(from, to);
    const found: Rewrite[] = [...inner.matchAll(CLIENT_SIGNAL)].map((m) => ({ kind: "signal", start: from + m.index, end: from + m.index + m[0].length, name: m[1] ?? "" }));
    // An action the signal pass has already rewritten a part of is no longer there to match.
    for (const m of inner.matchAll(CLIENT_ACTION)) {
      const start = from + m.index;
      const end = start + m[0].length;
      if (!found.some((f) => f.start < end && start < f.end)) found.push({ kind: "action", start, end, name: m[1] ?? "" });
    }
    found.sort((a, b) => a.start - b.start);
    let i = from;
    for (const r of found) {
      copy(i, r.start);
      put(r);
      i = r.end;
    }
    copy(i, to);
  };
  let i = 0;
  for (const m of text.matchAll(CLIENT_REWRITE)) {
    const at = m.index;
    const end = at + m[0].length;
    copy(i, at);
    if (m[1] !== undefined) {
      copy(at, at + 2);
      interpolation(at + 2, end - 1);
      copy(end - 1, end);
    } else if (m[2] !== undefined) put({ kind: "signal", start: at, end, name: m[2] });
    else if (m[3] !== undefined) put({ kind: "action", start: at, end, name: m[3] });
    else {
      copy(at, end);
      runs.push({ start: at, end });
    }
    i = end;
  }
  copy(i, text.length);
  origin.push(text.length);
  return { js, origin, rewrites, runs };
}

/**
 * The signals the client reads in `text`: the references it rewrites where JavaScript runs them,
 * not in a string, a template's text, a regular expression or a comment, and not the tail of a
 * longer name such as `foo$bar`.
 */
export function clientSignalReads(text: string): Rewrite[] {
  const regions = jsRegions(text);
  return clientRewrite(text).rewrites.filter((r) => r.kind === "signal" && regions[r.start] === Region.CODE && !isIdentPart(text[r.start - 1]));
}

/**
 * The function body Datastar compiles from `text` (engine.ts:385-430 in 1.0.4). The text is
 * trimmed. With `returnsValue` it is split into statements, the last of them wrapped in
 * `return (...);` unless it starts with `return`, and the statements joined with `;\n`; the
 * separators and the wrapping map to where the statement started or ended.
 */
export function clientScript(text: string, returnsValue: boolean): ClientScript {
  let js = "";
  const origin: number[] = [];
  const append = (piece: string, from: number) => {
    for (let k = 0; k < piece.length; k++) origin.push(from + k);
    js += piece;
  };
  const synthetic = (piece: string, at: number) => {
    for (let k = 0; k < piece.length; k++) origin.push(at);
    js += piece;
  };
  const trimmed = text.trim();
  if (trimmed.length === 0) return { js: "", origin: [text.length] };
  const start = text.indexOf(trimmed);
  const end = start + trimmed.length;
  if (!returnsValue) append(trimmed, start);
  else {
    const statements = [...trimmed.matchAll(STATEMENT)];
    statements.forEach((m, i) => {
      if (i > 0) synthetic(";\n", start + m.index - 1);
      const last = m[0].trim();
      if (i === statements.length - 1 && !last.startsWith("return")) {
        const lastStart = start + m.index + m[0].indexOf(last);
        synthetic("return (", lastStart);
        append(last, lastStart);
        synthetic(");", lastStart + last.length);
      } else append(m[0], start + m.index);
    });
  }
  origin.push(end);
  return { js, origin };
}

/** The offsets of the `;` at which the client splits a value-returning `text` into statements. */
function statementSplits(text: string): number[] {
  const trimmed = text.trim();
  const start = text.indexOf(trimmed);
  const covered = new Uint8Array(trimmed.length);
  for (const m of trimmed.matchAll(STATEMENT)) covered.fill(1, m.index, m.index + m[0].length);
  const out: number[] = [];
  for (let k = 0; k < trimmed.length; k++) if (!covered[k]) out.push(start + k);
  return out;
}

/**
 * What to write for a `$name` in the text of a template literal that the client rewrites: `${$name}`
 * for the signal's value, `${'$'}name` to keep the text. A name that opens with a digit, `$5`, is
 * text: nobody names a signal that.
 */
function templateAdvice(written: string): string {
  const text = `\${'$'}${written.slice(1)}`;
  const keep = `write ${text} or put the text in a quoted string, which Datastar leaves as written.`;
  return /^\$[0-9]/.test(written) ? ` To keep the text, ${keep}` : ` Write \${${written}} for the signal's value. To keep the text, ${keep}`;
}

/**
 * For each offset of `js`, where the run of string characters it is in starts: the quote that
 * opens the string. One pass, so that a string full of rewrites is not walked back once for each.
 */
function stringStarts(regions: Uint8Array): Int32Array {
  const out = new Int32Array(regions.length);
  for (let k = 0; k < regions.length; k++) out[k] = k > 0 && regions[k - 1] === Region.STRING ? out[k - 1]! : k;
  return out;
}

/** Where a character the client paired as a quote sits, for the message that names it. */
function placeOf(region: number): string {
  switch (region) {
    case Region.COMMENT:
      return " in a comment";
    case Region.REGEX:
      return " in a regular expression";
    case Region.TEMPLATE:
      return " in a template literal";
    case Region.STRING:
      return " in a string";
    default:
      return "";
  }
}

/** `include: '` or `"exclude": "`, a pattern given as a string that opens with a slash. */
const STRING_PATTERN_HEAD = /(["']?)(include|exclude)\1\s*:\s*(["'`])\//g;

/**
 * The flags a filter's pattern may end with. `d` is left out: it changes nothing a filter does, so
 * nobody writes it there, and the tail of a path such as `'/users/id'` is then not read as flags.
 */
const REGEX_FLAGS = /^[gimsuyv]+$/;

export interface StringPattern {
  key: string;
  start: number;
  end: number;
  pattern: string;
  flags: string;
}

/**
 * The patterns of a filter written as a string with a slash before them and flags after the last
 * one: `include: '/^foo/i'`. The client's `toRegExp` (signals.ts:752 in 1.0.4) cuts one `/` from
 * each end and nothing else, so `'/^foo/'` works and `'/^foo/i'` looks for `/i`. Only the keys of
 * the object between `from` and `to` count: not `xinclude`, nor a key in a string or a comment. A
 * template literal counts when it holds no `${`. A scan rather than one regular expression, which
 * backtracks without end on a run of escapes. Shared with the analysis module (`stringPatterns`
 * in Expression.kt).
 */
export function stringPatterns(text: string, regions: Uint8Array = jsRegions(text), from = 0, to = text.length): StringPattern[] {
  const out: StringPattern[] = [];
  const head = new RegExp(STRING_PATTERN_HEAD.source, "g");
  head.lastIndex = from;
  for (let m = head.exec(text); m !== null && m.index < to; m = head.exec(text)) {
    const at = m.index;
    const keyOpens = m[1] ? regions[at] === Region.STRING && regions[at - 1] !== Region.STRING : regions[at] === Region.CODE;
    if (!keyOpens || isIdentPart(text[at - 1])) continue;
    const quote = m[3]!;
    const start = at + m[0].length - 2;
    let j = start + 1;
    while (j < to && text[j] !== quote && !(quote === "`" && text.startsWith("${", j))) j += text[j] === "\\" ? 2 : 1;
    if (j >= to || text[j] !== quote) continue;
    const content = text.slice(start + 1, j);
    // An escaped slash is a slash all the same once the string is read: '\/' is '/'.
    const slash = content.lastIndexOf("/");
    const flags = content.slice(slash + 1);
    if (slash <= 0 || !REGEX_FLAGS.test(flags)) continue;
    out.push({ key: m[2]!, start, end: j + 1, pattern: content.slice(1, slash), flags });
  }
  return out;
}

/** Flags a regular expression literal takes: each once, and `u` and `v` not together. */
function validFlags(flags: string): boolean {
  return new Set(flags).size === flags.length && !(flags.includes("u") && flags.includes("v"));
}

/** `pattern` as the body of a literal: a `/` escaped, as `SignalFilter.regexLiteral` in streamlord-html writes it. */
function literalBody(pattern: string): string {
  if (pattern.length === 0) return "(?:)";
  let out = "";
  for (let i = 0; i < pattern.length; i++) {
    if (pattern[i] === "\\") {
      out += pattern.slice(i, i + 2);
      i++;
    } else out += pattern[i] === "/" ? "\\/" : pattern[i];
  }
  return out;
}

/**
 * The `filter-pattern` warnings for the string patterns of the object between `from` and `to` in
 * `text`: a filter's value, `json` when it is JSON, or the `filterSignals` of an action. Shared
 * with the analysis module (`filterPatternIssues` in Expression.kt).
 */
export function filterPatternIssues(text: string, regions: Uint8Array, from: number, to: number, json: boolean, link: string): Issue[] {
  return stringPatterns(text, regions, from, to).map((p) => {
    const literal = `/${literalBody(p.pattern)}/${p.flags}`;
    const valid = validFlags(p.flags);
    const advice = !valid
      ? `As flags ${p.flags} would not do either: each is given once, and u and v not together. Write a regular expression literal${json ? ", in JavaScript rather than JSON," : ""} with the flags meant.`
      : json
        ? `JSON holds no regular expression: write the value as JavaScript, with the literal ${literal}.`
        : `Write the regular expression literal ${literal}.`;
    // The string's escapes would mean something else in a literal, and a quote or a reference in an attribute would need its own, so the fix is offered only without them.
    const fixable = valid && !json && !/[\\'"`&]/.test(p.pattern);
    return {
      start: p.start,
      end: p.end,
      message: `Datastar cuts one / from each end of a string ${p.key} and takes the rest as the pattern, so /${p.flags} stays in it and is matched as text, not read as flags. ${advice}`,
      severity: "warning" as const,
      code: "filter-pattern",
      link,
      ...(fixable ? { fixes: [{ title: `Change to ${literal}`, start: p.start, end: p.end, text: literal }] } : {}),
    };
  });
}

/** `filterSignals: {` in the options of an action, which the client reads with the same `toRegExp`. */
const FILTER_SIGNALS = /(["']?)filterSignals\1\s*:\s*\{/g;

/** The `filter-pattern` warnings for the `filterSignals` of the actions in `text`. */
function filterSignalsIssues(text: string): Issue[] {
  if (!text.includes("filterSignals")) return [];
  const regions = jsRegions(text);
  const out: Issue[] = [];
  for (const m of text.matchAll(FILTER_SIGNALS)) {
    const keyOpens = m[1] ? regions[m.index] === Region.STRING && regions[m.index - 1] !== Region.STRING : regions[m.index] === Region.CODE;
    if (!keyOpens || isIdentPart(text[m.index - 1])) continue;
    const open = m.index + m[0].length - 1;
    let depth = 0;
    let close = text.length;
    for (let i = open; i < text.length; i++) {
      if (regions[i] !== Region.CODE) continue;
      if (text[i] === "{") depth++;
      else if (text[i] === "}" && --depth === 0) {
        close = i + 1;
        break;
      }
    }
    out.push(...filterPatternIssues(text, regions, open, close, false, DOCS.actions));
  }
  return out;
}

/**
 * The issues of `text`. With `returnsValue` known, the syntax is judged on the script Datastar
 * compiles, see `clientScript`; without it, on the text as a script, with an object literal
 * allowed to open it. A `$my-value` named in `hyphenatedSignals` is a declared signal, not a
 * kebab-case slip.
 */
const NO_SIGNALS: ReadonlySet<string> = new Set();

export function validateExpression(text: string, returnsValue?: boolean, hyphenatedSignals: ReadonlySet<string> = NO_SIGNALS): Issue[] {
  const issues: Issue[] = [];
  if (text.trim().length === 0) {
    return [{ start: 0, end: Math.max(text.length, 1), message: "Empty Datastar expression.", severity: "warning", code: "empty-expression" }];
  }
  // As the client does it: the statements split and wrapped first, the signals and actions rewritten after.
  const script: ClientScript = returnsValue === undefined ? { js: text, origin: [...Array(text.length + 1).keys()] } : clientScript(text, returnsValue);
  const rewritten = clientRewrite(script.js);
  const regions = jsRegions(script.js);
  const at = (c: ClientScript, offset: number) => c.origin[Math.min(Math.max(offset, 0), c.origin.length - 1)] ?? 0;
  // From the script to the text, and from the rewritten script to the text.
  const fromScript = (offset: number) => at(script, offset);
  const toText = (offset: number) => at(script, at(rewritten, offset));
  const span = (start: number, end: number) => ({ start: fromScript(start), end: fromScript(end - 1) + 1 });

  // The client leaves `@get (` as written, which the browser cannot parse; this says why.
  const spaced = new Set<number>();
  for (const m of script.js.matchAll(SPACED_ACTION)) {
    if (regions[m.index] !== Region.CODE) continue;
    const name = m[1] ?? "";
    const { start } = span(m.index, m.index + 1);
    const end = start + 1 + name.length;
    const paren = start + m[0].length - 1;
    spaced.add(start);
    issues.push({
      start,
      end: paren,
      message: `Datastar reads an action only as @${name}( with nothing before the parenthesis. With a space the @ reaches the browser, which cannot parse it.`,
      severity: "error",
      code: "action-space",
      link: DOCS.actions,
      fixes: [{ title: "Remove the space", start: end, end: paren, text: "" }],
    });
  }

  let syntaxError = false;
  try {
    parseScript(rewritten.js);
  } catch (e) {
    if (returnsValue !== undefined || !objectLiteralParses(rewritten.js)) {
      const err = e as { pos?: number; raisedAt?: number; message?: string };
      const pos = Math.min(toText(err.pos ?? 0), text.length);
      const end = Math.max(pos + 1, Math.min(toText(err.raisedAt ?? pos + 1), text.length));
      const msg = (err.message ?? "Syntax error").replace(/\s*\(\d+:\d+\)$/, "");
      syntaxError = true;
      if (!spaced.has(pos)) issues.push({ start: pos, end, message: `Datastar expression: ${msg}`, severity: "error", code: "expression-syntax", link: DOCS.expressions });
    }
  }

  let starts: Int32Array | null = null;
  for (const r of rewritten.rewrites) {
    const region = regions[r.start] ?? Region.CODE;
    if (region === Region.COMMENT) continue;
    const written = script.js.slice(r.start, r.kind === "signal" ? r.end : r.end - 1);
    const { start, end } = span(r.start, r.start + written.length);
    if (region !== Region.CODE) {
      const becomes = r.kind === "signal" ? signalPath(r.name) : `${actionCall(r.name)}...)`;
      const advice =
        region === Region.TEMPLATE
          ? r.kind === "signal"
            ? templateAdvice(written)
            : " Put the text in a quoted string, which Datastar leaves as written."
          : region === Region.REGEX
            ? " Build the pattern with new RegExp('...'): Datastar leaves a quoted string as written."
            : "";
      const why =
        region === Region.TEMPLATE
          ? ", because the literal also holds a ${...}, so the text holds that"
          : region === Region.REGEX
            ? ", which changes the pattern"
            : script.js[(starts ??= stringStarts(regions))[r.start]!] === (r.kind === "signal" ? "'" : '"')
              ? `, because its pattern pairs the quotes before it differently. The ${r.kind === "signal" ? "'" : '"'} it writes ends the string, so the browser cannot parse the expression`
              : ", because its pattern pairs the quotes before it differently, so the string holds that";
      issues.push({
        start,
        end,
        message: `Datastar rewrites ${written}${placeOf(region)} into ${becomes}${why}.${advice}`,
        severity: "warning",
        code: "literal-rewritten",
        link: DOCS.expressions,
      });
      continue;
    }
    if (r.kind === "signal") {
      if (!r.name.includes("-")) continue;
      // data-signals:my-value__case.kebab, data-bind="my-value" or {'my-value': 1} declares $my-value as written, and the expression reaches it.
      if ([...hyphenatedSignals].some((h) => r.name === h || r.name.startsWith(`${h}.`))) continue;
      const head = written.slice(0, written.indexOf("-"));
      const rest = written.slice(written.indexOf("-") + 1);
      // `-1`, `-2px`: nobody names a signal that; the author subtracts. `-bar`: a kebab-case key, or a subtraction of a variable.
      const subtraction = /^[0-9]/.test(rest);
      // The browser lowercases the key, dots and all, and the client raises only a lowercase letter
      // after a hyphen (caseFns.camel in text.ts): a key a-b-1 is the signal aB-1, a-b.c-d is aB.cD,
      // and foo-_bar keeps its name.
      const key = written.slice(1).toLowerCase();
      const signal = key.replace(/-[a-z]/g, (x) => x[1]!.toUpperCase());
      const camel = `$${signal}`;
      const spacedOut = written.replaceAll("-", " - ");
      const fixes: Fix[] = [];
      if (!subtraction && signal !== key) fixes.push({ title: `Change to ${camel}`, start, end, text: camel });
      fixes.push({ title: `Write ${spacedOut}`, start, end, text: spacedOut });
      const reads = `Datastar reads ${written} as one signal named ${r.name}`;
      const subtract = `For a subtraction write ${spacedOut}, with spaces.`;
      issues.push({
        start,
        end,
        message: subtraction
          ? `${reads}, not as ${head} minus ${rest}. ${subtract}`
          : signal === key
            ? `${reads}, and nothing here declares it: a key ${key} keeps that name, since Datastar camel-cases only a lowercase letter after a hyphen. ${subtract}`
            : `${reads}, and nothing here declares it: a key ${key} is the signal $${signal}. ${subtract}`,
        severity: "warning",
        code: "signal-kebab",
        link: DOCS.signals,
        fixes,
      });
      continue;
    }
    const spec = catalog.actionsByName.get(r.name);
    const nameEnd = start + 1 + r.name.length;
    if (!spec) {
      const near = catalog.actions.map((a) => a.name).find((n) => n.toLowerCase() === r.name.toLowerCase());
      issues.push({
        start,
        end: nameEnd,
        message: near ? `Unknown action @${r.name}. Did you mean @${near}?` : `Unknown action @${r.name}.`,
        severity: "warning",
        code: "unknown-action",
        link: DOCS.actions,
        fixes: near ? [{ title: `Change to @${near}`, start, end: nameEnd, text: `@${near}` }] : undefined,
      });
    } else if (spec.pro) {
      issues.push({ start, end: nameEnd, message: `@${r.name} is a Datastar Pro action; it needs the Pro bundle.`, severity: "hint", code: "pro-action", link: DOCS.actions });
    }
  }

  // A `$name` in code the client did not rewrite reaches the browser as a JavaScript variable.
  const rewrittenAt = new Set(rewritten.rewrites.map((r) => r.start));
  for (let p = 0; p < script.js.length; p++) {
    if (script.js[p] !== "$" || regions[p] !== Region.CODE || rewrittenAt.has(p) || isIdentPart(script.js[p - 1])) continue;
    const next = script.js[p + 1];
    if (next === undefined || next === "$") continue;
    if (/\w/.test(next)) {
      CLIENT_SIGNAL_HERE.lastIndex = p;
      const written = CLIENT_SIGNAL_HERE.exec(script.js)?.[0] ?? "$";
      const run = rewritten.runs.find((q) => q.start <= p && p < q.end);
      const why = run
        ? `: its pattern takes the ${script.js[run.start]}${placeOf(regions[run.start] ?? Region.CODE)} before it for the start of a string, and leaves everything up to the next ${script.js[run.start]} as written`
        : "";
      issues.push({
        ...span(p, p + written.length),
        message: `Datastar does not rewrite ${written}${why}. The browser reads ${written} as a JavaScript variable, not as the signal.`,
        severity: "warning",
        code: "signal-not-read",
        link: DOCS.expressions,
      });
    } else if (isIdentPart(next)) {
      let q = p + 1;
      while (q < script.js.length && isIdentPart(script.js[q])) q++;
      const written = script.js.slice(p, q);
      issues.push({
        ...span(p, q),
        message: `Datastar reads only ASCII letters, digits and _ after a $, so ${written} is no signal: the browser looks for a JavaScript variable named ${written} and fails.`,
        severity: "warning",
        code: "signal-not-read",
        link: DOCS.signals,
      });
    }
  }

  // A `;` inside brackets splits the expression all the same, and what it computed is lost.
  if (returnsValue === true && !syntaxError) {
    const textRegions = jsRegions(text);
    let depth = 0;
    const splits = new Set(statementSplits(text));
    for (let k = 0; k < text.length; k++) {
      if (splits.has(k) && depth > 0) {
        issues.push({
          start: k,
          end: k + 1,
          message:
            "Datastar splits an expression whose value is used at every ; outside quotes, this one inside brackets too, and returns only what the last piece gives: the value is lost. A function without parameters called at once, (() => { ... })(), is kept whole.",
          severity: "warning",
          code: "statement-split",
          link: DOCS.expressions,
        });
        break;
      }
      if (textRegions[k] !== Region.CODE) continue;
      const c = text[k];
      if (c === "(" || c === "[" || c === "{") depth++;
      else if ((c === ")" || c === "]" || c === "}") && depth > 0) depth--;
    }
  }
  issues.push(...filterSignalsIssues(text));
  return issues;
}

/** The signal name under or just before `offset`, for completions. */
export function signalPrefixAt(text: string, offset: number): string | null {
  const before = text.slice(0, offset);
  const m = /\$([A-Za-z_][A-Za-z0-9_.]*)?$/.exec(before);
  return m ? (m[1] ?? "") : null;
}

export function actionPrefixAt(text: string, offset: number): string | null {
  const before = text.slice(0, offset);
  const m = /@([A-Za-z_][A-Za-z0-9_]*)?$/.exec(before);
  return m ? (m[1] ?? "") : null;
}
