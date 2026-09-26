import { catalog, type CallSiteSpec } from "./catalog.ts";
import { validateExpression, type Issue } from "./expression.ts";
import { PLACEHOLDER, toSource, type KotlinString } from "./kotlinStrings.ts";
import { tokenize, validateAttributes, validateMarkup } from "./markup.ts";
import { findCallSites, namedArgText, selectStringArg, type CallSite } from "./scanner.ts";

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

export function analyzeKotlin(src: string, opts: AnalyzeOptions): Issue[] {
  const issues: Issue[] = [];
  for (const site of findCallSites(src, ALL_SITE_NAMES)) {
    const expr = catalog.callSites.expression[site.name];
    if (expr) issues.push(...checkExpressionSite(site, expr));
    const html = catalog.callSites.html[site.name];
    if (html) issues.push(...checkHtmlSite(site, html, opts));
    const script = catalog.callSites.script[site.name];
    if (script) issues.push(...checkScriptSite(site, script));
    const selector = catalog.callSites.selector[site.name];
    if (selector) issues.push(...checkSelectorSite(site, selector));
  }
  return issues;
}

function interpolationIssues(s: KotlinString): Issue[] {
  const issues: Issue[] = [];
  for (const ip of s.interpolations) {
    if (ip.kind === "simple") {
      issues.push({
        start: ip.start,
        end: ip.end,
        message: `Kotlin interpolates $${ip.text} here; the browser will never see a signal. Write signal("${ip.text}"), \${'$'}${ip.text} or \\$${ip.text}.`,
        severity: "error",
        code: "kotlin-interpolation",
      });
    }
  }
  return issues;
}

function mapIssues(s: KotlinString, issues: Issue[]): Issue[] {
  return issues.map((i) => {
    const r = toSource(s, i.start, i.end);
    return { ...i, start: r.start, end: r.end };
  });
}

function checkExpressionSite(site: CallSite, spec: CallSiteSpec): Issue[] {
  if (spec.onlyIfStringArgs && site.args.some((a) => a.named === null && a.string === null)) return [];
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const issues = interpolationIssues(s);
  const text = s.text;
  if (text.includes(PLACEHOLDER) && s.interpolations.some((i) => i.kind === "simple")) return issues;
  issues.push(...mapIssues(s, validateExpression(text)));
  return issues;
}

function checkHtmlSite(site: CallSite, spec: CallSiteSpec, opts: AnalyzeOptions): Issue[] {
  const s = selectStringArg(site, spec.arg, spec.named);
  if (!s || s.unterminated) return [];
  const selector = spec.selectorArg ? namedArgText(site, spec.selectorArg) : null;
  const modeText = spec.modeArg ? namedArgText(site, spec.modeArg) : null;
  const mode = modeText ? /\.([A-Z]+)\s*$/.exec(modeText)?.[1]?.toLowerCase() ?? null : null;
  const issues: Issue[] = interpolationIssues(s).map((i) => ({ ...i, severity: "hint" as const, message: `Kotlin interpolates $${i.message.slice(19).split(" ")[0]} into the HTML. Make sure it is escaped.` }));
  if (mode && mode !== "outer" && mode !== "replace" && !selector) {
    issues.push({ start: site.nameStart, end: site.openParen, message: `Mode ${mode.toUpperCase()} requires a selector; Streamlord will reject this event at runtime.`, severity: "error", code: "mode-needs-selector" });
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
