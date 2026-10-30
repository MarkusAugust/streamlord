import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeHtml, htmlStringAt } from "../src/analyze.ts";
import { readKotlinStringAt, type KotlinString } from "../src/kotlinStrings.ts";
import { findCallSites, findKotlinStrings } from "../src/scanner.ts";
import { collectSignals } from "../src/signals.ts";
import { collectSelectors } from "../src/selectors.ts";
import { twinCases } from "./twins.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };

interface Case {
  name: string;
  kind: string;
  source: string;
  [field: string]: unknown;
}

const literal = (s: KotlinString | null) =>
  s && {
    start: s.start,
    end: s.end,
    raw: s.raw,
    dollars: s.dollars,
    contentStart: s.contentStart,
    contentEnd: s.contentEnd,
    text: s.text,
    map: s.map,
    interpolations: s.interpolations.map((i) => ({ start: i.start, end: i.end, kind: i.kind, text: i.text, decodedStart: i.decodedStart })),
    unterminated: s.unterminated,
  };

/** What each kind of case expects, in the fields `twins/analyze.json` describes; `AnalyzerTest.kt` projects the same. */
const project: Record<string, (c: Case) => Record<string, unknown>> = {
  "kotlin-string": (c) => ({ string: literal(readKotlinStringAt(c.source, c.at as number)) }),
  "call-sites": (c) => ({
    sites: findCallSites(c.source, new Set(c.names as string[])).map((s) => ({
      name: s.name,
      trailingLambda: s.trailingLambda,
      args: s.args.map((a) => ({ named: a.named, text: a.text, string: a.string?.text ?? null })),
    })),
  }),
  "kotlin-strings": (c) => ({ texts: findKotlinStrings(c.source).map((s) => s.text) }),
  "html-string-at": (c) => ({
    at: (c.at as { needle: string }[]).map(({ needle }) => ({ needle, raw: htmlStringAt(c.source, c.source.indexOf(needle) + 1)?.raw ?? null })),
  }),
  signals: (c) => ({ signals: [...collectSignals(c.source, c.language as "kotlin" | "html")].sort() }),
  selectors: (c) => {
    const s = collectSelectors(c.source);
    return { ids: [...s.ids].sort(), classes: [...s.classes].sort() };
  },
  fixture: (c) => ({ codes: analyzeHtml(readFileSync(new URL(`./fixtures/${c.file}`, import.meta.url), "utf8"), opts).map((i) => i.code) }),
};

describe("the analysis's twin cases", () => {
  for (const c of twinCases<Case>("analyze.json")) {
    it(c.name, () => {
      const actual = project[c.kind]!(c);
      assert.deepEqual(actual, Object.fromEntries(Object.keys(actual).map((k) => [k, c[k]])));
    });
  }
});
