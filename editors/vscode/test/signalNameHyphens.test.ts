import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeKotlin } from "../src/analyze.ts";
import type { Issue } from "../src/expression.ts";
import { twinCases } from "./twins.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };
const hyphens = (src: string): Issue[] => analyzeKotlin(src, opts).filter((i) => i.code === "signal-name-hyphen");
const dsl = "import io.github.markusaugust.streamlord.html.*\n";

type Case = { name: string; src: string; names?: string[]; fixes?: string[][]; messages?: string[]; severities?: string[]; fixed?: string };

describe("a hyphen in a signal name", () => {
  for (const c of twinCases<Case>("signal-name-hyphens.json")) {
    it(c.name, () => {
      const { src } = c;
      const issues = hyphens(src);
      const fixed = issues
        .flatMap((i) => i.fixes ?? [])
        .sort((a, b) => b.start - a.start)
        .reduce((s, f) => s.slice(0, f.start) + f.text + s.slice(f.end), src);
      if (c.names) assert.deepEqual(issues.map((i) => src.slice(i.start + 1, i.end - 1)), c.names, c.name);
      if (c.fixes) assert.deepEqual(issues.map((i) => (i.fixes ?? []).map((f) => f.text)), c.fixes, c.name);
      if (c.messages) assert.deepEqual(issues.map((i) => i.message), c.messages, c.name);
      if (c.severities) assert.deepEqual(issues.map((i) => i.severity), c.severities, c.name);
      if (c.fixed !== undefined) assert.equal(fixed, c.fixed, c.name);
    });
  }

  // The table DataSignalsSecondArgumentTest in streamlord-analysis compiles: `kotlin` is the overload the compiler picked.
  it("reads the second argument of dataSignals as the compiler does", () => {
    type Row = { argument: string; kotlin: string; analysis: string };
    const table = JSON.parse(readFileSync(new URL("./fixtures/data-signals-second-argument.json", import.meta.url), "utf8")) as { cases: Row[] };
    assert.equal(table.cases.length, 32);
    for (const { argument, kotlin, analysis } of table.cases) {
      assert.ok(analysis === "unknown" || analysis === kotlin, argument);
      const src = `${dsl}fun p(mode: String, flag: Boolean, expr: String, n: Int) = div { dataSignals("a-b", ${argument}) }`;
      assert.equal(hyphens(src).length > 0, analysis === "expression", argument);
    }
  });
});
