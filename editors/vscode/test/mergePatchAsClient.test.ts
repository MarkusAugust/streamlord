import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mergePatchAsClient, SignalPatchError } from "../src/sse.ts";

/**
 * The scenarios of streamlord-core's `MergePatchAsClientTest`, read from its resources so both
 * folds answer to the same cases. Each was run through `mergePatch` from the Datastar 1.0.4 source
 * (engine/signals.ts), and `client` is `JSON.stringify` of its root after each step, or `THROWS`
 * and the root once the step threw. A step with `refused` is one the fold throws on by design, a
 * `__proto__` that is not `null`; its `kept` is the client's root after the patch cut off before
 * that key, and the client takes the rest of the scenario from there.
 */
const resources = new URL("../../../streamlord-core/src/test/resources/", import.meta.url);
const read = <T>(file: string): T => JSON.parse(readFileSync(new URL(file, resources), "utf8")) as T;

type Step = { signals: string; onlyIfMissing: boolean; client: string; refused?: string; kept?: string };

const PROTO_MESSAGE = "The client sets a signal named __proto__ as the prototype of the object holding it, and its store cannot be shown as JSON after that";

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
    assert.equal(scenarios.length, 350);
    for (const s of scenarios) {
      let store = (s.store ? JSON.parse(s.store) : {}) as Record<string, unknown>;
      s.steps.forEach((step, i) => {
        let got: string;
        try {
          store = mergePatchAsClient(store, JSON.parse(step.signals), step.onlyIfMissing);
          got = JSON.stringify(store);
        } catch (e) {
          assert.ok(e instanceof SignalPatchError, `${s.name} step ${i}: ${e}`);
          if (step.refused !== undefined) assert.equal(e.message, PROTO_MESSAGE);
          store = e.store;
          got = `THROWS ${JSON.stringify(store)}`;
        }
        assert.equal(got, step.kept !== undefined ? `THROWS ${step.kept}` : step.client, `${s.name} step ${i}`);
      });
    }
  });

  it("refuses __proto__ with a value and passes over it with null", () => {
    const store = { o: { k: 1 }, a: [1] };
    for (const value of ["1", '"s"', "true", "[]", "[1]", "{}", '{"x":1}']) {
      for (const [patch, kept] of [
        [`{"b":1,"__proto__":${value}}`, '{"o":{"k":1},"a":[1],"b":1}'],
        [`{"o":{"m":1,"__proto__":${value},"n":2}}`, '{"o":{"k":1,"m":1},"a":[1]}'],
      ] as const) {
        assert.throws(
          () => mergePatchAsClient(store, JSON.parse(patch)),
          (e: unknown) => e instanceof SignalPatchError && e.message === PROTO_MESSAGE && JSON.stringify(e.store) === kept,
          patch,
        );
      }
    }
    assert.equal(JSON.stringify(mergePatchAsClient(store, JSON.parse('{"o":{"__proto__":null,"m":1}}'))), '{"o":{"k":1,"m":1},"a":[1]}');
    // In an array value the key is not shown, and the object that had it is replaced by the next patch into it.
    const held = mergePatchAsClient(store, JSON.parse('{"a":[{"__proto__":null,"k":1}]}'));
    assert.equal(JSON.stringify(held), '{"o":{"k":1},"a":[{"k":1}]}');
    const next = mergePatchAsClient(held, { o: { j: 1 } });
    assert.equal(JSON.stringify(mergePatchAsClient(next, { a: { 0: { z: 2 } } })), '{"o":{"k":1,"j":1},"a":[{"z":2}]}');
  });

  it("leaves the store it is given as it was", () => {
    const store = { a: [1, 2], o: { k: 1 } };
    const before = JSON.stringify(store);
    assert.throws(() => mergePatchAsClient(store, { o: { m: 1 }, a: { 0: 9, length: -1 } }), SignalPatchError);
    mergePatchAsClient(store, { o: { k: null }, a: { 5: 1 } });
    assert.equal(JSON.stringify(store), before);
  });
});
