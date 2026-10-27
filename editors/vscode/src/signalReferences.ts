import { ALL_SITE_NAMES, ATTRIBUTES_INDEX, attributesIndexValue, lexCached, markupString } from "./analyze.ts";
import { catalog, distance, parseAttributeName } from "./catalog.ts";
import { clientSignalReads, DOCS, type Issue } from "./expression.ts";
import { PLACEHOLDER, toSource, type KotlinString } from "./kotlinStrings.ts";
import { ANY_SIGNAL } from "./signals.ts";
import { asciiLowercase, decodeEntities, hasTemplateSyntax, tokenize } from "./markup.ts";
import { findCallSites, isHtmlString, selectStringArg } from "./scanner.ts";

/*
 * The signals a Datastar expression reads, with their offsets, and the check of each against the
 * signals the workspace defines. The analysis sees one file; which names are defined is the
 * signal index's to say (see `collectSignalDefinitions`). The cases are those of the analysis
 * module's `SignalReferencesTest.kt`.
 */

/** `$name` or `$name.path` in a Datastar expression; `name` is the whole path without the dollar. */
export interface SignalReference {
  start: number;
  end: number;
  name: string;
}

/**
 * The signals an expression reads, with offsets relative to `text`: what the client rewrites as a
 * signal where JavaScript runs it (see `clientSignalReads`), shared with `SignalReferences.kt`. A
 * `$foo-bar` is left to the `signal-kebab` check, and a name opening with a digit is no name a
 * page declares.
 */
export function expressionSignalReferences(text: string): SignalReference[] {
  const out: SignalReference[] = [];
  for (const r of clientSignalReads(text)) {
    if (r.name.includes("-") || !/^[A-Za-z_]/.test(r.name) || r.name.includes(PLACEHOLDER)) continue;
    out.push({ start: r.start, end: r.end, name: r.name });
  }
  return out;
}

/** The DSL helpers whose first argument names a signal they read: `signal("count")` is `$count`. */
const HELPER_READ_CALLS: ReadonlySet<string> = new Set(["signal", "increment", "decrement", "not"]);

/**
 * A name a helper writes as it stands. A kebab or bracketed name reaches the client rewritten
 * (`foo-bar` is `$fooBar`), so a fix that wrote the defined name back would change how it is
 * written; those are left out, as `$foo-bar` is in an expression.
 */
const PLAIN_HELPER_NAME = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/;

/** The signal the literal first argument of a helper reads, with offsets relative to its text. */
export function helperSignalReference(text: string): SignalReference | null {
  return PLAIN_HELPER_NAME.test(text) && !text.includes(PLACEHOLDER) ? { start: 0, end: text.length, name: text } : null;
}

/** The signals the expressions of an HTML fragment or document read, with offsets relative to `html`. */
export function signalReferencesInHtml(html: string, prefix: string): SignalReference[] {
  const out: SignalReference[] = [];
  for (const tag of tokenize(html).tags) {
    if (tag.closing) continue;
    for (const attr of tag.attributes) {
      const spec = parseAttributeName(asciiLowercase(attr.name), prefix)?.spec;
      const value = attr.value;
      if (!spec || spec.valueKind !== "expression" || value === null || value.trim().length === 0 || hasTemplateSyntax(value)) continue;
      const decoded = decodeEntities(value);
      for (const ref of expressionSignalReferences(decoded.text)) {
        out.push({ ...ref, start: attr.valueStart + (decoded.map[ref.start] ?? 0), end: attr.valueStart + (decoded.map[ref.end] ?? value.length) });
      }
    }
  }
  return out;
}

/**
 * Every signal the Datastar expressions of a Kotlin file read: in the expression handed to a DSL
 * call, in the name handed to a helper such as `signal("count")`, and in the attributes of HTML
 * strings, handed to a call or free-standing. A Kotlin template (`$count` in a plain literal) is
 * not a signal and is left out; the interpolation check speaks for it.
 */
export function signalReferencesInKotlin(src: string, prefix: string): SignalReference[] {
  const out: SignalReference[] = [];
  const claimed = new Set<number>();
  const lex = lexCached(src);
  const add = (s: KotlinString, refs: SignalReference[]) => {
    for (const ref of refs) out.push({ ...ref, ...toSource(s, ref.start, ref.end) });
  };
  for (const site of findCallSites(src, ALL_SITE_NAMES, lex.mask)) {
    for (const a of site.args) if (a.string) claimed.add(a.string.start);
    const expr = catalog.callSites.expression[site.name];
    if (expr && !(expr.onlyIfStringArgs && site.args.some((a) => a.named === null && a.string === null))) {
      const s = selectStringArg(site, expr.arg, expr.named);
      if (s && !s.unterminated) add(s, expressionSignalReferences(s.text));
    }
    const html = catalog.callSites.html[site.name];
    if (html) {
      const s = markupString(site, html);
      if (s && !s.unterminated) add(s, signalReferencesInHtml(s.text, prefix));
    }
  }
  for (const site of findCallSites(src, HELPER_READ_CALLS, lex.mask)) {
    const s = site.args.find((a) => a.named === null)?.string;
    if (!s || s.unterminated) continue;
    const ref = helperSignalReference(s.text);
    if (ref) add(s, [ref]);
  }
  for (const m of src.matchAll(ATTRIBUTES_INDEX)) {
    if (lex.mask[m.index ?? 0] === 0) continue;
    const s = attributesIndexValue(src, m, lex.strings);
    if (!s) continue;
    claimed.add(s.start);
    if (parseAttributeName(asciiLowercase(m[1] ?? ""), prefix)?.spec?.valueKind === "expression") add(s, expressionSignalReferences(s.text));
  }
  for (const s of lex.strings) {
    if (claimed.has(s.start) || s.unterminated || !isHtmlString(src, s)) continue;
    add(s, signalReferencesInHtml(s.text, prefix));
  }
  return out;
}

/**
 * Does the workspace define the signal `path` reads? It does when it defines the path itself, a
 * signal the path reaches into (`$user.name.length` with `user` defined), or a signal inside the
 * path (`$form` with `form.email` defined). A spread of a variable into the object form,
 * `{...init}`, may declare any signal, written `*`.
 */
export function isKnownSignal(path: string, defined: ReadonlySet<string>): boolean {
  if (defined.has(path) || defined.has(ANY_SIGNAL)) return true;
  let p = path;
  while (p.includes(".")) {
    p = p.slice(0, p.lastIndexOf("."));
    if (defined.has(p)) return true;
  }
  const inside = `${path}.`;
  for (const d of defined) if (d.startsWith(inside)) return true;
  return false;
}

/** The defined signal `name` most likely misspells, or null when none is close. */
export function nearestSignal(name: string, defined: ReadonlySet<string>): string | null {
  const limit = name.length <= 4 ? 1 : 2;
  let best: string | null = null;
  let bestDistance = Infinity;
  for (const d of defined) {
    if (d === name || d === ANY_SIGNAL) continue;
    const far = distance(name.toLowerCase(), d.toLowerCase());
    if (far <= limit && far < bestDistance) {
      best = d;
      bestDistance = far;
    }
  }
  return best;
}

/** The warning for a signal the workspace never defines, or null when it does. */
export function unknownSignalIssue(ref: SignalReference, defined: ReadonlySet<string>): Issue | null {
  if (isKnownSignal(ref.name, defined)) return null;
  const head = ref.name.split(".")[0] ?? ref.name;
  // `$telefon.length`: the misspelling is the signal, not the property read on it.
  const misspelt = head !== ref.name && !isKnownSignal(head, defined) ? head : ref.name;
  const near = nearestSignal(misspelt, defined);
  // The fix rewrites the name and leaves the dollar as written, which in Kotlin may be `${'$'}`.
  const nameStart = ref.end - ref.name.length;
  return {
    start: ref.start,
    end: ref.end,
    message: `No markup or code in the project defines the signal '${ref.name}'.` + (near ? ` Did you mean $${near}?` : ""),
    severity: "warning",
    code: "unknown-signal",
    link: DOCS.signals,
    fixes: near ? [{ title: `Change to $${near}`, start: nameStart, end: nameStart + misspelt.length, text: near }] : undefined,
  };
}
