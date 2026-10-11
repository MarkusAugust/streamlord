import { catalog, parseAttributeName, type CallSiteSpec } from "./catalog.ts";
import { attributeDoc, DOCS, validateExpression, type Fix, type Issue } from "./expression.ts";
import { PLACEHOLDER, toSource, type Interpolation, type KotlinString } from "./kotlinStrings.ts";
import { asciiLowercase, indexOfAsciiIgnoreCase, keyReading, rawKeyNote, tokenize, validateAttributes, validateMarkup, wireKey } from "./markup.ts";
import { findCallSites, isHtmlString, lexKotlin, namedArgText, selectStringArg, stringAt, type CallSite } from "./scanner.ts";

/**
 * Editor-independent analysis: Kotlin source in, issues with source offsets out.
 */

export interface AnalyzeOptions {
  prefix: string;
  checkHtmlAttributes: boolean;
}

export const ALL_SITE_NAMES: ReadonlySet<string> = new Set([
  ...Object.keys(catalog.callSites.expression),
  ...Object.keys(catalog.callSites.html),
  ...Object.keys(catalog.callSites.script),
  ...Object.keys(catalog.callSites.selector),
]);

const HTML_SITE_NAMES: ReadonlySet<string> = new Set(Object.keys(catalog.callSites.html));

/** Keyed helpers that take a key but no expression, so the catalog's expression sites do not list them. */
const KEY_ONLY_HELPERS: ReadonlySet<string> = new Set(["dataMatchMedia", "dataPersist"]);
for (const name of KEY_ONLY_HELPERS) (ALL_SITE_NAMES as Set<string>).add(name);

/**
 * Helpers that write the signal name in the value, which keeps its case, unless a `case` is
 * given: Datastar applies `__case` to a key only, so then the name moves into the key.
 */
const VALUE_UNLESS_CASED_HELPERS: ReadonlySet<string> = new Set(["dataBind", "dataRef", "dataIndicator"]);
for (const name of VALUE_UNLESS_CASED_HELPERS) (ALL_SITE_NAMES as Set<string>).add(name);

/**
 * The last source lexed, so a hover or completion right after a diagnostics pass does not lex
 * the file again. Bounded: a file past the cap is lexed each time rather than kept resident.
 */
const LEX_CACHE_CAP = 256 * 1024;
let lastLex: { src: string; lex: ReturnType<typeof lexKotlin> } | null = null;
export function lexCached(src: string): ReturnType<typeof lexKotlin> {
  if (src.length > LEX_CACHE_CAP) return lexKotlin(src);
  if (lastLex === null || lastLex.src.length !== src.length || lastLex.src !== src) lastLex = { src, lex: lexKotlin(src) };
  return lastLex.lex;
}

export function analyzeKotlin(src: string, opts: AnalyzeOptions): Issue[] {
  const issues: Issue[] = [];
  const claimed = new Set<number>();
  const lex = lexCached(src);
  for (const site of findCallSites(src, ALL_SITE_NAMES, lex.mask)) {
    const expr = catalog.callSites.expression[site.name];
    if (expr) issues.push(...checkExpressionSite(site, expr, src));
    const html = catalog.callSites.html[site.name];
    if (html) issues.push(...checkHtmlSite(site, html, opts, src));
    const script = catalog.callSites.script[site.name];
    if (script) issues.push(...checkScriptSite(site, script));
    const selector = catalog.callSites.selector[site.name];
    if (selector) issues.push(...checkSelectorSite(site, selector));
    issues.push(...checkKeyCaseSite(site, src));
    for (const a of site.args) if (a.string) claimed.add(a.string.start);
  }
  for (const s of lex.strings) {
    if (claimed.has(s.start) || s.unterminated || !isHtmlString(src, s)) continue;
    issues.push(...checkFreeHtmlString(s, opts, src));
  }
  return issues;
}

/**
 * The string literal at an offset when it holds HTML: the argument of an HTML call site, or a
 * free-standing literal that looks like HTML (see [isHtmlString]). Completion and hover use
 * it to give Kotlin the HTML side.
 */
export function htmlStringAt(src: string, offset: number): KotlinString | null {
  const lex = lexCached(src);
  const s = stringAt(src, offset, lex.strings);
  if (!s) return null;
  if (isHtmlString(src, s)) return s;
  const site = findCallSites(src, HTML_SITE_NAMES, lex.mask).find((c) => c.openParen < s.start && s.end <= c.closeParen + 1);
  if (!site) return null;
  const spec = catalog.callSites.html[site.name];
  const arg = spec ? markupString(site, spec) : null;
  return arg && arg.start === s.start ? s : null;
}

/**
 * The markup handed to an HTML call site, or null when the markup is a trailing lambda: in the
 * kotlinx.html form, `patchElements(selector, mode, ...) { li { } }`, the first string is the selector.
 */
export function markupString(site: CallSite, spec: CallSiteSpec): KotlinString | null {
  return site.trailingLambda ? null : selectStringArg(site, spec.arg, spec.named);
}

/**
 * A string that holds HTML but is not handed straight to a Streamlord call: a function that
 * returns markup, a `val` with a fragment. Nothing is known about how it will be patched, so
 * only the attributes are checked, as in a template file; ids and completeness are not.
 */
function checkFreeHtmlString(s: KotlinString, opts: AnalyzeOptions, src: string): Issue[] {
  const issues: Issue[] = interpolationHints(s, src, opts.prefix);
  if (opts.checkHtmlAttributes) issues.push(...mapIssues(s, src, analyzeHtml(s.text, opts)));
  return issues;
}

/** The DSL helper that says the same as a whole-string expression, when there is one. */
const HELPERS: [RegExp, (n: string) => string][] = [
  [/^\$([A-Za-z_][A-Za-z0-9_.]*)$/, (n) => `signal("${n}")`],
  [/^\$([A-Za-z_][A-Za-z0-9_.]*)\+\+$/, (n) => `increment("${n}")`],
  [/^\$([A-Za-z_][A-Za-z0-9_.]*)--$/, (n) => `decrement("${n}")`],
  [/^!\$([A-Za-z_][A-Za-z0-9_.]*)$/, (n) => `not("${n}")`],
];

const TRIM_AFTER = /\s*\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)/y;

/**
 * The whole literal rewritten as a `$$` literal (Kotlin 2.2+): the flagged template becomes a
 * signal by staying as it is, every other template gains a dollar so it stays Kotlin, and the
 * `${'$'}` idiom becomes the plain dollar it always meant.
 */
function multiDollarFix(s: KotlinString, src: string, signal: Interpolation): Fix {
  const content = src.slice(s.contentStart, s.contentEnd);
  // Enough dollars that no run already in the text opens a template: one more than the longest run.
  const longestRun = Math.max(0, ...(content.match(/\$+/g) ?? []).map((r) => r.length));
  const dollars = Math.max(2, longestRun + 1);
  const prefix = "$".repeat(dollars);
  // A kept template gains the dollars it now needs; the dollars before it stay text, as they were.
  // The flagged one keeps its run, which is now text.
  const keep = new Map(s.interpolations.filter((ip) => ip !== signal).map((ip) => [ip.start, dollars - 1]));
  let out = prefix + src.slice(s.start, s.contentStart).replace(/^\$+/, "");
  let i = s.contentStart;
  while (i < s.contentEnd) {
    const pad = keep.get(i);
    if (pad !== undefined && pad > 0) out += "$".repeat(pad);
    if (src.startsWith("${'$'}", i)) {
      out += "$";
      i += 6;
      continue;
    }
    out += src[i];
    i++;
  }
  out += src.slice(s.contentEnd, s.end);
  return { title: `Make it a ${prefix} literal, where $${signal.text} is a signal`, start: s.start, end: s.end, text: out };
}

function interpolationIssues(s: KotlinString, src: string): Issue[] {
  const issues: Issue[] = [];
  // In a multi-dollar literal a single $ is text, so there is no trap: every template there is deliberate.
  if (s.dollars > 1) return issues;
  for (const ip of s.interpolations) {
    if (ip.kind !== "simple") continue;
    const fixes: Fix[] = [multiDollarFix(s, src, ip), { title: `Escape as \${'$'}${ip.text}`, start: ip.start, end: ip.start + 1, text: "${'$'}" }];
    if (!s.raw) fixes.push({ title: `Escape as \\$${ip.text}`, start: ip.start, end: ip.start, text: "\\" });
    // The literal as the author meant it, with every template read as a signal.
    const meant = src.slice(s.contentStart, s.contentEnd);
    if (!/\$\{/.test(meant)) {
      // A helper is not a string: it replaces the literal together with the trim call after it.
      TRIM_AFTER.lastIndex = s.end;
      const end = s.end + (TRIM_AFTER.exec(src)?.[0].length ?? 0);
      for (const [re, helper] of HELPERS) {
        const m = re.exec(meant);
        if (m?.[1]) fixes.unshift({ title: `Use ${helper(m[1])}`, start: s.start, end, text: helper(m[1]) });
      }
      const toggle = /^\$([A-Za-z_][A-Za-z0-9_.]*) = !\$\1$/.exec(meant);
      if (toggle?.[1]) fixes.unshift({ title: `Use toggle("${toggle[1]}")`, start: s.start, end, text: `toggle("${toggle[1]}")` });
    }
    issues.push({
      start: ip.start,
      end: ip.end,
      message: `Kotlin interpolates $${ip.text} here; the browser will never see a signal. Write signal("${ip.text}"), make the literal $$"..." (Kotlin 2.2+), or escape as \${'$'}${ip.text}.`,
      severity: "error",
      code: "kotlin-interpolation",
      link: DOCS.expressions,
      fixes,
    });
  }
  return issues;
}

/**
 * In HTML a Kotlin template is usually meant (`<li>$name</li>`), so interpolation is a hint,
 * not an error, unless it sits in a `data-*` attribute that takes an expression: there the
 * browser expects a signal and gets whatever Kotlin evaluated.
 */
function interpolationHints(s: KotlinString, src: string, prefix: string): Issue[] {
  const tags = tokenize(s.text).tags;
  /** `whole` when the template is the entire attribute value, `part` when it sits inside a larger expression. */
  const inExpression = (decoded: number): "whole" | "part" | null => {
    for (const t of tags) {
      for (const a of t.attributes) {
        if (a.value === null || decoded < a.valueStart || decoded >= a.valueStart + a.value.length) continue;
        const parsed = parseAttributeName(asciiLowercase(a.name), prefix);
        if (parsed?.spec?.valueKind !== "expression") return null;
        // data-signals:count="$initial" seeds a signal from the server, like the object form does.
        if (parsed.spec.name === "signals") return "part";
        return a.value.trim() === PLACEHOLDER ? "whole" : "part";
      }
    }
    return null;
  };
  return interpolationIssues(s, src).map((i) => {
    const ip = s.interpolations.find((x) => x.start === i.start);
    const name = ip?.text ?? "";
    const where = ip ? inExpression(ip.decodedStart) : null;
    const fixes = i.fixes?.filter((f) => f.title.startsWith("Make it") || f.title.startsWith("Escape"));
    if (where === "whole") {
      // data-show="$isAdmin" renders as data-show="true", which works; meant as the signal $isAdmin, it is the trap.
      return {
        ...i,
        severity: "warning" as const,
        fixes,
        message: `Kotlin interpolates $${name} here, as the whole Datastar expression. Meant as a server value, that is fine; meant as the signal $${name}, make the whole literal $$"""...""" (Kotlin 2.2+), or escape as \${'$'}${name}.`,
      };
    }
    if (where === "part") {
      // `data-signals="{count: $initialCount}"` seeds a signal from a server value as often as it is the trap.
      return {
        ...i,
        severity: "warning" as const,
        fixes,
        message: `Kotlin interpolates $${name} inside a Datastar expression. Meant as a server value, that is fine; meant as the signal $${name}, make the literal $$"""...""" (Kotlin 2.2+) or escape as \${'$'}${name}.`,
      };
    }
    return { ...i, severity: "hint" as const, fixes: undefined, message: `Kotlin interpolates $${name} into the HTML. Make sure it is escaped.` };
  });
}

function mapIssues(s: KotlinString, src: string, issues: Issue[]): Issue[] {
  return issues.map((i) => {
    const r = toSource(s, i.start, i.end);
    const fixes = i.fixes?.map((f) => {
      const fr = f.start === f.end ? { start: toSource(s, f.start, f.start + 1).start, end: toSource(s, f.start, f.start + 1).start } : toSource(s, f.start, f.end);
      return { ...f, start: fr.start, end: fr.end, text: f.start === f.end ? f.text : keepDollarEscape(s, src, f) };
    });
    return { ...i, start: r.start, end: r.end, fixes };
  });
}

/**
 * A fix that starts on a dollar the source wrote as an escape (`\$`, `${'$'}`) must write the
 * escape back: a bare `$` before a name would turn the signal into a Kotlin template.
 */
function keepDollarEscape(s: KotlinString, src: string, fix: Fix): string {
  if (s.text[fix.start] !== "$" || !fix.text.startsWith("$")) return fix.text;
  const escape = src.slice(s.map[fix.start], s.map[fix.start + 1]);
  return escape.length > 1 ? escape + fix.text.slice(1) : fix.text;
}

function checkExpressionSite(site: CallSite, spec: CallSiteSpec, src: string): Issue[] {
  if (spec.onlyIfStringArgs && site.args.some((a) => a.named === null && a.string === null)) return [];
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const issues = interpolationIssues(s, src);
  const text = s.text;
  if (text.includes(PLACEHOLDER) && s.interpolations.some((i) => i.kind === "simple")) return issues;
  issues.push(...mapIssues(s, src, validateExpression(text)));
  return issues;
}

function checkHtmlSite(site: CallSite, spec: CallSiteSpec, opts: AnalyzeOptions, src: string): Issue[] {
  const s = markupString(site, spec);
  if (!s && !site.trailingLambda) return [];
  if (s?.unterminated) return [];
  // In every signature the selector and the mode follow the elements, so they may be given by
  // position. A trailing lambda holds the elements, so the selector comes first.
  const named = (name?: string) => (name ? site.args.find((a) => a.named === name) : undefined);
  const positional = site.args.filter((a) => a.named === null);
  const elements = !s ? -1 : named(spec.named) ? null : spec.arg === "last" ? 0 : spec.arg;
  const selectorArg = named(spec.selectorArg) ?? (elements === null ? undefined : positional[elements + 1]);
  const selector = selectorArg && selectorArg.text !== "null" ? selectorArg.text : null;
  const modeArg = named(spec.modeArg) ?? (elements === null ? undefined : positional[elements + 2]);
  // `ElementPatchMode.APPEND`, or `APPEND` when the constant is imported. Anything else is a value not known here.
  const modeName = modeArg ? (/(?:^|\.)([A-Z]+)\s*$/.exec(modeArg.text)?.[1]?.toLowerCase() ?? null) : null;
  const mode = modeName === "default" ? "outer" : modeName !== null && catalog.patchModes.includes(modeName) ? modeName : null;
  const issues: Issue[] = s ? interpolationHints(s, src, opts.prefix) : [];
  if (mode && mode !== "outer" && mode !== "replace" && selector === null && modeArg) {
    const at = modeArg.start - (modeArg.named?.length ?? 0) - (src.slice(0, modeArg.start).match(/\s*=\s*$/)?.[0].length ?? 0);
    issues.push({
      start: site.nameStart,
      end: site.openParen,
      message: `Mode ${mode.toUpperCase()} requires a selector; Streamlord will reject this event at runtime.`,
      severity: "error",
      code: "mode-needs-selector",
      link: DOCS.sse,
      fixes: modeArg.named !== null ? [{ title: 'Add selector = ""', start: at, end: at, text: 'selector = "", ' }] : undefined,
    });
  }
  // A mode that cannot be read here (a variable) may be one that needs no ids.
  const requireIds = selector === null && (!modeArg || mode === "outer");
  if (s) issues.push(...mapIssues(s, src, validateMarkup(s.text, { requireIds, prefix: opts.prefix, checkAttributes: opts.checkHtmlAttributes })));
  return issues;
}

/**
 * The DSL writes a camelCase key as the kebab-case key Datastar reads back as that name, with
 * `__case` where Datastar's default is not camel. That is handled, but not hidden: a hint on
 * the call says what goes on the wire, because in HTML and templates the author writes it
 * that way themselves.
 */
function checkKeyCaseSite(site: CallSite, src: string): Issue[] {
  const spec = catalog.attributesByKotlin.get(site.name);
  if (!spec?.keyed || !spec.keyCase) return [];
  // Only helpers whose first parameter is a key: those whose expression is a later argument
  // (dataOn, dataSignals, dataClass, ...), the two Pro helpers that take a key and no
  // expression, and dataBind, dataRef and dataIndicator once a case moves their name into the
  // key. dataOnClick("expr") passes an expression, never a key.
  const expr = catalog.callSites.expression[site.name];
  const valueUnlessCased = VALUE_UNLESS_CASED_HELPERS.has(site.name);
  if (expr ? expr.arg === 0 : !(KEY_ONLY_HELPERS.has(site.name) || valueUnlessCased)) return [];
  const positional = site.args.filter((a) => a.named === null);
  const key = positional[0]?.string;
  // With an expression helper, one positional string is the object form (dataClass("{...}")); the key form has two.
  if (!key || key.interpolations.length > 0 || (expr && positional.length < 2)) return [];
  const name = key.text;
  // dataSignals("{fooBar: 1}", ...) is the object form: an expression, not a key.
  if (!/[A-Z]/.test(name) || /^\s*[{\[]/.test(name)) return [];
  // `case = Case.X`, a positional `Case.X` (dataRef("name", Case.CAMEL)) or `case = Case.X` in the trailing lambda.
  const named = site.args.find((a) => a.named === "case" || (a.named === null && /^\s*Case\.[A-Z]+\s*$/.test(a.text)))?.text;
  const explicit = named ? /Case\.([A-Z]+)/.exec(named) : /\bcase\s*=\s*Case\.([A-Z]+)/.exec(trailingLambdaText(site, src));
  const explicitCase = explicit?.[1]?.toLowerCase() ?? null;
  if (valueUnlessCased && !explicitCase) return [];
  const wire = wireKey(name, spec.keyCase, explicitCase);
  const what = explicitCase ? `a ${explicitCase}-cased name` : keyReading(spec, name, wire);
  return [{
    start: key.start,
    end: key.end,
    message: `Written on the wire as data-${spec.name}:${wire}, which Datastar reads back as ${what}: the browser lowercases attribute names, so a key is kebab-case. In HTML or a template you write it that way yourself.${rawKeyNote(spec, "data-", name)}`,
    severity: "hint",
    code: "key-case-wire",
    link: attributeDoc(spec.name),
  }];
}

/** The text of the trailing lambda `{ ... }` after a call, braces balanced; empty when there is none. */
function trailingLambdaText(site: CallSite, src: string): string {
  if (!site.trailingLambda) return "";
  const open = src.indexOf("{", site.closeParen + 1);
  if (open < 0) return "";
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === "{") depth++;
    else if (c === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  return src.slice(open);
}

function checkScriptSite(site: CallSite, spec: CallSiteSpec): Issue[] {
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const issues: Issue[] = [];
  const idx = indexOfAsciiIgnoreCase(s.text, "</script", 0);
  if (idx >= 0) {
    const r = toSource(s, idx, idx + 8);
    issues.push({ start: r.start, end: r.end, message: "`</script` inside a script body. Streamlord escapes it to `<\\/script`, which only works inside a JavaScript string or regex.", severity: "warning", code: "script-close" });
  }
  return issues;
}

function checkSelectorSite(site: CallSite, spec: CallSiteSpec): Issue[] {
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  if (s.text.trim().length === 0) return [{ start: s.start, end: s.end, message: "Selector must not be blank.", severity: "error", code: "blank-selector" }];
  if (/[\r\n]/.test(s.text)) return [{ start: s.start, end: s.end, message: "Selector must not contain line breaks.", severity: "error", code: "selector-newline" }];
  return [];
}

/** Analyse an HTML document (a template): attributes and expressions only, no id or completeness rules. */
export function analyzeHtml(src: string, opts: AnalyzeOptions): Issue[] {
  const issues: Issue[] = [];
  for (const tag of tokenize(src).tags) {
    if (tag.closing) continue;
    issues.push(...validateAttributes(tag, opts.prefix, src.slice(tag.start, tag.end)));
  }
  return issues;
}
