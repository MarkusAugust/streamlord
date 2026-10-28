import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
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

  // A header's set, a map's set, a codec's respondSignals and a Boolean ifMissing in a variable.
  it("a name that may not be a signal's is left alone", () => {
    const src = `${dsl}fun t() = testApplication { client.get("/") { headers { set("datastar-request", "true") } } }
fun m(map: MutableMap<String, Int>) = with(map) { set("user-id", 1) }
suspend fun r(call: ApplicationCall) = call.respondSignals("a-b" to 1)
fun p(flag: Boolean) = div { dataSignals("a-b", flag) }
fun q() = div { dataSignals("user" to buildMap { set("first-name", "") }) }`;
    assert.deepEqual(hyphens(src), []);
  });

  it("the fix and the bracket form leave a key inside brackets as it is", () => {
    const src = `${dsl}fun p() = div { dataText(signal("form.first-name['x-y']")); dataText(signal("items[0]-x")); dataText(signal("a-b.c-d")) }`;
    const issues = hyphens(src);
    assert.deepEqual(issues.map((i) => i.fixes![0]!.text), ["form.firstName['x-y']", "items[0]X", "aB.cD"]);
    assert.ok(issues.every((i) => !i.message.includes("for a key with a hyphen")));
    const [one] = hyphens(`${dsl}fun p() = div { dataText(signal("form.first-name.x")) }`);
    assert.ok(one!.message.includes("or form['first-name'].x for a key"), one!.message);
    const named = `${dsl}fun p() = div { dataComputed(name = "a-b", expression = "1"); dataMatchMedia(signal = "c-d", mediaQuery = "x") }`;
    assert.deepEqual(names(named), ["a-b", "c-d"]);
  });

  // The table DataSignalsSecondArgumentTest in streamlord-analysis compiles: `kotlin` is the overload the compiler picked.
  it("reads the second argument of dataSignals as the compiler does", () => {
    type Case = { argument: string; kotlin: string; analysis: string };
    const table = JSON.parse(readFileSync(new URL("./fixtures/data-signals-second-argument.json", import.meta.url), "utf8")) as { cases: Case[] };
    assert.equal(table.cases.length, 32);
    for (const { argument, kotlin, analysis } of table.cases) {
      assert.ok(analysis === "unknown" || analysis === kotlin, argument);
      const src = `${dsl}fun p(mode: String, flag: Boolean, expr: String, n: Int) = div { dataSignals("a-b", ${argument}) }`;
      assert.equal(hyphens(src).length > 0, analysis === "expression", argument);
    }
  });

  it("a name is read as the client reads it", () => {
    const src = `${dsl}fun p() = div {\n  dataText(signal("a['x]y-z']"))\n  dataText(signal("items[\\$i-1]"))\n  dataText(signal("a['['].b-c"))\n}`;
    assert.deepEqual(hyphens(src).map((i) => i.fixes![0]!.text), ["items[\\$i1]", "a['['].bC"]);
    assert.ok(hyphens(src)[0]!.message.endsWith("Write items[\\$i1]."), hyphens(src)[0]!.message);
  });

  // The demo writes dataText("${signal("running")} ? ..."), so a call inside a template is the DSL's too.
  it("a call inside a string template is read, and a templated name is reported without a fix", () => {
    const src =
      `${dsl}import kotlin.collections.set\n` +
      "fun p(id: Int, expr: String) = div {\n" +
      `  dataText("\${signal("is-running")} ? 'Stop' : 'Go'")\n` +
      `  dataOnClick("\${set("click-count", 1)}; @post('/x')")\n` +
      `  dataSignals("item-$id" to false)\n` +
      `  dataComputed("full-name", expr)\n` +
      `  dataOnClick(setExpr("draft-text", "''"))\n` +
      "}\n";
    const issues = hyphens(src);
    assert.deepEqual(names(src), ["is-running", "click-count", "item-$id", "full-name", "draft-text"]);
    assert.equal(issues[0]!.fixes![0]!.text, "isRunning");
    assert.deepEqual(issues[2]!.fixes, []);
    assert.ok(issues[2]!.message.includes("Write it in camelCase."), issues[2]!.message);
    assert.equal(src.slice(issues[0]!.fixes![0]!.start, issues[0]!.fixes![0]!.end), "is-running");
  });

  it("a name written with escapes is reported without a fix", () => {
    const [issue, ...rest] = hyphens(`${dsl}fun p() = div { dataBind("a\\u002db") }`);
    assert.deepEqual(rest, []);
    assert.deepEqual(issue!.fixes, []);
  });
});
