import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import { applyFix, validateExpression, type Issue } from "../src/expression.ts";
import { validateMarkup } from "../src/markup.ts";
import { findCallSites, lexKotlin } from "../src/scanner.ts";
import { signalReferencesInHtml, signalReferencesInKotlin, unknownSignalIssue } from "../src/signalReferences.ts";
import { collectSignalDefinitions } from "../src/signals.ts";

/**
 * The corpus in `fixtures/corpus` is read by this suite and by `CorpusTest.kt`, so the two
 * editors are held to the same cases. The format is described in each file.
 */

interface Expected {
  code: string;
  start?: number;
  end?: number;
  severity?: string;
  message?: string;
  fixes?: string[];
}
interface Case {
  name: string;
  source: string;
  requireIds?: boolean;
  /** The attribute prefix of an aliased bundle; the default bundle's otherwise. */
  prefix?: string;
  /** The workspace's `const val` names. */
  constants?: string[];
  /** Whether the project has streamlord-html, as the host knows it; the file decides otherwise. */
  htmlDsl?: boolean;
  /** false: the project has no streamlord-core. */
  core?: boolean;
  /** The calls the host knows are not Streamlord's: every call of these names in the source. */
  foreign?: string[];
  issues: Expected[];
}

/**
 * Where each call of `names` in `text` starts, those in the code of a `${...}` template too, read
 * as the hyphen check reads them (collectHyphenSites): offsets of the whole source.
 */
function callStarts(text: string, names: ReadonlySet<string>, shift = 0, depth = 0): number[] {
  const lex = lexKotlin(text);
  const out = findCallSites(text, names, lex.mask).map((s) => s.nameStart + shift);
  if (depth >= 4) return out;
  for (const s of lex.strings) {
    for (const t of s.interpolations) {
      if (t.kind !== "braced") continue;
      const open = text.indexOf("{", t.start);
      if (open < 0 || open + 1 > t.end - 1 || text.slice(open + 1, t.end - 1) !== t.text) continue;
      out.push(...callStarts(t.text, names, shift + open + 1, depth + 1));
    }
  }
  return out;
}

/** Where every call of the case's foreign names starts, as the scanner finds the calls. */
const foreignCalls = (c: Case) => new Set(c.foreign ? callStarts(c.source, new Set(c.foreign)) : []);
const opts = (c: Case) => ({
  prefix: c.prefix ?? "data-",
  checkHtmlAttributes: true,
  constants: new Set(c.constants ?? []),
  htmlDsl: c.htmlDsl ?? null,
  core: c.core ?? null,
  foreignCalls: foreignCalls(c),
});
const run: Record<string, (c: Case) => Issue[]> = {
  expression: (c) => validateExpression(c.source),
  markup: (c) => validateMarkup(c.source, { requireIds: c.requireIds ?? false, prefix: c.prefix ?? "data-", checkAttributes: true }),
  html: (c) => analyzeHtml(c.source, opts(c)),
  kotlin: (c) => analyzeKotlin(c.source, opts(c)),
  // The signals the source reads that it does not itself declare.
  references: (c) => {
    const defined = collectSignalDefinitions(c.source, "html");
    return signalReferencesInHtml(c.source, c.prefix ?? "data-").flatMap((r) => unknownSignalIssue(r, defined) ?? []);
  },
  // The same of a Kotlin source: the signals its DSL calls and HTML strings read that it does not declare.
  "kotlin-references": (c) => {
    const defined = collectSignalDefinitions(c.source, "kotlin");
    return signalReferencesInKotlin(c.source, c.prefix ?? "data-", opts(c)).flatMap((r) => unknownSignalIssue(r, defined) ?? []);
  },
};
const byCode = (a: Issue, b: Issue) => ((a.code ?? "") < (b.code ?? "") ? -1 : (a.code ?? "") > (b.code ?? "") ? 1 : 0);
const dir = new URL("./fixtures/corpus/", import.meta.url);

describe("shared corpus", () => {
  for (const file of readdirSync(dir).sort()) {
    const corpus = JSON.parse(readFileSync(new URL(file, dir), "utf8")) as { kind: string; cases: Case[] };
    it(`judges every case in ${file} as written`, () => {
      for (const c of corpus.cases) {
        const actual = [...run[corpus.kind]!(c)].sort((a, b) => a.start - b.start || a.end - b.end || byCode(a, b));
        const seen = actual.map((i, n) => {
          const o: Expected = { code: i.code ?? "" };
          // An expected issue without a range is checked by code only; a severity or a message is checked where the case gives one.
          if (c.issues[n]?.start !== undefined || !c.issues[n]) Object.assign(o, { start: i.start, end: i.end });
          if (c.issues[n]?.severity !== undefined) o.severity = i.severity;
          if (c.issues[n]?.message !== undefined) o.message = i.message;
          if (i.fixes?.length) o.fixes = i.fixes.map((f) => applyFix(c.source, f));
          return o;
        });
        assert.deepEqual(seen, c.issues, `${file}: ${c.name}`);
      }
    });
  }
});
