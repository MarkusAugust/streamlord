import { catalog, parseAttributeName, type AttributeSpec, type CallSiteSpec } from "./catalog.ts";
import { attributeDoc, DOCS, quoteAt, validateExpression, type Fix, type Issue } from "./expression.ts";
import { PLACEHOLDER, toSource, type Interpolation, type KotlinString } from "./kotlinStrings.ts";
import { capitalIn, kebab, nameProblem, runsIntoModifiers, spellings } from "./attributeKeys.ts";
import { signalNameRefusal } from "./signalNames.ts";
import { asciiLowercase, decodeEntities, indexOfAsciiIgnoreCase, keyReading, rawKeyNote, tokenize, validateAttributes, validateMarkup, wireKey, wireKeyReads, type Attribute, type Tag } from "./markup.ts";
import { findCallSites, isHtmlString, lexKotlin, namedArgText, selectStringArg, stringAt, type Arg, type CallSite } from "./scanner.ts";
import { holeLandings, urlSchemeFixedBy, type HoleLanding } from "./holes.ts";
import { argKind, chainFrom, declaredKind, holeDisplay, interpolateFix, isMarkup, literalOnly, markupView, percentHoles, plainName, shadowed, trimmed, withNested, withPercentHoles, type ArgKind } from "./interpolation.ts";
import { dslHelpers, EXPRESSION_HELPERS, signalNameHyphenSites } from "./signalNameHyphens.ts";
import { clientKeyName, collectSignalDefinitions } from "./signals.ts";

/**
 * Editor-independent analysis: Kotlin source in, issues with source offsets out.
 */

export interface AnalyzeOptions {
  prefix: string;
  checkHtmlAttributes: boolean;
  /** The `const val` names the workspace declares: a value the author wrote, silent wherever it lands. */
  constants?: ReadonlySet<string>;
  /** What the editor knows of the value in a hole, from types the text cannot show (IntelliJ). */
  holeFact?: (start: number, end: number) => HoleFact | null;
}

/** What the editor knows of a value: its type, or that it is a constant or markup. */
export type HoleFact = "string" | "number" | "boolean" | "constant" | "markup";

export const ALL_SITE_NAMES: ReadonlySet<string> = new Set([
  ...Object.keys(catalog.callSites.expression),
  ...Object.keys(catalog.callSites.html),
  ...Object.keys(catalog.callSites.script),
  ...Object.keys(catalog.callSites.selector),
]);

const HTML_SITE_NAMES: ReadonlySet<string> = new Set(Object.keys(catalog.callSites.html));

// The helpers whose key is an HTML attribute's name, in which the DSL refuses a capital.
const ATTRIBUTE_NAME_HELPERS: ReadonlySet<string> = new Set(catalog.attributes.filter((a) => a.keyIsAttributeName).flatMap((a) => a.kotlin));

/** Helpers that write the signal name in the value (the catalog's `valueKind` signal), which its call sites do not list. */
const VALUE_NAME_HELPERS: ReadonlySet<string> = new Set(catalog.attributes.filter((a) => a.valueKind === "signal").flatMap((a) => a.kotlin));
for (const name of VALUE_NAME_HELPERS) (ALL_SITE_NAMES as Set<string>).add(name);

/** Keyed helpers that take a key but no expression, so the catalog's expression sites do not list them. */
const KEY_ONLY_HELPERS: ReadonlySet<string> = new Set(
  catalog.attributes
    .filter((a) => a.keyed)
    .flatMap((a) => a.kotlin)
    .filter((k) => !(k in catalog.callSites.expression) && !VALUE_NAME_HELPERS.has(k)),
);
for (const name of KEY_ONLY_HELPERS) (ALL_SITE_NAMES as Set<string>).add(name);

/**
 * The last source lexed, so a hover or completion right after a diagnostics pass does not lex
 * the file again. Bounded: a file past the cap is lexed each time rather than kept resident.
 */
const LEX_CACHE_CAP = 256 * 1024;

/** kotlinx.html's own way to write any attribute: `attributes["data-on:click"] = "..."`. */
export const ATTRIBUTES_INDEX = /\battributes\s*\[\s*"(data-[^"\\]*)"\s*]\s*=\s*/g;

/**
 * The signals with a hyphen `src` declares as the client stores them, so `$my-value` is not a slip
 * there: `data-signals:my-value__case.kebab`, `data-bind="my-value"`, `{'my-value': 1}`, or
 * `data-signals:a-1`, which camel casing leaves as it is.
 */
export function hyphenatedSignals(src: string, language: "kotlin" | "html"): ReadonlySet<string> {
  return new Set([...collectSignalDefinitions(src, language)].filter((n) => n.includes("-")));
}
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
  const hyphenated = hyphenatedSignals(src, "kotlin");
  for (const site of findCallSites(src, ALL_SITE_NAMES, lex.mask)) {
    const expr = catalog.callSites.expression[site.name];
    if (expr) issues.push(...checkExpressionSite(site, expr, src, hyphenated, opts));
    const html = catalog.callSites.html[site.name];
    if (html) issues.push(...checkHtmlSite(site, html, opts, src, hyphenated));
    const script = catalog.callSites.script[site.name];
    if (script) issues.push(...checkScriptSite(site, script));
    const selector = catalog.callSites.selector[site.name];
    if (selector) issues.push(...checkSelectorSite(site, selector));
    issues.push(...checkKeyCaseSite(site, src));
    for (const a of site.args) if (a.string) claimed.add(a.string.start);
    // The literals of a `+` chain handed to the site are judged there, as one string.
    const chain = html ? markupString(site, html, src) : null;
    if (chain && !site.args.some((a) => a.string === chain)) for (const x of lex.strings) if (x.start >= chain.start && x.end <= chain.end) claimed.add(x.start);
  }
  for (const site of findCallSites(src, INTERPOLATE, lex.mask)) {
    const format = interpolateFormat(site, src);
    if (!format) continue;
    for (const x of lex.strings) if (x.start >= format.string.start && x.end <= format.string.end) claimed.add(x.start);
    issues.push(...checkInterpolateSite(site, format, src, opts, hyphenated));
  }
  issues.push(...signalNameHyphenSites(src, lex, dslHelpers(src, EXPRESSION_HELPERS), new Set([...ALL_SITE_NAMES, "statements"])));
  for (const m of src.matchAll(ATTRIBUTES_INDEX)) {
    if (lex.mask[m.index ?? 0] === 0) continue;
    const value = attributesIndexValue(src, m, lex.strings);
    if (value) claimed.add(value.start);
    issues.push(...checkAttributesIndex(m, value, opts, src, hyphenated));
  }
  // A literal joined to values with `+` is read as one string with a hole for each value.
  const chained = new Set<number>();
  const topLevel = new Set(lex.strings);
  for (const s of withNested(lex.strings)) {
    if (claimed.has(s.start) || chained.has(s.start) || s.unterminated) continue;
    // A literal inside a template is read on its own: its neighbours there are the template's code.
    const chain = topLevel.has(s) ? chainFrom(src, s, lex.mask) : null;
    const whole = chain ?? s;
    if (chain) for (let at = chain.start; at < chain.end; at++) if (src[at] === '"' || src[at] === "$") chained.add(at);
    if (!isHtmlString(src, s) && !(chain && /^\s*<(?:[A-Za-z]|!--)/.test(chain.text))) continue;
    issues.push(...checkFreeHtmlString(whole, opts, src, hyphenated));
  }
  return issues;
}

/** What may follow the value of an assignment: the end of the statement, nothing more of an expression. */
const STATEMENT_END = /[ \t]*(?:[;}\n\r]|$)/y;

/** The string literal assigned in an `attributes["..."] =` match, when it is the whole value: not the first half of a concatenation. */
export function attributesIndexValue(src: string, m: RegExpMatchArray, strings: KotlinString[]): KotlinString | null {
  const at = (m.index ?? 0) + m[0].length;
  const s = stringAt(src, at + 1, strings);
  if (!s || s.start !== at || s.unterminated) return null;
  STATEMENT_END.lastIndex = s.end;
  return STATEMENT_END.test(src) ? s : null;
}

/**
 * `attributes["data-on:click"] = "$count++"` judged as the attribute it writes: the name as in
 * markup, the value as the expression a DSL call takes, with Kotlin's interpolation trap. A
 * value that is not a literal is taken for a template, so only the name is judged.
 */
function checkAttributesIndex(m: RegExpMatchArray, value: KotlinString | null, opts: AnalyzeOptions, src: string, hyphenated: ReadonlySet<string>): Issue[] {
  if (!opts.checkHtmlAttributes) return [];
  const name = m[1] ?? "";
  const nameStart = (m.index ?? 0) + m[0].indexOf('"') + 1;
  // The attribute in a space of its own, `name="value"`: the name at 0, the value after the `="`.
  const valueStart = name.length + 2;
  const text = value?.text ?? PLACEHOLDER;
  const attribute: Attribute = { name, nameStart: 0, value: text, valueStart, quoted: true };
  const tag: Tag = { name: "div", start: 0, end: valueStart + text.length + 1, closing: false, selfClosing: false, attributes: [attribute] };
  const issues: Issue[] = [];
  const kind = parseAttributeName(asciiLowercase(name), opts.prefix)?.spec?.valueKind;
  if (value && (kind === "expression" || kind === "signal")) issues.push(...expressionHoles(value, src, opts, name));
  // With a template in the value the interpolation issue speaks for it, as at a DSL call.
  const judgeValue = value !== null && !(value.text.includes(PLACEHOLDER) && value.interpolations.some((i) => i.kind === "simple"));
  // A range in the value maps through the literal as a whole, so an error at its end keeps a width.
  const range = (start: number, end: number) => (end <= name.length ? { start: nameStart + start, end: nameStart + end } : toSource(value!, start - valueStart, end - valueStart));
  for (const i of validateAttributes(tag, opts.prefix, undefined, hyphenated, true)) {
    const inName = i.end <= name.length;
    if (!inName && !judgeValue) continue;
    const fixes = i.fixes
      ?.filter((f) => (f.end <= name.length) === inName)
      .map((f) => {
        if (inName) return { ...f, start: nameStart + f.start, end: nameStart + f.end };
        // In the literal's own coordinates, so a `\$` the source wrote is written back.
        const inValue = { ...f, start: f.start - valueStart, end: f.end - valueStart };
        const at = toSource(value!, inValue.start, Math.max(inValue.end, inValue.start + 1));
        return { ...f, start: at.start, end: f.start === f.end ? at.start : at.end, text: keepDollarEscape(value!, src, inValue) };
      });
    issues.push({ ...i, ...range(i.start, i.end), ...(fixes ? { fixes } : {}) });
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
export function markupString(site: CallSite, spec: CallSiteSpec, src?: string): KotlinString | null {
  if (site.trailingLambda) return null;
  const s = selectStringArg(site, spec.arg, spec.named);
  if (s || src === undefined) return s;
  // `patchElements("<p>" + name + "</p>")`: the argument is one string with a hole for the value.
  const positional = site.args.filter((a) => a.named === null);
  const arg = (spec.named ? site.args.find((a) => a.named === spec.named) : undefined) ?? (spec.arg === "last" ? positional[positional.length - 1] : positional[spec.arg]);
  if (!arg) return null;
  const lex = lexCached(src);
  const first = lex.strings.find((x) => x.start === arg.start);
  const chain = first ? chainFrom(src, first, lex.mask) : null;
  return chain && chain.end === arg.end ? chain : null;
}

/**
 * A string that holds HTML but is not handed straight to a Streamlord call: a function that
 * returns markup, a `val` with a fragment. Nothing is known about how it will be patched, so
 * only the attributes are checked, as in a template file; ids and completeness are not.
 */
function checkFreeHtmlString(s: KotlinString, opts: AnalyzeOptions, src: string, hyphenated: ReadonlySet<string>): Issue[] {
  const view = markupView(src, s);
  const issues: Issue[] = judgeHoles(s, src, opts, view);
  if (opts.checkHtmlAttributes) issues.push(...mapIssues(s, src, analyzeHtmlWith(view, opts, hyphenated)));
  return issues;
}

const INTERPOLATE: ReadonlySet<string> = new Set(["interpolate"]);

interface InterpolateFormat {
  arg: Arg;
  string: KotlinString;
}

/** The format handed to `interpolate`: its first argument or `format =`, a literal or a `+` chain that starts with one. */
function interpolateFormat(site: CallSite, src: string): InterpolateFormat | null {
  const arg = site.args.find((a) => a.named === "format") ?? site.args.find((a) => a.named === null);
  if (!arg) return null;
  if (arg.string) return arg.string.unterminated ? null : { arg, string: arg.string };
  const lex = lexCached(src);
  const first = lex.strings.find((x) => x.start === arg.start);
  const chain = first ? chainFrom(src, first, lex.mask) : null;
  return chain && chain.end === arg.end ? { arg, string: chain } : null;
}

const ESCAPED: ReadonlySet<string> = new Set(["TEXT", "ATTRIBUTE", "URL"]);

/**
 * A call to `interpolate`, judged as it will run. A Kotlin template in the format is filled in
 * before `interpolate` sees the markup, so it is neither escaped nor refused. The `%s` holes
 * are counted against the values, and each is judged by where it lands and what the text shows
 * the value to be: core's `admits`, so an error only where `interpolate` throws for certain.
 * The format is read as HTML like any free string; it is not a patch, so no id is asked for.
 */
function checkInterpolateSite(site: CallSite, f: InterpolateFormat, src: string, opts: AnalyzeOptions, hyphenated: ReadonlySet<string>): Issue[] {
  const s = f.string;
  const holes = percentHoles(s.text);
  const { format, at } = withPercentHoles(s, holes);
  const values = site.args.filter((a) => a !== f.arg && a.named === null);
  // The values one by one, as the vararg overload takes them: no spread and nothing by name.
  const plain = site.args.every((a) => a.named === null) && !values.some((a) => a.text.startsWith("*"));
  const kinds = values.map((a) => factKind(opts.holeFact?.(a.start, a.end) ?? null) ?? argKind(src, a.text, a.string !== null, s.start));
  const issues: Issue[] = [];
  // The overload that takes a List is called with one or two values, each of which could be a List.
  const counted = plain && (values.length === 0 || values.length >= 3 || kinds.some((k) => k !== null));
  if (counted && holes.length !== values.length) {
    const h = holes.length;
    const v = values.length;
    issues.push({
      start: site.nameStart,
      end: site.nameStart + site.name.length,
      severity: "error",
      code: "interpolate-holes",
      message: `interpolate throws IllegalArgumentException here: the markup has ${h} %s ${h === 1 ? "hole" : "holes"} and ${v} ${v === 1 ? "value is" : "values are"} given. Write %% for a per cent sign that is not a hole.`,
    });
  }
  const mapped = plain && holes.length === values.length;
  const view = markupView(src, format);
  const landings = holeLandings(
    view,
    [...format.interpolations.map((ip) => ip.decodedStart), ...at].map((d) => ({ start: d, end: d + PLACEHOLDER.length })),
    [...new Set([opts.prefix, catalog.prefix, catalog.aliasedPrefix])],
  );
  const traps = new Map(interpolationIssues(format, src).map((i) => [i.start, i]));
  format.interpolations.forEach((ip, k) => {
    const landing = landings[k] as HoleLanding;
    const trap = traps.get(ip.start);
    if (trap && ip.kind === "simple" && landing.position === "DATASTAR") {
      const judged = datastarTrap(trap, ip, landing, view, opts.prefix);
      if (judged) {
        issues.push(judged);
        return;
      }
    }
    const fact = holeFact(format, src, opts, ip);
    if (fact === "constant" || fact === "number" || fact === "boolean" || (fact === "markup" && landing.position === "TEXT")) return;
    const shown = holeDisplay(ip, format.dollars);
    // A `%` right before the template would make `%%s` of the hole: a per cent sign and an s.
    const percents = /%*$/.exec(format.text.slice(0, ip.decodedStart))?.[0].length ?? 0;
    const fixable = ESCAPED.has(landing.position) && ip.kind !== "operand" && mapped && percents % 2 === 0;
    issues.push({ start: ip.start, end: ip.end, severity: "warning", code: "interpolate-template", link: DOCS.expressions, message: templateMessage(shown, landing), fixes: fixable ? [holeFix(ip, shown, at, values, f.arg)] : undefined });
  });
  at.forEach((d, k) => {
    const landing = landings[format.interpolations.length + k] as HoleLanding;
    if (ESCAPED.has(landing.position)) return;
    const arg = mapped ? values[k] : undefined;
    const kind = arg ? (kinds[k] ?? null) : null;
    const allowed: ArgKind[] = landing.position === "DATASTAR" ? ["number", "boolean"] : landing.position === "STYLE" ? ["number"] : [];
    if (kind === "trusted" || (kind !== null && allowed.includes(kind))) return;
    const shown = arg ? shorten(arg.text.trim()) : "the value of this %s";
    issues.push({ ...toSource(format, d, d + PLACEHOLDER.length), severity: kind === null ? "warning" : "error", code: "unsafe-interpolation", link: DOCS.expressions, message: refusedMessage(shown, landing, kind !== null) });
  });
  if (opts.checkHtmlAttributes) issues.push(...mapIssues(format, src, analyzeHtmlWith(view, opts, hyphenated)));
  return issues;
}

function factKind(fact: HoleFact | null): ArgKind | null {
  return fact === "string" || fact === "number" || fact === "boolean" ? fact : fact === "markup" ? "markup" : null;
}

function shorten(text: string): string {
  return text.length <= 30 ? text : `${text.slice(0, 29)}…`;
}

/** The template made a `%s` hole in place, and its expression passed as the value at the same place among the others. */
function holeFix(ip: Interpolation, shown: string, holes: readonly number[], values: readonly Arg[], format: Arg): Fix {
  const index = holes.filter((h) => h < ip.decodedStart).length;
  const expr = ip.kind === "simple" ? ip.text : ip.text.trim();
  const before = values[index];
  const after = values[values.length - 1] ?? format;
  const edit = before ? { start: before.start, end: before.start, text: `${expr}, ` } : { start: after.end, end: after.end, text: `, ${expr}` };
  return { title: `Make ${shown} a %s hole, which interpolate escapes`, start: ip.start, end: ip.end, text: "%s", edits: [edit] };
}

/** Where a hole that is not escaped lands, said for a message, and what to write instead. */
function landingWords(landing: HoleLanding): { where: string; instead: string } {
  switch (landing.position) {
    case "DATASTAR":
      return { where: `${landing.attribute ?? "a Datastar attribute"}, which Datastar runs as an expression or follows as a signal path`, instead: "Put the value in a signal and reference it from the expression, or write the signal's name in the markup yourself." };
    case "STYLE":
      return { where: `${landing.attribute ?? "style"}, which is read as CSS`, instead: "Pass a number and write the unit in the markup, or choose between classes." };
    case "CODE":
      return { where: `${landing.attribute ?? "a script or style element"}, which the browser runs`, instead: "Put the value in a data-* attribute or a signal and read it from there; wrap markup you built yourself in Trusted(...)." };
    default:
      return { where: "the markup's own structure, a tag name, an attribute name or an unquoted value", instead: "Build that part of the markup from literals, or quote the attribute value; wrap markup you built yourself in Trusted(...)." };
  }
}

function templateMessage(shown: string, landing: HoleLanding): string {
  const base = `Kotlin fills in ${shown} before interpolate sees the markup, so interpolate neither escapes nor refuses it.`;
  if (ESCAPED.has(landing.position)) return `${base} Make it a %s hole and pass the value to interpolate.`;
  const { where, instead } = landingWords(landing);
  return `${base} It lands in ${where}. ${instead}`;
}

function refusedMessage(shown: string, landing: HoleLanding, certain: boolean): string {
  const allowed = landing.position === "DATASTAR" ? "a number or a boolean" : landing.position === "STYLE" ? "a number" : "Trusted(...)";
  const { where, instead } = landingWords(landing);
  return certain ? `interpolate always throws here: ${shown} lands in ${where}, and only ${allowed} is let in there. ${instead}` : `interpolate throws here unless ${shown} is ${allowed}: it lands in ${where}. ${instead}`;
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
 * Each hole of an HTML string, judged by where it lands as `interpolate` judges it at run time
 * (holes.ts, the twin of core): escaping saves it, or nothing does. What the analysis can see is
 * the author's own (a constant, a literal, markup built here) is left alone; what it cannot see
 * into is never an error, since a build fails on errors and the text cannot tell `count: Int`
 * from a String.
 */
function judgeHoles(s: KotlinString, src: string, opts: AnalyzeOptions, view: string): Issue[] {
  if (s.interpolations.length === 0) return [];
  const traps = new Map(interpolationIssues(s, src).map((i) => [i.start, i]));
  const landings = holeLandings(
    view,
    s.interpolations.map((ip) => ({ start: ip.decodedStart, end: ip.decodedStart + PLACEHOLDER.length })),
    // Both bundles' prefixes, as interpolate reads them: a value is judged by what either would run.
    [...new Set([opts.prefix, catalog.prefix, catalog.aliasedPrefix])],
  );
  const facts = s.interpolations.map((ip) => holeFact(s, src, opts, ip));
  // interpolate(...) can take the literal only when every hole is a value it escapes and keeps.
  const fixable =
    s.dollars === 1 &&
    !trimmed(src, s) &&
    s.interpolations.every((ip) => ip.kind !== "operand") &&
    landings.every((l, k) => (l.position === "TEXT" || l.position === "ATTRIBUTE" || l.position === "URL") && facts[k] !== "markup");
  const issues: Issue[] = [];
  s.interpolations.forEach((ip, k) => {
    const landing = landings[k] as HoleLanding;
    const fact = facts[k] ?? null;
    const shown = holeDisplay(ip, s.dollars);
    const at = { start: ip.start, end: ip.end, link: DOCS.expressions };
    // Markup is the author's own in element text; in an attribute or code it is characters that can break out.
    const own = fact === "constant" || (fact === "markup" && landing.position === "TEXT");
    const fixes = () => (fixable ? [interpolateFix(src, s)] : undefined);
    switch (landing.position) {
      case "TEXT":
      case "ATTRIBUTE":
        if (own || fact === "number" || fact === "boolean") return;
        issues.push({ ...at, severity: fact === "string" ? "warning" : "hint", code: "unescaped-interpolation", message: unescapedMessage(shown), fixes: fixes() });
        return;
      case "URL": {
        if (own || fact === "number" || fact === "boolean") return;
        const attribute = landing.attribute ?? "the URL";
        const before = decodeEntities(view.slice(landing.valueStart, ip.decodedStart)).text;
        if (urlSchemeFixedBy(attribute, before)) {
          issues.push({ ...at, severity: fact === "string" ? "warning" : "hint", code: "unescaped-interpolation", message: unescapedMessage(shown), fixes: fixes() });
        } else {
          issues.push({ ...at, severity: "warning", code: "unsafe-interpolation", message: `Kotlin writes ${shown} at the start of ${attribute}, so the value decides the scheme, and a javascript: URL runs. Escaping does not help: start the value with /, # or https://, or build the string with interpolate(...), which refuses a scheme that runs.`, fixes: fixes() });
        }
        return;
      }
      case "STYLE":
        if (own || fact === "number") return;
        issues.push({ ...at, severity: "warning", code: "unsafe-interpolation", message: `Kotlin writes ${shown} into ${landing.attribute ?? "style"}, where it is read as CSS, so escaping does not help. Choose between classes, or write a number.` });
        return;
      case "DATASTAR": {
        const trap = traps.get(ip.start);
        if (trap && ip.kind === "simple") {
          const judged = datastarTrap(trap, ip, landing, view, opts.prefix);
          if (judged) {
            issues.push(judged);
            return;
          }
        }
        if (own || fact === "number" || fact === "boolean") return;
        issues.push({ ...at, severity: "warning", code: "unsafe-interpolation", message: datastarMessage(shown, landing.attribute ?? "a Datastar attribute") });
        return;
      }
      case "CODE":
        if (own) return;
        issues.push({
          ...at,
          severity: "warning",
          code: "unsafe-interpolation",
          message: `Kotlin writes ${shown} into ${landing.attribute ?? "a script or style element"}, which the browser runs, so escaping does not help. Put the value in a data-* attribute or a signal and read it from there.`,
        });
        return;
      case "STRUCTURE": {
        if (own || fact === "number" || fact === "boolean") return;
        const before = view.slice(0, ip.decodedStart);
        // `</$tag>` names the element `<$tag` opened; the opening one is judged.
        if (/<\/\s*$/.test(before) && s.interpolations.slice(0, k).some((o) => o.text === ip.text)) return;
        if (/=[ \t\n\f\r]*[^\s"'=<>`]*$/.test(before)) {
          issues.push({ ...at, severity: "warning", code: "unsafe-interpolation", message: `Kotlin writes ${shown} into an attribute value without quotes, where a space starts a new attribute. Quote the value.` });
          return;
        }
        issues.push({
          ...at,
          severity: fact === "string" ? "warning" : "hint",
          code: "unsafe-interpolation",
          message: `Kotlin writes ${shown} into the markup's own structure: a tag name, an attribute name or a list of attributes. Fine for one chosen in code; a value from outside decides the markup itself, and escaping cannot prevent that.`,
        });
        return;
      }
    }
  });
  return issues;
}

/** What the analysis knows of a hole's value: the editor's facts first, then what the text shows. */
function holeFact(s: KotlinString, src: string, opts: AnalyzeOptions, ip: Interpolation): HoleFact | null {
  const known = opts.holeFact?.(ip.start, ip.end) ?? null;
  if (known === "constant" || known === "markup") return known;
  if (isMarkup(src, s, ip)) return "markup";
  if (ip.kind !== "simple" && literalOnly(ip.text)) return "constant";
  const name = plainName(ip);
  if (name !== null && opts.constants?.has(name) && !shadowed(src, name, s.start)) return "constant";
  return known ?? (name !== null ? declaredKind(src, name, s.start) : null);
}

function unescapedMessage(shown: string): string {
  return `Kotlin writes ${shown} into the HTML as it is. If it can hold text from outside, build the string with interpolate(...), which escapes it.`;
}

function datastarMessage(shown: string, attribute: string): string {
  return `Kotlin writes ${shown} into ${attribute}, which Datastar runs as an expression or follows as a signal path. Text from outside becomes code there, even inside quotes. Put the value in a signal, or in an attribute of its own read with el.dataset; only a number or a boolean is safe to write in.`;
}

/**
 * `$name` where a signal could stand: the Kotlin value or the signal of that name, which only the
 * author knows. Null when the hole sits in a JavaScript string or an attribute that takes no
 * expression, where no signal was ever possible and the value is what is judged.
 */
function datastarTrap(trap: Issue, ip: Interpolation, landing: HoleLanding, view: string, prefix: string): Issue | null {
  const attribute = landing.attribute;
  if (attribute === null || landing.valueStart < 0) return null;
  const spec = parseAttributeName(asciiLowercase(attribute), prefix)?.spec;
  if (spec?.valueKind !== "expression") return null;
  const quote = view[landing.valueStart - 1] ?? '"';
  const valueEnd = view.indexOf(quote, landing.valueStart);
  const value = view.slice(landing.valueStart, valueEnd < 0 ? view.length : valueEnd);
  const offset = ip.decodedStart - landing.valueStart;
  if (quoteAt(decodeEntities(value.slice(0, offset)).text, decodeEntities(value.slice(0, offset)).text.length) !== null) return null;
  const name = ip.text;
  const fixes = trap.fixes?.filter((f) => f.title.startsWith("Make it") || f.title.startsWith("Escape"));
  // data-signals:count="$initial" seeds a signal from the server, like the object form does.
  const whole = spec.name !== "signals" && value.trim() === PLACEHOLDER;
  return {
    ...trap,
    severity: "warning",
    fixes,
    message: whole
      ? `Kotlin interpolates $${name} here, as the whole Datastar expression. Meant as a server value, only a number or a boolean is safe there; meant as the signal $${name}, make the whole literal $$"""...""" (Kotlin 2.2+), or escape as \${'$'}${name}.`
      : `Kotlin interpolates $${name} inside a Datastar expression. Meant as a server value, only a number or a boolean is safe there; meant as the signal $${name}, make the literal $$"""...""" (Kotlin 2.2+) or escape as \${'$'}${name}.`,
  };
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

function checkExpressionSite(site: CallSite, spec: CallSiteSpec, src: string, hyphenated: ReadonlySet<string>, opts: AnalyzeOptions): Issue[] {
  if (spec.onlyIfStringArgs && site.args.some((a) => a.named === null && a.string === null)) return [];
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const attribute = catalog.attributesByKotlin.get(site.name);
  const issues = expressionHoles(s, src, opts, attribute ? `${catalog.prefix}${attribute.name}` : `the expression of ${site.name}`);
  const text = s.text;
  if (text.includes(PLACEHOLDER) && s.interpolations.some((i) => i.kind === "simple")) return issues;
  issues.push(...mapIssues(s, src, validateExpression(text, undefined, hyphenated, attribute)));
  return issues;
}

/**
 * The templates of a literal Datastar runs as an expression. `$name` where a signal could stand
 * is the trap. Any other template, `${expr}`, one inside a JavaScript string (`@post('/x/$id')`,
 * where Datastar leaves a `$` alone, engine.ts:448 in 1.0.4), or one in a `$$` literal, writes
 * the Kotlin value into code Datastar runs: a `'` in text from outside ends the string and the
 * rest runs. A number or a boolean is safe there, as `interpolate` lets in.
 */
function expressionHoles(s: KotlinString, src: string, opts: AnalyzeOptions, attribute: string): Issue[] {
  const traps = new Map(interpolationIssues(s, src).map((i) => [i.start, i]));
  const out: Issue[] = [];
  for (const ip of s.interpolations) {
    const trap = traps.get(ip.start);
    if (trap && quoteAt(s.text, ip.decodedStart) === null) {
      out.push(trap);
      continue;
    }
    const fact = holeFact(s, src, opts, ip);
    if (fact === "constant" || fact === "number" || fact === "boolean") continue;
    out.push({ start: ip.start, end: ip.end, severity: "warning", code: "unsafe-interpolation", link: DOCS.expressions, message: datastarMessage(holeDisplay(ip, s.dollars), attribute) });
  }
  return out;
}

function checkHtmlSite(site: CallSite, spec: CallSiteSpec, opts: AnalyzeOptions, src: string, hyphenated: ReadonlySet<string>): Issue[] {
  const s = markupString(site, spec, src);
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
  const view = s ? markupView(src, s) : "";
  const issues: Issue[] = s ? judgeHoles(s, src, opts, view) : [];
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
  if (s) issues.push(...mapIssues(s, src, validateMarkup(view, { requireIds, prefix: opts.prefix, checkAttributes: opts.checkHtmlAttributes, hyphenatedSignals: hyphenated })));
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
  if (spec && VALUE_NAME_HELPERS.has(site.name)) return valueNameIssues(site, spec.name);
  if (!spec?.keyed || !spec.keyCase) return [];
  // Only helpers whose first parameter is a key: those whose expression is a later argument
  // (dataOn, dataSignals, dataClass, ...) and the two Pro helpers that take a key and no
  // expression. dataOnClick("expr") passes an expression, never a key.
  const expr = catalog.callSites.expression[site.name];
  if (ATTRIBUTE_NAME_HELPERS.has(site.name)) return attributeNameIssues(site, spec.name, src);
  if (expr ? expr.arg === 0 : !KEY_ONLY_HELPERS.has(site.name)) return [];
  const positional = site.args.filter((a) => a.named === null);
  const namedKey = KEY_PARAMETERS.map((p) => site.args.find((a) => a.named === p)?.string).find((k) => k) ?? null;
  const key = namedKey ?? positional[0]?.string;
  // With an expression helper, one positional string is the object form (dataClass("{...}")); the key form has two.
  const objectForm = !namedKey && expr && positional.length < 2 && !site.args.some((a) => a.named === "expression");
  if (!key || key.interpolations.length > 0 || objectForm) return [];
  const name = key.text;
  // dataSignals("{fooBar: 1}", ...) is the object form: an expression, not a key.
  if (/^\s*[{\[]/.test(name)) return [];
  // A key that names a signal goes through Casing.signalName, which refuses more than a key.
  const signalKey = spec.keyCase === "camel";
  const refused = signalKey ? signalNameRefusal(name) : nameProblem(name);
  if (refused) return [refusedKey(spec.name, key, name, refused)];
  // A hyphen it refuses before the modifiers are joined on, which signalNameHyphenIssues says.
  if (signalKey && name.includes("-")) return [];
  // `case = Case.X`, a positional `Case.X` (dataClass("name", "x", Case.CAMEL)) or `case = Case.X` in the trailing lambda.
  const named = site.args.find((a) => a.named === "case" || (a.named === null && /^\s*Case\.[A-Z]+\s*$/.test(a.text)))?.text;
  const explicit = named ? /Case\.([A-Z]+)/.exec(named) : /\bcase\s*=\s*Case\.([A-Z]+)/.exec(modifiersLambda(site, src));
  const explicitCase = explicit?.[1]?.toLowerCase() ?? null;
  const capital = /[A-Z]/.test(name);
  const wire = capital ? wireKey(name, spec.keyCase, explicitCase) : name + (explicitCase ? `__case.${explicitCase}` : "");
  // What the DSL writes after the key, as Casing.withModifiers is handed it: the case, then the rest.
  if (wire !== null) {
    // The key itself, not what comes before the first __: in b___case.pascal that is b.
    const keyPart = capital ? kebab(name) : name;
    const runs = runsIntoModifiers(keyPart, wire.slice(keyPart.length) + (addsModifiers(site, spec, src) ? "__" : ""));
    if (runs) return [refusedKey(spec.name, key, name, runs)];
  }
  if (!capital) return [];
  if (wire === null) {
    return [{
      start: key.start,
      end: key.end,
      message: `No key gives ${name} back: the browser lowercases attribute names, and with __case.camel or __case.pascal Datastar turns every hyphen before a lower-case letter into a capital and reads ${wireKeyReads(name)}. Write it in one case.`,
      severity: "hint",
      code: "key-case-wire",
      link: attributeDoc(spec.name),
    }];
  }
  const what = explicitCase ? `a ${explicitCase}-cased name` : keyReading(spec, clientKeyName(wire), wire);
  return [{
    start: key.start,
    end: key.end,
    message: `Written on the wire as data-${spec.name}:${wire}, which Datastar reads back as ${what}: the browser lowercases attribute names, so a key is kebab-case. In HTML or a template you write it that way yourself.${rawKeyNote(spec, "data-", name)}`,
    severity: "hint",
    code: "key-case-wire",
    link: attributeDoc(spec.name),
  }];
}

/** A name `dataBind`, `dataRef` or `dataIndicator` writes in the value that `Casing.signalName` refuses, as it does in a key (`signalNameRefusal`). */
function valueNameIssues(site: CallSite, attribute: string): Issue[] {
  const name = (site.args.find((a) => a.named === "signal") ?? site.args.find((a) => a.named === "name") ?? site.args.find((a) => a.named === null))?.string;
  if (!name || name.interpolations.length > 0) return [];
  const split = signalNameRefusal(name.text);
  return split ? [refusedKey(attribute, name, name.text, split)] : [];
}

/** A key the DSL refuses at runtime, as `Casing` refuses it, where a hint would say what it writes. */
function refusedKey(attribute: string, key: KotlinString, name: string, reason: string): Issue {
  return { start: key.start, end: key.end, message: `Streamlord refuses "${name}" at runtime: it ${reason}.`, severity: "error", code: "key-refused", link: attributeDoc(attribute) };
}

/**
 * A capital in the key of `dataAttr` or `dataAnimate`, which the DSL refuses at runtime: the
 * browser lowercases an attribute name, so `viewBox` could only reach `view-box`. The fixes are
 * the spellings HTML may have, lowercased for a name with a hyphen already (`aria-Label`), else
 * kebab-case or one word, which only the attribute tells apart (`aria-label`, `tabindex`); one
 * that really has capitals, such as SVG's `viewBox`, the message sends to the object form.
 */
function attributeNameIssues(site: CallSite, attribute: string, src: string): Issue[] {
  const positional = site.args.filter((a) => a.named === null);
  // The key form takes the name and an expression; one positional string is the object form.
  const key = (site.args.find((a) => a.named === "name") ?? site.args.find((a) => a.named === "attribute"))?.string ?? (positional.length >= 2 || site.args.some((a) => a.named === "expression") ? positional[0]?.string : null);
  if (!key) return [];
  const name = key.text;
  if (key.interpolations.length > 0 || /^\s*[{\[]/.test(name)) return [];
  const split = nameProblem(name);
  if (split) return [refusedKey(attribute, key, name, split)];
  if (!/[A-Z]/.test(name)) {
    const runs = runsIntoModifiers(name, addsModifiers(site, catalog.attributesByKotlin.get(site.name)!, src) ? "__" : "");
    return runs ? [refusedKey(attribute, key, name, runs)] : [];
  }
  const reason = capitalIn(name, site.name)!;
  const plain = src.slice(key.contentStart, key.contentEnd) === name;
  return [{
    ...refusedKey(attribute, key, name, reason),
    code: "attribute-name-case",
    fixes: plain ? spellings(name).map((t) => ({ title: `Change to ${t}`, start: key.contentStart, end: key.contentEnd, text: t })) : [],
  }];
}

// A property set in a lambda of modifiers, `{ once = true }`, and its value; `==` is a comparison and
// `val once = 1` a variable of the lambda's own.
const LAMBDA_ASSIGNMENT = /(?<!\b(?:val|var)\s)\b([A-Za-z]+)\s*=(?!=)\s*([^;\n}]*)/g;
const KOTLIN_STRING = /"(?:[^"\\]|\\.)*"/g;

// The parameter a DSL helper takes its key by, when it is passed by name.
const KEY_PARAMETERS = ["name", "event", "key", "property", "attribute", "signal"];

/**
 * Whether the DSL writes a modifier besides `__case` after the key of `site`, as far as its text
 * shows: `ifMissing` of `dataSignals` and `session` of `dataPersist` set to true, or a modifier of
 * the attribute set in the lambda of modifiers, to anything but false or null. The same rule as
 * `addsModifiers` in streamlord-analysis.
 */
function addsModifiers(site: CallSite, spec: AttributeSpec, src: string): boolean {
  const positional = site.args.filter((a) => a.named === null);
  const on = (parameter: string) => (site.args.find((a) => a.named === parameter) ?? positional[2])?.text.trim() === "true";
  if (site.name === "dataSignals" && on("ifMissing")) return true;
  if (site.name === "dataPersist" && on("session")) return true;
  const names = spec.modifiers.map((m) => m.name.toLowerCase()).filter((n) => n !== "case");
  for (const m of modifiersLambda(site, src).replace(KOTLIN_STRING, '""').matchAll(LAMBDA_ASSIGNMENT)) {
    const property = m[1]!.toLowerCase();
    const value = m[2]!.trim();
    if (value !== "false" && value !== "null" && names.some((n) => property.startsWith(n))) return true;
  }
  return false;
}

/** The lambda of modifiers of `site`: the trailing one, or one passed as an argument, by name or not. */
function modifiersLambda(site: CallSite, src: string): string {
  const trailing = trailingLambdaText(site, src);
  if (trailing) return trailing;
  return (site.args.find((a) => a.named === "modifiers") ?? site.args.find((a) => a.named === null && a.text.trimStart().startsWith("{")))?.text ?? "";
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
  return analyzeHtmlWith(src, opts, hyphenatedSignals(src, "html"));
}

function analyzeHtmlWith(src: string, opts: AnalyzeOptions, hyphenated: ReadonlySet<string>): Issue[] {
  const issues: Issue[] = [];
  for (const tag of tokenize(src).tags) {
    if (tag.closing) continue;
    issues.push(...validateAttributes(tag, opts.prefix, src.slice(tag.start, tag.end), hyphenated));
  }
  return issues;
}
