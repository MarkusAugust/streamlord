import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mergePatchAsClient, PROTO_REFUSED, SignalPatchError, STORE_PROTO_REFUSED } from "../src/sse.ts";

/**
 * The scenarios of streamlord-core's `MergePatchAsClientTest`, read from `conformance/dom/` so both
 * folds answer to the same cases. Each was run through `mergePatch` from the Datastar 1.0.4 source
 * (engine/signals.ts), and `client` is `JSON.stringify` of its root after each step, or `THROWS`
 * and the root once the step threw. A step with `refused` is one the fold refuses on purpose, a key
 * `__proto__` anywhere in the patch; its `kept` is the client's root after the patch cut before the
 * key that holds the `__proto__`, and the client takes the rest of the scenario from there.
 */
const resources = new URL("../../../conformance/dom/", import.meta.url);
const read = <T>(file: string): T => JSON.parse(readFileSync(new URL(file, resources), "utf8")) as T;

type Step = { signals: string; onlyIfMissing: boolean; client: string; refused?: boolean; kept?: string };

describe("mergePatchAsClient", () => {
  it("holds what the client holds", () => {
    const cases = read<{ name: string; steps: Step[]; client: string }[]>("store-cases.json");
    assert.equal(cases.length, 27);
    for (const c of cases) {
      let store: Record<string, unknown> = {};
      for (const step of c.steps) store = mergePatchAsClient(store, JSON.parse(step.signals), step.onlyIfMissing);
      assert.equal(JSON.stringify(store), c.client, c.name);
    }
  });

  it("folds each step as the client's own mergePatch does", () => {
    const scenarios = read<{ name: string; store?: string; steps: Step[] }[]>("merge-differential.json");
    assert.equal(scenarios.length, 366);
    for (const s of scenarios) {
      let store = (s.store ? JSON.parse(s.store) : {}) as Record<string, unknown>;
      s.steps.forEach((step, i) => {
        let got: string;
        try {
          store = mergePatchAsClient(store, JSON.parse(step.signals), step.onlyIfMissing);
          got = JSON.stringify(store);
        } catch (e) {
          assert.ok(e instanceof SignalPatchError, `${s.name} step ${i}: ${e}`);
          assert.equal(e.refused, step.refused === true, `${s.name} step ${i}`);
          if (step.refused) assert.equal(e.message, PROTO_REFUSED);
          store = e.store;
          got = `THROWS ${JSON.stringify(store)}`;
        }
        assert.equal(got, step.kept !== undefined ? `THROWS ${step.kept}` : step.client, `${s.name} step ${i}`);
      });
    }
  });

  // Measured against the 1.0.4 source: the client gives the object a foreign prototype, then
  // throws on some later keys, replaces the object on a later patch, or pollutes String.prototype.
  it("refuses a key __proto__ anywhere in the patch, whatever it holds", () => {
    const store = { o: { k: 1 }, a: [1] };
    for (const value of ["null", "1", '"s"', "true", "[]", "[1]", "{}", '{"x":1}', '{"__proto__":{"y":1}}']) {
      for (const [patch, kept] of [
        [`{"b":1,"__proto__":${value}}`, '{"o":{"k":1},"a":[1],"b":1}'],
        [`{"o":{"m":1,"__proto__":${value},"n":2}}`, '{"o":{"k":1,"m":1},"a":[1]}'],
        [`{"b":1,"l":[0,{"__proto__":${value},"k":1}],"c":2}`, '{"o":{"k":1},"a":[1],"b":1}'],
        [`{"o":{"m":1,"l":[[{"k":{"__proto__":${value}}}]]}}`, '{"o":{"k":1,"m":1},"a":[1]}'],
        [`{"a":{"0":2,"1":[{"__proto__":${value}}]}}`, '{"o":{"k":1},"a":[2]}'],
        [`[5,[{"__proto__":${value}}]]`, '{"0":5,"o":{"k":1},"a":[1]}'],
      ] as const) {
        for (const ifMissing of [false, true]) {
          assert.throws(
            () => mergePatchAsClient(store, JSON.parse(patch), ifMissing),
            (e: unknown) => e instanceof SignalPatchError && e.message === PROTO_REFUSED && e.refused && (ifMissing || JSON.stringify(e.store) === kept),
            patch,
          );
        }
      }
    }
    assert.equal(JSON.stringify(store), '{"o":{"k":1},"a":[1]}');
  });

  // A store the client never holds: refused where a patch reaches the object with the key.
  it("refuses to fold into an object of the store that has a key __proto__", () => {
    const store = JSON.parse('{"a":[{"k":1},{"__proto__":null,"k":2}],"o":{"k":1}}') as Record<string, unknown>;
    assert.equal(JSON.stringify(mergePatchAsClient(store, { a: { 0: { m: 1 } } })), '{"a":[{"k":1,"m":1},{"__proto__":null,"k":2}],"o":{"k":1}}');
    assert.throws(
      () => mergePatchAsClient(store, JSON.parse('{"o":{"j":1},"a":{"1":{"m":1}}}')),
      (e: unknown) => e instanceof SignalPatchError && e.message === STORE_PROTO_REFUSED && e.refused && JSON.stringify(e.store) === '{"a":[{"k":1},{"__proto__":null,"k":2}],"o":{"k":1,"j":1}}',
    );
    const top = JSON.parse('{"__proto__":null,"k":1}') as Record<string, unknown>;
    assert.throws(() => mergePatchAsClient(top, { x: 1 }), (e: unknown) => e instanceof SignalPatchError && e.refused);
  });

  it("tells a patch the client throws on from one refused on purpose", () => {
    const store = { a: [1] };
    for (const patch of ['{"a":{"20000":1}}', '{"a":{"length":20000}}']) {
      assert.throws(() => mergePatchAsClient(store, JSON.parse(patch)), (e: unknown) => e instanceof SignalPatchError && e.refused, patch);
    }
    for (const length of ["-1", '"x"', "1.5"]) {
      assert.throws(() => mergePatchAsClient(store, JSON.parse(`{"a":{"length":${length}}}`)), (e: unknown) => e instanceof SignalPatchError && !e.refused, length);
    }
  });

  it("hands back a store without a prototype for a patch that patches nothing", () => {
    for (const patch of [5, true, null]) {
      const out = mergePatchAsClient({ a: 1 }, patch);
      assert.equal(Object.getPrototypeOf(out), null, String(patch));
      assert.equal(JSON.stringify(out), '{"a":1}');
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
