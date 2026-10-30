import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { collectSignalDefinitions, collectSignals } from "../src/signals.ts";

/** The cases of the analysis module's `SignalsTest.kt`. */
const kotlin = (src: string): string[] => [...collectSignals(src, "kotlin")].sort();
const html = (src: string): string[] => [...collectSignals(src, "html")].sort();

describe("signal collection", () => {
  it("a signals class may carry any modifier", () => {
    const src = `@Serializable public data class Signals(val count: Int = 0, var name: String)
@Serializable internal class Inner(val inner: Int)
@Serializable(with = X::class) private value class Wrapped(val wrapped: Int)`;
    assert.deepEqual(kotlin(src), ["count", "inner", "name", "wrapped"]);
  });

  it("a name is collected as written, wherever it is written", () => {
    assert.deepEqual(kotlin(`dataSignals("fooBar" to 7); patchSignals("fooBar" to 1); dataBind("x")`), ["fooBar", "x"]);
    // A name with a hyphen is refused at runtime, and collected as it stands until it is fixed.
    assert.deepEqual(kotlin(`dataSignals("foo-bar" to 7); signal("foo-bar")`), ["foo-bar"]);
  });

  it("a page declared from a variable is read by what the file says about it", () => {
    const encoded = (src: string) => [...collectSignalDefinitions(src, "kotlin")].filter((d) => d === "*" || d.includes(":"));
    const cases: [string, string[]][] = [
      [`val init = "{count: 0}"\nfun p() = div { dataSignals(init) }`, []],
      ["fun p(pairs: Array<Pair<String, Any?>>) = div { dataSignals(*pairs) }", []],
      [`val p = "count" to 0\nfun f() = div { dataSignals(p) }`, []],
      ["fun p(s: JsonObject) = div { dataSignals(s) }", ["*"]],
      ["class X(val form: String)\nfun p() { val form = Form(); div { dataSignals(codec.toJsonValue(form)) } }", ["toJsonValue:Form"]],
      ["fun p() = div { dataSignals(codec.toJsonValue(forms.Form())) }", ["toJsonValue:Form"]],
      ["fun p() = div { dataSignals(codec.toJsonValue(Form()), true) }", ["toJsonValue:Form"]],
      ["fun p(text: String) = div { dataSignals(codec.toJsonValue(text)) }", []],
      [`const val INIT = "{count: 0}"\nfun p() = div { dataSignals(INIT); dataSignals(initial(0)) }`, []],
      ["fun p() = div { dataSignals(codec.toJsonValue(Page<String>())) }", ["toJsonValue:Page"]],
      [
        "fun a(form: LoginForm) = div { dataSignals(codec.toJsonValue(form)) }\nfun b(form: SearchSignals) = div { dataSignals(codec.toJsonValue(form)) }",
        ["toJsonValue:LoginForm", "toJsonValue:SearchSignals"],
      ],
    ];
    for (const [src, expected] of cases) assert.deepEqual(encoded(src), expected, src);
  });

  it("the keys of a map handed over as the signals are signals", () => {
    assert.deepEqual(kotlin(`stream.patchSignals(mapOf("count" to 1, "user" to mapOf("name" to ""))); respondSignals(hashMapOf("open" to true))`), ["count", "open", "user"]);
    assert.deepEqual(kotlin(`dataSignals(("count" to 0))`), ["count"]);
  });

  it("the keys of the JSON a server call sends are signals", () => {
    for (const call of ["stream.patchSignals", "call.respondSignals", "datastarSignals"]) assert.deepEqual(kotlin(`${call}("""{"open": true}""")`), ["open"], call);
  });

  it("a key inside a pair's value declares nothing on its own", () => {
    assert.deepEqual(kotlin(`dataSignals("user" to mapOf("first-name" to "", "age" to 1))`), ["user"]);
  });

  it("a property goes by its serial name, and a parenthesis does not end the class", () => {
    const src = `@Serializable
data class PageSignals(
    @SerialName("page_no") val page: Int = 0,
    val tags: List<String> = listOf(),
    @SerialName(value = "sort-by") @Required private val sort: String,
    val after: Int = 0,
)`;
    assert.deepEqual(kotlin(src), ["after", "page_no", "sort-by", "tags"]);
  });

  it("the object form declares quoted keys in either kind of quote", () => {
    // A key of an object under a key is a signal under that one, as the client stores it.
    assert.deepEqual(html(`<div data-signals='{"query": "", "nested": {"deep": 1}}'></div>`), ["nested", "nested.deep", "query"]);
    assert.deepEqual(html(`<div data-signals__ifmissing="{'single': 1, bare: 2}"></div>`), ["bare", "single"]);
    assert.deepEqual(kotlin(String.raw`val k = "<div data-signals='{\"esc\": 1}'></div>"`), ["esc"]);
  });

  it("a spread of a variable may define any signal, and names none to complete", () => {
    const src = `<div data-signals="{a: 1, ...init, b: {...more}}"></div>`;
    assert.deepEqual(html(src), ["a", "b"]);
    assert.deepEqual([...collectSignalDefinitions(src, "html")].sort(), ["*", "a", "b"]);
  });

  it("a key with template syntax in it declares nothing", () => {
    const keys = `<div data-signals:\${key}="1"></div><div data-signals:{{ name }}="1"></div><div data-signals:th:text="1"></div><div data-signals:<%= k %>="1"></div><div data-signals:foo{{x}}="1"></div>`;
    assert.deepEqual(html(keys + `<div data-bind:user-name__case.kebab></div><div data-signals:år="1"></div>`), ["user-name", "år"]);
    assert.deepEqual(kotlin(`val x = "<div data-signals:\${name}=\\"1\\"></div>"`), []);
  });
});
