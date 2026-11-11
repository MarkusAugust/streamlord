// Runs inside VS Code's extension host: opens every file of the workspace and writes down what the editor's providers answer.
const vscode = require("vscode");
const fs = require("node:fs");
const path = require("node:path");

const job = JSON.parse(fs.readFileSync(process.env.STREAMLORD_REAL_JOB, "utf8"));
const root = vscode.Uri.file(job.workspace).toString() + "/";
const outside = vscode.Uri.file(job.outside.folder).toString() + "/";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const run = (command, ...args) => vscode.commands.executeCommand(command, ...args);

/** Polls `probe` until it returns something, and fails by name when it never does. */
async function until(what, probe, timeout = 30_000) {
  const end = Date.now() + timeout;
  for (;;) {
    const got = await probe();
    if (got) return got;
    if (Date.now() > end) throw new Error(`Timed out after ${timeout} ms waiting for ${what}.`);
    await sleep(50);
  }
}

// What the providers hand back, as plain values: 1-based line:column, paths from the workspace root.
const at = (p) => `${p.line + 1}:${p.character + 1}`;
const span = (r) => (r ? `${at(r.start)}-${at(r.end)}` : undefined);
const under = (uri, folder) => (uri.toString().startsWith(folder) ? decodeURIComponent(uri.toString().slice(folder.length)) : null);
const file = (uri) => under(uri, root) ?? (under(uri, outside) === null ? uri.toString() : job.outside.name + under(uri, outside));
const text = (d) => (d === undefined || d === null ? undefined : typeof d === "string" ? d : d.value);
const place = (l) => ({ file: file(l.targetUri ?? l.uri), range: span(l.targetSelectionRange ?? l.targetRange ?? l.range) });
const byPlace = (a, b) => a.file.localeCompare(b.file) || rank(a.range) - rank(b.range);
const rank = (s) => {
  const [line, column] = s.split(/[:-]/).map(Number);
  return line * 100_000 + column;
};
const edits = (edit) =>
  edit
    .entries()
    .flatMap(([uri, list]) => list.map((e) => ({ file: file(uri), range: span(e.range), text: e.newText })))
    .sort(byPlace);

function diagnostic(d) {
  return {
    range: span(d.range),
    code: typeof d.code === "object" ? d.code.value : d.code,
    link: typeof d.code === "object" ? d.code.target.toString() : undefined,
    severity: vscode.DiagnosticSeverity[d.severity],
    message: d.message,
    source: d.source,
    tags: d.tags?.map((t) => vscode.DiagnosticTag[t]),
    related: d.relatedInformation?.map((r) => ({ ...place(r.location), message: r.message })),
  };
}

/** The document after `edit`, worked out on a copy so that the open document is never touched. */
function applied(document, edit) {
  let out = document.getText();
  const mine = edit
    .entries()
    .filter(([uri]) => uri.toString() === document.uri.toString())
    .flatMap(([, list]) => list)
    .map((e) => ({ from: document.offsetAt(e.range.start), to: document.offsetAt(e.range.end), text: e.newText }))
    .sort((a, b) => b.from - a.from || b.to - a.to);
  for (let i = 0; i < mine.length; i++) {
    if (i > 0 && mine[i].to > mine[i - 1].from) throw new Error(`Overlapping edits in ${file(document.uri)} at offset ${mine[i].from}.`);
    out = out.slice(0, mine[i].from) + mine[i].text + out.slice(mine[i].to);
  }
  return out;
}

function action(document, a) {
  return {
    title: a.title,
    kind: a.kind?.value,
    preferred: a.isPreferred,
    disabled: a.disabled?.reason,
    fixes: a.diagnostics?.map((d) => `${typeof d.code === "object" ? d.code.value : d.code} ${span(d.range)}`),
    command: a.command?.command,
    edits: a.edit ? edits(a.edit) : undefined,
    after: a.edit ? applied(document, a.edit) : undefined,
  };
}

/** A range on the caret's line as columns before and after the caret, so that an item offered in many places is the same item in each. */
const around = (r, caret) => (r.start.line === caret.line && r.end.line === caret.line ? `${r.start.character - caret.character}..${r.end.character - caret.character}` : span(r));

function completion(i, caret) {
  const range = i.range ? (i.range.inserting ? { inserting: around(i.range.inserting, caret), replacing: around(i.range.replacing, caret) } : around(i.range, caret)) : undefined;
  const label = typeof i.label === "string" ? { label: i.label } : { label: i.label.label, labelDetail: i.label.detail, labelDescription: i.label.description };
  return {
    ...label,
    kind: i.kind === undefined ? undefined : vscode.CompletionItemKind[i.kind],
    detail: i.detail,
    documentation: text(i.documentation),
    insert: i.insertText === undefined ? undefined : typeof i.insertText === "string" ? i.insertText : { snippet: i.insertText.value },
    range,
    textEdit: i.textEdit ? { range: around(i.textEdit.range, caret), text: i.textEdit.newText } : undefined,
    more: i.additionalTextEdits?.map((e) => ({ range: span(e.range), text: e.newText })),
    sort: i.sortText,
    filter: i.filterText,
    preselect: i.preselect,
    commit: i.commitCharacters,
    command: i.command?.command,
    tags: i.tags?.map((t) => vscode.CompletionItemTag[t]),
  };
}

const order = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

// What is asked at every offset of a file. An answer that throws is the error a user is shown, so it is an answer too.
const everywhere = {
  hover: async (uri, p) => {
    const found = await run("vscode.executeHoverProvider", uri, p);
    return found.map((h) => ({ range: span(h.range), contents: h.contents.map(text) }));
  },
  signature: async (uri, p) => {
    const help = await run("vscode.executeSignatureHelpProvider", uri, p);
    if (!help) return undefined;
    return {
      active: help.activeSignature,
      parameter: help.activeParameter,
      signatures: help.signatures.map((s) => ({
        label: s.label,
        documentation: text(s.documentation),
        parameter: s.activeParameter,
        parameters: s.parameters.map((x) => ({ label: x.label, documentation: text(x.documentation) })),
      })),
    };
  },
  definition: async (uri, p) => (await run("vscode.executeDefinitionProvider", uri, p)).map(place).sort(byPlace),
  references: async (uri, p) => (await run("vscode.executeReferenceProvider", uri, p)).map(place).sort(byPlace),
  rename: async (uri, p) => {
    const ready = await run("vscode.prepareRename", uri, p);
    return ready ? { range: span(ready.range), placeholder: ready.placeholder } : undefined;
  },
};

const empty = (v) => v === undefined || (Array.isArray(v) && v.length === 0);

/** One provider's answers over the whole file: each run of offsets with the same answer is one entry, and no answer is none. */
async function sweep(document, ask) {
  const length = document.getText().length;
  const out = [];
  let last = null;
  for (let from = 0; from <= length; from += 64) {
    const offsets = Array.from({ length: Math.min(64, length + 1 - from) }, (_, i) => from + i);
    const answers = await Promise.all(
      offsets.map((o) =>
        ask(document.uri, document.positionAt(o)).then(
          (value) => value,
          (e) => ({ error: e.message }),
        ),
      ),
    );
    answers.forEach((value, i) => {
      const o = offsets[i];
      // Two offsets in one line break are one position.
      if (o > 0 && document.offsetAt(document.positionAt(o)) !== o) return;
      const key = empty(value) ? null : JSON.stringify(value);
      if (last && last.key === key) last.to = o;
      else {
        last = { key, from: o, to: o };
        if (key !== null) out.push(last);
      }
    });
  }
  return out.map((r) => ({ at: `${at(document.positionAt(r.from))}-${at(document.positionAt(r.to))}`, ...wrap(JSON.parse(r.key)) }));
}

const wrap = (value) => (Array.isArray(value) ? { answers: value } : value);

/** The diagnostics once they have stood unchanged for longer than the extension waits before it lints again. */
async function settled(uri, name) {
  const read = () => JSON.stringify(vscode.languages.getDiagnostics(uri).map(diagnostic));
  let seen = read();
  let since = Date.now();
  await until(`the diagnostics of ${name} to settle`, () => {
    const now = read();
    if (now !== seen) {
      seen = now;
      since = Date.now();
    }
    return Date.now() - since >= job.settle;
  });
  return vscode.languages.getDiagnostics(uri);
}

async function record(name, marks, folder = job.workspace) {
  const uri = vscode.Uri.file(path.join(folder, name));
  const document = await vscode.workspace.openTextDocument(uri);
  await vscode.window.showTextDocument(document);
  const found = await settled(uri, name);
  const out = { language: document.languageId, diagnostics: found.map(diagnostic).sort((a, b) => rank(a.range) - rank(b.range) || order(JSON.stringify(a), JSON.stringify(b))) };

  // The lightbulb at each diagnostic, and the one over the whole file.
  const whole = new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length));
  const ranges = new Map([[span(whole), whole], ...found.map((d) => [span(d.range), d.range])]);
  out.actions = [];
  for (const [key, range] of [...ranges].sort(([a], [b]) => rank(a) - rank(b) || order(a, b))) {
    const actions = (await run("vscode.executeCodeActionProvider", uri, range)) ?? [];
    actions.forEach((a, i) => out.actions.push({ at: key, n: i, ...action(document, a) }));
  }

  for (const [kind, ask] of Object.entries(everywhere)) out[kind] = await sweep(document, ask);

  // A rename from each place that takes one: every edit it makes in the workspace, or the refusal.
  out.renames = [];
  for (const r of out.rename.filter((x) => x.range)) {
    const [line, column] = r.range.split(/[:-]/).map(Number);
    const result = await run("vscode.executeDocumentRenameProvider", uri, new vscode.Position(line - 1, column - 1), job.renameTo).then(
      (edit) => ({ edits: edit ? edits(edit) : [] }),
      (e) => ({ error: e.message }),
    );
    out.renames.push({ at: r.range, to: job.renameTo, ...result });
  }

  out.completions = [];
  for (const mark of marks) {
    const caret = mark.at.indexOf(job.caret);
    const start = nth(document.getText(), mark.at.replace(job.caret, ""), mark.nth ?? 0);
    if (caret < 0 || start < 0) throw new Error(`The mark ${JSON.stringify(mark)} is not in ${name}.`);
    const position = document.positionAt(start + caret);
    const list = await run("vscode.executeCompletionItemProvider", uri, position, mark.trigger, 10_000);
    const items = list.items.map((i) => completion(i, position)).sort((a, b) => order(a.sort ?? a.label, b.sort ?? b.label) || order(JSON.stringify(a), JSON.stringify(b)));
    out.completions.push({ mark: mark.at, nth: mark.nth, at: at(position), trigger: mark.trigger, incomplete: list.isIncomplete || undefined, items });
  }

  out.lenses = ((await run("vscode.executeCodeLensProvider", uri, 10_000)) ?? [])
    // The request the lens fills the inspector with is what a user meets; the rest of what the command is handed is the extension's own.
    .map((l) => ({ range: span(l.range), title: l.command?.title, tooltip: l.command?.tooltip, command: l.command?.command, prefill: l.command?.arguments?.[0]?.request }))
    .sort((a, b) => rank(a.range) - rank(b.range) || order(a.title, b.title));
  out.links = ((await run("vscode.executeLinkProvider", uri, 10_000)) ?? [])
    .map((l) => ({ range: span(l.range), target: l.target ? file(l.target) : undefined, tooltip: l.tooltip }))
    .sort((a, b) => rank(a.range) - rank(b.range));
  out.colors = ((await run("vscode.executeDocumentColorProvider", uri)) ?? [])
    .map((c) => ({ range: span(c.range), color: [c.color.red, c.color.green, c.color.blue, c.color.alpha].join(" ") }))
    .sort((a, b) => rank(a.range) - rank(b.range));

  // A lint that came in while the providers were asked would make the answers above those of two different states.
  if (JSON.stringify(vscode.languages.getDiagnostics(uri).map(diagnostic)) !== JSON.stringify(found.map(diagnostic))) throw new Error(`The diagnostics of ${name} changed while it was recorded.`);
  await run("workbench.action.closeActiveEditor");
  return out;
}

function nth(haystack, needle, n) {
  let from = -1;
  for (let i = 0; i <= n; i++) {
    from = haystack.indexOf(needle, from + 1);
    if (from < 0) return -1;
  }
  return from;
}

exports.run = async () => {
  const out = { vscode: vscode.version, files: {} };
  try {
    const extension = vscode.extensions.getExtension("MarkusAugust.streamlord");
    if (!extension) throw new Error("The Streamlord extension is not loaded.");
    await extension.activate();
    out.extension = extension.packageJSON.version;
    // Without its folder the editor reads no file it was not shown, and every answer across files would be missing.
    const folders = (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
    if (folders.length !== 1 || fs.realpathSync(folders[0]) !== fs.realpathSync(job.workspace)) throw new Error(`VS Code opened ${JSON.stringify(folders)}, not the workspace ${job.workspace}.`);
    out.commands = (await vscode.commands.getCommands(true)).filter((c) => c.startsWith("streamlord.")).sort();

    for (const pass of job.passes) {
      await vscode.workspace.getConfiguration("streamlord").update("attributePrefix", pass.prefix, vscode.ConfigurationTarget.Global);
      // The command returns when the workspace has been read, which is when the unknown-signal check starts.
      await run("streamlord.reindexSignals");
      // The HTML language server reads the extension's custom data and answers hovers and completions beside it; it starts on the first HTML file.
      const html = pass.files.find((f) => f.endsWith(".html"));
      if (html) {
        const uri = vscode.Uri.file(path.join(job.workspace, html));
        const document = await vscode.workspace.openTextDocument(uri);
        const tag = document.positionAt(document.getText().search(/<[a-z]/) + 1);
        await until("the HTML language server", async () => (await run("vscode.executeCompletionItemProvider", uri, tag)).items.some((i) => i.label === "div"));
      }
      for (const name of pass.files) out.files[name] = await record(name, job.marks.filter((m) => m.file === name));
      // Written for the catalog's own prefix, which the first pass reads with.
      if (pass === job.passes[0]) for (const name of job.outside.files) out.files[job.outside.name + name] = await record(name, [], job.outside.folder);
    }
  } catch (e) {
    out.error = e.stack ?? String(e);
  }
  fs.writeFileSync(job.out, JSON.stringify(out));
};
