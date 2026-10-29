import { catalog, distance, parseAttributeName, type AttributeSpec } from "./catalog.ts";
import { kebab } from "./attributeKeys.ts";
import { attributeDoc, DOCS, expressionError, filterPatternIssues, isIdentPart, jsRegions, quoteAt, Region, validateExpression, type Fix, type Issue } from "./expression.ts";
import { clientKeyName, objectKeys, signalNameProblem, signalPathProblem, type ObjectKey } from "./signals.ts";
import { PROTOTYPE_NAMES } from "./signalNames.ts";

/**
 * Markup checks for the HTML Datastar patches: complete elements, ids where the protocol needs
 * them, and well-formed `data-*` attributes. A deliberately small tokenizer, not a browser.
 */

const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"]);

/** Elements whose content is text, whatever it looks like: a `<div>` in a `<textarea>` or a `<title>` is not a tag. The list of MarkupScan in streamlord-core. */
const RAW_TEXT = new Set(["script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext"]);

/**
 * The elements whose end tag HTML lets the author leave out, each with the start tags that close
 * it. `<p>` is closed by another `<p>` only: the block elements that also close it in a browser
 * are left alone, so that `<p><div></div></p>` reads the way its author meant it.
 */
const OPTIONAL_END: Record<string, readonly string[]> = Object.assign(Object.create(null), {
  li: ["li"],
  p: ["p"],
  dt: ["dt", "dd"],
  dd: ["dt", "dd"],
  option: ["option", "optgroup"],
  optgroup: ["optgroup"],
  td: ["td", "th"],
  th: ["td", "th"],
  tr: ["tr"],
  thead: ["tbody", "tfoot"],
  tbody: ["tbody", "tfoot"],
  tfoot: ["tbody"],
});

/**
 * Where a template engine or a Kotlin template will substitute text, the expression cannot be
 * judged before rendering: Pebble, Twig, Jinja, Handlebars and Mustache (`{{ }}`, `{% %}`),
 * ERB, EJS and JTE comments (`<% %>`), Kotlin, Thymeleaf, FreeMarker, JTE, kte and Velocity
 * (`${ }`, and JTE's `!{ }` for unescaped output), Thymeleaf's `*{ }`, `#{ }` and `@{ }`,
 * FreeMarker's square-bracket syntax (`[# ]`, `[= ]`), JTE's control flow (`@if`, `@for`,
 * ...), Velocity's (`#if`, `#foreach`, ...), and the placeholder for a Kotlin interpolation.
 */
const TEMPLATE_SYNTAX =
  /\{\{|\{%|<%|[$!*#@]\{|\[#|\[=|(?:^|[\s>])@(?:if|elseif|else|endif|for|endfor|template|import|param|raw|endraw)\b|(?:^|[\s>])#(?:if|elseif|else|end|foreach|set|macro|parse|include)\b|__kt__/;

/**
 * Tags that belong to a template engine, not to the document: FreeMarker directives and macro
 * calls (`<#if>`, `</#if>`, `<@row/>`), and its `<#-- -->` comments. Skipped like `<!DOCTYPE>`.
 */
const TEMPLATE_TAG = /^<\/?[#@]/;

/**
 * Lowercases A to Z and nothing else, so the result is as long as the input: `toLowerCase()`
 * turns U+0130 into two characters and moves every offset after it.
 */
export function asciiLowercase(s: string): string {
  return s.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32));
}

/** `indexOf` that ignores the case of A to Z; `needle` is given in lowercase. */
export function indexOfAsciiIgnoreCase(s: string, needle: string, from: number): number {
  return asciiLowercase(s).indexOf(needle, from);
}

/*
 * A named reference with its semicolon, one of the few legacy names the browser also reads
 * without it (unless `=` or a letter or digit follows, as in a URL's `&copy=1`), or a numeric
 * one, which the browser reads with or without its semicolon.
 */
const ENTITY = /&(?:([A-Za-z][A-Za-z0-9]*);|(amp|AMP|lt|LT|gt|GT|quot|QUOT|nbsp)(?![=A-Za-z0-9])|#(\d+);?|#[xX]([0-9A-Fa-f]+);?)/y;

/** The named references that spell a character an expression may hold: the ASCII punctuation, and the whitespace. */
const NAMED_ENTITIES: Record<string, string> = {
  amp: "&", AMP: "&", lt: "<", LT: "<", gt: ">", GT: ">", quot: '"', QUOT: '"', apos: "'",
  nbsp: "\u00A0", Tab: "\t", NewLine: "\n", excl: "!", num: "#", dollar: "$", percnt: "%",
  lpar: "(", rpar: ")", ast: "*", midast: "*", plus: "+", comma: ",", period: ".", sol: "/",
  colon: ":", semi: ";", equals: "=", quest: "?", commat: "@", lsqb: "[", lbrack: "[", bsol: "\\",
  rsqb: "]", rbrack: "]", Hat: "^", lowbar: "_", UnderBar: "_", grave: "`", DiacriticalGrave: "`",
  lcub: "{", lbrace: "{", verbar: "|", vert: "|", VerticalLine: "|", rcub: "}", rbrace: "}",
};

/** Where `</name` begins after `from` as a whole name, `</titlex>` not ending a `title`; -1 when nowhere. */
function rawTextEnd(html: string, name: string, from: number): number {
  for (let at = from; ; ) {
    const close = indexOfAsciiIgnoreCase(html, `</${name}`, at);
    if (close < 0) return -1;
    const next = html[close + 2 + name.length];
    if (next === undefined || next === "/" || next === ">" || "\t\n\f\r ".includes(next)) return close;
    at = close + 1;
  }
}

/** The character a numeric reference names, as the browser reads it: NUL, a surrogate and past U+10FFFF are U+FFFD. */
function codePoint(cp: number): string {
  return cp === 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) ? "\ufffd" : String.fromCodePoint(WINDOWS_1252.get(cp) ?? cp);
}

// The numeric references the HTML standard reads as windows-1252 rather than as C1 controls: `&#128;` is the euro sign.
const WINDOWS_1252: ReadonlyMap<number, number> = new Map([[0x80, 0x20ac], [0x82, 0x201a], [0x83, 0x0192], [0x84, 0x201e], [0x85, 0x2026], [0x86, 0x2020], [0x87, 0x2021], [0x88, 0x02c6], [0x89, 0x2030], [0x8a, 0x0160], [0x8b, 0x2039], [0x8c, 0x0152], [0x8e, 0x017d], [0x91, 0x2018], [0x92, 0x2019], [0x93, 0x201c], [0x94, 0x201d], [0x95, 0x2022], [0x96, 0x2013], [0x97, 0x2014], [0x98, 0x02dc], [0x99, 0x2122], [0x9a, 0x0161], [0x9b, 0x203a], [0x9c, 0x0153], [0x9e, 0x017e], [0x9f, 0x0178]]);

/** The character `&name;` stands for; an own key only, so `&toString;` is no entity. */
function namedEntity(name: string): string | null {
  return Object.hasOwn(NAMED_ENTITIES, name) ? (NAMED_ENTITIES[name] ?? null) : null;
}

/**
 * The attribute value as the browser hands it to Datastar: `&amp;&amp;` is `&&` by then. The
 * named references that spell ASCII punctuation or whitespace, the legacy ones the browser reads
 * without a semicolon, and the numeric ones are decoded; `&copy;` and the rest stay as written,
 * which to the expression they are. `map` has, for every decoded index, the offset it came from:
 * `text.length + 1` entries.
 */
export function decodeEntities(value: string): { text: string; map: number[] } {
  let text = "";
  const map: number[] = [];
  let i = 0;
  while (i < value.length) {
    ENTITY.lastIndex = i;
    const m = value[i] === "&" ? ENTITY.exec(value) : null;
    const decoded = !m ? null : m[1] ? namedEntity(m[1]) : m[2] ? namedEntity(m[2]) : codePoint(m[3] ? parseInt(m[3], 10) : parseInt(m[4] ?? "", 16));
    if (!m || decoded === null) {
      text += value[i];
      map.push(i);
      i++;
      continue;
    }
    for (let k = 0; k < decoded.length; k++) map.push(i);
    text += decoded;
    i += m[0].length;
  }
  map.push(value.length);
  return { text, map };
}

/**
 * Does the value hold template syntax, so that it cannot be judged before rendering? A `${`
 * inside a JavaScript template literal is JavaScript's own and does not count.
 */
export function hasTemplateSyntax(value: string): boolean {
  for (const m of value.matchAll(new RegExp(TEMPLATE_SYNTAX.source, "g"))) {
    if (m[0] !== "${" || quoteAt(value, m.index) !== "`") return true;
  }
  return false;
}

/**
 * Where a template construct that starts at `at` inside a tag ends, or `at` when none starts
 * there: `<% %>`, `{{ }}`, `{% %}`, `{# #}`, a FreeMarker directive, or a JTE or Velocity
 * directive with its parenthesised condition. Its `>` does not end the tag.
 */
function templateConstructEnd(html: string, at: number): number {
  const after = (close: string, from: number) => {
    const idx = html.indexOf(close, from);
    return idx < 0 ? at : idx + close.length;
  };
  if (html.startsWith("<%", at)) return after("%>", at + 2);
  if (html.startsWith("{{", at)) return after("}}", at + 2);
  if (html.startsWith("{%", at)) return after("%}", at + 2);
  if (html.startsWith("{#", at)) return after("#}", at + 2);
  if (html.startsWith("<#", at) || html.startsWith("</#", at) || html.startsWith("<@", at)) return after(">", at + 2);
  const call = /^[@#][A-Za-z]+\s*\(/.exec(html.slice(at, at + 32));
  if (!call) return at;
  let depth = 0;
  for (let i = at + call[0].length - 1; i < html.length; i++) {
    if (html[i] === "(") depth++;
    else if (html[i] === ")" && --depth === 0) return i + 1;
  }
  return at;
}

/** Whether a template construct starts at `at` in a tag's text, where it cuts the attribute name before it short: `${`, `{{`, `<%`. */
function cutByTemplate(tagSource: string, at: number): boolean {
  return tagSource[at] === "{" || templateConstructEnd(tagSource, at) > at;
}

export interface Attribute {
  name: string;
  nameStart: number;
  value: string | null;
  valueStart: number;
  quoted: boolean;
}

export interface Tag {
  name: string;
  start: number;
  end: number;
  closing: boolean;
  selfClosing: boolean;
  attributes: Attribute[];
}

export interface MarkupOptions {
  /** Each top-level element must carry an id (no selector given, mode is outer/replace). */
  requireIds: boolean;
  /** Attribute prefix of the Datastar bundle. */
  prefix: string;
  /** Validate data-* attributes and their expressions. */
  checkAttributes: boolean;
  /** Kebab-case signal names the source declares with `__case.kebab`, which `$my-value` then reaches as written. */
  hyphenatedSignals?: ReadonlySet<string>;
}

const NO_SIGNALS: ReadonlySet<string> = new Set();

/** The white space of HTML, which alone ends an attribute name or value: a no-break space is part of one. */
const HTML_SPACE = "\t\n\f\r ";

const FOREIGN_ROOTS: ReadonlySet<string> = new Set(["svg", "math"]);
const MATHML_TEXT: ReadonlySet<string> = new Set(["mi", "mo", "mn", "ms", "mtext"]);
const HTML_ENCODINGS: ReadonlySet<string> = new Set(["text/html", "application/xhtml+xml"]);

/**
 * Whether `name`, opened in the foreign element `root`, is an HTML integration point, in which the
 * browser reads the content as HTML: a foreignObject, desc or title in an svg, and in a math the
 * text elements and an annotation-xml whose encoding is HTML.
 */
function integrationPoint(root: string, name: string, attributes: Attribute[]): boolean {
  if (root === "svg") return name === "foreignobject" || name === "desc" || name === "title";
  return MATHML_TEXT.has(name) || (name === "annotation-xml" && attributes.some((a) => asciiLowercase(a.name) === "encoding" && HTML_ENCODINGS.has(asciiLowercase(a.value ?? ""))));
}

export function tokenize(html: string): { tags: Tag[]; topLevelText: { start: number; end: number }[] } {
  const tags: Tag[] = [];
  const topLevelText: { start: number; end: number }[] = [];
  let i = 0;
  let depth = 0;
  let textStart = -1;
  // The svg and math elements open, and the HTML integration points open in them: inside an svg
  // or a math a style or a script is no raw text and `/>` closes it, and inside an integration
  // point, such as a foreignObject, the content is HTML again.
  const scopes: string[] = [];
  const flushText = (end: number) => {
    if (textStart >= 0 && depth === 0) {
      const t = html.slice(textStart, end);
      if (t.trim().length > 0) topLevelText.push({ start: textStart + (t.length - t.trimStart().length), end: textStart + t.trimEnd().length });
    }
    textStart = -1;
  };
  while (i < html.length) {
    if (html.startsWith("<!--", i) || html.startsWith("<#--", i)) {
      flushText(i);
      const close = html.indexOf("-->", i + 4);
      i = close < 0 ? html.length : close + 3;
      continue;
    }
    if (html.startsWith("<%--", i)) {
      flushText(i);
      const close = html.indexOf("--%>", i + 4);
      i = close < 0 ? html.length : close + 4;
      continue;
    }
    // Pebble and Twig comments, and Mustache and Handlebars ones. Skipped only when they close,
    // because `{#` also opens a block in other template languages.
    const commentClose = html.startsWith("{#", i) ? "#}" : html.startsWith("{{!--", i) ? "--}}" : html.startsWith("{{!", i) ? "}}" : null;
    const commentEnd = commentClose === null ? -1 : html.indexOf(commentClose, i + 2);
    if (commentClose !== null && commentEnd >= 0) {
      flushText(i);
      i = commentEnd + commentClose.length;
      continue;
    }
    if (html[i] === "<" && (html.startsWith("<!", i) || html.startsWith("<?", i) || TEMPLATE_TAG.test(html.slice(i, i + 3)))) {
      flushText(i);
      const close = html.indexOf(">", i);
      i = close < 0 ? html.length : close + 1;
      continue;
    }
    const tagMatch = /^<(\/?)([A-Za-z][A-Za-z0-9:-]*)/.exec(html.slice(i, i + 64));
    if (html[i] === "<" && tagMatch) {
      flushText(i);
      const closing = tagMatch[1] === "/";
      const name = asciiLowercase(tagMatch[2] ?? "");
      let j = i + tagMatch[0].length;
      const attributes: Attribute[] = [];
      let selfClosing = false;
      while (j < html.length && html[j] !== ">") {
        const c = html[j] ?? "";
        if (HTML_SPACE.includes(c)) {
          j++;
          continue;
        }
        if (c === "/" ) {
          selfClosing = true;
          j++;
          continue;
        }
        const constructEnd = templateConstructEnd(html, j);
        if (constructEnd > j) {
          j = constructEnd;
          continue;
        }
        // A name also ends where a template construct starts: `data-x{{/if}}`, `data-x</#if>`.
        const am = /^([^\t\n\f\r "'<>\/={]+)/.exec(html.slice(j));
        if (!am) {
          j++;
          continue;
        }
        const attrName = am[1] ?? "";
        const nameStart = j;
        j += attrName.length;
        let k = j;
        while (k < html.length && HTML_SPACE.includes(html[k] ?? "")) k++;
        let value: string | null = null;
        let valueStart = -1;
        let quoted = false;
        if (html[k] === "=") {
          k++;
          while (k < html.length && HTML_SPACE.includes(html[k] ?? "")) k++;
          const q = html[k];
          if (q === '"' || q === "'") {
            const close = html.indexOf(q, k + 1);
            valueStart = k + 1;
            value = html.slice(k + 1, close < 0 ? html.length : close);
            quoted = true;
            j = close < 0 ? html.length : close + 1;
          } else {
            const vm = /^[^\t\n\f\r >]*/.exec(html.slice(k));
            valueStart = k;
            value = vm?.[0] ?? "";
            j = k + value.length;
          }
        }
        attributes.push({ name: attrName, nameStart, value, valueStart, quoted });
      }
      const end = Math.min(j + 1, html.length);
      // As the browser reads it, and MarkupScan in core: a `/>` closes no raw-text element,
      // plaintext runs to the end, and only `</name` as a whole name ends the others.
      const foreign = FOREIGN_ROOTS.has(scopes[scopes.length - 1] ?? "");
      const rawText = !closing && RAW_TEXT.has(name) && !foreign;
      tags.push({ name, start: i, end, closing, selfClosing: (selfClosing && !rawText) || VOID.has(name), attributes });
      if (closing) {
        // An svg or a math closes what is open inside it; an integration point closes itself only.
        const at = scopes.lastIndexOf(name);
        if (at >= 0 && (FOREIGN_ROOTS.has(name) || at === scopes.length - 1)) scopes.length = at;
      } else if (!selfClosing && (FOREIGN_ROOTS.has(name) || (foreign && integrationPoint(scopes[scopes.length - 1]!, name, attributes)))) {
        scopes.push(name);
      }
      if (rawText) {
        const closeIdx = name === "plaintext" ? -1 : rawTextEnd(html, name, end);
        i = closeIdx < 0 ? html.length : closeIdx;
        depth++;
        continue;
      }
      if (!closing && !selfClosing && !VOID.has(name)) {
        depth++;
      } else if (closing) {
        depth = Math.max(0, depth - 1);
      }
      i = end;
      continue;
    }
    if (textStart < 0) textStart = i;
    i++;
  }
  flushText(html.length);
  return { tags, topLevelText };
}

export function validateMarkup(html: string, opts: MarkupOptions): Issue[] {
  const issues: Issue[] = [];
  const { tags, topLevelText } = tokenize(html);
  const stack: Tag[] = [];
  const topLevel: Tag[] = [];
  for (const tag of tags) {
    if (tag.closing) {
      const idx = stack.map((t) => t.name).lastIndexOf(tag.name);
      if (idx < 0) {
        issues.push({ start: tag.start, end: tag.end, message: `Stray closing tag </${tag.name}>.`, severity: "error", code: "stray-close" });
      } else {
        for (const unclosed of stack.splice(idx + 1)) {
          if (unclosed.name in OPTIONAL_END) continue;
          issues.push({ start: unclosed.start, end: unclosed.end, message: `<${unclosed.name}> is never closed.`, severity: "error", code: "unclosed" });
        }
        stack.pop();
      }
      continue;
    }
    closeImplied(stack, tag.name);
    if (stack.length === 0) topLevel.push(tag);
    if (!tag.selfClosing) stack.push(tag);
    if (opts.checkAttributes) issues.push(...validateAttributes(tag, opts.prefix, html.slice(tag.start, tag.end), opts.hyphenatedSignals));
  }
  for (const unclosed of stack) {
    if (unclosed.name in OPTIONAL_END) continue;
    issues.push({ start: unclosed.start, end: unclosed.end, message: `<${unclosed.name}> is never closed.`, severity: "error", code: "unclosed" });
  }
  for (const t of topLevelText) {
    if (TEMPLATE_SYNTAX.test(html.slice(t.start, t.end))) continue;
    issues.push({
      start: t.start,
      end: t.end,
      message: "Datastar patches complete elements, not text fragments. Wrap this in an element.",
      severity: "warning",
      code: "top-level-text",
    });
  }
  if (opts.requireIds) {
    for (const tag of topLevel) {
      if (tag.name === "html" || tag.name === "body" || tag.name === "head") continue;
      if (!tag.attributes.some((a) => a.name.toLowerCase() === "id")) {
        const afterName = tag.start + 1 + tag.name.length;
        issues.push({
          start: tag.start,
          end: tag.end,
          message: `<${tag.name}> has no id. Without a selector, Datastar matches top-level elements by id and silently ignores the rest.`,
          severity: "warning",
          code: "missing-id",
          link: DOCS.sse,
          fixes: [{ title: `Add id="${tag.name}"`, start: afterName, end: afterName, text: ` id="${tag.name}"` }],
        });
      }
    }
  }
  return issues;
}

/**
 * A start tag closes the open elements whose end tag was left out: `<li>` closes the `<li>`
 * before it, `<tbody>` closes the cell, the row and the `<thead>` above it. The search stops
 * at the first element that needs its end tag.
 */
function closeImplied(stack: Tag[], opening: string): void {
  for (let i = stack.length - 1; i >= 0; i--) {
    const closers = OPTIONAL_END[stack[i]!.name];
    if (!closers) return;
    if (closers.includes(opening)) stack.length = i;
  }
}

/** Datastar reads `500ms`, `1s`, and a bare number as milliseconds. */
const DURATION = /^\d+(ms|s)?$/;

/** Below this length a Datastar name has too many honest neighbours (test, kind, once) to judge a bare `data-*` at all. */
const LONG_NAME = 6;
const IDENT = /^[A-Za-z_][A-Za-z0-9_-]*$/;

/** A Kotlin template, `$name` or `${...}`, in a name written in Kotlin source. */
const KOTLIN_TEMPLATE = /\$[A-Za-z_{]/;

/**
 * Validate the Datastar attributes on one tag; with its text, a template construct the tokenizer
 * stepped over is seen too. `kotlin` says the names are written in Kotlin source, where `$name`
 * and `${...}` are templates.
 */
export function validateAttributes(tag: Tag, prefix: string, tagSource?: string, hyphenatedSignals: ReadonlySet<string> = NO_SIGNALS, kotlin = false): Issue[] {
  const issues: Issue[] = [];
  for (const attr of tag.attributes) {
    const lower = asciiLowercase(attr.name);
    if (prefix === "data-" && lower.startsWith("data-star-")) {
      issues.push({ start: attr.nameStart, end: attr.nameStart + attr.name.length, message: "This is an aliased Datastar attribute, but the prefix is set to data-. Check streamlord.attributePrefix.", severity: "warning", code: "prefix-mismatch" });
      continue;
    }
    if (!lower.startsWith(prefix)) continue;
    const parsed = parseAttributeName(lower, prefix);
    if (!parsed) continue;
    const nameEnd = attr.nameStart + attr.name.length;
    // data-on:${evt}, data-signals:{{ name }}, attributes["data-bind:$key"]: a template construct
    // cut the name short or stands in it, so the key, its modifiers and the value that went with
    // the name are not known until the page is rendered.
    const cut = tagSource !== undefined && cutByTemplate(tagSource, nameEnd - tag.start);
    const templated = cut || hasTemplateSyntax(attr.name) || (kotlin && KOTLIN_TEMPLATE.test(attr.name));
    if (!parsed.spec && templated) continue;
    if (!parsed.spec) {
      // The rule of the runtime guard. A key or a modifier makes a custom attribute implausible, so
      // any one-letter neighbour is a typo. A bare name (data-test, data-kind, data-effects) is an
      // honest word more often than not: only a swapped letter in a long name is judged.
      const qualified = parsed.key !== null || parsed.modifiers.length > 0;
      const found = catalog.attributes.find((a) => distance(parsed.base, a.name) === 1 && (qualified || (a.name.length >= LONG_NAME && a.name.length === parsed.base.length)));
      const near = found ? { a: found } : undefined;
      const baseEnd = attr.nameStart + prefix.length + parsed.base.length;
      if (near) {
        issues.push({
          start: attr.nameStart,
          end: nameEnd,
          message: `Unknown Datastar attribute ${prefix}${parsed.base}. Did you mean ${prefix}${near.a.name}?`,
          severity: "warning",
          code: "unknown-attribute",
          link: attributeDoc(near.a.name),
          fixes: [{ title: `Change to ${prefix}${near.a.name}`, start: attr.nameStart, end: baseEnd, text: `${prefix}${near.a.name}` }],
        });
        continue;
      }
      // data-on-click, data-bind-value: the pre-1.0 syntax, which 1.0 reads as an attribute it does not have.
      const dash = parsed.base.indexOf("-");
      const keyedSpec = dash > 0 && parsed.key === null ? catalog.attributesByName.get(parsed.base.slice(0, dash)) : undefined;
      if (keyedSpec?.keyed) {
        const rest = parsed.base.slice(dash + 1);
        const init = keyedSpec.name === "on" && rest === "load";
        const fixed = init ? `${prefix}init` : `${prefix}${keyedSpec.name}:${rest}`;
        issues.push({
          start: attr.nameStart,
          end: nameEnd,
          message: `${prefix}${parsed.base} is Datastar's pre-1.0 syntax, which 1.0 ignores. ${init ? `An expression run when the element appears is ${fixed}.` : `The key follows a colon: ${fixed}.`}`,
          severity: "warning",
          code: "unknown-attribute",
          link: attributeDoc(init ? "init" : keyedSpec.name),
          fixes: [{ title: `Change to ${fixed}`, start: attr.nameStart, end: baseEnd, text: fixed }],
        });
      }
      continue;
    }
    const spec = parsed.spec;
    if (parsed.key === "" && !templated) {
      // data-indicator: the client splits the key off at a colon with something after it, so it
      // looks up an attribute named indicator:, finds none and leaves the element alone.
      const colonEnd = attr.nameStart + prefix.length + spec.name.length + 1;
      issues.push({
        start: attr.nameStart,
        end: nameEnd,
        message: `${prefix}${spec.name}: has an empty key, so Datastar reads ${spec.name}: as an attribute it does not have and does nothing with it. ` + emptyKeyAdvice(spec, prefix, !!attr.value, cut),
        severity: "warning",
        code: "unknown-attribute",
        link: attributeDoc(spec.name),
        // Only where the attribute runs without the colon: data-text with no value raises ValueRequired.
        fixes: spec.keyRequired || emptyKeyNeeds(spec, !!attr.value, cut) ? undefined : [{ title: `Change to ${prefix}${spec.name}`, start: colonEnd - 1, end: colonEnd, text: "" }],
      });
      continue;
    }
    if (spec.pro) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} is a Datastar Pro attribute; it needs the Pro bundle.`, severity: "hint", code: "pro-attribute", link: attributeDoc(spec.name) });
    }
    if (spec.keyRequired && !parsed.key && !templated) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} needs a key, e.g. ${spec.forms[0] ?? ""}.`, severity: "error", code: "missing-key", link: attributeDoc(spec.name) });
    }
    if (!spec.keyed && parsed.key !== null && !spec.keys.includes(parsed.key) && !templated) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} does not take a key.`, severity: "error", code: "unexpected-key", link: attributeDoc(spec.name) });
    }
    if (parsed.key !== null && !templated) issues.push(...validateKeyCase(attr, parsed, spec, prefix));
    const named = validateSignalNames(attr, parsed, spec, prefix, templated);
    issues.push(...named);
    if (!templated) issues.push(...validateDottedKey(attr, parsed, spec, prefix));
    const hasKey = !!parsed.key || templated;
    const hasValue = !!attr.value;
    if (spec.requires === "value" && !hasValue && !cut) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} needs a value; without one Datastar raises ValueRequired.`, severity: "error", code: "missing-value", link: attributeDoc(spec.name) });
    }
    if (spec.requires === "exclusive" && hasKey === hasValue) {
      issues.push({
        start: attr.nameStart,
        end: nameEnd,
        message: hasKey
          ? `${prefix}${spec.name} takes the signal as a key or as a value, not both; Datastar raises KeyAndValueProvided.`
          : `${prefix}${spec.name} needs a signal, as a key or as a value; without one Datastar raises KeyOrValueRequired.`,
        severity: "error",
        code: hasKey ? "key-and-value" : "missing-key-or-value",
        link: attributeDoc(spec.name),
      });
    }
    if (spec.onlyOn && !spec.onlyOn.includes(tag.name)) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} only works on <${spec.onlyOn.join(">, <")}>.`, severity: "warning", code: "wrong-element" });
    }
    // data-on:my__event: the event is the key before the first __, so the name the author may have
    // meant whole is cut there, and recased as __case says.
    const listensFor = spec.name === "on" && parsed.key ? `, so it listens for the event ${clientCase(parsed.key, parsed.modifiers, "kebab")}` : "";
    for (const mod of templated ? [] : parsed.modifiers) {
      const mend = attr.nameStart + mod.offset + mod.text.length;
      // An empty modifier has no text to underline: the `__` that opens it is marked.
      const mstart = mod.text.length === 0 ? mend - 2 : mend - mod.text.length;
      const mspec = spec.modifiers.find((m) => m.name === mod.name);
      if (!mspec) {
        // Datastar ignores a modifier it does not know and runs the handler anyway, so these are
        // warnings. __debounce_150ms: an argument joined with an underscore, which Datastar reads
        // as one unknown name. Arguments follow a dot.
        const dotted = dottedArguments(mod.name, spec);
        if (dotted) {
          issues.push({
            start: mstart,
            end: mend,
            message: `Unknown modifier __${mod.name} on ${prefix}${spec.name}, which Datastar ignores${listensFor}. A modifier's arguments follow a dot: __${dotted}.`,
            severity: "warning",
            code: "unknown-modifier",
            link: attributeDoc(spec.name),
            fixes: [{ title: `Change to __${dotted}`, start: mstart, end: mstart + mod.name.length, text: dotted }],
          });
          continue;
        }
        const near = spec.modifiers.map((m) => m.name).find((n) => distance(n, mod.name) <= 2);
        issues.push({
          start: mstart,
          end: mend,
          message: `Unknown modifier __${mod.name} on ${prefix}${spec.name}, which Datastar ignores${listensFor}.` + (near ? ` Did you mean __${near}?` : ""),
          severity: "warning",
          code: "unknown-modifier",
          link: attributeDoc(spec.name),
          fixes: near ? [{ title: `Change to __${near}`, start: mstart, end: mstart + mod.name.length, text: near }] : undefined,
        });
        continue;
      }
      const present = parsed.modifiers.filter((m) => m !== mod).map((m) => m.name);
      issues.push(...validateModifierArgs(mspec, mod.args, mstart, mend, prefix + spec.name, spec, present, listensFor).map((i) => ({ ...i, link: attributeDoc(spec.name) })));
    }
    if (attr.value !== null && spec.valueKind === "expression" && attr.value.trim().length > 0 && !hasTemplateSyntax(attr.value)) {
      const decoded = decodeEntities(attr.value);
      const at = (i: number) => decoded.map[Math.min(Math.max(i, 0), decoded.map.length - 1)] ?? 0;
      const source = (start: number, end: number) => {
        const from = at(start);
        return { start: attr.valueStart + from, end: attr.valueStart + (end > start ? Math.max(at(end), from + 1) : from) };
      };
      for (const issue of validateExpression(decoded.text, undefined, hyphenatedSignals, spec)) {
        const fixes = issue.fixes?.map((f) => ({ ...f, ...source(f.start, f.end) }));
        issues.push({ ...issue, ...source(issue.start, issue.end), ...(fixes ? { fixes } : {}) });
      }
    }
    if (attr.value !== null && spec.valueKind === "filter" && attr.value.trim().length > 0 && !hasTemplateSyntax(attr.value)) issues.push(...validateFilter(attr, spec, prefix));
    if (spec.name === "computed" && parsed.key === null && attr.value !== null && attr.value.trim().length > 0 && !hasTemplateSyntax(attr.value)) issues.push(...validateComputedObject(attr, prefix));
    if (attr.value !== null && spec.valueKind === "signal" && attr.value.trim().length > 0 && !hasTemplateSyntax(attr.value) && !named.some((i) => i.start >= attr.valueStart) && !/^[A-Za-z_$][A-Za-z0-9_.$-]*$/.test(attr.value.trim())) {
      issues.push({ start: attr.valueStart, end: attr.valueStart + attr.value.length, message: `${prefix}${spec.name} takes a signal name, not an expression.`, severity: "warning", code: "signal-name-expected" });
    }
    if (spec.valueKind === "none" && attr.value !== null && attr.value.trim().length > 0) {
      issues.push({ start: attr.valueStart, end: attr.valueStart + attr.value.length, message: `${prefix}${spec.name} takes no value.`, severity: "warning", code: "unexpected-value" });
    }
  }
  issues.push(...indicatorWithoutAction(tag, prefix, tagSource));
  return issues;
}

/**
 * What `data-x:` with an empty key lacks besides the colon dropped: null when the attribute runs
 * without it as it stands, else the error the client raises.
 */
function emptyKeyNeeds(spec: AttributeSpec, hasValue: boolean, cut: boolean): "ValueRequired" | "KeyOrValueRequired" | null {
  if (hasValue) return null;
  if (spec.requires === "value" && !cut) return "ValueRequired";
  if (spec.requires === "exclusive") return "KeyOrValueRequired";
  return null;
}

function emptyKeyAdvice(spec: AttributeSpec, prefix: string, hasValue: boolean, cut: boolean): string {
  if (spec.keyRequired) return `The key follows the colon, e.g. ${spec.forms[0] ?? ""}.`;
  switch (emptyKeyNeeds(spec, hasValue, cut)) {
    case "ValueRequired":
      return `Without the colon it is ${prefix}${spec.name}, which also needs a value; without one Datastar raises ValueRequired.`;
    case "KeyOrValueRequired":
      return `It needs a signal, as the key after the colon, e.g. ${spec.forms[0] ?? ""}, or as the value without the colon, e.g. ${spec.forms[1] ?? ""}; without one Datastar raises KeyOrValueRequired.`;
    default:
      return `Without the colon it is ${prefix}${spec.name}.`;
  }
}

/** The cases the 1.0.4 client has a function for (utils/text.ts); any other value leaves the key as it is. */
const camelCase = (s: string): string => s.replace(/-[a-z]/g, (x) => x[1]!.toUpperCase());
const CASE_FNS = new Map<string, (s: string) => string>([
  ["camel", camelCase],
  ["snake", (s) => s.replace(/-/g, "_")],
  ["pascal", (s) => (s.length > 0 ? s[0]!.toUpperCase() + camelCase(s.slice(1)) : s)],
]);

/** The key as the client recases it: each value of the last `__case` in turn, or the attribute's default without one. */
function clientCase(key: string, modifiers: { name: string; args: string[] }[], defaultCase: string): string {
  const mod = modifiers.filter((m) => m.name === "case").at(-1);
  let out = key;
  for (const c of mod ? new Set(mod.args) : [defaultCase]) out = CASE_FNS.get(c)?.(out) || out;
  return out;
}

/**
 * The key to write, and the `__case` to add, so that a key the author typed with capitals comes
 * back as that name: `foo-bar` for a signal (Datastar reads it as camelCase), `widget-loaded__case.camel`
 * for an event or class (kebab by default), `aria-label` where the key is used as it is. An
 * explicit `__case` is kept: the key is still kebab-cased, because the browser lowercases it
 * either way. A signal key is read as camelCase, so `fooBar-baz` comes back as `fooBarBaz`, the
 * name the DSL's `signal()` reads from it too; [clientKeyName] says what. Null for an event or
 * class name that mixes capitals with a hyphen before a lower-case letter, `fooBar-baz` or `A-b`,
 * since the client's camel and pascal turn that hyphen into a capital too (`modifyCasing` in
 * text.ts) and no key gives it back; [wireKeyReads] says what it would read. Shared by the HTML
 * warning and the Kotlin wire hint.
 */
export function wireKey(key: string, keyCase: AttributeSpec["keyCase"], explicitCase: string | null): string | null {
  const base = kebab(key);
  if (explicitCase) return `${base}__case.${explicitCase}`;
  const wanted = /^[A-Z]/.test(key) ? "pascal" : "camel";
  if (keyCase === "raw") return base;
  if (keyCase === "kebab" && CASE_FNS.get(wanted)!(base) !== key) return null;
  const defaultCase = keyCase === "camel" ? "camel" : "kebab";
  return wanted === defaultCase ? base : `${base}__case.${wanted}`;
}

/** What the client reads from the kebab-case key of `key` under the case its capitals ask for. */
export function wireKeyReads(key: string): string {
  return CASE_FNS.get(/^[A-Z]/.test(key) ? "pascal" : "camel")!(kebab(key));
}

/**
 * The browser lowercases attribute names, so a capital letter in a key never reaches Datastar:
 * `data-signals:fooBar` declares `$foobar`, `data-on:widgetLoaded` listens to `widgetloaded`.
 * The fix writes the key as [wireKey] says, so that the name the author typed is the name the
 * browser ends up with.
 */
/** What a key is to Datastar: the noun the messages and the hover use, from `keyCase` in the catalog. */
export function keyKind(spec: AttributeSpec): "signal" | "event" | "class" | "raw" {
  if (spec.keyCase === "camel") return "signal";
  if (spec.keyCase === "kebab") return spec.name === "on" ? "event" : "class";
  return "raw";
}

/**
 * How Datastar reads a key written as [wire]: "the signal $fooBar", "the event widgetLoaded",
 * "the class isOpen", or, for a raw key, the key itself. One wording for the HTML warning, the
 * Kotlin wire hint and the hover note.
 */
export function keyReading(spec: AttributeSpec, name: string, wire: string): string {
  switch (keyKind(spec)) {
    case "signal":
      return `the signal $${name}`;
    case "event":
      return `the event ${name}`;
    case "class":
      return `the class ${name}`;
    default:
      return `the ${spec.name === "style" ? "property" : "attribute"} ${wire.split("__")[0]}`;
  }
}

/**
 * What a raw key with a capital can do, said once, where it applies: an attribute keeps its name in
 * the object form (attr.ts sets the key as written); a style property has no form that keeps one,
 * since the object form writes the key kebab-case (style.ts:50 in 1.0.4).
 */
export function rawKeyNote(spec: AttributeSpec, prefix: string, key: string): string {
  if (keyKind(spec) !== "raw" || spec.valueKind !== "expression") return "";
  if (spec.name === "style") {
    // The object form writes a key kebab-case too (style.ts:50 in 1.0.4), so no form keeps a capital.
    return key.startsWith("--")
      ? ` No form keeps a capital in a custom property: name it in kebab-case, here and in the CSS, as ${kebab(key)}.`
      : ` A CSS property is written in kebab-case: ${prefix}${spec.name}:${kebab(key)}.`;
  }
  return ` For a name that really has capitals, such as SVG's viewBox, use the object form: ${prefix}${spec.name}="{${key}: ...}".`;
}

/**
 * `data-on:click.prevent`: a modifier written after a dot, which Datastar reads as part of the
 * event name, so the listener waits for an event that never comes. An error, unlike an unknown
 * modifier, which Datastar ignores. Only for events; a dot in a signal key is a path.
 */
function validateDottedKey(attr: Attribute, parsed: { key: string | null }, spec: AttributeSpec, prefix: string): Issue[] {
  const key = parsed.key;
  if (!key || !key.includes(".") || keyKind(spec) !== "event") return [];
  const pieces = key.split(".");
  const mods = pieces.slice(1);
  if (!pieces[0] || mods.some((m) => !spec.modifiers.some((x) => x.name === m))) return [];
  const keyStart = attr.nameStart + attr.name.indexOf(":") + 1;
  const fixed = pieces[0] + mods.map((m) => `__${m}`).join("");
  return [{
    start: keyStart,
    end: keyStart + key.length,
    message: `Datastar reads ${key} as ${keyReading(spec, key, key)}, dots and all, so the expression never runs on ${pieces[0]}. A modifier follows two underscores: ${prefix}${spec.name}:${fixed}.`,
    severity: "error",
    code: "unknown-modifier",
    link: attributeDoc(spec.name),
    fixes: [{ title: `Change to ${fixed}`, start: keyStart, end: keyStart + key.length, text: fixed }],
  }];
}

/**
 * The signal an attribute declares, when the client cannot hold it or no `$` reference reaches it:
 * the key as the client reads it (`clientKeyName`), the name in the value of `data-bind`,
 * `data-ref` and `data-indicator`, and the keys of the object form. The DSL holds a name to the same
 * rules (`SignalNames` in streamlord-core, `Casing.signalName` and `Casing.heldKey` in
 * streamlord-html).
 */
function validateSignalNames(attr: Attribute, parsed: { key: string | null }, spec: AttributeSpec, prefix: string, cut: boolean): Issue[] {
  if (keyKind(spec) !== "signal") return [];
  const out: Issue[] = [];
  const name = prefix + spec.name;
  const colon = attr.name.indexOf(":");
  // data-signals:__proto__ has no key to the client: the `__` starts the modifiers, and no plugin is named `signals:`.
  if (parsed.key && colon >= 0 && !cut) {
    const written = attr.name.slice(colon + 1);
    const held = clientKeyName(asciiLowercase(written));
    const keyStart = attr.nameStart + colon + 1;
    const keyEnd = keyStart + (written.split("__")[0] ?? "").length;
    // data-signals:constructor="{a: 1}" puts an object under the last part, which the client then writes into the built-in one.
    const objectUnder = spec.name === "signals" && mayGiveObject(decodeEntities(attr.value ?? "").text);
    const path = signalPathProblem(held, objectUnder);
    const reach = path ? null : signalNameProblem(held);
    if (path || reach) {
      out.push({
        start: keyStart,
        end: keyEnd,
        message: `Datastar's client reads this key as the signal ${held}, which ${path ?? reach}.`,
        severity: path ? "error" : "warning",
        code: "signal-name",
        link: attributeDoc(spec.name),
      });
    }
  }
  const value = attr.value;
  if (value === null || value.trim().length === 0 || hasTemplateSyntax(value)) return out;
  const decoded = decodeEntities(value);
  const source = (from: number, to: number) => ({ start: attr.valueStart + (decoded.map[from] ?? 0), end: attr.valueStart + (decoded.map[to] ?? value.length) });
  if (spec.valueKind === "signal" && /^[\p{L}\p{Nd}_$.-]+$/u.test(decoded.text)) {
    const held = decoded.text;
    const path = signalPathProblem(held);
    const reach = path ? null : signalNameProblem(held);
    if (path || reach) {
      const fixes: Fix[] = !path && held.startsWith("$") && signalNameProblem(held.slice(1)) === null ? [{ title: `Change to ${held.slice(1)}`, ...source(0, 1), text: "" }] : [];
      out.push({
        ...source(0, decoded.text.length),
        message: `${name} declares the signal ${held}, as written, which ${path ?? reach}.`,
        severity: path ? "error" : "warning",
        code: "signal-name",
        link: attributeDoc(spec.name),
        ...(fixes.length ? { fixes } : {}),
      });
    }
  }
  const text = decoded.text;
  const open = text.indexOf("{");
  if (parsed.key === null && (spec.name === "signals" || spec.name === "computed") && open >= 0 && text.slice(0, open).trim() === "" && text.trimEnd().endsWith("}")) {
    const body = text.slice(open + 1, text.lastIndexOf("}"));
    for (const k of objectKeys(body)) {
      const at = source(open + 1 + k.start, open + 1 + k.end);
      if (k.key === "__proto__") {
        out.push({ ...at, message: `In an object literal __proto__ sets the object's prototype, so Datastar gets no signal from it.`, severity: "warning", code: "signal-name", link: attributeDoc(spec.name) });
      } else if (k.object && PROTOTYPE_NAMES.has(k.key)) {
        out.push({
          ...at,
          message: `Every object already has ${k.key}. The client holds the signal, but the next request writes the object under it into the built-in one and sends the page's signals without it.`,
          severity: "error",
          code: "signal-name",
          link: attributeDoc(spec.name),
        });
      } else {
        const parts = keyParts(k);
        const reach = signalNameProblem(k.key, parts);
        if (reach) out.push({ ...at, message: `This declares the signal ${parts.join(".")}, as written, which ${reach}.`, severity: "warning", code: "signal-name", link: attributeDoc(spec.name) });
      }
    }
  }
  return out;
}

/**
 * The path of a key of the object form, part by part as the keys are written and read as the
 * client gets them, for the bracket form: `{'a.b': {'c d': 1}}` is `$['a.b']['c d']`, `{'a\\b': 1}`
 * is the key a\b.
 */
function keyParts(k: ObjectKey): string[] {
  return k.parts.map((p) => p.replace(/\\(.)/gs, "$1"));
}

/**
 * Whether the JavaScript expression `value` may give an object literal it holds: one that opens the
 * value or stands where the value of a group, a conditional or a `||`, `&&`, `??` or `,` comes
 * from, as in `({x: 1})`, `$a ? {x: 1} : 0` and `null || {x: 1}`. An object passed to a call, put
 * in an array or returned by a function does not count, and a value with `=>` in it is a function.
 * Shared with the analysis module (`mayGiveObject` in Markup.kt).
 */
export function mayGiveObject(value: string): boolean {
  const regions = jsRegions(value);
  // One entry per open bracket: whether it is a group, through which the value comes.
  const open: boolean[] = [];
  // The last character of code outside a string, a template or a comment, `x` after one of those.
  let last = "";
  let beforeLast = "";
  for (let i = 0; i < value.length; i++) {
    const r = regions[i];
    if (r === Region.COMMENT) continue;
    const c = value[i]!;
    if (r !== Region.CODE) {
      beforeLast = last;
      last = "x";
      continue;
    }
    if (/\s/.test(c)) continue;
    const through = open.every((g) => g);
    if (through && c === "=" && value[i + 1] === ">") return false;
    // At the start, after `(`, `?` (and `??`), `:`, `,`, `||` or `&&`.
    const opensOperand = last === "" || "(?:,".includes(last) || ((last === "|" || last === "&") && beforeLast === last);
    if (c === "{" && through && opensOperand) return true;
    if (c === "(" || c === "[" || c === "{") open.push(c === "(" && opensOperand);
    else if (c === ")" || c === "]" || c === "}") open.pop();
    beforeLast = last;
    last = c;
  }
  return false;
}

/** A number literal, whole. */
const NUMBER = /^(?:0[xX][\da-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|(?:\d[\d_]*(?:\.[\d_]*)?|\.\d[\d_]*)(?:[eE][+-]?\d+)?)n?$/;
const NO_FUNCTION_WORDS = /^(?:true|false|null|undefined|NaN|Infinity)$/;

/**
 * Whether the JavaScript expression `value` is sure to give no function: a literal (a number, a
 * string, a template, a regular expression, an array with such an item), a unary operation, or
 * operands joined by an arithmetic, bitwise or comparison operator. A name, a call, `new`, or
 * anything with `?`, `||`, `&&`, `??`, `=`, `=>` or `,` at its top level may give one, and is not
 * judged. Shared with the analysis module (`certainlyNoFunction` in Markup.kt).
 */
export function certainlyNoFunction(value: string): boolean {
  const v = value.trim();
  if (v.length === 0) return false;
  if (NUMBER.test(v) || NO_FUNCTION_WORDS.test(v)) return true;
  const regions = jsRegions(v);
  let depth = 0;
  let binary = false;
  let unary = false;
  // Whether what came last at the top level ends an operand, and whether anything came yet.
  let operand = false;
  let started = false;
  // The literals and brackets that open an operand at the top level, and whether a name, an
  // operator or a member access stands there as well.
  let groups = 0;
  let code = false;
  let lastRegion: number = Region.CODE;
  for (let i = 0; i < v.length; i++) {
    const c = v[i]!;
    const r = regions[i]!;
    if (r === Region.TEMPLATE && c === "$" && v[i + 1] === "{" && v[i - 1] !== "\\") {
      depth++;
      i++;
      continue;
    }
    if (r === Region.TEMPLATE && c === "}" && depth > 0 && regions[i - 1] === Region.CODE) {
      depth--;
      continue;
    }
    if (r === Region.CODE && (c === "(" || c === "[" || c === "{")) {
      if (depth === 0) {
        if (operand) code = true;
        else groups++;
        started = true;
        lastRegion = Region.CODE;
      }
      depth++;
      continue;
    }
    if (r === Region.CODE && (c === ")" || c === "]" || c === "}")) {
      depth = Math.max(depth - 1, 0);
      if (depth === 0) operand = true;
      continue;
    }
    if (depth > 0 || r === Region.COMMENT) continue;
    if (r !== Region.CODE) {
      if (r !== lastRegion) {
        if (operand) code = true;
        else groups++;
      }
      lastRegion = r;
      operand = true;
      started = true;
      continue;
    }
    lastRegion = Region.CODE;
    if (/\s/.test(c)) continue;
    if (c === "?" || c === "," || (c === "=" && v[i + 1] === ">") || ((c === "|" || c === "&") && v[i + 1] === c)) return false;
    if (c === "=" && v[i + 1] !== "=" && !"=!<>".includes(v[i - 1] ?? "x")) return false;
    if (isIdentPart(c)) {
      let j = i + 1;
      while (j < v.length && isIdentPart(v[j])) j++;
      const word = v.slice(i, j);
      i = j - 1;
      if (operand && (word === "in" || word === "instanceof")) {
        binary = true;
        operand = false;
      } else if (!started && (word === "typeof" || word === "void" || word === "delete")) {
        unary = true;
      } else {
        operand = true;
        code = true;
      }
      started = true;
      continue;
    }
    if (c === ".") {
      code = true;
      continue;
    }
    if (!"+-*/%<>&|^!=~".includes(c)) return false;
    if (operand) binary = true;
    else if (!started) unary = true;
    operand = false;
    code = true;
    started = true;
  }
  if (depth !== 0) return false;
  if (binary || unary) return true;
  if (groups !== 1 || code) return false;
  // The client takes the functions out of an array as it does out of an object (`updateLeaves`
  // in paths.ts), so an array gives no function only where one of its items is sure to give none.
  if (v[0] === "[") return arrayItems(v, regions).some(certainlyNoFunction);
  return regions[0] !== Region.CODE;
}

/** The items of the array literal `v`, split at the commas of its top level. */
function arrayItems(v: string, regions: Uint8Array): string[] {
  const items: string[] = [];
  let depth = 0;
  let from = 1;
  for (let i = 0; i < v.length; i++) {
    if (regions[i] !== Region.CODE) continue;
    const c = v[i];
    if (c === "(" || c === "[" || c === "{") depth++;
    else if (c === ")" || c === "]" || c === "}") {
      if (--depth === 0) items.push(v.slice(from, i));
    } else if (c === "," && depth === 1) {
      items.push(v.slice(from, i));
      from = i + 1;
    }
  }
  return items;
}

/** Where the value that starts at `from` in an object's `body` ends: at the `,` after it or the bracket that closes the object. */
function valueEnd(body: string, regions: Uint8Array, from: number): number {
  let depth = 0;
  for (let i = from; i < body.length; i++) {
    if (regions[i] !== Region.CODE) continue;
    const c = body[i];
    if (c === "(" || c === "[" || c === "{") depth++;
    else if (c === ")" || c === "]" || c === "}") {
      if (depth === 0) return i;
      depth--;
    } else if (c === "," && depth === 0) return i;
  }
  return body.length;
}

/**
 * `data-computed` without a key takes an object of functions (computed.ts in 1.0.4): any other value
 * declares nothing, and a value under a key that is no function raises ComputedExpectedFunction.
 * Only a value sure to be no function is judged: `fns`, `{total: $a ? f : g}` and
 * `{total: new Function(...)}` may well give one.
 */
function validateComputedObject(attr: Attribute, prefix: string): Issue[] {
  const value = attr.value ?? "";
  const decoded = decodeEntities(value);
  const text = decoded.text;
  const open = text.indexOf("{");
  const source = (from: number, to: number) => ({ start: attr.valueStart + (decoded.map[from] ?? 0), end: attr.valueStart + (decoded.map[to] ?? value.length) });
  const name = `${prefix}computed`;
  if (open < 0 || text.slice(0, open).trim() !== "") {
    if (!certainlyNoFunction(text)) return [];
    return [{
      ...source(0, text.length),
      message: `Without a key, ${name} takes an object of functions, such as {total: () => $a + 1}: Datastar declares no signal from this value, and raises ComputedExpectedFunction when it is a string. Name the signal in the key instead: ${name}:total.`,
      severity: "warning",
      code: "computed-value",
      link: attributeDoc("computed"),
    }];
  }
  const body = text.slice(open + 1, text.lastIndexOf("}") < open ? text.length : text.lastIndexOf("}"));
  const regions = jsRegions(body);
  const out: Issue[] = [];
  for (const k of objectKeys(body)) {
    if (k.object || !certainlyNoFunction(body.slice(k.valueStart, valueEnd(body, regions, k.valueStart)))) continue;
    out.push({
      ...source(open + 1 + k.start, open + 1 + k.end),
      message: `Without a key, each value of ${name} must be a function, such as ${k.key}: () => ...; Datastar raises ComputedExpectedFunction for ${k.key}.`,
      severity: "error",
      code: "computed-value",
      link: attributeDoc("computed"),
    });
  }
  return out;
}

/**
 * The value of `data-json-signals` and `data-on-signal-patch-filter`: JSON, or else JavaScript the
 * client evaluates as `return (value)` without rewriting signals (`jsStrToObject` in text.ts), whose
 * `include` and `exclude` become regular expressions (`filtered` in signals.ts).
 */
function validateFilter(attr: Attribute, spec: AttributeSpec, prefix: string): Issue[] {
  const value = attr.value ?? "";
  const decoded = decodeEntities(value);
  const text = decoded.text;
  const source = (from: number, to: number) => ({ start: attr.valueStart + (decoded.map[from] ?? 0), end: attr.valueStart + (decoded.map[Math.max(to, from + 1)] ?? value.length) });
  const name = prefix + spec.name;
  const out: Issue[] = [];
  let json = false;
  try {
    JSON.parse(text);
    json = true;
  } catch {
    const err = expressionError(text);
    if (err) {
      return [{ ...source(err.pos, err.raisedAt), message: `Datastar filter: ${err.message}`, severity: "error", code: "expression-syntax", link: attributeDoc(spec.name) }];
    }
    const regions = jsRegions(text);
    for (const m of text.matchAll(/\$[A-Za-z_][\w$]*/g)) {
      if (regions[m.index] !== Region.CODE || /[\w$]/.test(text[m.index - 1] ?? "")) continue;
      out.push({
        ...source(m.index, m.index + m[0].length),
        message: `Datastar evaluates the value of ${name} without signals, so ${m[0]} is a JavaScript variable here, which does not exist.`,
        severity: "warning",
        code: "signal-not-read",
        link: attributeDoc(spec.name),
      });
    }
  }
  for (const issue of filterPatternIssues(text, jsRegions(text), 0, text.length, json, attributeDoc(spec.name))) {
    out.push({ ...issue, ...source(issue.start, issue.end), ...(issue.fixes ? { fixes: issue.fixes.map((f) => ({ ...f, ...source(f.start, f.end) })) } : {}) });
  }
  return out;
}

function validateKeyCase(attr: Attribute, parsed: { key: string | null; base: string; modifiers: { name: string; args: string[] }[] }, spec: AttributeSpec, prefix: string): Issue[] {
  const colon = attr.name.indexOf(":");
  if (colon < 0 || !spec.keyCase) return [];
  const key = attr.name.slice(colon + 1).split("__")[0] ?? "";
  if (!/[A-Z]/.test(key)) return [];
  // HTML written in capitals (DATA-ON:CLICK) is not camelCase: lowercased, the key is what the author meant.
  if (key.length > 1 && !/[a-z]/.test(key)) return [];
  const keyStart = attr.nameStart + colon + 1;
  const name = prefix + spec.name;
  const existing = parsed.modifiers.find((m) => m.name === "case");
  // With an explicit __case already there, only the key itself changes, when that case gives the name back.
  const fixed = existing ? (clientCase(kebab(key), [existing], "camel") === key ? kebab(key) : null) : wireKey(key, spec.keyCase, null);
  const reaches = keyReading(spec, key.toLowerCase(), key.toLowerCase());
  if (fixed === null) {
    const noKey = existing
      ? `No key with __case.${existing.args.join(".")} gives ${key} back: the browser lowercases the key, and from ${kebab(key)}__case.${existing.args.join(".")} Datastar reads ${clientCase(kebab(key), [existing], "camel")}.`
      : `No key gives ${key} back: with __case.camel or __case.pascal Datastar turns every hyphen before a lower-case letter into a capital and reads ${wireKeyReads(key)}.`;
    return [{
      start: keyStart,
      end: keyStart + key.length,
      message: `The browser lowercases attribute names, so this reaches Datastar as ${reaches}, not ${key}. ${noKey} Write it in one case.`,
      severity: "warning",
      code: "key-case",
      link: attributeDoc(spec.name),
    }];
  }
  if (keyKind(spec) === "raw") {
    // A kebab-case key would set view-box, not viewBox: for an attribute the object form keeps the
    // name, for a style property no form does, since the object form writes it kebab-case.
    const value = attr.value;
    const quote = value === null || !value.includes('"') ? '"' : !value.includes("'") ? "'" : null;
    const fixes: Fix[] = [];
    if (spec.name === "attr" && value !== null && quote !== null) {
      const end = attr.valueStart + value.length + (attr.quoted ? 1 : 0);
      fixes.push({ title: "Use the object form", start: attr.nameStart, end, text: `${name}=${quote}{${key}: ${value}}${quote}` });
    }
    if (spec.name === "style" && !key.startsWith("--")) fixes.push({ title: `Change to ${kebab(key)}`, start: keyStart, end: keyStart + key.length, text: kebab(key) });
    return [{
      start: keyStart,
      end: keyStart + key.length,
      message: `The browser lowercases attribute names, so this sets ${key.toLowerCase()}, not ${key}.${rawKeyNote(spec, prefix, key)}`,
      severity: "warning",
      code: "key-case",
      link: attributeDoc(spec.name),
      fixes,
    }];
  }
  return [{
    start: keyStart,
    end: keyStart + key.length,
    message: `The browser lowercases attribute names, so this reaches Datastar as ${reaches}, not ${key}. Write ${name}:${fixed}${existing ? "__" + existing.name + (existing.args.length ? "." + existing.args.join(".") : "") : ""}: keys are kebab-case${keyKind(spec) === "signal" ? ", and Datastar reads a kebab-case signal key as camelCase" : ""}.`,
    severity: "warning",
    code: "key-case",
    link: attributeDoc(spec.name),
    fixes: [{ title: `Change to ${fixed}`, start: keyStart, end: keyStart + key.length, text: fixed }],
  }];
}

/** The actions the catalog lists as sending a request. */
const BACKEND_ACTIONS = catalog.actions.filter((a) => a.kind === "backend").map((a) => a.name);

/** A call to one of them, written as Datastar parses it: no space before the parenthesis. */
const BACKEND_ACTION = new RegExp("@(?:" + BACKEND_ACTIONS.map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|") + ")\\(");

/** An attribute name as a browser parses it, without template syntax or a spread in it. */
const PLAIN_ATTRIBUTE_NAME = /^[A-Za-z_:][A-Za-z0-9_:.@-]*$/;

/** A modifier name with arguments run into it by underscores, and nothing else. */
const MODIFIER_WORD = /^[a-z][a-z0-9]*(?:_[A-Za-z0-9]+)+$/;

/**
 * `data-indicator` tracks the requests its own element sends: the client matches the fetch event's
 * element against it. On an element whose attributes send none, it never turns on. Elements with
 * template syntax in an attribute are left alone, since what they send is not known until rendered.
 */
function indicatorWithoutAction(tag: Tag, prefix: string, tagSource?: string): Issue[] {
  const indicator = tag.attributes.find((a) => {
    const lower = asciiLowercase(a.name);
    // data-indicator: with an empty key is no indicator at all, and says so on its own.
    return lower === `${prefix}indicator` || lower.startsWith(`${prefix}indicator__`) || (lower.startsWith(`${prefix}indicator:`) && !!parseAttributeName(lower, prefix)?.key);
  });
  if (!indicator) return [];
  // With both a key and a value, or neither, Datastar raises before the indicator exists, and the
  // error on the same name says so.
  if (!!parseAttributeName(asciiLowercase(indicator.name), prefix)?.key === !!indicator.value) return [];
  // Template syntax in a value, or attributes spread into the tag by name, leave what it sends unknown until rendered.
  if ((tagSource !== undefined && hasTemplateSyntax(tagSource)) || tag.attributes.some((a) => (a.value !== null && hasTemplateSyntax(a.value)) || !PLAIN_ATTRIBUTE_NAME.test(a.name))) return [];
  const sends = tag.attributes.some((a) => asciiLowercase(a.name).startsWith(prefix) && a.value !== null && BACKEND_ACTION.test(decodeEntities(a.value).text));
  if (sends) return [];
  return [{
    start: indicator.nameStart,
    end: indicator.nameStart + indicator.name.length,
    message: `${prefix}indicator tracks the requests this element sends, and nothing on it sends one. Put it on the element whose attribute calls ${BACKEND_ACTIONS.map((n) => "@" + n).join(", ")}.`,
    severity: "warning",
    code: "indicator-without-action",
    link: attributeDoc("indicator"),
  }];
}

/**
 * `debounce_150ms` read as `debounce.150ms`, when the part before the first underscore is a modifier
 * that takes arguments, the rest is plain words, and those words are arguments it takes; null
 * otherwise, so the fix never offers something that fails in turn.
 */
function dottedArguments(name: string, spec: AttributeSpec): string | null {
  const underscore = name.indexOf("_");
  if (underscore <= 0 || underscore === name.length - 1 || !MODIFIER_WORD.test(name)) return null;
  const head = name.slice(0, underscore);
  const modifier = spec.modifiers.find((m) => m.name === head);
  if (!modifier || modifier.type === "flag") return null;
  const args = name.slice(underscore + 1).split("_");
  if (validateModifierArgs(modifier, args, 0, 0, "", spec).length > 0) return null;
  return head + "." + args.join(".");
}

/** The modifiers whose value the client reads from the first tag, empty or not: `tagFirst` and `tagToMs` in tags.ts. */
const FIRST_TAG_READ: ReadonlySet<string> = new Set(["duration", "int", "ident"]);

function validateModifierArgs(spec: AttributeSpec["modifiers"][number], written: string[], start: number, end: number, attrName: string, attribute: AttributeSpec, present: string[] = [], listensFor = ""): Issue[] {
  const issues: Issue[] = [];
  // An empty tag after the value, as in __threshold.50., or the one of __once., is nothing the client reads.
  const args = written.filter((a) => a !== "");
  const fail = (message: string, fixes?: Issue["fixes"]) => issues.push({ start, end, message, severity: "error", code: "modifier-args", ...(fixes ? { fixes } : {}) });
  // Arguments the client never reads: the modifier still does what it does, so these are warnings.
  const ignored = (message: string, fixes?: Issue["fixes"]) => issues.push({ start, end, message, severity: "warning", code: "modifier-args", ...(fixes ? { fixes } : {}) });
  const unknownFlag = (f: string) => {
    ignored(`Unknown flag .${f} for __${spec.name}, which Datastar ignores. Allowed: ${(spec.flags ?? []).map((x) => "." + x).join(", ") || "none"}.`);
  };
  const overruled = spec.ignoredWith?.find((m) => present.includes(m));
  if (overruled) {
    // __half__threshold.50: onIntersect.ts takes __half and never reads __threshold.
    ignored(`Datastar ignores __${spec.name} with __${overruled}.`);
    return issues;
  }
  if (FIRST_TAG_READ.has(spec.type) && written[0] === "" && args.length > 0) {
    // __debounce..500ms: the client reads the empty tag as the value and never comes to .500ms.
    // Without it, the modifier is the fix only if it holds up itself: __debounce..leading has no duration.
    const fixed = "." + args.join(".");
    const holds = validateModifierArgs(spec, args, start, end, attrName, attribute, present, listensFor).every((i) => i.severity !== "error");
    fail(
      `Datastar reads the first argument of __${spec.name}, which is empty here, and not .${args[0]}.${holds ? ` Write __${spec.name}${fixed}.` : ""}`,
      holds ? [{ title: `Change to __${spec.name}${fixed}`, start: start + spec.name.length, end, text: fixed }] : undefined,
    );
    return issues;
  }
  switch (spec.type) {
    case "flag": {
      // __prevent.stop: a second modifier joined with a dot, read as an argument.
      const dropped = `__${spec.name} takes no arguments; Datastar ignores ${args.map((a) => "." + a).join("")}.`;
      const siblings = attribute.modifiers.filter((m) => m.type === "flag" && m.name !== spec.name).map((m) => m.name);
      if (args.length > 0 && args.every((a) => siblings.includes(a))) {
        // Each once, and not one the attribute already has.
        const joined = [...new Set(args)].filter((a) => !present.includes(a)).map((a) => "__" + a).join("");
        const at = start + spec.name.length;
        if (joined.length === 0) {
          ignored(dropped, [{ title: `Change to __${spec.name}`, start: at, end, text: "" }]);
        } else {
          ignored(`${dropped} Each modifier starts with two underscores: __${spec.name}${joined}.`, [
            { title: `Change to __${spec.name}${joined}`, start: at, end, text: joined },
          ]);
        }
      } else if (args.length > 0) {
        ignored(dropped);
      }
      break;
    }
    case "duration": {
      const [d, ...flags] = args;
      const later = flags.findIndex((f) => DURATION.test(f));
      const known = (a: string) => (spec.flags ?? []).includes(a);
      if (d && !DURATION.test(d) && known(d) && later >= 0) {
        // __debounce.leading.500ms: the client reads the first argument as the duration.
        const ordered = [flags[later]!, d, ...flags.filter((_, k) => k !== later)];
        const fixed = `__${spec.name}.${ordered.join(".")}`;
        const unknown = flags.filter((f, k) => k !== later && !known(f));
        // Only with every other argument a flag it knows is the reordered modifier the one meant:
        // __debounce.leading.1.5s would become __debounce.1.leading.5s.
        fail(
          `Datastar reads the first argument of __${spec.name} as its duration, so with .${d} first it reads none and does not wait. The duration comes first${unknown.length === 0 ? `: ${fixed}` : ""}.`,
          unknown.length === 0 ? [{ title: `Change to ${fixed}`, start: start + spec.name.length, end, text: "." + ordered.join(".") }] : undefined,
        );
        for (const f of unknown) if (!DURATION.test(f)) unknownFlag(f);
        break;
      }
      if (d && /^\d+$/.test(d) && flags[0] !== undefined && /^\d+(ms|s)?$/.test(flags[0])) {
        // __debounce.1.5s: the dot ends the duration, which the client reads as 1 ms.
        const unit = flags[0].endsWith("ms") ? "ms" : flags[0].endsWith("s") ? "s" : "ms";
        const ms = Number(`${d}.${flags[0].replace(/(ms|s)$/, "")}`) * (unit === "s" ? 1000 : 1);
        const whole = Number.isInteger(ms) ? `${ms}ms` : null;
        // With a flag it does not know after it, the whole duration would fail in turn, so it is no fix.
        const fixable = whole !== null && flags.slice(1).every(known);
        fail(
          `Datastar reads __${spec.name}.${d}.${flags[0]} as ${d} ms: a dot separates arguments, so a duration has no fraction.${whole ? ` Write __${spec.name}.${whole}.` : ""}`,
          fixable ? [{ title: `Change to __${spec.name}.${whole}`, start: start + spec.name.length, end: start + spec.name.length + 2 + d.length + flags[0].length, text: `.${whole}` }] : undefined,
        );
        for (const f of flags.slice(1)) if (!known(f)) unknownFlag(f);
        break;
      }
      if (!d || !DURATION.test(d)) {
        fail(`__${spec.name} needs a duration such as __${spec.name}.500ms or __${spec.name}.1s.`);
        // A dot with nothing after it, __debounce., is no duration either, and the fix takes its place.
        if (!d && args.length <= 1) issues[issues.length - 1]!.fixes = [{ title: `Add .500ms`, start: start + spec.name.length, end, text: ".500ms" }];
      }
      for (const f of flags) if (!known(f)) unknownFlag(f);
      break;
    }
    case "enum": {
      const [v, ...rest] = args;
      const values = spec.values ?? [];
      const allowed = values.map((x) => "." + x).join(", ");
      if (!v || !values.includes(v)) {
        // The client leaves the key as it is for a case it has no function for. Where the default
        // is kebab, which it has none for either, that is the key without the modifier.
        if (spec.name === "case" && attribute.keyCase === "kebab") {
          const also = rest.filter((r) => !CASE_FNS.has(r)).map((r) => "." + r).join("");
          ignored(`${v ? `Unknown value .${v} for __case, which Datastar ignores${also ? `, as it does ${also}` : ""}` : "Datastar ignores __case without a value"}${listensFor}. Allowed: ${allowed}.`);
        } else {
          fail(`__${spec.name} needs one of: ${allowed}.`);
        }
      }
      // __case.camel.foo: the client applies each value it has a function for and skips the rest.
      const applied = spec.name === "case" ? rest.filter((r) => CASE_FNS.has(r)) : rest;
      if (applied.length > 0) fail(`__${spec.name} takes a single value.`);
      else if (rest.length > 0 && v && values.includes(v)) ignored(`__${spec.name} takes a single value; Datastar ignores ${rest.map((r) => "." + r).join("")}${listensFor}.`);
      break;
    }
    case "int": {
      const [v, ...rest] = args;
      // The client reads Number of the first tag and clamps it (onIntersect.ts); NaN fails in the observer.
      const n = Number(asciiLowercase(v ?? ""));
      const { min, max } = spec;
      if (Number.isNaN(n)) {
        fail(`__${spec.name} needs an integer${min !== undefined ? ` between ${min} and ${max}` : ""}, e.g. __${spec.name}.50.`);
      } else if (v === undefined) {
        ignored(`Datastar reads __${spec.name} without a value as 0.`);
      } else if ((min !== undefined && n < min) || (max !== undefined && n > max)) {
        ignored(`Datastar clamps __${spec.name}.${v} to ${min !== undefined && n < min ? min : max}.`);
      }
      if (rest.length > 0) fail(`__${spec.name} takes a single value.`);
      break;
    }
    case "ident": {
      const [v, ...rest] = args;
      if (!v || !IDENT.test(v)) fail(`__${spec.name} needs a name, e.g. __${spec.name}.value.`);
      if (rest.length > 0) fail(`__${spec.name} takes a single name.`);
      break;
    }
    case "idents":
      if (args.length === 0 || args.some((a) => !IDENT.test(a))) fail(`__${spec.name} needs one or more names, e.g. __${spec.name}.input.blur.`);
      break;
  }
  void attrName;
  return issues;
}
