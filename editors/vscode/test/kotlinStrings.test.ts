import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin } from "../src/analyze.ts";
import { readKotlinStringAt } from "../src/kotlinStrings.ts";
import { findCallSites } from "../src/scanner.ts";
import { twinCases } from "./twins.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };

type Case = {
  name: string;
  src: string;
  at?: number;
  text?: string;
  interpolations?: string[][];
  calls?: string[];
  sites?: (string | null)[][];
  codes?: string[];
  starts?: number[];
};

describe("kotlin string reader", () => {
  for (const c of twinCases<Case>("kotlin-strings.json")) {
    it(c.name, () => {
      if (c.at !== undefined) {
        const s = readKotlinStringAt(c.src, c.at);
        assert.ok(s, c.name);
        if (c.text !== undefined) assert.equal(s.text, c.text, c.name);
        if (c.interpolations) assert.deepEqual(s.interpolations.map((i) => [i.kind, i.text]), c.interpolations, c.name);
      }
      if (c.sites) {
        const sites = findCallSites(c.src, new Set(c.calls!));
        assert.deepEqual(sites.map((site) => site.args.map((a) => a.string?.text ?? null)), c.sites, c.name);
      }
      const issues = analyzeKotlin(c.src, opts);
      if (c.codes) assert.deepEqual(issues.map((i) => i.code), c.codes, c.name);
      if (c.starts) assert.deepEqual(issues.map((i) => i.start), c.starts, c.name);
    });
  }
});
