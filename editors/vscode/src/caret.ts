/**
 * The Datastar expression a caret stands in, in any file the extension reads: an attribute value
 * in markup, an HTML string in Kotlin, the value of an `attributes["data-..."] =`, or the string handed to a DSL call. Completion, hover and
 * signature help read it alike; the text before the caret is what the browser or the DSL hands
 * Datastar, with Kotlin's escapes and character references decoded. The twin of
 * expressionAtCaret in CompletionContext.kt, both held to test/fixtures/twins/expressions-at-caret.json.
 */
import { ATTRIBUTES_INDEX, htmlStringAt, lexCached } from "./analyze.ts";
import { catalog, parseAttributeName } from "./catalog.ts";
import { valueAtCaret } from "./completionContext.ts";
import type { KotlinString } from "./kotlinStrings.ts";
import { asciiLowercase } from "./markup.ts";
import { findCallSites, selectStringArg, stringAt } from "./scanner.ts";

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
function inMarkup(markup: string, caret: number, prefix: string, templates = true): ExpressionAtCaret | null {
  const at = valueAtCaret(markup, caret, prefix, templates);
  if (!at) return null;
  const spec = parseAttributeName(at.attribute.toLowerCase(), prefix)?.spec;
  if (!spec || (spec.valueKind !== "expression" && spec.valueKind !== "signal")) return null;
  return { before: at.before, allowActions: spec.valueKind === "expression", patchInScope: spec.name === "on-signal-patch" };
}

/** The expression the caret stands in, in `src` (a Kotlin file when `kotlin`), or null. */
export function expressionAtCaret(src: string, offset: number, kotlin: boolean, prefix: string): ExpressionAtCaret | null {
  if (!kotlin) return inMarkup(src, offset, prefix);
  // The diagnostics read kotlinx.html's own form before a free HTML string, and so does the caret.
  const lex = lexCached(src);
  for (const m of src.matchAll(ATTRIBUTES_INDEX)) {
    const start = m.index ?? 0;
    if (lex.mask[start] === 0) continue;
    const at = start + m[0].length;
    const nameStart = start + m[0].indexOf('"') + 1;
    const s = stringAt(src, at + 1, lex.strings);
    const value = s && s.start === at ? s : null;
    if (offset < nameStart || offset > (value?.end ?? nameStart)) continue;
    if (!value || offset <= value.start || offset > value.contentEnd + (value.unterminated ? 1 : 0)) return null;
    const spec = parseAttributeName(asciiLowercase(m[1] ?? ""), prefix)?.spec;
    if (!spec || (spec.valueKind !== "expression" && spec.valueKind !== "signal")) return null;
    return { before: value.text.slice(0, decodedIndex(value, offset)), allowActions: spec.valueKind === "expression", patchInScope: spec.name === "on-signal-patch" };
  }
  const html = htmlStringAt(src, offset);
  if (html) {
    if (offset <= html.contentStart || offset > html.contentEnd) return null;
    return inMarkup(html.text, decodedIndex(html, offset), prefix, false);
  }
  const site = findCallSites(src, EXPRESSION_SITES, lex.mask).find((s) => s.openParen < offset && offset <= s.closeParen);
  if (!site) return null;
  const spec = catalog.callSites.expression[site.name];
  if (!spec) return null;
  const s = selectStringArg(site, spec.arg, spec.named) ?? site.args.map((a) => a.string).find((x) => x && x.start < offset && offset <= x.end) ?? null;
  if (!s || offset <= s.start || offset > s.contentEnd + (s.unterminated ? 1 : 0)) return null;
  // dataOnSignalPatch also writes the filter, so the call is looked up among the attribute's own names.
  const patch = catalog.attributesByName.get("on-signal-patch")?.kotlin.includes(site.name) ?? false;
  return { before: s.text.slice(0, decodedIndex(s, offset)), allowActions: true, patchInScope: patch };
}
