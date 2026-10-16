import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { SseParser, type SseMessage } from "../src/sse.ts";

/**
 * The SSE corpus in `fixtures/sse` is read by this suite and by `SseCorpusTest.kt` in
 * streamlord-analysis, so both inspectors read a stream alike. Each case runs twice: in its own
 * chunks, and one character at a time, since a chunk may end anywhere.
 */
interface Case {
  name: string;
  chunks: string[];
  messages: SseMessage[];
  finish: SseMessage | null;
}

const dir = new URL("./fixtures/sse/", import.meta.url);

function read(chunks: string[]): { messages: SseMessage[]; finish: SseMessage | null } {
  const parser = new SseParser();
  const messages = chunks.flatMap((c) => parser.feed(c));
  return { messages, finish: parser.finish() };
}

describe("shared SSE corpus", () => {
  for (const file of readdirSync(dir).sort()) {
    const corpus = JSON.parse(readFileSync(new URL(file, dir), "utf8")) as { cases: Case[] };
    for (const c of corpus.cases) {
      it(`${file}: ${c.name}`, () => {
        const expected = { messages: c.messages, finish: c.finish };
        assert.deepEqual(read(c.chunks), expected, c.name);
        assert.deepEqual(read([...c.chunks.join("")]), expected, `${c.name}, one character at a time`);
      });
    }
  }
});
