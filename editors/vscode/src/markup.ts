import { catalog, distance, parseAttributeName, type AttributeSpec } from "./catalog.ts";
import { attributeDoc, DOCS, quoteAt, validateExpression, type Issue } from "./expression.ts";

/**
 * Markup checks for the HTML Datastar patches: complete elements, ids where the protocol needs
 * them, and well-formed `data-*` attributes. A deliberately small tokenizer, not a browser.
 */

const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"]);

/** Elements whose content is text, whatever it looks like: a `<div>` in a `<textarea>` or a `<title>` is not a tag. */
const RAW_TEXT = new Set(["script", "style", "textarea", "title"]);

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

const ENTITY = /&(?:(amp|lt|gt|quot|apos)|#(\d{1,7})|#[xX]([0-9A-Fa-f]{1,6}));/y;
const NAMED_ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

function codePoint(cp: number): string | null {
  return cp === 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) ? null : String.fromCodePoint(cp);
}

/**
 * The attribute value as the browser hands it to Datastar: `&amp;&amp;` is `&&` by then. The
 * named references a value needs (amp, lt, gt, quot, apos) and the numeric ones are decoded.
 * `map` has, for every decoded index, the offset it came from: `text.length + 1` entries.
 */
export function decodeEntities(value: string): { text: string; map: number[] } {
  let text = "";
  const map: number[] = [];
  let i = 0;
  while (i < value.length) {
    ENTITY.lastIndex = i;
    const m = value[i] === "&" ? ENTITY.exec(value) : null;
    const decoded = !m ? null : m[1] ? (NAMED_ENTITIES[m[1]] ?? null) : codePoint(m[2] ? parseInt(m[2], 10) : parseInt(m[3] ?? "", 16));
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
}

export function tokenize(html: string): { tags: Tag[]; topLevelText: { start: number; end: number }[] } {
  const tags: Tag[] = [];
  const topLevelText: { start: number; end: number }[] = [];
  let i = 0;
  let depth = 0;
  let textStart = -1;
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
        if (/\s/.test(c)) {
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
        const am = /^([^\s"'<>\/={]+)/.exec(html.slice(j));
        if (!am) {
          j++;
          continue;
        }
        const attrName = am[1] ?? "";
        const nameStart = j;
        j += attrName.length;
        let k = j;
        while (k < html.length && /\s/.test(html[k] ?? "")) k++;
        let value: string | null = null;
        let valueStart = -1;
        let quoted = false;
        if (html[k] === "=") {
          k++;
          while (k < html.length && /\s/.test(html[k] ?? "")) k++;
          const q = html[k];
          if (q === '"' || q === "'") {
            const close = html.indexOf(q, k + 1);
            valueStart = k + 1;
            value = html.slice(k + 1, close < 0 ? html.length : close);
            quoted = true;
            j = close < 0 ? html.length : close + 1;
          } else {
            const vm = /^[^\s>]*/.exec(html.slice(k));
            valueStart = k;
            value = vm?.[0] ?? "";
            j = k + value.length;
          }
        }
        attributes.push({ name: attrName, nameStart, value, valueStart, quoted });
      }
      const end = Math.min(j + 1, html.length);
      tags.push({ name, start: i, end, closing, selfClosing: selfClosing || VOID.has(name), attributes });
      if (!closing && !selfClosing && !VOID.has(name)) {
        if (RAW_TEXT.has(name)) {
          const closeIdx = indexOfAsciiIgnoreCase(html, `</${name}`, end);
          i = closeIdx < 0 ? html.length : closeIdx;
          depth++;
          continue;
        }
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
    if (opts.checkAttributes) issues.push(...validateAttributes(tag, opts.prefix, html.slice(tag.start, tag.end)));
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

/** Validate the Datastar attributes on one tag; with its text, a template construct the tokenizer stepped over is seen too. */
export function validateAttributes(tag: Tag, prefix: string, tagSource?: string): Issue[] {
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
    if (!parsed.spec) {
      // The rule of the runtime guard. A key or a modifier makes a custom attribute implausible, so
      // any one-letter neighbour is a typo. A bare name (data-test, data-kind, data-effects) is an
      // honest word more often than not: only a swapped letter in a long name is judged.
      const qualified = parsed.key !== null || parsed.modifiers.length > 0;
      const found = catalog.attributes.find((a) => distance(parsed.base, a.name) === 1 && (qualified || (a.name.length >= LONG_NAME && a.name.length === parsed.base.length)));
      const near = found ? { a: found } : undefined;
      if (near) {
        const baseEnd = attr.nameStart + prefix.length + parsed.base.length;
        issues.push({
          start: attr.nameStart,
          end: nameEnd,
          message: `Unknown Datastar attribute ${prefix}${parsed.base}. Did you mean ${prefix}${near.a.name}?`,
          severity: "warning",
          code: "unknown-attribute",
          link: attributeDoc(near.a.name),
          fixes: [{ title: `Change to ${prefix}${near.a.name}`, start: attr.nameStart, end: baseEnd, text: `${prefix}${near.a.name}` }],
        });
      }
      continue;
    }
    const spec = parsed.spec;
    if (spec.pro) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} is a Datastar Pro attribute; it needs the Pro bundle.`, severity: "hint", code: "pro-attribute", link: attributeDoc(spec.name) });
    }
    if (spec.keyRequired && !parsed.key) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} needs a key, e.g. ${spec.forms[0] ?? ""}.`, severity: "error", code: "missing-key", link: attributeDoc(spec.name) });
    }
    if (!spec.keyed && parsed.key !== null) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} does not take a key.`, severity: "error", code: "unexpected-key", link: attributeDoc(spec.name) });
    }
    if (parsed.key !== null) issues.push(...validateKeyCase(attr, parsed, spec, prefix));
    const hasKey = !!parsed.key;
    const hasValue = !!attr.value;
    if (spec.requires === "value" && !hasValue) {
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
    for (const mod of parsed.modifiers) {
      const mend = attr.nameStart + mod.offset + mod.text.length;
      // An empty modifier has no text to underline: the `__` that opens it is marked.
      const mstart = mod.text.length === 0 ? mend - 2 : mend - mod.text.length;
      const mspec = spec.modifiers.find((m) => m.name === mod.name);
      if (!mspec) {
        // __debounce_150ms: an argument joined with an underscore, which Datastar reads as one
        // unknown name. Arguments follow a dot.
        const dotted = dottedArguments(mod.name, spec);
        if (dotted) {
          issues.push({
            start: mstart,
            end: mend,
            message: `Unknown modifier __${mod.name} on ${prefix}${spec.name}. A modifier's arguments follow a dot: __${dotted}.`,
            severity: "error",
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
          message: near ? `Unknown modifier __${mod.name} on ${prefix}${spec.name}. Did you mean __${near}?` : `Unknown modifier __${mod.name} on ${prefix}${spec.name}.`,
          severity: "error",
          code: "unknown-modifier",
          link: attributeDoc(spec.name),
          fixes: near ? [{ title: `Change to __${near}`, start: mstart, end: mstart + mod.name.length, text: near }] : undefined,
        });
        continue;
      }
      issues.push(...validateModifierArgs(mspec, mod.args, mstart, mend, prefix + spec.name, spec).map((i) => ({ ...i, link: attributeDoc(spec.name) })));
    }
    if (attr.value !== null && spec.valueKind === "expression" && attr.value.trim().length > 0 && !hasTemplateSyntax(attr.value)) {
      const decoded = decodeEntities(attr.value);
      const at = (i: number) => decoded.map[Math.min(Math.max(i, 0), decoded.map.length - 1)] ?? 0;
      const source = (start: number, end: number) => {
        const from = at(start);
        return { start: attr.valueStart + from, end: attr.valueStart + (end > start ? Math.max(at(end), from + 1) : from) };
      };
      for (const issue of validateExpression(decoded.text)) {
        const fixes = issue.fixes?.map((f) => ({ ...f, ...source(f.start, f.end) }));
        issues.push({ ...issue, ...source(issue.start, issue.end), ...(fixes ? { fixes } : {}) });
      }
    }
    if (attr.value !== null && spec.valueKind === "signal" && attr.value.trim().length > 0 && !hasTemplateSyntax(attr.value) && !/^[A-Za-z_$][A-Za-z0-9_.$-]*$/.test(attr.value.trim())) {
      issues.push({ start: attr.valueStart, end: attr.valueStart + attr.value.length, message: `${prefix}${spec.name} takes a signal name, not an expression.`, severity: "warning", code: "signal-name-expected" });
    }
    if (spec.valueKind === "none" && attr.value !== null && attr.value.trim().length > 0) {
      issues.push({ start: attr.valueStart, end: attr.valueStart + attr.value.length, message: `${prefix}${spec.name} takes no value.`, severity: "warning", code: "unexpected-value" });
    }
  }
  issues.push(...indicatorWithoutAction(tag, prefix, tagSource));
  return issues;
}

/** `fooBar` -> `foo-bar`, one hyphen per capital, which Datastar's camel conversion turns back into `fooBar`. */
export function kebab(name: string): string {
  const out = name.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase());
  // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
  return /^[A-Z]/.test(name) ? out.replace(/^-/, "") : out;
}

/**
 * The key to write, and the `__case` to add, so that a key the author typed with capitals comes
 * back as that name: `foo-bar` for a signal (Datastar reads it as camelCase), `widget-loaded__case.camel`
 * for an event or class (kebab by default), `aria-label` where the key is used as it is. An
 * explicit `__case` is kept: the key is still kebab-cased, because the browser lowercases it
 * either way. Shared by the HTML warning and the Kotlin wire hint.
 */
export function wireKey(key: string, keyCase: AttributeSpec["keyCase"], explicitCase: string | null): string {
  const base = kebab(key);
  if (explicitCase) return `${base}__case.${explicitCase}`;
  const wanted = /^[A-Z]/.test(key) ? "pascal" : "camel";
  if (keyCase === "raw") return base;
  const defaultCase = keyCase === "camel" ? "camel" : "kebab";
  return wanted === defaultCase ? base : `${base}__case.${wanted}`;
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

/** For a raw key the only way to keep a capital is the object form; said once, where it applies. */
export function rawKeyNote(spec: AttributeSpec, prefix: string, key: string): string {
  return keyKind(spec) === "raw" && spec.valueKind === "expression"
    ? ` For a name that really has capitals, such as SVG's viewBox, use the object form: ${prefix}${spec.name}="{${key}: ...}".`
    : "";
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
  // With an explicit __case already there, only the key itself changes.
  const fixed = existing ? kebab(key) : wireKey(key, spec.keyCase, null);
  const reaches = keyReading(spec, key.toLowerCase(), key.toLowerCase());
  return [{
    start: keyStart,
    end: keyStart + key.length,
    message: `The browser lowercases attribute names, so this reaches Datastar as ${reaches}, not ${key}. Write ${name}:${fixed}${existing ? "__" + existing.name + (existing.args.length ? "." + existing.args.join(".") : "") : ""}: keys are kebab-case${keyKind(spec) === "signal" ? ", and Datastar reads a kebab-case signal key as camelCase" : ""}.${rawKeyNote(spec, prefix, key)}`,
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
    return lower === `${prefix}indicator` || lower.startsWith(`${prefix}indicator:`) || lower.startsWith(`${prefix}indicator__`);
  });
  if (!indicator) return [];
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
 * that takes arguments and the rest is plain words; null otherwise.
 */
function dottedArguments(name: string, spec: AttributeSpec): string | null {
  const underscore = name.indexOf("_");
  if (underscore <= 0 || underscore === name.length - 1 || !MODIFIER_WORD.test(name)) return null;
  const head = name.slice(0, underscore);
  const modifier = spec.modifiers.find((m) => m.name === head);
  if (!modifier || modifier.type === "flag") return null;
  return head + "." + name.slice(underscore + 1).replaceAll("_", ".");
}

function validateModifierArgs(spec: AttributeSpec["modifiers"][number], args: string[], start: number, end: number, attrName: string, attribute: AttributeSpec): Issue[] {
  const issues: Issue[] = [];
  const fail = (message: string, fixes?: Issue["fixes"]) => issues.push({ start, end, message, severity: "error", code: "modifier-args", ...(fixes ? { fixes } : {}) });
  switch (spec.type) {
    case "flag": {
      // __prevent.stop: a second modifier joined with a dot, read as an argument.
      const siblings = attribute.modifiers.filter((m) => m.type === "flag" && m.name !== spec.name).map((m) => m.name);
      if (args.length > 0 && args.every((a) => siblings.includes(a))) {
        const joined = args.map((a) => "__" + a).join("");
        const at = start + spec.name.length;
        fail(`__${spec.name} takes no arguments. Each modifier starts with two underscores: __${spec.name}${joined}.`, [
          { title: `Change to __${spec.name}${joined}`, start: at, end, text: joined },
        ]);
      } else if (args.length > 0) {
        fail(`__${spec.name} takes no arguments.`);
      }
      break;
    }
    case "duration": {
      const [d, ...flags] = args;
      if (!d || !DURATION.test(d)) {
        fail(`__${spec.name} needs a duration such as __${spec.name}.500ms or __${spec.name}.1s.`);
        if (!d) issues[issues.length - 1]!.fixes = [{ title: `Add .500ms`, start: start + spec.name.length, end: start + spec.name.length, text: ".500ms" }];
      }
      for (const f of flags) if (!(spec.flags ?? []).includes(f)) fail(`Unknown flag .${f} for __${spec.name}. Allowed: ${(spec.flags ?? []).map((x) => "." + x).join(", ") || "none"}.`);
      break;
    }
    case "enum": {
      const [v, ...rest] = args;
      if (!v || !(spec.values ?? []).includes(v)) fail(`__${spec.name} needs one of: ${(spec.values ?? []).map((x) => "." + x).join(", ")}.`);
      if (rest.length > 0) fail(`__${spec.name} takes a single value.`);
      break;
    }
    case "int": {
      const [v, ...rest] = args;
      const n = v !== undefined ? Number(v) : NaN;
      if (!Number.isInteger(n) || (spec.min !== undefined && n < spec.min) || (spec.max !== undefined && n > spec.max)) {
        fail(`__${spec.name} needs an integer${spec.min !== undefined ? ` between ${spec.min} and ${spec.max}` : ""}, e.g. __${spec.name}.50.`);
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
