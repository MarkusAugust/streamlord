import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { clientRewrite } from "../src/expression.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; source: string; js: string; runs: [number, number, number][]; rewrites: [string, number, number, string][] };

/** The client's rewrite gives the same text, runs and rewrites here as in Kotlin: ClientRewriteTwinTest.kt reads the same cases. */
describe("the client's rewrite, as both twins give it", () => {
  for (const c of twinCases<Case>("client-rewrites.json")) {
    it(c.name, () => {
      const read = clientRewrite(c.source);
      assert.equal(read.js, c.js);
      assert.deepEqual(read.runs.map((r) => [r.start, r.end, r.quote]), c.runs);
      assert.deepEqual(read.rewrites.map((r) => [r.kind, r.start, r.end, r.name]), c.rewrites);
    });
  }
});
