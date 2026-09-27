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

/** `$foo-bar`: a kebab-case key written where the camelCase signal belongs. */
const KEBAB_SIGNAL = /\$[A-Za-z_][A-Za-z0-9_.]*(?:-[a-z][A-Za-z0-9_]*)+/g;

/** Is the offset inside a single- or double-quoted JavaScript string literal? */
function insideQuotes(text: string, offset: number): boolean {
  let quote: string | null = null;
  for (let i = 0; i < offset; i++) {
    const c = text[i];
    if (c === "\\") {
      i++;
      continue;
    }
    if (quote) {
      if (c === quote) quote = null;
    } else if (c === "'" || c === '"' || c === "`") quote = c;
  }
  return quote !== null;
}

export function attributeDoc(name: string): string {
  return `${DOCS.attributes}#${name.startsWith("data-") ? name : "data-" + name}`;
}

const ACTION = /@([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;

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
  const js = text.replace(/@/g, "_");
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
  KEBAB_SIGNAL.lastIndex = 0;
  let k: RegExpExecArray | null;
  while ((k = KEBAB_SIGNAL.exec(text)) !== null) {
    const written = k[0];
    const after = text[k.index + written.length] ?? "";
    // `$total-el.offsetWidth` and `$count-evt.detail.delta` are subtractions of a scope variable;
    // a `$id-preview` inside a quoted string is text.
    if (after === "." || after === "(" || after === "[") continue;
    if (insideQuotes(text, k.index)) continue;
    const camel = written.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
    issues.push({
      start: k.index,
      end: k.index + written.length,
      message: `${written} reads as ${written.split("-")[0]} minus the rest. Datastar names signals in camelCase: a key foo-bar is the signal $fooBar.`,
      severity: "warning",
      code: "signal-kebab",
      link: DOCS.signals,
      fixes: [{ title: `Change to ${camel}`, start: k.index, end: k.index + written.length, text: camel }],
    });
  }
  ACTION.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = ACTION.exec(text)) !== null) {
    const name = m[1] ?? "";
    const spec = catalog.actionsByName.get(name);
    const start = m.index;
    const end = start + 1 + name.length;
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
