import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { catalog } from "../src/catalog.ts";
import { actionCallAt, valueAtCaret } from "../src/completionContext.ts";
import { twinCases } from "./twins.ts";

type Case = {
  name: string;
  prefix?: string;
  before: string;
  after: string;
  attribute: string | null;
  value?: string;
};

describe("completion contexts", () => {
  for (const c of twinCases<Case>("completion-contexts.json")) {
    it(c.name, () => {
      const at = valueAtCaret(c.before + c.after, c.before.length, c.prefix ?? "data-");
      assert.equal(at?.attribute ?? null, c.attribute);
      if (at) assert.equal(at.before, c.value);
    });
  }
});

type CallCase = {
  name: string;
  before: string;
  action: string | null;
  argument?: number;
  option?: { typed: string; written: string[] } | null;
};

describe("action calls", () => {
  for (const c of twinCases<CallCase>("action-calls.json")) {
    it(c.name, () => {
      const call = actionCallAt(c.before, c.before.length);
      assert.equal(call?.action ?? null, c.action);
      if (!call) return;
      assert.equal(call.argument, c.argument);
      assert.deepEqual(call.option, c.option ?? null);
    });
  }
});

describe("the catalog for completion", () => {
  it("says what each option does and names each action's parameters", () => {
    assert.deepEqual(catalog.fetchOptions.filter((o) => !o.doc?.trim()).map((o) => o.name), []);
    assert.deepEqual(catalog.actionsByName.get("get")?.parameters, ["uri", "options?"]);
    assert.deepEqual(catalog.actionsByName.get("peek")?.parameters, ["() => expr"]);
  });
});
