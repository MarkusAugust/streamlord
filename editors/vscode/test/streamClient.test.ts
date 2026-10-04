import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { parseHeaderLines, toCurl, type SavedRequest } from "../src/requests.ts";
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
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.end('event: datastar-patch-signals\ndata: signals {"ok":true}\n\n');
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
