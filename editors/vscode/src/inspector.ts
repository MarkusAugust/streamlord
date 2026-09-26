import * as vscode from "vscode";
import { fillPath, pathParams, resolveRequest, toCurl, type SavedRequest } from "./requests.ts";
import type { RequestStore } from "./requestStore.ts";
import { mergePatch, type DatastarFrame } from "./sse.ts";
import { openStream, parseHeaderLines } from "./streamClient.ts";

/**
 * The Stream Inspector: a webview that opens a Datastar request against your running server and
 * shows every event as it arrives, decoded, with the signal store as the client would see it.
 * Requests can be saved to the workspace, recalled from the recent list, filled from a route
 * code lens, parameterised with `{{variables}}` and exported as curl.
 */
export class Inspector {
  private panel: vscode.WebviewPanel | null = null;
  private abort: AbortController | null = null;
  private signals: unknown = {};

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly store: RequestStore,
  ) {
    context.subscriptions.push(store.onDidChange(() => void this.pushRequests()));
  }

  /** Open the panel, optionally prefilled. */
  open(prefill?: Partial<SavedRequest>): void {
    if (!this.panel) {
      this.panel = vscode.window.createWebviewPanel("streamlord.inspector", "Streamlord Stream Inspector", vscode.ViewColumn.Beside, { enableScripts: true, retainContextWhenHidden: true });
      this.panel.webview.html = html(this.panel.webview.cspSource);
      this.panel.onDidDispose(() => {
        this.stop();
        this.panel = null;
      });
      this.panel.webview.onDidReceiveMessage((msg: Message) => void this.handle(msg));
      this.context.subscriptions.push(this.panel);
    } else {
      this.panel.reveal();
    }
    void this.pushRequests(prefill ?? this.store.lastUsed() ?? { url: "{{baseUrl}}/" });
  }

  /** Open with a route from a code lens: ask for path parameters, then prefill. */
  async openWithRoute(route: { method: string; path: string }): Promise<void> {
    const values: Record<string, string> = {};
    for (const p of pathParams(route.path)) {
      const v = await vscode.window.showInputBox({ prompt: `Value for {${p.name}}${p.optional ? " (optional, leave empty to omit)" : ""}`, ignoreFocusOut: true });
      if (v === undefined) return;
      values[p.name] = v;
    }
    this.open({ name: "", method: route.method, url: `{{baseUrl}}${fillPath(route.path, values)}`, signals: "", headers: "" });
  }

  private post(message: unknown): void {
    void this.panel?.webview.postMessage(message);
  }

  private async pushRequests(current?: Partial<SavedRequest>): Promise<void> {
    const [saved, variables] = await Promise.all([this.store.saved(), this.store.variables()]);
    this.post({ type: "requests", saved, recent: this.store.recent(), variables, current });
  }

  private stop(): void {
    this.abort?.abort();
    this.abort = null;
  }

  private async handle(msg: Message): Promise<void> {
    switch (msg.type) {
      case "ready":
        return this.pushRequests(this.store.lastUsed() ?? { url: "{{baseUrl}}/" });
      case "connect":
        return this.connect(msg.request);
      case "stop":
        this.stop();
        this.post({ type: "status", status: "idle" });
        return;
      case "reset":
        this.signals = {};
        this.post({ type: "signals", signals: this.signals });
        return;
      case "save": {
        const name = await vscode.window.showInputBox({ prompt: "Name for this request", value: msg.request.name || suggestName(msg.request), ignoreFocusOut: true });
        if (!name) return;
        try {
          await this.store.save({ ...msg.request, name });
          this.post({ type: "saved", name });
        } catch (e) {
          this.post({ type: "error", message: (e as Error).message });
        }
        return;
      }
      case "delete": {
        const ok = await vscode.window.showWarningMessage(`Delete saved request “${msg.name}”?`, { modal: true }, "Delete");
        if (ok === "Delete") await this.store.delete(msg.name);
        return;
      }
      case "curl": {
        const { request, missing } = resolveRequest(msg.request, await this.store.variables());
        if (missing.length) {
          this.post({ type: "error", message: `Unknown variables: ${missing.map((m) => `{{${m}}}`).join(", ")}` });
          return;
        }
        try {
          await vscode.env.clipboard.writeText(toCurl(request));
          void vscode.window.showInformationMessage("Streamlord: curl command copied.");
        } catch (e) {
          this.post({ type: "error", message: (e as Error).message });
        }
        return;
      }
      case "openFile": {
        const folder = vscode.workspace.workspaceFolders?.[0];
        if (!folder) return;
        const rel = vscode.workspace.getConfiguration("streamlord").get<string>("inspector.requestsFile", ".streamlord/inspector.json");
        const uri = vscode.Uri.joinPath(folder.uri, rel);
        try {
          await vscode.workspace.fs.stat(uri);
        } catch {
          await vscode.workspace.fs.createDirectory(vscode.Uri.joinPath(uri, ".."));
          await vscode.workspace.fs.writeFile(uri, Buffer.from('{\n  "version": 1,\n  "requests": []\n}\n', "utf8"));
        }
        await vscode.window.showTextDocument(uri, { viewColumn: vscode.ViewColumn.One });
        return;
      }
    }
  }

  private async connect(raw: SavedRequest): Promise<void> {
    this.stop();
    const { request, missing } = resolveRequest(raw, await this.store.variables());
    if (missing.length) {
      this.post({ type: "error", message: `Unknown variables: ${missing.map((m) => `{{${m}}}`).join(", ")}. Define them in settings (streamlord.inspector.variables) or .streamlord/env.json.` });
      return;
    }
    let signals: unknown;
    try {
      signals = request.signals.trim() ? JSON.parse(request.signals) : {};
    } catch (e) {
      this.post({ type: "error", message: `Signals are not valid JSON: ${(e as Error).message}` });
      return;
    }
    await this.store.addRecent(raw);
    void this.pushRequests();
    this.abort = new AbortController();
    await openStream(
      { url: request.url, method: request.method, signals, headers: parseHeaderLines(request.headers) },
      {
        onStatus: (status, detail) => this.post({ type: "status", status, resolvedUrl: request.url, ...(detail ?? {}) }),
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

type Message =
  | { type: "ready" }
  | { type: "connect"; request: SavedRequest }
  | { type: "stop" }
  | { type: "reset" }
  | { type: "save"; request: SavedRequest }
  | { type: "delete"; name: string }
  | { type: "curl"; request: SavedRequest }
  | { type: "openFile" };

function suggestName(r: SavedRequest): string {
  try {
    return `${r.method} ${new URL(r.url.replace(/\{\{[^}]+\}\}/g, "http://x")).pathname}`;
  } catch {
    return `${r.method} ${r.url}`;
  }
}

function html(cspSource: string): string {
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
  input, select, textarea { width: 100%; box-sizing: border-box; }
  textarea { min-height: 48px; font-family: var(--vscode-editor-font-family); }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; cursor: pointer; padding: 5px 12px; }
  button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  button.link { background: none; color: var(--vscode-textLink-foreground); padding: 0 4px; }
  button:disabled { opacity: .5; cursor: default; }
  .row { grid-column: 1 / -1; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .saved-row { display: flex; gap: 6px; align-items: center; }
  .saved-row select { flex: 1; width: auto; }
  .dirty { color: var(--vscode-editorWarning-foreground); font-size: .9em; white-space: nowrap; }
  .status { margin-left: auto; opacity: .8; }
  .status.open { color: var(--vscode-testing-iconPassed); }
  .status.error { color: var(--vscode-errorForeground); }
  .resolved { grid-column: 2; opacity: .6; font-family: var(--vscode-editor-font-family); font-size: .9em; min-height: 0; }
  .resolved:empty { display: none; }
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
  aside details { margin-top: 10px; opacity: .8; }
  .error { color: var(--vscode-errorForeground); }
  .empty { opacity: .6; }
</style>
</head>
<body>
<h1>STREAM INSPECTOR</h1>
<form id="f">
  <label for="saved">Request</label>
  <div class="saved-row">
    <select id="saved"><option value="">New request…</option></select>
    <button type="button" class="secondary" id="save" title="Save the current request to .streamlord/inspector.json">Save</button>
    <button type="button" class="secondary" id="delete" title="Delete the selected saved request">Delete</button>
    <span class="dirty" id="dirty" hidden>● modified</span>
  </div>
  <label for="url">URL</label><input id="url" placeholder="{{baseUrl}}/api/feed">
  <span class="resolved" id="resolved"></span>
  <label for="method">Method</label>
  <select id="method"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option><option>QUERY</option></select>
  <label for="signals">Signals</label><textarea id="signals" placeholder='{"search": "ash"}'></textarea>
  <label for="headers">Headers</label><textarea id="headers" placeholder="X-Csrf-Token: {{csrf}}"></textarea>
  <div class="row">
    <button type="submit" id="go">Connect</button>
    <button type="button" class="secondary" id="stop">Stop</button>
    <button type="button" class="secondary" id="clear">Clear</button>
    <button type="button" class="secondary" id="reset">Reset signals</button>
    <button type="button" class="secondary" id="curl" title="Copy an equivalent curl command">Copy as curl</button>
    <button type="button" class="link" id="openFile" title="Open .streamlord/inspector.json">edit file</button>
    <span class="status" id="status">idle</span>
  </div>
</form>
<main>
  <section id="frames"><p class="empty">No events yet. The realm is quiet.</p></section>
  <aside>
    <h2>Signal store</h2><pre id="store">{}</pre>
    <details><summary>Variables</summary><pre id="vars">{}</pre></details>
  </aside>
</main>
<script>
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const frames = $('frames');
  let count = 0;
  let saved = [], recent = [], variables = {};
  let loaded = null; // the request as loaded from the list, to detect edits
  const loadedName = () => { const v = $('saved').value; return v.startsWith('s:') ? v.slice(2) : ''; };
  const fields = () => ({ name: loadedName(), url: $('url').value, method: $('method').value, signals: $('signals').value, headers: $('headers').value });
  const clearEmpty = () => { const e = frames.querySelector('.empty'); if (e) e.remove(); };
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const time = (t) => new Date(t).toLocaleTimeString(undefined, { hour12: false }) + '.' + String(t % 1000).padStart(3, '0');
  const substitute = (s) => s.replace(/\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*\\}\\}/g, (w, n) => (n in variables ? variables[n] : w));
  const refresh = () => {
    $('resolved').textContent = $('url').value.includes('{{') ? substitute($('url').value) : '';
    const f = fields();
    const dirty = !!loaded && ['url','method','signals','headers'].some((k) => f[k] !== loaded[k]);
    $('dirty').hidden = !dirty;
    $('delete').disabled = !loadedName();
  };
  const fill = (r) => { $('url').value = r.url ?? ''; $('method').value = r.method ?? 'GET'; $('signals').value = r.signals ?? ''; $('headers').value = r.headers ?? ''; };
  const renderList = (selectValue) => {
    const sel = $('saved');
    sel.innerHTML = '<option value="">New request…</option>';
    if (saved.length) { const g = document.createElement('optgroup'); g.label = 'Saved'; for (const r of saved) { const o = document.createElement('option'); o.value = 's:' + r.name; o.textContent = r.name; g.appendChild(o); } sel.appendChild(g); }
    if (recent.length) { const g = document.createElement('optgroup'); g.label = 'Recent'; recent.forEach((r, i) => { const o = document.createElement('option'); o.value = 'r:' + i; o.textContent = r.name; g.appendChild(o); }); sel.appendChild(g); }
    sel.value = selectValue ?? '';
    if (sel.value !== (selectValue ?? '')) sel.value = '';
    refresh();
  };
  $('saved').addEventListener('change', () => {
    const v = $('saved').value;
    if (v.startsWith('s:')) { fill(saved.find((r) => r.name === v.slice(2)) ?? {}); loaded = fields(); }
    else if (v.startsWith('r:')) { fill(recent[Number(v.slice(2))] ?? {}); loaded = null; }
    else loaded = null;
    refresh();
  });
  for (const id of ['url','method','signals','headers']) $(id).addEventListener('input', refresh);
  $('f').addEventListener('submit', (e) => { e.preventDefault(); vscode.postMessage({ type: 'connect', request: fields() }); });
  $('stop').addEventListener('click', () => vscode.postMessage({ type: 'stop' }));
  $('reset').addEventListener('click', () => vscode.postMessage({ type: 'reset' }));
  $('save').addEventListener('click', () => vscode.postMessage({ type: 'save', request: fields() }));
  $('delete').addEventListener('click', () => { const n = loadedName(); if (n) vscode.postMessage({ type: 'delete', name: n }); });
  $('curl').addEventListener('click', () => vscode.postMessage({ type: 'curl', request: fields() }));
  $('openFile').addEventListener('click', () => vscode.postMessage({ type: 'openFile' }));
  $('clear').addEventListener('click', () => { frames.innerHTML = '<p class="empty">Cleared.</p>'; count = 0; });
  window.addEventListener('message', (ev) => {
    const m = ev.data;
    if (m.type === 'requests') {
      saved = m.saved; recent = m.recent; variables = m.variables;
      $('vars').textContent = JSON.stringify(variables, null, 2);
      if (m.current) {
        fill(m.current);
        const known = m.current.name && saved.some((r) => r.name === m.current.name);
        renderList(known ? 's:' + m.current.name : '');
        loaded = known ? fields() : null;
        refresh();
      } else {
        renderList($('saved').value);
      }
    }
    if (m.type === 'saved') { /* the file watcher refreshes the list; select the saved name once it arrives */ setTimeout(() => { renderList('s:' + m.name); loaded = fields(); refresh(); }, 300); }
    if (m.type === 'status') { const s = $('status'); s.textContent = m.status + (m.http ? ' · ' + m.http : '') + (m.contentType ? ' · ' + m.contentType : ''); s.className = 'status ' + (m.status === 'open' ? 'open' : ''); s.title = m.resolvedUrl ?? ''; }
    if (m.type === 'error') { clearEmpty(); const p = document.createElement('p'); p.className = 'error'; p.textContent = m.message; frames.prepend(p); $('status').className = 'status error'; $('status').textContent = 'error'; }
    if (m.type === 'signals') { $('store').textContent = JSON.stringify(m.signals, null, 2); }
    if (m.type === 'comment') { clearEmpty(); const p = document.createElement('p'); p.className = 'comment'; p.textContent = time(m.at) + '  : ' + m.text; frames.prepend(p); }
    if (m.type === 'nonsse') { clearEmpty(); const d = document.createElement('div'); d.className = 'frame'; d.innerHTML = '<header><span class="ev">' + esc(m.http) + '</span><span>' + esc(m.contentType || 'no content-type') + '</span><span class="t">non-SSE response</span></header><dl>' + Object.entries(m.headers).map(([k,v]) => '<dt>' + esc(k) + '</dt><dd><pre>' + esc(v) + '</pre></dd>').join('') + '<dt>body</dt><dd><pre>' + esc(m.body) + '</pre></dd></dl>'; frames.prepend(d); }
    if (m.type === 'frame') {
      clearEmpty(); count++;
      const f = m.frame; const kind = f.event.endsWith('elements') ? 'elements' : f.event.endsWith('signals') ? 'signals' : '';
      const d = document.createElement('div'); d.className = 'frame';
      const rows = Object.entries(f.args).map(([k, v]) => '<dt>' + esc(k) + '</dt><dd><pre>' + esc(v) + '</pre></dd>').join('');
      d.innerHTML = '<header><span>#' + count + '</span><span class="ev ' + kind + '">' + esc(f.event) + '</span>' + (f.id !== null ? '<span>id ' + esc(f.id) + '</span>' : '') + (f.retry !== null ? '<span>retry ' + f.retry + 'ms</span>' : '') + '<span class="t">' + time(f.receivedAt) + '</span></header><dl>' + rows + '</dl>';
      frames.prepend(d);
    }
  });
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
}
