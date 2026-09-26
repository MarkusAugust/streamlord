import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { openStream } from "../../src/streamClient.ts";
import type { DatastarFrame } from "../../src/sse.ts";

/**
 * Runs the Stream Inspector's client against a live Datastar server (default: the
 * data-star-demo-skatt backend on :8080). Not part of `npm test`; run `npm run test:live`.
 */
const BASE = process.env.STREAMLORD_LIVE_URL ?? "http://localhost:8080/api";

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("live stream inspector", () => {
  it("receives the counter stream and sees a new event after an increment", async () => {
    const abort = new AbortController();
    const frames: DatastarFrame[] = [];
    const statuses: string[] = [];
    let http = "";
    let contentType = "";
    const done = openStream(
      { url: `${BASE}/counter-stream`, method: "GET" },
      {
        onStatus: (s, d) => {
          statuses.push(s);
          if (d?.http) http = d.http;
          if (d?.contentType) contentType = d.contentType;
        },
        onFrame: (f) => frames.push(f),
        onError: (m) => assert.fail(m),
      },
      abort.signal,
    );
    for (let i = 0; i < 50 && frames.length === 0; i++) await wait(100);
    assert.equal(http, "200 OK");
    assert.match(contentType, /text\/event-stream/);
    assert.equal(frames[0]?.event, "datastar-patch-elements");
    assert.match(frames[0]?.args.elements ?? "", /<span id="counter">\d+<\/span>/);
    const before = frames.length;
    const inc = await fetch(`${BASE}/increment-counter`, { method: "POST" });
    assert.equal(inc.status, 204);
    for (let i = 0; i < 50 && frames.length === before; i++) await wait(100);
    assert.ok(frames.length > before, "expected a new event after incrementing");
    const last = frames[frames.length - 1]!;
    const first = Number(/<span id="counter">(\d+)<\/span>/.exec(frames[0]!.args.elements ?? "")?.[1]);
    const latest = Number(/<span id="counter">(\d+)<\/span>/.exec(last.args.elements ?? "")?.[1]);
    assert.equal(latest, first + 1);
    abort.abort();
    await done;
    assert.equal(statuses.at(-1), "closed");
  });

  it("reports a non-SSE html response with its content type", async () => {
    const seen: { contentType: string; body: string }[] = [];
    await openStream({ url: `${BASE}/counter`, method: "GET" }, { onNonSse: (r) => seen.push(r), onError: (m) => assert.fail(m) }, new AbortController().signal);
    assert.equal(seen.length, 1, "expected a non-SSE response");
    assert.match(seen[0]!.contentType, /text\/html/);
    assert.match(seen[0]!.body, /counter/);
  });

  it("reports 404 and closes", async () => {
    let http = "";
    let closed = false;
    await openStream(
      { url: `${BASE}/finnes-ikke`, method: "GET" },
      {
        onStatus: (s, d) => {
          if (d?.http) http = d.http;
          if (s === "closed") closed = true;
        },
        onNonSse: () => {},
      },
      new AbortController().signal,
    );
    assert.match(http, /^404/);
    assert.ok(closed);
  });

  it("reports an unreachable host as an error", async () => {
    let error = "";
    await openStream({ url: "http://localhost:1/x", method: "GET" }, { onError: (m) => (error = m) }, new AbortController().signal);
    assert.ok(error.length > 0);
  });
});
