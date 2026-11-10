import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { expressionAtCaret, tagBeforeCaretInString } from "../src/caret.ts";
import { catalog } from "../src/catalog.ts";
import { actionCallAt, tagBeforeCaret, valueAtCaret } from "../src/completionContext.ts";
import { stringAt } from "../src/scanner.ts";
import { twinCases } from "./twins.ts";

type Case = {
  name: string;
  prefix?: string;
  templates?: boolean;
  before: string;
  after: string;
  attribute: string | null;
  value?: string;
  tag?: string | null;
};

describe("completion contexts", () => {
  for (const c of twinCases<Case>("completion-contexts.json")) {
    it(c.name, () => {
      const at = valueAtCaret(c.before + c.after, c.before.length, c.prefix ?? "data-", c.templates ?? true);
      assert.equal(at?.attribute ?? null, c.attribute);
      if (at) assert.equal(at.before, c.value);
    });
  }
});

describe("the tag before the caret", () => {
  const cases = twinCases<Case>("completion-contexts.json").filter((c) => c.tag !== undefined);
  it("has cases", () => assert.ok(cases.length > 0));
  for (const c of cases) {
    it(c.name, () => {
      assert.equal(tagBeforeCaret(c.before + c.after, c.before.length, c.prefix ?? "data-", c.templates ?? true), c.tag);
    });
  }

  it("reads a Kotlin string decoded, from the tag's <", () => {
    /** The tag before a caret just after `data-on:click__` in the string of `src`. */
    const at = (src: string) => {
      const offset = src.indexOf("data-on:click__") + "data-on:click__".length;
      const s = stringAt(src, offset);
      assert.ok(s, src);
      return tagBeforeCaretInString(s, offset, "data-");
    };
    assert.equal(at(`val x = "<div data-show=\\"\\$a > 1\\" data-on:click__"`), `<div data-show="$a > 1" data-on:click__`, "escaped quotes around a value holding >");
    assert.equal(at(`fun side() = """\n<div class="card"\n     data-on:click__\n"""`), `<div class="card"\n     data-on:click__`, "a raw string over two lines");
  });
});

type ExpressionCase = {
  name: string;
  kotlin: boolean;
  prefix?: string;
  before: string;
  after: string;
  expression: { before: string; allowActions: boolean; patchInScope: boolean } | null;
};

describe("expressions at the caret", () => {
  for (const c of twinCases<ExpressionCase>("expressions-at-caret.json")) {
    it(c.name, () => {
      assert.deepEqual(expressionAtCaret(c.before + c.after, c.before.length, c.kotlin, c.prefix ?? "data-"), c.expression);
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
