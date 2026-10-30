import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin, htmlStringAt } from "../src/analyze.ts";
import { twinCases } from "./twins.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };

type Case = {
  name: string;
  src: string;
  codes?: string[];
  spans?: string[];
  fix?: { on?: string; title: string; result: string };
  at?: number;
  html?: boolean;
};

describe("patch call sites", () => {
  for (const c of twinCases<Case>("call-sites.json")) {
    it(c.name, () => {
      const { src, fix } = c;
      const issues = analyzeKotlin(src, opts);
      if (c.codes) assert.deepEqual(issues.map((i) => i.code), c.codes, c.name);
      if (c.spans) assert.deepEqual(issues.map((i) => src.slice(i.start, i.end)), c.spans, c.name);
      if (fix) {
        const issue = issues.find((i) => fix.on === undefined || src.slice(i.start, i.end) === fix.on);
        const f = issue?.fixes?.find((x) => x.title.startsWith(fix.title));
        assert.ok(f, c.name);
        assert.equal(src.slice(0, f.start) + f.text + src.slice(f.end), fix.result, c.name);
      }
      if (c.at !== undefined) assert.equal(htmlStringAt(src, c.at) !== null, c.html, c.name);
    });
  }
});
