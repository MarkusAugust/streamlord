import * as vscode from "vscode";
import { collectSelectors, type Selectors } from "./selectors.ts";
import { collectSignalDefinitions, collectSignals } from "./signals.ts";

/** Template files read as markup, as the IntelliJ plugin reads them, whatever language an extension gives them. */
const MARKUP_EXTENSIONS = ["html", "htm", "xhtml", "jte", "kte", "ftl", "ftlh", "vm", "mustache", "peb", "pebble", "twig", "hbs"];

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
      vscode.workspace.onDidDeleteFiles((e) => e.files.forEach((f) => this.remove(f.toString()))),
      vscode.workspace.onDidRenameFiles((e) => e.files.forEach((f) => this.remove(f.oldUri.toString()))),
    );
  }

  async rebuild(): Promise<number> {
    // Built aside and swapped in whole, so a check that runs meanwhile still sees the old index.
    const next = new Map<string, FileEntry>();
    const files = await vscode.workspace.findFiles(`**/*.{kt,${MARKUP_EXTENSIONS.join(",")}}`, "**/{build,node_modules,.gradle,dist,out,target}/**", 5000);
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

  /** Read a document again, unless this version of it is already read. */
  update(document: vscode.TextDocument): void {
    const language = this.languageOf(document);
    if (!language) return;
    const key = document.uri.toString();
    const previous = this.byFile.get(key);
    if (previous?.version === document.version) return;
    const next = entry(document.getText(), language, document.version);
    this.byFile.set(key, next);
    if (!previous || !sameSet(previous.definitions, next.definitions)) {
      this.definitionsUnion = null;
      this.changed.fire();
    }
  }

  private remove(key: string): void {
    const previous = this.byFile.get(key);
    if (!previous) return;
    this.byFile.delete(key);
    if (previous.definitions.size > 0) {
      this.definitionsUnion = null;
      this.changed.fire();
    }
  }

  private languageOf(document: vscode.TextDocument): "kotlin" | "html" | null {
    if (document.languageId === "kotlin") return "kotlin";
    if (document.languageId === "html" || this.markupLanguages.has(document.languageId)) return "html";
    const extension = document.uri.path.slice(document.uri.path.lastIndexOf(".") + 1).toLowerCase();
    return MARKUP_EXTENSIONS.includes(extension) ? "html" : null;
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

function entry(text: string, language: "kotlin" | "html", version: number | null): FileEntry {
  return { version, signals: collectSignals(text, language), definitions: collectSignalDefinitions(text, language), selectors: collectSelectors(text) };
}

function sameSet(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) return false;
  for (const n of a) if (!b.has(n)) return false;
  return true;
}
