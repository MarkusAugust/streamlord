import { Parser } from "acorn";
import { catalog } from "./catalog.ts";

/**
 * Validation of Datastar expressions. A Datastar expression is JavaScript in which `$name` is a
 * signal (a legal JS identifier already) and `@name(...)` is an action (not legal JS). Replacing
 * every `@` with `_` yields JavaScript of identical length, so acorn's positions map 1:1.
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

/**
 * `$foo-bar`, `$count-1`, `$total-el.offsetWidth`: Datastar reads a signal with the pattern `\$(\w+(?:[.-]\w+)*)`,
 * so a hyphen followed by a word character is swallowed into the name. Only `$a-$b` is a subtraction.
 */
const HYPHENATED_SIGNAL = /\$[A-Za-z_][A-Za-z0-9_.]*(?:-[A-Za-z0-9_][A-Za-z0-9_.]*)+/g;

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

/**
 * Which offsets of an expression are code: not a string literal, not the text of a template
 * literal, not a comment. Datastar rewrites signals and actions in code only, and inside the
 * `${ }` of a template literal, which is code here too. A regular expression literal is not
 * recognised; a quote inside one opens a string.
 */
export function jsCodeMask(text: string): Uint8Array {
  const mask = new Uint8Array(text.length).fill(1);
  // One entry per open `${`, counting the braces opened inside it.
  const braces: number[] = [];
  const blank = (from: number, to: number) => {
    const end = Math.min(to, text.length);
    mask.fill(0, from, end);
    return end;
  };
  /** From just inside a template literal to just past its closing backtick, or past the `${` that opens code. */
  const template = (from: number) => {
    let j = from;
    while (j < text.length) {
      if (text[j] === "\\") j = blank(j, j + 2);
      else if (text[j] === "`") return blank(j, j + 1);
      else if (text.startsWith("${", j)) {
        braces.push(0);
        return j + 2;
      } else j = blank(j, j + 1);
    }
    return j;
  };
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === "'" || c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== c) j += text[j] === "\\" ? 2 : 1;
      i = blank(i, j + 1);
    } else if (c === "`") {
      mask[i] = 0;
      i = template(i + 1);
    } else if (c === "/" && text[i + 1] === "/") {
      const nl = text.indexOf("\n", i);
      i = blank(i, nl < 0 ? text.length : nl);
    } else if (c === "/" && text[i + 1] === "*") {
      const close = text.indexOf("*/", i + 2);
      i = blank(i, close < 0 ? text.length : close + 2);
    } else if (c === "{" && braces.length > 0) {
      braces[braces.length - 1]!++;
      i++;
    } else if (c === "}" && braces.length > 0) {
      if (braces[braces.length - 1] === 0) {
        braces.pop();
        i = template(i + 1);
      } else {
        braces[braces.length - 1]!--;
        i++;
      }
    } else i++;
  }
  return mask;
}

export function attributeDoc(name: string): string {
  return `${DOCS.attributes}#${name.startsWith("data-") ? name : "data-" + name}`;
}

const ACTION = /@([A-Za-z_$][A-Za-z0-9_$]*)(\s*)\(/g;

/** As the function body Datastar compiles with `Function()`: `return` is allowed at the top level, `await` is not. */
function parseScript(js: string): void {
  Parser.parse(js, {
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

/**
 * One statement of a value-returning expression, as Datastar's `genRx` matches them (engine.ts:412
 * in 1.0.4): everything up to a `;` that is not inside quotes, a regex literal or a no-argument IIFE.
 */
const STATEMENT = /(?:\/(?:\\\/|[^/])*\/|"(?:\\"|[^"])*"|'(?:\\'|[^'])*'|`(?:\\`|[^`])*`|\(\s*(?:(?:function)\s*\(\s*\)|(?:\(\s*\))\s*=>)\s*(?:\{[\s\S]*?\}|[^;){]*)\s*\)\s*\(\s*\)|[^;])+/gm;

/** JavaScript as Datastar compiles an expression, with a map from each of its offsets back to the expression. */
interface ClientScript {
  js: string;
  origin: number[];
}

/**
 * The client's rewrite of an expression (engine.ts:448 in 1.0.4), as one pattern: a quoted string
 * or a template literal without `${` is stepped over, a `${...}` has its signals rewritten, and a
 * `$name.path` outside them is written as `$['name']['path']`.
 */
const CLIENT_REWRITE = /(?:"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|`[^`\\$]*(?:(?:\\.|\$(?!\{))[^`\\$]*)*`)|\$\{([^{}]*)\}|\$(\w+(?:[.-]\w+)*)/g;

/** A signal reference as the client reads one: `\w` and `.`/`-` joined parts. */
const CLIENT_SIGNAL = /\$(\w+(?:[.-]\w+)*)/g;

const signalPath = (name: string) => "$" + name.split(".").map((part) => `['${part}']`).join("");

/**
 * The text with every `$name.path` written as the client writes it before compiling, so a signal
 * can be no parameter, declared name or property key, as it cannot be in the browser; what sits in
 * quotes stays as written, as it does there. Every character of a rewritten reference maps back to
 * where the reference began.
 */
export function clientSignals(text: string): ClientScript {
  let js = "";
  const origin: number[] = [];
  const copy = (from: number, to: number) => {
    for (let k = from; k < to; k++) {
      origin.push(k);
      js += text[k];
    }
  };
  const signals = (from: number, to: number) => {
    let i = from;
    for (const m of text.slice(from, to).matchAll(CLIENT_SIGNAL)) {
      const at = from + (m.index ?? 0);
      copy(i, at);
      const written = signalPath(m[1] ?? "");
      for (let k = 0; k < written.length; k++) origin.push(at);
      js += written;
      i = at + m[0].length;
    }
    copy(i, to);
  };
  let i = 0;
  for (const m of text.matchAll(CLIENT_REWRITE)) {
    const at = m.index ?? 0;
    copy(i, at);
    if (m[1] !== undefined) {
      copy(at, at + 2);
      signals(at + 2, at + 2 + m[1].length);
      copy(at + 2 + m[1].length, at + m[0].length);
    } else if (m[2] !== undefined) signals(at, at + m[0].length);
    else copy(at, at + m[0].length);
    i = at + m[0].length;
  }
  copy(i, text.length);
  origin.push(text.length);
  return { js, origin };
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
  // An action is legal JavaScript once its @ is a letter; a signal is written as the client writes it.
  const signals = clientSignals(text.replace(/@/g, "_"));
  const script = returnsValue === undefined ? null : clientScript(signals.js, returnsValue);
  const js = script ? script.js : signals.js;
  const at = (c: ClientScript, offset: number) => c.origin[Math.min(Math.max(offset, 0), c.origin.length - 1)] ?? 0;
  const toExpression = (offset: number) => at(signals, script ? at(script, offset) : offset);
  try {
    parseScript(js);
  } catch (e) {
    if (script || !objectLiteralParses(js)) {
      const err = e as { pos?: number; raisedAt?: number; message?: string };
      const pos = Math.min(toExpression(err.pos ?? 0), text.length);
      const end = Math.max(pos + 1, Math.min(toExpression(err.raisedAt ?? pos + 1), text.length));
      const msg = (err.message ?? "Syntax error").replace(/\s*\(\d+:\d+\)$/, "");
      issues.push({ start: pos, end, message: `Datastar expression: ${msg}`, severity: "error", code: "expression-syntax", link: DOCS.expressions });
    }
  }
  const code = jsCodeMask(text);
  HYPHENATED_SIGNAL.lastIndex = 0;
  let k: RegExpExecArray | null;
  while ((k = HYPHENATED_SIGNAL.exec(text)) !== null) {
    const written = k[0];
    if (code[k.index] === 0) continue;
    // data-signals:my-value__case.kebab declares $my-value as written, and the expression reaches it.
    const named = written.slice(1);
    if ([...hyphenatedSignals].some((h) => named === h || named.startsWith(`${h}.`))) continue;
    const head = written.slice(0, written.indexOf("-"));
    const rest = written.slice(written.indexOf("-") + 1);
    // `-1`, `-2px`: nobody names a signal that; the author subtracts. `-bar`: a kebab-case key, or a subtraction of a variable.
    const subtraction = /^[0-9]/.test(rest);
    const camel = written.replace(/-([A-Za-z0-9_])/g, (_, c: string) => c.toUpperCase());
    const spaced = written.replace(/-([A-Za-z0-9_])/g, (_, c: string) => ` - ${c}`);
    const end = k.index + written.length;
    const fixes: Fix[] = [];
    if (!subtraction) fixes.push({ title: `Change to ${camel}`, start: k.index, end, text: camel });
    fixes.push({ title: `Write ${spaced}`, start: k.index, end, text: spaced });
    issues.push({
      start: k.index,
      end,
      message: subtraction
        ? `Datastar reads ${written} as one signal named ${written.slice(1)}, not as ${head} minus ${rest}. For a subtraction write ${spaced}, with spaces.`
        : `Datastar reads ${written} as one signal named ${written.slice(1)}, which no key declares: a key ${head.slice(1)}-${rest.split(".")[0]} is the signal ${camel}. For a subtraction write ${spaced}, with spaces.`,
      severity: "warning",
      code: "signal-kebab",
      link: DOCS.signals,
      fixes,
    });
  }
  ACTION.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = ACTION.exec(text)) !== null) {
    const name = m[1] ?? "";
    const spec = catalog.actionsByName.get(name);
    const start = m.index;
    const end = start + 1 + name.length;
    if (code[start] === 0) continue;
    if (m[2]) {
      const paren = start + m[0].length - 1;
      issues.push({
        start,
        end: paren,
        message: `Datastar reads an action only as @${name}( with nothing before the parenthesis. With a space the @ reaches the browser, which cannot parse it.`,
        severity: "error",
        code: "action-space",
        link: DOCS.actions,
        fixes: [{ title: "Remove the space", start: end, end: paren, text: "" }],
      });
      continue;
    }
    if (!spec) {
      const near = catalog.actions.map((a) => a.name).find((n) => n.toLowerCase() === name.toLowerCase());
      issues.push({
        start,
        end,
        message: near ? `Unknown action @${name}. Did you mean @${near}?` : `Unknown action @${name}.`,
        severity: "warning",
        code: "unknown-action",
        link: DOCS.actions,
        fixes: near ? [{ title: `Change to @${near}`, start, end, text: `@${near}` }] : undefined,
      });
    } else if (spec.pro) {
      issues.push({ start, end, message: `@${name} is a Datastar Pro action; it needs the Pro bundle.`, severity: "hint", code: "pro-action", link: DOCS.actions });
    }
  }
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
