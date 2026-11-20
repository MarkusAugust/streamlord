import * as vscode from "vscode";
import { inSkippedFolder as skipped, languageOfFile, signalPlaces, workspaceFiles, WorkspaceIndex, type DriftFacts, type SignalPlaces } from "./analysis.ts";

/** The markup files read whatever language an extension gives them, the folders left unread with every hidden one, and the caps on what is listed, as the analysis has them. */
const { markupExtensions: MARKUP_EXTENSIONS, skippedFolders: SKIPPED_FOLDERS, fileCap: FILE_CAP, sizeCap: SIZE_CAP, settingsGlob: SETTINGS_GLOB } = workspaceFiles();
const SOURCE_GLOB = `**/*.{kt,${MARKUP_EXTENSIONS.join(",")}}`;
const EXCLUDED_GLOB = `**/{${SKIPPED_FOLDERS.join(",")},.*}/**`;

/** Where a file lives on a disk the workspace reads; a git diff, an untitled buffer or an output channel defines nothing. */
export const INDEXED_SCHEMES: ReadonlySet<string> = new Set(["file", "vscode-remote", "vscode-vfs"]);

/** The ids and classes markup declares, which a selector may name. */
export interface Selectors {
  ids: Set<string>;
  classes: Set<string>;
}

/** How a file is read: Kotlin, markup, or Spring's settings, which only say how the codec names signals. */
export type Language = "kotlin" | "html" | "settings";

/** What the adapter keeps of a file beside the analysis's index: the version of the document read, null for the disk's. */
interface FileEntry {
  version: number | null;
  language: Language;
  /** Where the file defines and reads signals: worked out on the first search, which completion never asks for. */
  places?: Places;
}

/** Where a file defines and reads signals, and where its lines start, so that an offset becomes a position without the text. */
export interface Places {
  /** The attribute prefix the reads were found with. */
  prefix: string;
  names: SignalPlaces;
  lineStarts: number[];
}

/**
 * Workspace-wide index of signal names, kept fresh as files change: when a file is read is this
 * adapter's to know, and what it gives the workspace is the analysis's, in its `WorkspaceIndex`. It
 * keeps where each signal is defined and read as well, so that go to definition, find references and
 * rename read a file only the first time one is asked for.
 */
export class SignalIndex implements vscode.Disposable {
  private byFile = new Map<string, FileEntry>();
  private index: WorkspaceIndex;
  private built = false;
  private readonly changed = new vscode.EventEmitter<void>();
  private readonly disposables: vscode.Disposable[] = [];

  /** Fires when the signals some file defines have changed, so the other files' checks are stale. */
  readonly onDidChangeDefinitions = this.changed.event;

  /** `markupLanguages` are the language ids the extension reads as markup, from `streamlord.languages`. */
  constructor(
    private readonly markupLanguages: ReadonlySet<string>,
    private readonly prefix: () => string = () => "data-",
  ) {
    this.index = new WorkspaceIndex(prefix());
    this.disposables.push(
      this.changed,
      // A file no folder of the workspace holds is found only here, and it may define a signal as any other.
      vscode.workspace.onDidOpenTextDocument((d) => this.update(d)),
      vscode.workspace.onDidSaveTextDocument((d) => {
        this.update(d);
        this.readDrift(d);
      }),
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
    const settings = vscode.workspace.createFileSystemWatcher(SETTINGS_GLOB);
    this.disposables.push(
      watcher,
      watcher.onDidCreate((u) => void this.read(u)),
      watcher.onDidChange((u) => void this.read(u)),
      watcher.onDidDelete((u) => this.remove(u.toString())),
      settings,
      settings.onDidCreate((u) => void this.read(u)),
      settings.onDidChange((u) => void this.read(u)),
      settings.onDidDelete((u) => this.remove(u.toString())),
    );
  }

  async rebuild(): Promise<number> {
    // Built aside and swapped in whole, so a check that runs meanwhile still sees the old index.
    const next = new Map<string, FileEntry>();
    const index = new WorkspaceIndex(this.prefix());
    // Markup and Kotlin under separate caps, so the pages that define signals are not crowded out by sources.
    const [markup, kotlin, settings] = await Promise.all([
      vscode.workspace.findFiles(`**/*.{${MARKUP_EXTENSIONS.join(",")}}`, EXCLUDED_GLOB, FILE_CAP),
      vscode.workspace.findFiles("**/*.kt", EXCLUDED_GLOB, FILE_CAP),
      vscode.workspace.findFiles(SETTINGS_GLOB, EXCLUDED_GLOB, FILE_CAP),
    ]);
    const files = [...markup, ...kotlin, ...settings].filter((u) => !inSkippedFolder(u));
    await Promise.all(
      files.map(async (uri) => {
        try {
          const bytes = await vscode.workspace.fs.readFile(uri);
          if (bytes.length > SIZE_CAP) return;
          const language = languageByExtension(uri) ?? "html";
          index.put(uri.toString(), Buffer.from(bytes).toString("utf8"), language, true);
          next.set(uri.toString(), { version: null, language });
        } catch {
          // unreadable file: skip
        }
      }),
    );
    this.byFile = next;
    this.index = index;
    this.built = true;
    // An open document may hold edits the disk does not.
    for (const d of vscode.workspace.textDocuments) this.update(d);
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
    let bytes: Uint8Array | null = null;
    try {
      bytes = await vscode.workspace.fs.readFile(uri);
    } catch {
      // unreadable file: forgotten below
    }
    if (!bytes || bytes.length > SIZE_CAP) {
      this.remove(uri.toString());
      return;
    }
    this.set(uri.toString(), Buffer.from(bytes).toString("utf8"), language, null);
  }

  /** Read a document again, unless this version of it is already read. */
  update(document: vscode.TextDocument): void {
    const language = this.languageOf(document);
    if (!language) return;
    const key = document.uri.toString();
    const previous = this.byFile.get(key);
    if (previous?.version === document.version) return;
    this.set(key, document.getText(), language, document.version);
  }

  /** Read what a document tells the others about the signals the server reads, when its text has changed since. */
  readDrift(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    if (this.byFile.get(key)?.version !== document.version) return;
    if (this.index.readFacts(key, document.getText())) this.changed.fire();
  }

  /**
   * Hand the analysis a file's text. Read from disk it is read whole; an open document's change keeps
   * what it said about server reads before until the document is linted ([readDrift]), not on each keystroke.
   */
  private set(key: string, text: string, language: Language, version: number | null): void {
    this.byFile.set(key, { version, language });
    if (this.index.put(key, text, language, version === null)) this.changed.fire();
  }

  /** Forget a file, or every file under a folder that was deleted or renamed. */
  private remove(key: string): void {
    const folder = key.endsWith("/") ? key : `${key}/`;
    for (const k of this.byFile.keys()) if (k === key || k.startsWith(folder)) this.byFile.delete(k);
    if (this.index.remove(key)) this.changed.fire();
  }

  private languageOf(document: vscode.TextDocument): Language | null {
    if (!INDEXED_SCHEMES.has(document.uri.scheme) || inSkippedFolder(document.uri)) return null;
    if (document.languageId === "kotlin") return "kotlin";
    if (document.languageId === "html" || this.markupLanguages.has(document.languageId)) return "html";
    return languageByExtension(document.uri);
  }

  /** Ids and classes declared anywhere in the workspace. */
  allSelectors(): Selectors {
    return { ids: new Set(this.index.ids()), classes: new Set(this.index.classes()) };
  }

  all(): Set<string> {
    return new Set(this.index.signals());
  }

  /**
   * Every signal defined anywhere in the workspace, which a `$name` is checked against: no reads, as [all] has.
   * The same array until one changes, which is how the analysis knows it has read it before.
   */
  allDefinitions(): string[] {
    return this.index.definitions();
  }

  /** Every `const val` the workspace's Kotlin lets other files see and declares as nothing else. The same array until one changes. */
  allConstants(): string[] {
    return this.index.constants();
  }

  /** What every file but `uri` tells it about the signals the server reads. */
  driftExcept(uri: vscode.Uri): DriftFacts[] {
    return this.index.driftExcept(uri.toString());
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

/** Whether a folder on the way to the file, below the workspace folder that holds it, is one the analysis leaves unread. */
export function inSkippedFolder(uri: vscode.Uri): boolean {
  const roots = (vscode.workspace.workspaceFolders ?? []).map((f) => (f.uri.path.endsWith("/") ? f.uri.path : `${f.uri.path}/`));
  const root = roots.filter((r) => uri.path.startsWith(r)).sort((a, b) => b.length - a.length)[0] ?? "/";
  return skipped(uri.path.slice(root.length));
}

function languageByExtension(uri: vscode.Uri): Language | null {
  return languageOfFile(uri.path.slice(uri.path.lastIndexOf("/") + 1)) as Language | null;
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
export function placesIn(text: string, language: Language, prefix: string): Places {
  const lineStarts = [0];
  for (let i = 0; i < text.length; i++) if (text[i] === "\n") lineStarts.push(i + 1);
  // Settings name no signal: they only say how the codec names the others.
  const names = language === "settings" ? { declarations: [], reads: [] } : signalPlaces(text, language === "kotlin", prefix);
  return { prefix, names, lineStarts };
}
