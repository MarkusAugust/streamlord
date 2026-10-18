import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mergePatchAsClient, SignalPatchError } from "../src/sse.ts";

/**
 * The scenarios of streamlord-core's `MergePatchAsClientTest`, read from its resources so both
 * folds answer to the same cases. Each was run through `mergePatch` from the Datastar 1.0.4 source
 * (engine/signals.ts), and `client` is `JSON.stringify` of its root after each step, or `THROWS`
 * and the root once the step threw.
 */
const resources = new URL("../../../streamlord-core/src/test/resources/", import.meta.url);
const read = <T>(file: string): T => JSON.parse(readFileSync(new URL(file, resources), "utf8")) as T;

type Step = { signals: string; onlyIfMissing: boolean; client: string };

describe("mergePatchAsClient", () => {
  it("holds what the client holds", () => {
    const cases = read<{ name: string; steps: Step[]; client: string }[]>("client-store-cases.json");
    assert.equal(cases.length, 27);
    for (const c of cases) {
      let store: Record<string, unknown> = {};
      for (const step of c.steps) store = mergePatchAsClient(store, JSON.parse(step.signals), step.onlyIfMissing);
      assert.equal(JSON.stringify(store), c.client, c.name);
    }
  });

  it("folds each step as the client's own mergePatch does", () => {
    const scenarios = read<{ name: string; store?: string; steps: Step[] }[]>("client-merge-differential.json");
    assert.equal(scenarios.length, 339);
    for (const s of scenarios) {
      let store = (s.store ? JSON.parse(s.store) : {}) as Record<string, unknown>;
      s.steps.forEach((step, i) => {
        let got: string;
        try {
          store = mergePatchAsClient(store, JSON.parse(step.signals), step.onlyIfMissing);
          got = JSON.stringify(store);
        } catch (e) {
          assert.ok(e instanceof SignalPatchError, `${s.name} step ${i}: ${e}`);
          store = e.store;
          got = `THROWS ${JSON.stringify(store)}`;
        }
        assert.equal(got, step.client, `${s.name} step ${i}`);
      });
    }
  });

  it("leaves the store it is given as it was", () => {
    const store = { a: [1, 2], o: { k: 1 } };
    const before = JSON.stringify(store);
    assert.throws(() => mergePatchAsClient(store, { o: { m: 1 }, a: { 0: 9, length: -1 } }), SignalPatchError);
    mergePatchAsClient(store, { o: { k: null }, a: { 5: 1 } });
    assert.equal(JSON.stringify(store), before);
  });
});
