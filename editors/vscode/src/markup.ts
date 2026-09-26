import { catalog, distance, parseAttributeName, type AttributeSpec } from "./catalog.ts";
import { validateExpression, type Issue } from "./expression.ts";

/**
 * Markup checks for the HTML Datastar patches: complete elements, ids where the protocol needs
 * them, and well-formed `data-*` attributes. A deliberately small tokenizer, not a browser.
 */

const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"]);
const RAW_TEXT = new Set(["script", "style"]);
const TEMPLATE_SYNTAX = /\{\{|\{%|<%|\$\{|__kt__/;

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
    if (html.startsWith("<!--", i)) {
      flushText(i);
      const close = html.indexOf("-->", i + 4);
      i = close < 0 ? html.length : close + 3;
      continue;
    }
    if (html[i] === "<" && (html.startsWith("<!", i) || html.startsWith("<?", i))) {
      flushText(i);
      const close = html.indexOf(">", i);
      i = close < 0 ? html.length : close + 1;
      continue;
    }
    const tagMatch = /^<(\/?)([A-Za-z][A-Za-z0-9:-]*)/.exec(html.slice(i, i + 64));
    if (html[i] === "<" && tagMatch) {
      flushText(i);
      const closing = tagMatch[1] === "/";
      const name = (tagMatch[2] ?? "").toLowerCase();
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
        const am = /^([^\s"'>\/=]+)/.exec(html.slice(j));
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
          const closeIdx = html.toLowerCase().indexOf(`</${name}`, end);
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
          issues.push({ start: unclosed.start, end: unclosed.end, message: `<${unclosed.name}> is never closed.`, severity: "error", code: "unclosed" });
        }
        stack.pop();
      }
      continue;
    }
    if (stack.length === 0) topLevel.push(tag);
    if (!tag.selfClosing) stack.push(tag);
    if (opts.checkAttributes) issues.push(...validateAttributes(tag, opts.prefix));
  }
  for (const unclosed of stack) {
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
        issues.push({
          start: tag.start,
          end: tag.end,
          message: `<${tag.name}> has no id. Without a selector, Datastar matches top-level elements by id and silently ignores the rest.`,
          severity: "warning",
          code: "missing-id",
        });
      }
    }
  }
  return issues;
}

const DURATION = /^\d+(ms|s)$/;
const IDENT = /^[A-Za-z_][A-Za-z0-9_-]*$/;

/** Validate the Datastar attributes on one tag. */
export function validateAttributes(tag: Tag, prefix: string): Issue[] {
  const issues: Issue[] = [];
  for (const attr of tag.attributes) {
    const lower = attr.name.toLowerCase();
    if (prefix === "data-" && lower.startsWith("data-star-")) {
      issues.push({ start: attr.nameStart, end: attr.nameStart + attr.name.length, message: "This is an aliased Datastar attribute, but the prefix is set to data-. Check streamlord.attributePrefix.", severity: "warning", code: "prefix-mismatch" });
      continue;
    }
    if (!lower.startsWith(prefix)) continue;
    const parsed = parseAttributeName(lower, prefix);
    if (!parsed) continue;
    const nameEnd = attr.nameStart + attr.name.length;
    if (!parsed.spec) {
      const near = catalog.attributes
        .map((a) => ({ a, d: distance(parsed.base, a.name) }))
        .filter((x) => x.d > 0 && x.d <= 2)
        .sort((x, y) => x.d - y.d)[0];
      if (near) {
        issues.push({ start: attr.nameStart, end: nameEnd, message: `Unknown Datastar attribute ${prefix}${parsed.base}. Did you mean ${prefix}${near.a.name}?`, severity: "warning", code: "unknown-attribute" });
      }
      continue;
    }
    const spec = parsed.spec;
    if (spec.pro) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} is a Datastar Pro attribute; it needs the Pro bundle.`, severity: "hint", code: "pro-attribute" });
    }
    if (spec.keyRequired && !parsed.key) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} needs a key, e.g. ${spec.forms[0] ?? ""}.`, severity: "error", code: "missing-key" });
    }
    if (!spec.keyed && parsed.key !== null) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} does not take a key.`, severity: "error", code: "unexpected-key" });
    }
    if (spec.onlyOn && !spec.onlyOn.includes(tag.name)) {
      issues.push({ start: attr.nameStart, end: nameEnd, message: `${prefix}${spec.name} only works on <${spec.onlyOn.join(">, <")}>.`, severity: "warning", code: "wrong-element" });
    }
    for (const mod of parsed.modifiers) {
      const mstart = attr.nameStart + mod.offset;
      const mend = mstart + mod.text.length;
      const mspec = spec.modifiers.find((m) => m.name === mod.name);
      if (!mspec) {
        const near = spec.modifiers.map((m) => m.name).find((n) => distance(n, mod.name) <= 2);
        issues.push({ start: mstart, end: mend, message: near ? `Unknown modifier __${mod.name} on ${prefix}${spec.name}. Did you mean __${near}?` : `Unknown modifier __${mod.name} on ${prefix}${spec.name}.`, severity: "error", code: "unknown-modifier" });
        continue;
      }
      issues.push(...validateModifierArgs(mspec, mod.args, mstart, mend, prefix + spec.name));
    }
    if (attr.value !== null && spec.valueKind === "expression" && attr.value.trim().length > 0 && !TEMPLATE_SYNTAX.test(attr.value)) {
      for (const issue of validateExpression(attr.value)) {
        issues.push({ ...issue, start: attr.valueStart + issue.start, end: attr.valueStart + issue.end });
      }
    }
    if (attr.value !== null && spec.valueKind === "signal" && attr.value.trim().length > 0 && !TEMPLATE_SYNTAX.test(attr.value) && !/^[A-Za-z_$][A-Za-z0-9_.$-]*$/.test(attr.value.trim())) {
      issues.push({ start: attr.valueStart, end: attr.valueStart + attr.value.length, message: `${prefix}${spec.name} takes a signal name, not an expression.`, severity: "warning", code: "signal-name-expected" });
    }
    if (spec.valueKind === "none" && attr.value !== null && attr.value.trim().length > 0) {
      issues.push({ start: attr.valueStart, end: attr.valueStart + attr.value.length, message: `${prefix}${spec.name} takes no value.`, severity: "warning", code: "unexpected-value" });
    }
  }
  return issues;
}

function validateModifierArgs(spec: AttributeSpec["modifiers"][number], args: string[], start: number, end: number, attrName: string): Issue[] {
  const issues: Issue[] = [];
  const fail = (message: string) => issues.push({ start, end, message, severity: "error", code: "modifier-args" });
  switch (spec.type) {
    case "flag":
      if (args.length > 0) fail(`__${spec.name} takes no arguments.`);
      break;
    case "duration": {
      const [d, ...flags] = args;
      if (!d || !DURATION.test(d)) fail(`__${spec.name} needs a duration such as __${spec.name}.500ms or __${spec.name}.1s.`);
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
