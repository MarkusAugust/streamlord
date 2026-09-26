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
}

const ACTION = /@([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;

export function validateExpression(text: string): Issue[] {
  const issues: Issue[] = [];
  if (text.trim().length === 0) {
    return [{ start: 0, end: Math.max(text.length, 1), message: "Empty Datastar expression.", severity: "warning", code: "empty-expression" }];
  }
  const js = text.replace(/@/g, "_");
  try {
    Parser.parse(js, {
      ecmaVersion: "latest",
      sourceType: "script",
      allowReturnOutsideFunction: true,
      allowAwaitOutsideFunction: true,
      allowHashBang: false,
    });
  } catch (e) {
    const err = e as { pos?: number; raisedAt?: number; message?: string };
    const pos = Math.min(err.pos ?? 0, text.length);
    const end = Math.max(pos + 1, Math.min(err.raisedAt ?? pos + 1, text.length));
    const msg = (err.message ?? "Syntax error").replace(/\s*\(\d+:\d+\)$/, "");
    issues.push({ start: pos, end, message: `Datastar expression: ${msg}`, severity: "error", code: "expression-syntax" });
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
      });
    } else if (spec.pro) {
      issues.push({ start, end, message: `@${name} is a Datastar Pro action; it needs the Pro bundle.`, severity: "hint", code: "pro-action" });
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
