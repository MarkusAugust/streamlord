import { catalog, parseAttributeName, type CallSiteSpec } from "./catalog.ts";
import { DOCS, validateExpression, type Fix, type Issue } from "./expression.ts";
import { PLACEHOLDER, toSource, type Interpolation, type KotlinString } from "./kotlinStrings.ts";
import { tokenize, validateAttributes, validateMarkup } from "./markup.ts";
import { findCallSites, findKotlinStrings, isHtmlString, namedArgText, selectStringArg, stringAt, type CallSite } from "./scanner.ts";

/**
 * Editor-independent analysis: Kotlin source in, issues with source offsets out.
 */

export interface AnalyzeOptions {
  prefix: string;
  checkHtmlAttributes: boolean;
}

const ALL_SITE_NAMES: ReadonlySet<string> = new Set([
  ...Object.keys(catalog.callSites.expression),
  ...Object.keys(catalog.callSites.html),
  ...Object.keys(catalog.callSites.script),
  ...Object.keys(catalog.callSites.selector),
]);

const HTML_SITE_NAMES: ReadonlySet<string> = new Set(Object.keys(catalog.callSites.html));

export function analyzeKotlin(src: string, opts: AnalyzeOptions): Issue[] {
  const issues: Issue[] = [];
  const claimed = new Set<number>();
  for (const site of findCallSites(src, ALL_SITE_NAMES)) {
    const expr = catalog.callSites.expression[site.name];
    if (expr) issues.push(...checkExpressionSite(site, expr, src));
    const html = catalog.callSites.html[site.name];
    if (html) issues.push(...checkHtmlSite(site, html, opts, src));
    const script = catalog.callSites.script[site.name];
    if (script) issues.push(...checkScriptSite(site, script));
    const selector = catalog.callSites.selector[site.name];
    if (selector) issues.push(...checkSelectorSite(site, selector));
    for (const a of site.args) if (a.string) claimed.add(a.string.start);
  }
  for (const s of findKotlinStrings(src)) {
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
  const s = stringAt(src, offset);
  if (!s) return null;
  if (isHtmlString(src, s)) return s;
  const site = findCallSites(src, HTML_SITE_NAMES).find((c) => c.openParen < s.start && s.end <= c.closeParen + 1);
  if (!site) return null;
  const spec = catalog.callSites.html[site.name];
  const arg = spec ? selectStringArg(site, spec.arg, spec.named) : null;
  return arg && arg.start === s.start ? s : null;
}

/**
 * A string that holds HTML but is not handed straight to a Streamlord call: a function that
 * returns markup, a `val` with a fragment. Nothing is known about how it will be patched, so
 * only the attributes are checked, as in a template file; ids and completeness are not.
 */
function checkFreeHtmlString(s: KotlinString, opts: AnalyzeOptions, src: string): Issue[] {
  const issues: Issue[] = interpolationHints(s, src);
  if (opts.checkHtmlAttributes) issues.push(...mapIssues(s, analyzeHtml(s.text, opts)));
  return issues;
}

/** The DSL helper that says the same as a whole-string expression, when there is one. */
const HELPERS: [RegExp, (n: string) => string][] = [
  [/^\$([A-Za-z_][A-Za-z0-9_.]*)$/, (n) => `signal("${n}")`],
  [/^\$([A-Za-z_][A-Za-z0-9_.]*)\+\+$/, (n) => `increment("${n}")`],
  [/^\$([A-Za-z_][A-Za-z0-9_.]*)--$/, (n) => `decrement("${n}")`],
  [/^!\$([A-Za-z_][A-Za-z0-9_.]*)$/, (n) => `not("${n}")`],
];

/**
 * The whole literal rewritten as a `$$` literal (Kotlin 2.2+): the flagged template becomes a
 * signal by staying as it is, every other template gains a dollar so it stays Kotlin, and the
 * `${'$'}` idiom becomes the plain dollar it always meant.
 */
function multiDollarFix(s: KotlinString, src: string, signal: Interpolation): Fix {
  const keep = new Set(s.interpolations.filter((ip) => ip !== signal).map((ip) => ip.start));
  let out = "$$" + src.slice(s.start, s.contentStart);
  let i = s.contentStart;
  while (i < s.contentEnd) {
    if (keep.has(i)) out += "$";
    if (src.startsWith("${'$'}", i)) {
      out += "$";
      i += 6;
      continue;
    }
    out += src[i];
    i++;
  }
  out += src.slice(s.contentEnd, s.end);
  return { title: `Make it a $$ literal, where $${signal.text} is a signal`, start: s.start, end: s.end, text: out };
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
      for (const [re, helper] of HELPERS) {
        const m = re.exec(meant);
        if (m?.[1]) fixes.unshift({ title: `Use ${helper(m[1])}`, start: s.start, end: s.end, text: helper(m[1]) });
      }
      const toggle = /^\$([A-Za-z_][A-Za-z0-9_.]*) = !\$\1$/.exec(meant);
      if (toggle?.[1]) fixes.unshift({ title: `Use toggle("${toggle[1]}")`, start: s.start, end: s.end, text: `toggle("${toggle[1]}")` });
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
function interpolationHints(s: KotlinString, src: string): Issue[] {
  const tags = tokenize(s.text).tags;
  const inExpression = (decoded: number) =>
    tags.some((t) =>
      t.attributes.some((a) => {
        if (a.value === null || decoded < a.valueStart || decoded >= a.valueStart + a.value.length) return false;
        const parsed = a.name.toLowerCase().startsWith("data-") ? parseAttributeName(a.name.toLowerCase(), "data-") : null;
        return parsed?.spec?.valueKind === "expression";
      }),
    );
  return interpolationIssues(s, src).map((i) => {
    const ip = s.interpolations.find((x) => x.start === i.start);
    const name = ip?.text ?? "";
    if (ip && inExpression(ip.decodedStart)) {
      return {
        ...i,
        fixes: i.fixes?.filter((f) => f.title.startsWith("Make it") || f.title.startsWith("Escape")),
        message: `Kotlin interpolates $${name} here, inside a Datastar expression; the browser will never see a signal. Make the whole literal $$"""...""" (Kotlin 2.2+), or escape as \${'$'}${name}.`,
      };
    }
    return { ...i, severity: "hint" as const, fixes: undefined, message: `Kotlin interpolates $${name} into the HTML. Make sure it is escaped.` };
  });
}

function mapIssues(s: KotlinString, issues: Issue[]): Issue[] {
  return issues.map((i) => {
    const r = toSource(s, i.start, i.end);
    const fixes = i.fixes?.map((f) => {
      const fr = f.start === f.end ? { start: toSource(s, f.start, f.start + 1).start, end: toSource(s, f.start, f.start + 1).start } : toSource(s, f.start, f.end);
      return { ...f, start: fr.start, end: fr.end };
    });
    return { ...i, start: r.start, end: r.end, fixes };
  });
}

function checkExpressionSite(site: CallSite, spec: CallSiteSpec, src: string): Issue[] {
  if (spec.onlyIfStringArgs && site.args.some((a) => a.named === null && a.string === null)) return [];
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const issues = interpolationIssues(s, src);
  const text = s.text;
  if (text.includes(PLACEHOLDER) && s.interpolations.some((i) => i.kind === "simple")) return issues;
  issues.push(...mapIssues(s, validateExpression(text)));
  return issues;
}

function checkHtmlSite(site: CallSite, spec: CallSiteSpec, opts: AnalyzeOptions, src: string): Issue[] {
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const selector = spec.selectorArg ? namedArgText(site, spec.selectorArg) : null;
  const modeArg = spec.modeArg ? site.args.find((a) => a.named === spec.modeArg) : undefined;
  const modeText = modeArg?.text ?? null;
  const mode = modeText ? /\.([A-Z]+)\s*$/.exec(modeText)?.[1]?.toLowerCase() ?? null : null;
  const issues: Issue[] = interpolationHints(s, src);
  if (mode && mode !== "outer" && mode !== "replace" && !selector && modeArg) {
    const at = modeArg.start - (spec.modeArg?.length ?? 0) - src.slice(0, modeArg.start).match(/\s*=\s*$/)![0].length;
    issues.push({
      start: site.nameStart,
      end: site.openParen,
      message: `Mode ${mode.toUpperCase()} requires a selector; Streamlord will reject this event at runtime.`,
      severity: "error",
      code: "mode-needs-selector",
      link: DOCS.sse,
      fixes: [{ title: 'Add selector = ""', start: at, end: at, text: 'selector = "", ' }],
    });
  }
  const requireIds = !selector && (mode === null || mode === "outer");
  issues.push(...mapIssues(s, validateMarkup(s.text, { requireIds, prefix: opts.prefix, checkAttributes: opts.checkHtmlAttributes })));
  return issues;
}

function checkScriptSite(site: CallSite, spec: CallSiteSpec): Issue[] {
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const issues: Issue[] = [];
  const idx = s.text.toLowerCase().indexOf("</script");
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
    issues.push(...validateAttributes(tag, opts.prefix));
  }
  return issues;
}
