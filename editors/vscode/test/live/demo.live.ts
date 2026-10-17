import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { openStream } from "../../src/streamClient.ts";
import type { DatastarFrame } from "../../src/sse.ts";

/**
 * Runs the Stream Inspector's client against the repository's own demo service
 * (`./gradlew :demo:run`, port 8080). Not part of `npm test`; run `npm run test:live`.
 */
const BASE = process.env.STREAMLORD_LIVE_URL ?? "http://localhost:8080";

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

const counted = (frame: DatastarFrame) =>
  Number(/class="counter__number">(\d+)</.exec(frame.args.elements ?? "")?.[1] ?? Number.NaN);

describe("live stream inspector", () => {
  it("receives the counter stream as it counts", async () => {
    const abort = new AbortController();
    const frames: DatastarFrame[] = [];
    const statuses: string[] = [];
    let http = "";
    let contentType = "";
    const done = openStream(
      { url: `${BASE}/counter`, method: "GET" },
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
    const numbers = () => frames.filter((f) => f.event === "datastar-patch-elements").map(counted);
    for (let i = 0; i < 50 && numbers().length < 2; i++) await wait(100);
    assert.equal(http, "200 OK");
    assert.match(contentType, /text\/event-stream/);
    assert.equal(frames[0]?.event, "datastar-patch-signals");
    assert.match(frames[0]?.args.signals ?? "", /"running": true/);
    assert.deepEqual(numbers().slice(0, 2), [1, 2]);
    abort.abort();
    await done;
    assert.equal(statuses.at(-1), "closed");
  });

  it("reports a non-SSE html response with its content type", async () => {
    const seen: { contentType: string; body: string }[] = [];
    await openStream({ url: `${BASE}/csp`, method: "GET" }, { onNonSse: (r) => seen.push(r), onError: (m) => assert.fail(m) }, new AbortController().signal);
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
    await openStream({ url: "http://localhost:59999/x", method: "GET" }, { onError: (m) => (error = m) }, new AbortController().signal);
    assert.match(error, /Connection refused at .*:59999\. Is the server running\?/);
  });
});
