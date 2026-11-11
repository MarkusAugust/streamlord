/**
 * Where a caret stands, for completion, hover and signature help: the twin of CompletionContext.kt
 * in streamlord-analysis, both held to test/fixtures/twins/completion-contexts.json and
 * action-calls.json.
 */
import { catalog } from "./catalog.ts";
import { jsRegions, Region } from "./expression.ts";
import { landingAsParsed } from "./holes.ts";
import { decodeEntities, withTemplateTagNames } from "./markup.ts";

/** The attribute value a caret stands in: the attribute's name as written, where its value starts, and the value before the caret, decoded. */
export interface ValueAtCaret {
  attribute: string;
  valueStart: number;
  before: string;
}

/** The character the caret is given in the markup: one that neither closes a value nor starts a reference. */
const CARET = "x";

/**
 * The quoted attribute value `offset` stands in, in `markup` read as the browser reads it, or null.
 * The reading is `landingAsParsed`, the browser's as it parses a patch and the one `tokenize` gives
 * the checks, so a value over several lines, in single quotes or in a script's text is found, and the
 * text of a `<textarea>` or a `<title>` is text, though `interpolate`, which takes the strictest
 * reading, still judges a value there. With `templates`, as in a template file, a value in a
 * template comment is not found, as the checks skip it.
 */
export function valueAtCaret(markup: string, offset: number, prefix: string, templates = true): ValueAtCaret | null {
  if (offset < 0 || offset > markup.length) return null;
  // A hole is at least one character wide, so the caret gets one of its own.
  const walked = withTemplateTagNames(markup);
  const probe = walked.slice(0, offset) + CARET + walked.slice(offset);
  const landing = landingAsParsed(probe, { start: offset, end: offset + 1 }, [prefix], templates);
  if (!landing.attribute) return null;
  if (landing.valueStart < 0 || landing.valueStart > offset) return null;
  return { attribute: landing.attribute, valueStart: landing.valueStart, before: decodeEntities(markup.slice(landing.valueStart, offset)).text };
}

/**
 * The markup of the tag `offset` stands in, from its `<` up to `offset`, read as `valueAtCaret`
 * reads it, or null in text, a comment, raw text or a quoted value. The tag may begin lines above,
 * with its attributes on lines of their own. tagBeforeCaret in CompletionContext.kt.
 */
export function tagBeforeCaret(markup: string, offset: number, prefix: string, templates = true): string | null {
  if (offset < 0 || offset > markup.length) return null;
  const walked = withTemplateTagNames(markup);
  const probe = walked.slice(0, offset) + CARET + walked.slice(offset);
  const landing = landingAsParsed(probe, { start: offset, end: offset + 1 }, [prefix], templates);
  if (landing.position !== "STRUCTURE" || landing.attribute !== null || landing.tagStart < 0 || landing.tagStart > offset) return null;
  return markup.slice(landing.tagStart, offset);
}

/** The action call a caret stands in: the action's name, which argument, and the key of the options object when the caret is on one. */
export interface ActionCall {
  action: string;
  argument: number;
  option: OptionKeyAt | null;
}

/** A key of a backend action's options object at the caret: what is typed of it, and the keys the object already holds. */
export interface OptionKeyAt {
  typed: string;
  written: string[];
}

const ACTION_OPEN = /@([A-Za-z_$][\w$]*)\s*\(/y;
const WRITTEN_KEY = /(?:^|,)\s*([A-Za-z_$][\w$]*)\s*:/g;
const TYPED_KEY = /^\s*([A-Za-z_$][\w$]*)?$/;
const BACKEND_ACTIONS = new Set(catalog.backendActions);
const CLOSERS: Record<string, string> = { "(": ")", "[": "]", "{": "}" };

/** A bracket open at the caret, with the action whose argument list it is and the key text of an options object. */
interface OpenBracket {
  close: string;
  action: string | null;
  options: boolean;
  argument: number;
  /** The code of an options object at its own depth, without strings or nested brackets: where its keys are read. */
  keys: string;
}

/** The innermost `@name(` still open at `offset` in the expression `text`, read from the text before the caret, or null. */
export function actionCallAt(text: string, offset: number): ActionCall | null {
  const source = text.slice(0, Math.max(0, Math.min(offset, text.length)));
  // A space after the caret is code exactly when the caret stands in code.
  const regions = jsRegions(`${source} `);
  const stack: OpenBracket[] = [];
  let i = 0;
  while (i < source.length) {
    const c = source[i] ?? "";
    if (regions[i] !== Region.CODE) {
      i++;
      continue;
    }
    const top = stack[stack.length - 1];
    ACTION_OPEN.lastIndex = i;
    const open = c === "@" ? ACTION_OPEN.exec(source) : null;
    if (open) {
      stack.push({ close: ")", action: open[1] ?? "", options: false, argument: 0, keys: "" });
      i = ACTION_OPEN.lastIndex;
      continue;
    }
    if (c === "(" || c === "[" || c === "{") {
      const options = c === "{" && top?.action != null && BACKEND_ACTIONS.has(top.action) && top.argument === 1;
      stack.push({ close: CLOSERS[c] ?? "", action: null, options, argument: 0, keys: "" });
    } else if (c === ")" || c === "]" || c === "}") {
      if (top?.close === c) stack.pop();
    } else if (c === "," && top?.action != null) {
      top.argument++;
    } else if (top?.options) {
      top.keys += c;
    }
    i++;
  }
  let index = -1;
  for (let k = stack.length - 1; k >= 0; k--) {
    if (stack[k]?.action != null) {
      index = k;
      break;
    }
  }
  const call = stack[index];
  if (!call?.action) return null;
  const top = stack[stack.length - 1];
  let option: OptionKeyAt | null = null;
  if (top?.options && regions[source.length] === Region.CODE) {
    const typed = TYPED_KEY.exec(top.keys.slice(top.keys.lastIndexOf(",") + 1));
    if (typed) option = { typed: typed[1] ?? "", written: [...top.keys.matchAll(WRITTEN_KEY)].map((m) => m[1] ?? "") };
  }
  return { action: call.action, argument: call.argument, option };
}
