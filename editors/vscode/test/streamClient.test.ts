import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { NON_STREAM_CUT, parseHeaderLines, toCurl, type SavedRequest } from "../src/requests.ts";
import { openStream } from "../src/streamClient.ts";

/** What a server receives: the request target as it stood on the wire, and the body. */
interface Received {
  method: string;
  target: string;
  body: string;
  headers: Record<string, string | string[] | undefined>;
}

let server: Server;
let base = "";
const received: Received[] = [];

before(async () => {
  server = createServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk: string) => (body += chunk));
    req.on("end", () => {
      received.push({ method: req.method ?? "", target: req.url ?? "", body, headers: req.headers });
      // Responses the client reads by status and Content-Type, not by what the body looks like.
      if (req.url?.startsWith("/no-type")) {
        res.writeHead(200);
        return void res.end("data: x\n\n");
      }
      if (req.url?.startsWith("/html")) {
        res.writeHead(200, { "Content-Type": "text/html" });
        const size = req.url.startsWith("/html-big") ? 2 * 1024 * 1024 : req.url.startsWith("/html-exact") ? 1024 * 1024 : 0;
        return void res.end(size ? "x".repeat(size) : "<p id=\"a\">x</p>");
      }
      // A stream with an error status that stays open: the client reads none of it.
      if (req.url?.startsWith("/missing-open")) {
        res.writeHead(404, { "Content-Type": "text/event-stream" });
        res.write("data: x\n\n");
        return void setTimeout(() => res.end(), 3000);
      }
      if (req.url?.startsWith("/missing")) {
        res.writeHead(404, { "Content-Type": "text/event-stream" });
        return void res.end("data: x\n\n");
      }
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      // Comments after the last message, which only the parser's finish hands back.
      if (req.url?.startsWith("/trailing")) res.end("data: x\n\n: bye\n");
      // A last comment cut inside a character, which only the decoder's final flush hands over.
      else if (req.url?.startsWith("/cut")) res.end(Buffer.concat([Buffer.from("data: x\n\n: bye"), Buffer.from([0xe2])]));
      // Two byte order marks: the parser drops one before the field, the client's decoder too.
      else if (req.url?.startsWith("/two-marks")) res.end(Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf]), Buffer.from("event: datastar-patch-signals\ndata: signals {}\n\n")]));
      else if (req.url?.startsWith("/comment-id")) res.end(": c\nid: 7\n\n");
      else res.end('event: datastar-patch-signals\ndata: signals {"ok":true}\n\n');
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(() => server.close());

/** Send the request as the inspector does, and return what arrived plus any error it reported. */
async function send(r: SavedRequest): Promise<{ got: Received | undefined; error: string | undefined }> {
  const before = received.length;
  let error: string | undefined;
  await openStream({ url: r.url, method: r.method, signals: r.signals, headers: parseHeaderLines(r.headers) }, { onError: (m) => (error = m) }, new AbortController().signal);
  return { got: received[before], error };
}

/** The request target and the body of a curl line, as curl would put them on the wire. */
function curlParts(line: string): { target: string; body: string } {
  const url = /^curl -N -X \S+ '([^']*)'/.exec(line)?.[1] ?? "";
  const data = /--data '((?:[^']|'\\'')*)'$/.exec(line)?.[1]?.replace(/'\\''/g, "'") ?? "";
  return { target: url.slice(base.length), body: data };
}

const request = (over: Partial<SavedRequest>): SavedRequest => ({ name: "", url: `${base}/api/feed`, method: "GET", signals: "", headers: "", ...over });

describe("stream client", () => {
  it("sends the client's Accept", async () => {
    const { got } = await send(request({}));
    assert.equal(got?.headers.accept, "text/event-stream, text/html, application/json");
  });

  it("reads a 200 without a Content-Type as a stream, as the client does", async () => {
    const frames: string[] = [];
    await openStream({ url: `${base}/no-type`, method: "GET", signals: "", headers: {} }, { onFrame: (f) => frames.push(f.event) }, new AbortController().signal);
    assert.deepEqual(frames, ["message"]);
  });

  it("shows HTML, and any status but 200, as a response without a stream", async () => {
    const shown: string[] = [];
    const frames: string[] = [];
    for (const path of ["/html", "/missing"]) {
      await openStream(
        { url: `${base}${path}`, method: "GET", signals: "", headers: {} },
        { onNonSse: (r) => shown.push(`${r.http}: ${r.body}`), onFrame: (f) => frames.push(f.event) },
        new AbortController().signal,
      );
    }
    assert.deepEqual(
      [shown, frames],
      [['200 OK: <p id="a">x</p>', "404 Not Found: Not read: the client reads no body with a 404, and a stream may never end."], []],
    );
  });

  it("shows a stream with an error status at once, without waiting on its body", async () => {
    const started = performance.now();
    let shown = "";
    await openStream({ url: `${base}/missing-open`, method: "GET", signals: "", headers: {} }, { onNonSse: (r) => (shown = r.http) }, new AbortController().signal);
    assert.equal(shown, "404 Not Found");
    assert.ok(performance.now() - started < 2000, "waited on the body");
  });

  it("reads a response without a stream only up to its limit", async () => {
    let body = "";
    await openStream({ url: `${base}/html-big`, method: "GET", signals: "", headers: {} }, { onNonSse: (r) => (body = r.body) }, new AbortController().signal);
    assert.equal(body, "x".repeat(1024 * 1024) + NON_STREAM_CUT);
    await openStream({ url: `${base}/html-exact`, method: "GET", signals: "", headers: {} }, { onNonSse: (r) => (body = r.body) }, new AbortController().signal);
    assert.equal(body, "x".repeat(1024 * 1024), "a body of exactly the limit is not cut");
  });

  it("hands over a last comment cut inside a character", async () => {
    const comments: string[] = [];
    await openStream({ url: `${base}/cut`, method: "GET", signals: "", headers: {} }, { onComment: (c) => comments.push(c) }, new AbortController().signal);
    assert.deepEqual(comments, ["bye\uFFFD"]);
  });

  it("leaves a second byte order mark in the field's name, as the client does", async () => {
    const events: string[] = [];
    await openStream({ url: `${base}/two-marks`, method: "GET", signals: "", headers: {} }, { onFrame: (f) => events.push(f.event) }, new AbortController().signal);
    assert.deepEqual(events, ["message"]);
  });

  it("shows comments with an id as a frame, not a keep-alive", async () => {
    const frames: string[] = [];
    const comments: string[] = [];
    await openStream(
      { url: `${base}/comment-id`, method: "GET", signals: "", headers: {} },
      { onFrame: (f) => frames.push(f.id ?? ""), onComment: (c) => comments.push(c) },
      new AbortController().signal,
    );
    assert.deepEqual([frames, comments], [["7"], []]);
  });

  it("shows the comments that end a stream after its last message", async () => {
    const comments: string[] = [];
    await openStream({ url: `${base}/trailing`, method: "GET", signals: "", headers: {} }, { onComment: (c) => comments.push(c) }, new AbortController().signal);
    assert.deepEqual(comments, ["bye"]);
  });

  it("sends what the curl line shows", async () => {
    const signals = ' { "n" : 1.0, "s": "\\u00e9 x" } ';
    for (const r of [
      request({ url: `${base}/api/feed?flag&q=a%20b+c&datastar=old`, signals, headers: "X-A: 1\n\u001fX-B : 2 " }),
      request({ url: `${base}/api/feed?q=1`, method: "POST", signals }),
      request({ method: "DELETE" }),
    ]) {
      const { got, error } = await send(r);
      assert.equal(error, undefined);
      const curl = curlParts(toCurl(r));
      assert.equal(got?.target, curl.target, r.method);
      assert.equal(got?.body, curl.body, r.method);
      if (r.headers) assert.deepEqual([got?.headers["x-a"], got?.headers["x-b"]], ["1", "2"]);
    }
  });

  it("sends a character beyond ASCII as curl does, in UTF-8", async () => {
    const { got } = await send(request({ url: `${base}/s\u00f8k`, method: "POST" }));
    assert.equal(got?.target, "/s%C3%B8k");
  });

  it("says why a url cannot be sent instead of throwing", async () => {
    assert.deepEqual(await send(request({ url: "/api/feed" })), {
      got: undefined,
      error: "Not an absolute URL: /api/feed. Start it with http:// or https://, or with {{baseUrl}}.",
    });
    const blank = await send(request({ signals: "\u00a0" }));
    assert.equal(blank.got, undefined);
    assert.match(blank.error ?? "", /^Signals are not valid JSON: /);
    assert.deepEqual(await send(request({ url: `${base}/users/{id}` })), {
      got: undefined,
      error: "The URL still holds {id}. Fill in the path parameter before sending.",
    });
  });
});
