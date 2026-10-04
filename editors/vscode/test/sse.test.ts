import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { decodeDatastar, SseParser, type SseMessage } from "../src/sse.ts";

/** The cases of the analysis module's `SseTest.kt`. */
const data = (messages: SseMessage[]): string[][] => messages.map((m) => m.data);

describe("sse parser", () => {
  it("a CRLF split across two chunks is one line ending", () => {
    const p = new SseParser();
    assert.deepEqual(p.feed("data: x\r"), []);
    assert.deepEqual(data(p.feed("\ndata: y\r\n\r\n")), [["x", "y"]]);
  });

  it("an empty chunk between the CR and the LF changes nothing", () => {
    const p = new SseParser();
    assert.deepEqual(p.feed("data: x\r"), []);
    assert.deepEqual(p.feed(""), []);
    assert.deepEqual(p.feed("\n"), []);
    assert.deepEqual(data(p.feed("\r\n")), [["x"]]);
  });

  it("a lone CR ends a line, also as the last character of a chunk", () => {
    const p = new SseParser();
    assert.deepEqual(data(p.feed("data: x\r\r")), [["x"]]);
    assert.deepEqual(data(p.feed("data: y\r\r")), [["y"]]);
    assert.deepEqual(p.feed("data: z\r"), []);
    assert.deepEqual(data(p.feed("\r")), [["z"]]);
  });

  it("a data line may start with any word", () => {
    const p = new SseParser();
    const messages = p.feed("event: e\ndata: constructor foo\ndata: toString bar\ndata: toString baz\ndata: __proto__ qux\n\n");
    assert.equal(messages.length, 1);
    assert.deepEqual(Object.entries(decodeDatastar(messages[0]!).args), [["constructor", "foo"], ["toString", "bar\nbaz"], ["__proto__", "qux"]]);
  });

  it("a retry that does not fit an int is dropped", () => {
    const p = new SseParser();
    assert.deepEqual(p.feed("retry: 2147483647\ndata: a\n\nretry: 2147483648\ndata: b\n\n").map((m) => m.retry), [2147483647, null]);
  });
});
