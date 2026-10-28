import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin } from "../src/analyze.ts";
import type { Issue } from "../src/expression.ts";

/** The cases of the analysis module's `SignalNameHyphensTest.kt`. */
const opts = { prefix: "data-", checkHtmlAttributes: true };
const hyphens = (src: string): Issue[] => analyzeKotlin(src, opts).filter((i) => i.code === "signal-name-hyphen");
const names = (src: string): string[] => hyphens(src).map((i) => src.slice(i.start + 1, i.end - 1));
const fixed = (src: string): string =>
  hyphens(src)
    .flatMap((i) => i.fixes ?? [])
    .sort((a, b) => b.start - a.start)
    .reduce((s, f) => s.slice(0, f.start) + f.text + s.slice(f.end), src);
const dsl = "import io.github.markusaugust.streamlord.html.*\n";

describe("a hyphen in a signal name", () => {
  it("a hyphen in each place that names a signal is an error with the camelCase fix", () => {
    const src = `${dsl}fun page() = div {
  dataSignals("foo-bar", "1")
  dataSignals("count" to 0, "user-name" to "")
  dataComputed("full-name", "1")
  dataBind("form.first-name")
  dataRef("my-ref")
  dataIndicator("is-busy")
  dataMatchMedia("is-dark", "(prefers-color-scheme: dark)")
  dataText(signal("form.first-name"))
  dataOnClick(statements(increment("click-count"), toggle("is-open"), set("a-b", 1)))
}
suspend fun send(stream: DatastarStream) {
  stream.patchSignals("foo-bar" to 1, "ok" to 2)
  stream.removeSignals("ok", "foo-bar")
}`;
    const issues = hyphens(src);
    assert.ok(issues.every((i) => i.severity === "error"));
    assert.deepEqual(
      issues.map((i) => src.slice(i.start + 1, i.end - 1)),
      ["foo-bar", "user-name", "full-name", "form.first-name", "my-ref", "is-busy", "is-dark", "form.first-name", "click-count", "is-open", "a-b", "foo-bar", "foo-bar"],
    );
    const after = fixed(src);
    assert.deepEqual(hyphens(after), []);
    assert.ok(after.includes('dataBind("form.firstName")') && after.includes('signal("form.firstName")'), after);
    assert.ok(issues[7]!.message.includes("or form['first-name'] for a key with a hyphen in it"), issues[7]!.message);
  });

  it("a key with a hyphen that is data is left alone", () => {
    const src = `${dsl}fun page() = div {
  dataSignals("user" to mapOf("first-name" to ""))
  dataSignals("{'foo-bar': 1}")
  dataSignals("{'foo-bar': 1}", true)
  dataComputed("{'full-name': () => 1}")
  dataText(signal("user['first-name']"))
  dataOn("my-event", "x()")
  dataClass("is-open", "true")
}
suspend fun send(stream: DatastarStream) {
  stream.patchSignals("user" to mapOf("first-name" to null))
  stream.patchSignals("""{"foo-bar": 1}""")
}`;
    assert.deepEqual(hyphens(src), []);
  });

  // set("user-id", x) on a cache, Hamcrest's not("x"): somebody else's helpers.
  it("an expression helper that is not the DSL's is left alone", () => {
    assert.deepEqual(hyphens('fun f() { cache.set("user-id", 1); signal("a-b") }'), []);
    assert.deepEqual(hyphens(`${dsl}fun f() { cache.set("user-id", 1) }`), []);
    assert.deepEqual(hyphens(`${dsl}import org.hamcrest.Matchers.not\nfun f() = not("a-b")`), []);
  });

  it("a name passed by name is read too", () => {
    const src = `${dsl}fun p() = div { dataBind(signal = "a-b"); dataSignals(name = "c-d", expression = "1"); dataText(signal(name = "e-f")) }`;
    assert.deepEqual(names(src), ["a-b", "c-d", "e-f"]);
  });

  it("a name written with escapes is reported without a fix", () => {
    const [issue, ...rest] = hyphens(`${dsl}fun p() = div { dataBind("a\\u002db") }`);
    assert.deepEqual(rest, []);
    assert.deepEqual(issue!.fixes, []);
  });
});
