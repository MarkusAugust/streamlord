import * as vscode from "vscode";
import { constantsAcross, signalPlaces, signalsOf, type FileSignals, type SignalPlaces } from "./analysis.ts";

/** Template files read as markup, as the IntelliJ plugin reads them by default, whatever language an extension gives them. */
const MARKUP_EXTENSIONS = [
  "html", "htm", "xhtml", "jte", "kte", "ftl", "ftlh", "vm", "mustache", "hbs", "handlebars", "peb", "pebble", "twig", "jinja", "jinja2", "j2",
  "cshtml", "razor", "php", "erb", "ejs", "liquid", "njk", "edge", "astro", "svelte", "vue",
];
const SOURCE_GLOB = `**/*.{kt,${MARKUP_EXTENSIONS.join(",")}}`;
/** The folders left unread, as `analyzeProject` leaves them: build output, dependencies, and every hidden folder. */
const SKIPPED_FOLDERS: readonly string[] = ["build", "node_modules", ".gradle", "dist", "out", "target"];
const EXCLUDED_GLOB = `**/{${SKIPPED_FOLDERS.join(",")},.*}/**`;
const FILE_CAP = 5000;

/** Where a file lives on a disk the workspace reads; a git diff, an untitled buffer or an output channel defines nothing. */
export const INDEXED_SCHEMES: ReadonlySet<string> = new Set(["file", "vscode-remote", "vscode-vfs"]);

/** The ids and classes markup declares, which a selector may name. */
export interface Selectors {
  ids: Set<string>;
  classes: Set<string>;
}

interface FileEntry {
  version: number | null;
  language: "kotlin" | "html";
  /** Where the file defines and reads signals: worked out on the first search, which completion never asks for. */
  places?: Places;
  /** What the file gives the workspace: its signals, its ids and classes, and the names its Kotlin lets other files see. */
  found: FileSignals;
}

/** Where a file defines and reads signals, and where its lines start, so that an offset becomes a position without the text. */
export interface Places {
  /** The attribute prefix the reads were found with. */
  prefix: string;
  names: SignalPlaces;
  lineStarts: number[];
}

/**
 * Workspace-wide index of signal names, kept fresh as files change. It keeps where each signal is
 * defined and read as well, so that go to definition, find references and rename read a file only
 * the first time one is asked for.
 */
export class SignalIndex implements vscode.Disposable {
  private byFile = new Map<string, FileEntry>();
  private built = false;
  private definitionsUnion: string[] | null = null;
  private constantsUnion: string[] | null = null;
  private readonly changed = new vscode.EventEmitter<void>();
  private readonly disposables: vscode.Disposable[] = [];

  /** Fires when the signals some file defines have changed, so the other files' checks are stale. */
  readonly onDidChangeDefinitions = this.changed.event;

  /** `markupLanguages` are the language ids the extension reads as markup, from `streamlord.languages`. */
  constructor(
    private readonly markupLanguages: ReadonlySet<string>,
    private readonly prefix: () => string = () => "data-",
  ) {
    this.disposables.push(
      this.changed,
      // A file no folder of the workspace holds is found only here, and it may define a signal as any other.
      vscode.workspace.onDidOpenTextDocument((d) => this.update(d)),
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
    // A watcher takes no exclude: read() leaves out a file in a skipped folder.
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
    const files = [...markup, ...kotlin].filter((u) => !inSkippedFolder(u));
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
    this.constantsUnion = null;
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
    if (!language || inSkippedFolder(uri)) return;
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
    const a = previous?.found;
    const b = next.found;
    if (!a || !sameNames(a.definitions, b.definitions) || !sameNames(a.constants, b.constants) || !sameNames(a.others, b.others)) {
      this.definitionsUnion = null;
      this.constantsUnion = null;
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
      defined ||= e.found.definitions.length > 0 || e.found.constants.length > 0 || e.found.others.length > 0;
    }
    if (defined) {
      this.definitionsUnion = null;
      this.constantsUnion = null;
      this.changed.fire();
    }
  }

  private languageOf(document: vscode.TextDocument): "kotlin" | "html" | null {
    if (!INDEXED_SCHEMES.has(document.uri.scheme) || inSkippedFolder(document.uri)) return null;
    if (document.languageId === "kotlin") return "kotlin";
    if (document.languageId === "html" || this.markupLanguages.has(document.languageId)) return "html";
    return languageByExtension(document.uri);
  }

  /** Ids and classes declared in one file. */
  selectorsForFile(uri: string): Selectors {
    const found = this.byFile.get(uri)?.found;
    return { ids: new Set(found?.ids), classes: new Set(found?.classes) };
  }

  /** Ids and classes declared anywhere in the workspace. */
  allSelectors(): Selectors {
    const ids = new Set<string>();
    const classes = new Set<string>();
    for (const e of this.byFile.values()) {
      for (const i of e.found.ids) ids.add(i);
      for (const c of e.found.classes) classes.add(c);
    }
    return { ids, classes };
  }

  /** Signals declared in one file, or an empty set. */
  forFile(uri: string): Set<string> {
    return new Set(this.byFile.get(uri)?.found.signals);
  }

  all(): Set<string> {
    const out = new Set<string>();
    for (const e of this.byFile.values()) for (const n of e.found.signals) out.add(n);
    return out;
  }

  /**
   * Every signal defined anywhere in the workspace, which a `$name` is checked against: no reads, as [all] has.
   * The same array until one changes, which is how the analysis knows it has read it before.
   */
  allDefinitions(): string[] {
    if (this.definitionsUnion) return this.definitionsUnion;
    const out = new Set<string>();
    for (const e of this.byFile.values()) for (const n of e.found.definitions) out.add(n);
    this.definitionsUnion = [...out];
    return this.definitionsUnion;
  }

  /** Every `const val` the workspace's Kotlin lets other files see and declares as nothing else. Cached until one changes. */
  allConstants(): string[] {
    if (this.constantsUnion) return this.constantsUnion;
    this.constantsUnion = constantsAcross([...this.byFile.values()].map((e) => e.found));
    return this.constantsUnion;
  }

  /** Where every indexed file defines and reads signals, by uri; a file is read again only the first time. */
  async allPlaces(): Promise<Map<string, Places>> {
    const out = new Map<string, Places>();
    await Promise.all(
      [...this.byFile].map(async ([uri, e]) => {
        const places = await this.placesOf(uri, e);
        if (places) out.set(uri, places);
      }),
    );
    return out;
  }

  private async placesOf(uri: string, e: FileEntry): Promise<Places | null> {
    if (e.places?.prefix === this.prefix()) return e.places;
    const text = await textOf(uri);
    if (text === null) return null;
    const places = placesIn(text, e.language, this.prefix());
    // Kept only while the entry is the index's: an edit meanwhile has put a new one in its place.
    if (this.byFile.get(uri) === e) e.places = places;
    return places;
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
  }
}

/** Whether a folder on the way to the file, below the workspace folder that holds it, is one the index leaves unread. */
export function inSkippedFolder(uri: vscode.Uri): boolean {
  const roots = (vscode.workspace.workspaceFolders ?? []).map((f) => (f.uri.path.endsWith("/") ? f.uri.path : `${f.uri.path}/`));
  const root = roots.filter((r) => uri.path.startsWith(r)).sort((a, b) => b.length - a.length)[0] ?? "/";
  const folders = uri.path.slice(root.length).split("/").slice(0, -1);
  return folders.some((name) => SKIPPED_FOLDERS.includes(name) || name.startsWith("."));
}

function languageByExtension(uri: vscode.Uri): "kotlin" | "html" | null {
  const extension = uri.path.slice(uri.path.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "kt") return "kotlin";
  return MARKUP_EXTENSIONS.includes(extension) ? "html" : null;
}

/** The text of a file: an open document's, which may hold edits the disk does not, or else the disk's. */
export async function textOf(uri: string): Promise<string | null> {
  const open = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri);
  if (open) return open.getText();
  try {
    return Buffer.from(await vscode.workspace.fs.readFile(vscode.Uri.parse(uri))).toString("utf8");
  } catch {
    return null;
  }
}

/** Where `text` defines and reads signals. */
export function placesIn(text: string, language: "kotlin" | "html", prefix: string): Places {
  const lineStarts = [0];
  for (let i = 0; i < text.length; i++) if (text[i] === "\n") lineStarts.push(i + 1);
  return { prefix, names: signalPlaces(text, language === "kotlin", prefix), lineStarts };
}

function entry(text: string, language: "kotlin" | "html", version: number | null): FileEntry {
  return { version, language, found: signalsOf(text, language === "kotlin") };
}

/** Whether two lists of distinct names hold the same names. */
function sameNames(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const names = new Set(a);
  return b.every((n) => names.has(n));
}
