import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { clientRewrite } from "../src/expression.ts";

/**
 * The client's rewrite grows with the expression, not with its square: a scan that searches on to
 * the end of the text at every template literal, or goes through the signals of a `${...}` again
 * at each action in it, takes seconds on these, and one that reads the text once some tens of
 * milliseconds. ClientRewriteTimingTest.kt holds the Kotlin scan to the same.
 */
describe("the client's rewrite of a long expression", () => {
  const quick = (what: string, block: () => void) => {
    block();
    const started = performance.now();
    block();
    const ms = performance.now() - started;
    assert.ok(ms < 2000, `${what} took ${Math.round(ms)} ms`);
  };

  it("reads many short template literals once", () => {
    const text = "``".repeat(200_000);
    quick("200000 empty template literals", () => assert.equal(clientRewrite(text).runs.length, 200_000));
  });

  it("reads many references in one ${...} once", () => {
    const text = "`${" + "$s + ".repeat(20_000) + "1}`";
    quick("20000 references in one ${...}", () => assert.equal(clientRewrite(text).rewrites.length, 20_000));
  });

  it("does not hold an action in a ${...} against every signal in it", () => {
    const text = "`${" + "$s + @a() + ".repeat(50_000) + "1}`";
    quick("50000 signals and 50000 actions in one ${...}", () => assert.equal(clientRewrite(text).rewrites.length, 100_000));
  });
});
