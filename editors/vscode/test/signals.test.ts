import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { collectSignals } from "../src/signals.ts";

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
    assert.deepEqual(html(`<div data-signals='{"query": "", "nested": {"deep": 1}}'></div>`), ["deep", "nested", "query"]);
    assert.deepEqual(html(`<div data-signals__ifmissing="{'single': 1, bare: 2}"></div>`), ["bare", "single"]);
    assert.deepEqual(kotlin(String.raw`val k = "<div data-signals='{\"esc\": 1}'></div>"`), ["esc"]);
  });
});
