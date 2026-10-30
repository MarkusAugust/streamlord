import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { decodeDatastar, SseParser, type SseMessage } from "../src/sse.ts";
import { twinCases } from "./twins.ts";

/** A case of `fixtures/twins/sse.json`, which SseTest.kt in streamlord-analysis reads too. */
interface Case {
  name: string;
  call: "feed" | "decode";
  chunks: string[];
  expected: unknown;
}

const shown = (m: SseMessage) => ({ event: m.event, id: m.id, retry: m.retry, data: m.data, comments: m.comments });

// The projections of SseTest.kt: what each chunk hands back, or every message decoded.
const calls = {
  feed: (c: Case) => {
    const p = new SseParser();
    return c.chunks.map((chunk) => p.feed(chunk).map(shown));
  },
  decode: (c: Case) => {
    const p = new SseParser();
    return c.chunks
      .flatMap((chunk) => p.feed(chunk))
      .map((m) => {
        const f = decodeDatastar(m);
        return { event: f.event, id: f.id, retry: f.retry, args: Object.entries(f.args) };
      });
  },
};

describe("sse parser", () => {
  for (const c of twinCases<Case>("sse.json")) {
    it(c.name, () => assert.deepEqual(calls[c.call](c), c.expected, c.name));
  }

  // One long line in small pieces used to be scanned again from its start on every piece.
  it("a long line in small pieces is read in linear time", () => {
    const parser = new SseParser();
    const started = performance.now();
    parser.feed("data: ");
    for (let i = 0; i < 80_000; i++) parser.feed("0123456789");
    const messages = parser.feed("\n\n");
    const millis = performance.now() - started;
    assert.equal(messages[0]!.data[0]!.length, 800_000);
    assert.ok(millis < 2_000, `took ${millis} ms`);
  });
});
