import * as vscode from "vscode";
import { mergePatch, type DatastarFrame } from "./sse.ts";
import { openStream, parseHeaderLines } from "./streamClient.ts";

/**
 * The Stream Inspector: a webview that opens a Datastar request against your running server and
 * shows every event as it arrives, decoded, with the signal store as the client would see it.
 */
export class Inspector {
  private panel: vscode.WebviewPanel | null = null;
  private abort: AbortController | null = null;
  private signals: unknown = {};

  constructor(private readonly context: vscode.ExtensionContext) {}

  open(): void {
    if (this.panel) {
      this.panel.reveal();
      return;
    }
    this.panel = vscode.window.createWebviewPanel("streamlord.inspector", "Streamlord Stream Inspector", vscode.ViewColumn.Beside, { enableScripts: true, retainContextWhenHidden: true });
    this.panel.webview.html = html(this.panel.webview.cspSource, vscode.workspace.getConfiguration("streamlord").get<string>("inspector.defaultUrl", "http://localhost:8080/"));
    this.panel.onDidDispose(() => {
      this.stop();
      this.panel = null;
    });
    this.panel.webview.onDidReceiveMessage((msg: { type: string; url?: string; method?: string; signals?: string; headers?: string }) => {
      if (msg.type === "connect") void this.connect(msg.url ?? "", msg.method ?? "GET", msg.signals ?? "{}", msg.headers ?? "");
      if (msg.type === "stop") this.stop();
      if (msg.type === "reset") {
        this.signals = {};
        this.post({ type: "signals", signals: this.signals });
      }
    });
    this.context.subscriptions.push(this.panel);
  }

  private post(message: unknown): void {
    void this.panel?.webview.postMessage(message);
  }

  private stop(): void {
    this.abort?.abort();
    this.abort = null;
    this.post({ type: "status", status: "idle" });
  }

  private async connect(url: string, method: string, signalsJson: string, headersText: string): Promise<void> {
    this.stop();
    let signals: unknown;
    try {
      signals = signalsJson.trim() ? JSON.parse(signalsJson) : {};
    } catch (e) {
      this.post({ type: "error", message: `Signals are not valid JSON: ${(e as Error).message}` });
      return;
    }
    this.abort = new AbortController();
    await openStream(
      { url, method, signals, headers: parseHeaderLines(headersText) },
      {
        onStatus: (status, detail) => this.post({ type: "status", status, ...(detail ?? {}) }),
        onComment: (text) => this.post({ type: "comment", text, at: Date.now() }),
        onNonSse: (r) => this.post({ type: "nonsse", ...r }),
        onFrame: (frame) => {
          this.applyFrame(frame);
          this.post({ type: "frame", frame });
        },
        onError: (message) => {
          this.post({ type: "error", message });
          this.post({ type: "status", status: "idle" });
        },
      },
      this.abort.signal,
    );
  }

  private applyFrame(frame: DatastarFrame): void {
    if (frame.event !== "datastar-patch-signals" || !frame.args.signals) return;
    try {
      const patch = JSON.parse(frame.args.signals);
      if (frame.args.onlyIfMissing === "true") {
        const existing = this.signals as Record<string, unknown>;
        const filtered = Object.fromEntries(Object.entries(patch as Record<string, unknown>).filter(([k]) => !(k in existing)));
        this.signals = mergePatch(this.signals, filtered);
      } else {
        this.signals = mergePatch(this.signals, patch);
      }
      this.post({ type: "signals", signals: this.signals });
    } catch {
      // invalid JSON in the stream is shown raw; the frame itself is still posted
    }
  }
}

function html(cspSource: string, defaultUrl: string): string {
  return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${cspSource} 'unsafe-inline'; script-src ${cspSource} 'unsafe-inline';">
<title>Streamlord Stream Inspector</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); background: var(--vscode-editor-background); margin: 0; padding: 12px; }
  h1 { font-size: 1.1em; margin: 0 0 8px; letter-spacing: .04em; }
  form { display: grid; grid-template-columns: 90px 1fr; gap: 6px 8px; align-items: start; margin-bottom: 10px; }
  input, select, textarea, button { font: inherit; color: var(--vscode-input-foreground); background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, transparent); padding: 4px 6px; border-radius: 3px; }
  textarea { width: 100%; box-sizing: border-box; min-height: 48px; font-family: var(--vscode-editor-font-family); }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; cursor: pointer; padding: 5px 12px; }
  button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  .row { grid-column: 1 / -1; display: flex; gap: 8px; align-items: center; }
  .status { margin-left: auto; opacity: .8; }
  .status.open { color: var(--vscode-testing-iconPassed); }
  .status.error { color: var(--vscode-errorForeground); }
  main { display: grid; grid-template-columns: 1fr 320px; gap: 12px; }
  @media (max-width: 800px) { main { grid-template-columns: 1fr; } }
  .frame { border: 1px solid var(--vscode-widget-border, #444); border-radius: 4px; margin-bottom: 8px; overflow: hidden; }
  .frame header { display: flex; gap: 10px; padding: 4px 8px; background: var(--vscode-editorWidget-background); font-family: var(--vscode-editor-font-family); }
  .frame header .ev { font-weight: 600; }
  .frame header .ev.elements { color: var(--vscode-charts-blue); }
  .frame header .ev.signals { color: var(--vscode-charts-purple); }
  .frame header .t { margin-left: auto; opacity: .6; }
  .frame dl { display: grid; grid-template-columns: max-content 1fr; gap: 2px 10px; margin: 0; padding: 6px 8px; }
  .frame dt { opacity: .7; font-family: var(--vscode-editor-font-family); }
  .frame dd { margin: 0; }
  pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: var(--vscode-editor-font-family); font-size: .95em; }
  .comment { opacity: .6; font-style: italic; margin: 0 0 8px; font-family: var(--vscode-editor-font-family); }
  aside h2 { font-size: 1em; margin: 0 0 6px; }
  aside pre { border: 1px solid var(--vscode-widget-border, #444); border-radius: 4px; padding: 8px; min-height: 60px; }
  .error { color: var(--vscode-errorForeground); }
  .empty { opacity: .6; }
</style>
</head>
<body>
<h1>STREAM INSPECTOR</h1>
<form id="f">
  <label for="url">URL</label><input id="url" value="${escapeHtml(defaultUrl)}">
  <label for="method">Method</label>
  <select id="method"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option><option>QUERY</option></select>
  <label for="signals">Signals</label><textarea id="signals" placeholder='{"search": "ash"}'></textarea>
  <label for="headers">Headers</label><textarea id="headers" placeholder="X-Csrf-Token: ..."></textarea>
  <div class="row">
    <button type="submit" id="go">Connect</button>
    <button type="button" class="secondary" id="stop">Stop</button>
    <button type="button" class="secondary" id="clear">Clear</button>
    <button type="button" class="secondary" id="reset">Reset signals</button>
    <span class="status" id="status">idle</span>
  </div>
</form>
<main>
  <section id="frames"><p class="empty">No events yet. The realm is quiet.</p></section>
  <aside><h2>Signal store</h2><pre id="store">{}</pre></aside>
</main>
<script>
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const frames = $('frames');
  let count = 0;
  const clearEmpty = () => { const e = frames.querySelector('.empty'); if (e) e.remove(); };
  $('f').addEventListener('submit', (e) => { e.preventDefault(); vscode.postMessage({ type: 'connect', url: $('url').value, method: $('method').value, signals: $('signals').value, headers: $('headers').value }); });
  $('stop').addEventListener('click', () => vscode.postMessage({ type: 'stop' }));
  $('reset').addEventListener('click', () => vscode.postMessage({ type: 'reset' }));
  $('clear').addEventListener('click', () => { frames.innerHTML = '<p class="empty">Cleared.</p>'; count = 0; });
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const time = (t) => new Date(t).toLocaleTimeString(undefined, { hour12: false }) + '.' + String(t % 1000).padStart(3, '0');
  window.addEventListener('message', (ev) => {
    const m = ev.data;
    if (m.type === 'status') { const s = $('status'); s.textContent = m.status + (m.http ? ' · ' + m.http : '') + (m.contentType ? ' · ' + m.contentType : ''); s.className = 'status ' + (m.status === 'open' ? 'open' : ''); }
    if (m.type === 'error') { clearEmpty(); const p = document.createElement('p'); p.className = 'error'; p.textContent = m.message; frames.prepend(p); $('status').className = 'status error'; $('status').textContent = 'error'; }
    if (m.type === 'signals') { $('store').textContent = JSON.stringify(m.signals, null, 2); }
    if (m.type === 'comment') { clearEmpty(); const p = document.createElement('p'); p.className = 'comment'; p.textContent = time(m.at) + '  : ' + m.text; frames.prepend(p); }
    if (m.type === 'nonsse') { clearEmpty(); const d = document.createElement('div'); d.className = 'frame'; d.innerHTML = '<header><span class="ev">' + esc(m.contentType) + '</span><span class="t">non-SSE response</span></header><dl>' + Object.entries(m.headers).map(([k,v]) => '<dt>' + esc(k) + '</dt><dd><pre>' + esc(v) + '</pre></dd>').join('') + '<dt>body</dt><dd><pre>' + esc(m.body) + '</pre></dd></dl>'; frames.prepend(d); }
    if (m.type === 'frame') {
      clearEmpty(); count++;
      const f = m.frame; const kind = f.event.endsWith('elements') ? 'elements' : f.event.endsWith('signals') ? 'signals' : '';
      const d = document.createElement('div'); d.className = 'frame';
      const rows = Object.entries(f.args).map(([k, v]) => '<dt>' + esc(k) + '</dt><dd><pre>' + esc(v) + '</pre></dd>').join('');
      d.innerHTML = '<header><span>#' + count + '</span><span class="ev ' + kind + '">' + esc(f.event) + '</span>' + (f.id !== null ? '<span>id ' + esc(f.id) + '</span>' : '') + (f.retry !== null ? '<span>retry ' + f.retry + 'ms</span>' : '') + '<span class="t">' + time(f.receivedAt) + '</span></header><dl>' + rows + '</dl>';
      frames.prepend(d);
    }
  });
</script>
</body>
</html>`;
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c] ?? c);
}
