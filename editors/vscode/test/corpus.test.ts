import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import { validateExpression, type Issue } from "../src/expression.ts";
import { validateMarkup } from "../src/markup.ts";

/**
 * The corpus in `fixtures/corpus` is read by this suite and by `CorpusTest.kt`, so the two
 * editors are held to the same cases. The format is described in each file.
 */

interface Expected {
  code: string;
  start?: number;
  end?: number;
  fixes?: string[];
}
interface Case {
  name: string;
  source: string;
  requireIds?: boolean;
  issues: Expected[];
}

const opts = { prefix: "data-", checkHtmlAttributes: true };
const run: Record<string, (c: Case) => Issue[]> = {
  expression: (c) => validateExpression(c.source),
  markup: (c) => validateMarkup(c.source, { requireIds: c.requireIds ?? false, prefix: "data-", checkAttributes: true }),
  html: (c) => analyzeHtml(c.source, opts),
  kotlin: (c) => analyzeKotlin(c.source, opts),
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
          // An expected issue without a range is checked by code only.
          if (c.issues[n]?.start !== undefined || !c.issues[n]) Object.assign(o, { start: i.start, end: i.end });
          if (i.fixes?.length) o.fixes = i.fixes.map((f) => c.source.slice(0, f.start) + f.text + c.source.slice(f.end));
          return o;
        });
        assert.deepEqual(seen, c.issues, `${file}: ${c.name}`);
      }
    });
  }
});
