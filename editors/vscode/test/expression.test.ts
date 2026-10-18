import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { validateExpression, type Fix } from "../src/expression.ts";

const codes = (issues: { code?: string }[]) => issues.map((i) => i.code);
const apply = (src: string, fix: Fix) => src.slice(0, fix.start) + fix.text + src.slice(fix.end);

describe("expressions", () => {
  it("accepts a numeric segment in a signal path", () => {
    assert.deepEqual(validateExpression("$foo.0.name"), []);
    assert.deepEqual(validateExpression("$items.12.title + $a.b.0 + 1.5"), []);
    assert.deepEqual(codes(validateExpression("$foo.0.name +")), ["expression-syntax"]);
  });

  it("leaves an action name in a string, a template literal or a comment alone", () => {
    assert.deepEqual(validateExpression("$msg = 'mail me @home (please)'"), []);
    assert.deepEqual(validateExpression("$b = `x @foo(y)`"), []);
    assert.deepEqual(validateExpression("$a = 1 // @nope('x')"), []);
    assert.deepEqual(validateExpression("/* @nada(1) */ $a = 1"), []);
    assert.deepEqual(codes(validateExpression("`${@bogus('/y')}`")), ["unknown-action"]);
  });

  it("reports a space between an action and its parenthesis", () => {
    const src = "@get ('/x')";
    const [issue] = validateExpression(src);
    assert.equal(issue!.code, "action-space");
    assert.equal(src.slice(issue!.start, issue!.end), "@get ");
    assert.equal(apply(src, issue!.fixes![0]!), "@get('/x')");
    assert.deepEqual(codes(validateExpression("@get('/x')")), []);
  });

  it("camel-cases a capital after the hyphen in the signal fix", () => {
    const src = "$a-B";
    const [issue] = validateExpression(src);
    assert.equal(apply(src, issue!.fixes![0]!), "$aB");
  });

  it("judges a hyphenated signal in the code of a template literal, and tells that the client rewrites its text", () => {
    const src = "'$a-b' + `$c-d ${$e-f}`";
    const issues = validateExpression(src);
    // The literal holds a ${...}, so the client's pattern does not step over it: $c-d in its text becomes $['c-d'].
    assert.deepEqual(codes(issues), ["literal-rewritten", "signal-kebab"]);
    assert.equal(src.slice(issues[0]!.start, issues[0]!.end), "$c-d");
    assert.equal(src.slice(issues[1]!.start, issues[1]!.end), "$e-f");
    assert.deepEqual(validateExpression("`$c-d`"), []);
  });
});
