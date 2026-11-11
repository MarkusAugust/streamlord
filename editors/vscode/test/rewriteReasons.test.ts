import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { validateExpression } from "../src/expression.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; source: string; code: string; written: string; holds: string[]; lacks: string[] };

/** A warning about what the client did with a reference gives the reason that holds for that reference. RewriteReasonsTest.kt reads the same cases. */
describe("the reason a rewrite warning gives", () => {
  for (const c of twinCases<Case>("rewrite-reasons.json")) {
    it(c.name, () => {
      const issues = validateExpression(c.source, true).filter((i) => i.code === c.code && c.source.slice(i.start, i.end) === c.written);
      assert.equal(issues.length, 1, `${c.code} on ${c.written}`);
      const { message } = issues[0]!;
      for (const words of c.holds) assert.ok(message.includes(words), `"${words}" is missing from: ${message}`);
      for (const words of c.lacks) assert.ok(!message.includes(words), `"${words}" is in: ${message}`);
    });
  }
});
