/**
 * The Datastar expression a caret stands in, in any file the extension reads: an attribute value
 * in markup, an HTML string in Kotlin, or the string handed to a DSL call. Completion and
 * signature help read it alike; the text before the caret is what the browser or the DSL hands
 * Datastar, with Kotlin's escapes and character references decoded.
 */
import { htmlStringAt, lexCached } from "./analyze.ts";
import { catalog, parseAttributeName } from "./catalog.ts";
import { valueAtCaret } from "./completionContext.ts";
import type { KotlinString } from "./kotlinStrings.ts";
import { findCallSites, selectStringArg } from "./scanner.ts";

const EXPRESSION_SITES: ReadonlySet<string> = new Set(Object.keys(catalog.callSites.expression));

export interface ExpressionAtCaret {
  /** The expression before the caret, as Datastar is handed it. */
  before: string;
  /** Whether actions belong there: an expression, not a signal name. */
  allowActions: boolean;
  /** Whether `patch` is in scope: data-on-signal-patch only. */
  patchInScope: boolean;
}

/** The decoded index of a source offset in a Kotlin string: the last character that starts at or before it. */
function decodedIndex(s: KotlinString, offset: number): number {
  let i = 0;
  while (i + 1 < s.map.length && (s.map[i + 1] ?? Infinity) <= offset) i++;
  return i;
}

/** The expression in the attribute value the caret stands in, in `markup` at `caret`, or null. */
function inMarkup(markup: string, caret: number, prefix: string): ExpressionAtCaret | null {
  const at = valueAtCaret(markup, caret, prefix);
  if (!at) return null;
  const spec = parseAttributeName(at.attribute.toLowerCase(), prefix)?.spec;
  if (!spec || (spec.valueKind !== "expression" && spec.valueKind !== "signal")) return null;
  return { before: at.before, allowActions: spec.valueKind === "expression", patchInScope: spec.name === "on-signal-patch" };
}

/** The expression the caret stands in, in `src` (a Kotlin file when `kotlin`), or null. */
export function expressionAtCaret(src: string, offset: number, kotlin: boolean, prefix: string): ExpressionAtCaret | null {
  if (!kotlin) return inMarkup(src, offset, prefix);
  const html = htmlStringAt(src, offset);
  if (html) {
    if (offset <= html.contentStart || offset > html.contentEnd) return null;
    return inMarkup(html.text, decodedIndex(html, offset), prefix);
  }
  const site = findCallSites(src, EXPRESSION_SITES, lexCached(src).mask).find((s) => s.openParen < offset && offset <= s.closeParen);
  if (!site) return null;
  const spec = catalog.callSites.expression[site.name];
  if (!spec) return null;
  const s = selectStringArg(site, spec.arg, spec.named) ?? site.args.map((a) => a.string).find((x) => x && x.start < offset && offset <= x.end) ?? null;
  if (!s || offset <= s.start || offset > s.contentEnd + (s.unterminated ? 1 : 0)) return null;
  // dataOnSignalPatch also writes the filter, so the call is looked up among the attribute's own names.
  const patch = catalog.attributesByName.get("on-signal-patch")?.kotlin.includes(site.name) ?? false;
  return { before: s.text.slice(0, decodedIndex(s, offset)), allowActions: true, patchInScope: patch };
}
