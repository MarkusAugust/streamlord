/**
 * Which of Streamlord's modules a Kotlin file can use, as far as its imports prove it without the
 * classpath IntelliJ reads. The editor features read a DSL call's string as Datastar only where
 * the file can call the DSL, as the IntelliJ plugin does. Whether the project has Streamlord at
 * all no text can prove: a parent pom, a published catalog or a platform names it out of sight.
 */
import { ATTRIBUTES_INDEX, DSL_NAMES, htmlStringAt, lexCached, type AnalyzeOptions } from "./analyze.ts";
import { expressionAtCaret, type ExpressionAtCaret } from "./caret.ts";
import type { KotlinString } from "./kotlinStrings.ts";
import { findCallSites, isHtmlString } from "./scanner.ts";
import { USES_HTML_DSL } from "./signalNameHyphens.ts";

const OFFICIAL_SDK_IMPORT = /^[ \t]*import[ \t]+dev\.datastar\.kotlin\.sdk\b/m;
const STREAMLORD_IMPORT = /^[ \t]*import[ \t]+io\.github\.markusaugust\.streamlord\./m;

/**
 * The modules a Kotlin file can use. The DSL's functions are top-level functions of `io.github.markusaugust.streamlord.html`,
 * which a file calls only through an import of that package or from inside it. Core's are members of a stream a file
 * may be handed without any import, so core is left unknown, and the analysis takes it for there, except in a file
 * that imports the official SDK and nothing of Streamlord.
 */
export function kotlinModules(src: string): Pick<AnalyzeOptions, "htmlDsl" | "core"> {
  if (OFFICIAL_SDK_IMPORT.test(src) && !STREAMLORD_IMPORT.test(src)) return { core: false, htmlDsl: false };
  return { core: null, htmlDsl: USES_HTML_DSL.test(src) };
}

/** The HTML string at `offset`: one that looks like HTML, or the markup handed to a Streamlord call where the file has core. */
export function kotlinHtmlStringAt(src: string, offset: number, modules: Pick<AnalyzeOptions, "core">): KotlinString | null {
  const s = htmlStringAt(src, offset);
  return s && (modules.core !== false || isHtmlString(src, s)) ? s : null;
}

/** Whether `offset` lies in an `attributes["data-..."] = "..."`, kotlinx.html's own form, which is Datastar in every project. */
function inAttributesIndex(src: string, offset: number): boolean {
  const lex = lexCached(src);
  for (const m of src.matchAll(ATTRIBUTES_INDEX)) {
    const start = m.index ?? 0;
    if (lex.mask[start] === 0) continue;
    const nameStart = start + m[0].indexOf('"') + 1;
    if (offset < nameStart) continue;
    const at = start + m[0].length;
    const value = lex.strings.find((s) => s.start === at);
    if (offset <= (value?.end ?? nameStart)) return true;
  }
  return false;
}

/**
 * The Datastar expression at `offset` in a Kotlin file with `modules`: in kotlinx.html's attributes form or an HTML
 * string whatever the file has, and in the string of a DSL call only where the file can call the DSL.
 */
export function kotlinExpressionAtCaret(
  src: string,
  offset: number,
  prefix: string,
  modules: Pick<AnalyzeOptions, "htmlDsl" | "core">,
): ExpressionAtCaret | null {
  const expression = expressionAtCaret(src, offset, true, prefix);
  // Only a DSL call's string needs the DSL: the rest is looked up only where the file cannot call it.
  if (expression === null || modules.htmlDsl !== false) return expression;
  return inAttributesIndex(src, offset) || kotlinHtmlStringAt(src, offset, modules) !== null ? expression : null;
}

/** The call sites whose names are Streamlord's in a file with `modules`: none without core, none of the DSL's without it. */
export function streamlordSites(names: ReadonlySet<string>, modules: Pick<AnalyzeOptions, "htmlDsl" | "core">): ReadonlySet<string> {
  if (modules.core === false) return new Set();
  if (modules.htmlDsl === false) return new Set([...names].filter((n) => !DSL_NAMES.has(n)));
  return names;
}

/** Whether `offset` is in the arguments of a call named as one of the DSL's in a file that cannot call the DSL. */
export function inForeignDslCall(src: string, offset: number, modules: Pick<AnalyzeOptions, "htmlDsl" | "core">): boolean {
  if (modules.htmlDsl !== false) return false;
  return findCallSites(src, DSL_NAMES, lexCached(src).mask).some((s) => s.openParen < offset && offset <= s.closeParen);
}
