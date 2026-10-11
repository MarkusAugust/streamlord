import * as vscode from "vscode";
import { collectSelectors, type Selectors } from "./selectors.ts";
import { collectSignalDefinitions, collectSignals } from "./signals.ts";

/** Template files read as markup, as the IntelliJ plugin reads them by default, whatever language an extension gives them. */
const MARKUP_EXTENSIONS = [
  "html", "htm", "xhtml", "jte", "kte", "ftl", "ftlh", "vm", "mustache", "hbs", "handlebars", "peb", "pebble", "twig", "jinja", "jinja2", "j2",
  "cshtml", "razor", "php", "erb", "ejs", "liquid", "njk", "edge", "astro", "svelte", "vue",
];
const SOURCE_GLOB = `**/*.{kt,${MARKUP_EXTENSIONS.join(",")}}`;
const EXCLUDED_GLOB = "**/{build,node_modules,.gradle,dist,out,target}/**";
const FILE_CAP = 5000;

/** Where a file lives on a disk the workspace reads; a git diff, an untitled buffer or an output channel defines nothing. */
const INDEXED_SCHEMES: ReadonlySet<string> = new Set(["file", "vscode-remote", "vscode-vfs"]);

interface FileEntry {
  version: number | null;
  signals: Set<string>;
  definitions: Set<string>;
  selectors: Selectors;
}

/** Workspace-wide index of signal names, kept fresh as files change. */
export class SignalIndex implements vscode.Disposable {
  private byFile = new Map<string, FileEntry>();
  private built = false;
  private definitionsUnion: Set<string> | null = null;
  private readonly changed = new vscode.EventEmitter<void>();
  private readonly disposables: vscode.Disposable[] = [];

  /** Fires when the signals some file defines have changed, so the other files' checks are stale. */
  readonly onDidChangeDefinitions = this.changed.event;

  /** `markupLanguages` are the language ids the extension reads as markup, from `streamlord.languages`. */
  constructor(private readonly markupLanguages: ReadonlySet<string>) {
    this.disposables.push(
      this.changed,
      vscode.workspace.onDidSaveTextDocument((d) => this.update(d)),
      vscode.workspace.onDidChangeTextDocument((e) => this.update(e.document)),
      // A closed document's unsaved edits are gone; what counts is the disk again.
      vscode.workspace.onDidCloseTextDocument((d) => void this.read(d.uri)),
      vscode.workspace.onDidDeleteFiles((e) => e.files.forEach((f) => this.remove(f.toString()))),
      vscode.workspace.onDidRenameFiles((e) =>
        e.files.forEach((f) => {
          this.remove(f.oldUri.toString());
          void this.read(f.newUri);
        }),
      ),
    );
    // Files changed outside the editor: a git checkout, a generated template, a file created or deleted on disk.
    const watcher = vscode.workspace.createFileSystemWatcher(SOURCE_GLOB);
    this.disposables.push(
      watcher,
      watcher.onDidCreate((u) => void this.read(u)),
      watcher.onDidChange((u) => void this.read(u)),
      watcher.onDidDelete((u) => this.remove(u.toString())),
    );
  }

  async rebuild(): Promise<number> {
    // Built aside and swapped in whole, so a check that runs meanwhile still sees the old index.
    const next = new Map<string, FileEntry>();
    // Markup and Kotlin under separate caps, so the pages that define signals are not crowded out by sources.
    const [markup, kotlin] = await Promise.all([
      vscode.workspace.findFiles(`**/*.{${MARKUP_EXTENSIONS.join(",")}}`, EXCLUDED_GLOB, FILE_CAP),
      vscode.workspace.findFiles("**/*.kt", EXCLUDED_GLOB, FILE_CAP),
    ]);
    const files = [...markup, ...kotlin];
    await Promise.all(
      files.map(async (uri) => {
        try {
          const bytes = await vscode.workspace.fs.readFile(uri);
          next.set(uri.toString(), entry(Buffer.from(bytes).toString("utf8"), uri.path.endsWith(".kt") ? "kotlin" : "html", null));
        } catch {
          // unreadable file: skip
        }
      }),
    );
    this.byFile = next;
    this.built = true;
    // An open document may hold edits the disk does not.
    for (const d of vscode.workspace.textDocuments) this.update(d);
    this.definitionsUnion = null;
    this.changed.fire();
    return this.all().size;
  }

  /** Has the workspace been read once? Until then a signal missing from the index may only be unread. */
  get ready(): boolean {
    return this.built;
  }

  /** Read a file from disk, unless an open document holds its text, which the change events keep. */
  private async read(uri: vscode.Uri): Promise<void> {
    if (!INDEXED_SCHEMES.has(uri.scheme)) return;
    const language = languageByExtension(uri);
    if (!language) return;
    if (vscode.workspace.textDocuments.some((d) => d.uri.toString() === uri.toString() && !d.isClosed)) return;
    let text: string;
    try {
      text = Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
    } catch {
      this.remove(uri.toString());
      return;
    }
    this.set(uri.toString(), entry(text, language, null));
  }

  /** Read a document again, unless this version of it is already read. */
  update(document: vscode.TextDocument): void {
    const language = this.languageOf(document);
    if (!language) return;
    const key = document.uri.toString();
    if (this.byFile.get(key)?.version === document.version) return;
    this.set(key, entry(document.getText(), language, document.version));
  }

  private set(key: string, next: FileEntry): void {
    const previous = this.byFile.get(key);
    this.byFile.set(key, next);
    if (!previous || !sameSet(previous.definitions, next.definitions)) {
      this.definitionsUnion = null;
      this.changed.fire();
    }
  }

  /** Forget a file, or every file under a folder that was deleted or renamed. */
  private remove(key: string): void {
    const folder = key.endsWith("/") ? key : `${key}/`;
    let defined = false;
    for (const [k, e] of this.byFile) {
      if (k !== key && !k.startsWith(folder)) continue;
      this.byFile.delete(k);
      defined ||= e.definitions.size > 0;
    }
    if (defined) {
      this.definitionsUnion = null;
      this.changed.fire();
    }
  }

  private languageOf(document: vscode.TextDocument): "kotlin" | "html" | null {
    if (!INDEXED_SCHEMES.has(document.uri.scheme)) return null;
    if (document.languageId === "kotlin") return "kotlin";
    if (document.languageId === "html" || this.markupLanguages.has(document.languageId)) return "html";
    return languageByExtension(document.uri);
  }

  /** Ids and classes declared in one file. */
  selectorsForFile(uri: string): Selectors {
    return this.byFile.get(uri)?.selectors ?? { ids: new Set(), classes: new Set() };
  }

  /** Ids and classes declared anywhere in the workspace. */
  allSelectors(): Selectors {
    const ids = new Set<string>();
    const classes = new Set<string>();
    for (const e of this.byFile.values()) {
      for (const i of e.selectors.ids) ids.add(i);
      for (const c of e.selectors.classes) classes.add(c);
    }
    return { ids, classes };
  }

  /** Signals declared in one file, or an empty set. */
  forFile(uri: string): Set<string> {
    return this.byFile.get(uri)?.signals ?? new Set();
  }

  all(): Set<string> {
    const out = new Set<string>();
    for (const e of this.byFile.values()) for (const n of e.signals) out.add(n);
    return out;
  }

  /** Every signal defined anywhere in the workspace, which a `$name` is checked against: no reads, as [all] has. Cached until one changes. */
  allDefinitions(): ReadonlySet<string> {
    if (this.definitionsUnion) return this.definitionsUnion;
    const out = new Set<string>();
    for (const e of this.byFile.values()) for (const n of e.definitions) out.add(n);
    this.definitionsUnion = out;
    return out;
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
  }
}

function languageByExtension(uri: vscode.Uri): "kotlin" | "html" | null {
  const extension = uri.path.slice(uri.path.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "kt") return "kotlin";
  return MARKUP_EXTENSIONS.includes(extension) ? "html" : null;
}

function entry(text: string, language: "kotlin" | "html", version: number | null): FileEntry {
  return { version, signals: collectSignals(text, language), definitions: collectSignalDefinitions(text, language), selectors: collectSelectors(text) };
}

function sameSet(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) return false;
  for (const n of a) if (!b.has(n)) return false;
  return true;
}
