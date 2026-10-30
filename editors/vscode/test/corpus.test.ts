import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import { validateExpression, type Issue } from "../src/expression.ts";
import { validateMarkup } from "../src/markup.ts";
import { signalReferencesInHtml, unknownSignalIssue } from "../src/signalReferences.ts";
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
  issues: Expected[];
}

const opts = (c: Case) => ({ prefix: c.prefix ?? "data-", checkHtmlAttributes: true });
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
          if (i.fixes?.length) o.fixes = i.fixes.map((f) => c.source.slice(0, f.start) + f.text + c.source.slice(f.end));
          return o;
        });
        assert.deepEqual(seen, c.issues, `${file}: ${c.name}`);
      }
    });
  }
});
