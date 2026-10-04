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

function parseScript(js: string): void {
  Parser.parse(js, {
    ecmaVersion: "latest",
    sourceType: "script",
    allowReturnOutsideFunction: true,
    allowAwaitOutsideFunction: true,
    allowHashBang: false,
  });
}

function objectLiteralParses(js: string): boolean {
  if (!js.trimStart().startsWith("{")) return false;
  try {
    parseScript(`(${js})`);
    return true;
  } catch {
    return false;
  }
}

export function validateExpression(text: string): Issue[] {
  const issues: Issue[] = [];
  if (text.trim().length === 0) {
    return [{ start: 0, end: Math.max(text.length, 1), message: "Empty Datastar expression.", severity: "warning", code: "empty-expression" }];
  }
  // `$foo.0.name` is the path foo, 0, name: Datastar rewrites it to bracket form before the browser
  // parses it. To a JavaScript parser `.0` is an error, so the digit is read as a letter, at the same width.
  const js = text.replace(/@/g, "_").replace(/\$\w+(?:\.\w+)+/g, (path) => path.replace(/\.\d/g, "._"));
  try {
    parseScript(js);
  } catch (e) {
    // An expression that opens with `{` is an object literal to Datastar, which wraps the last
    // statement in `return (...)` for the attributes that take a value; to a script parser it is a
    // block. When the script parse fails, the text is tried once more inside parentheses.
    if (!objectLiteralParses(js)) {
      const err = e as { pos?: number; raisedAt?: number; message?: string };
      const pos = Math.min(err.pos ?? 0, text.length);
      const end = Math.max(pos + 1, Math.min(err.raisedAt ?? pos + 1, text.length));
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
