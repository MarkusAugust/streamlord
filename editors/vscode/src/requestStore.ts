import * as vscode from "vscode";
import { ENV_FILE, emptyRequestsFile, mergeVariables, parseEnv, parseRequestsFile, pushRecent, removeRequest, REQUESTS_FILE, requestsPathProblem, saveRequest, withBaseUrl, withEnvVariables, type RequestsEdit, type RequestsFile, type SavedRequest, type Variable } from "./requests.ts";
import type { RunningServers } from "./serverLog.ts";

/**
 * Where inspector requests live: saved ones in `.streamlord/inspector.json` in the workspace,
 * recent ones and the last-used request in the workspace state, and the variables in
 * `.streamlord/env.json`, which is meant for local hosts and tokens and stays out of version control.
 */
export class RequestStore implements vscode.Disposable {
  private readonly onChange = new vscode.EventEmitter<void>();
  readonly onDidChange = this.onChange.event;
  private readonly disposables: vscode.Disposable[] = [];

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly running: RunningServers,
  ) {
    // The Variables box follows a server started or stopped from the editor, as Connect reads it.
    this.disposables.push(running.onChange(() => this.onChange.fire()));
    const watcher = vscode.workspace.createFileSystemWatcher("**/.streamlord/*.json");
    this.disposables.push(watcher, watcher.onDidChange(() => this.onChange.fire()), watcher.onDidCreate(() => this.onChange.fire()), watcher.onDidDelete(() => this.onChange.fire()));
    this.disposables.push(vscode.workspace.onDidChangeConfiguration((e) => e.affectsConfiguration("streamlord.inspector") && this.onChange.fire()));
    // The Variables box follows the env file as it is typed, as Connect reads it.
    this.disposables.push(vscode.workspace.onDidChangeTextDocument((e) => e.document.uri.path.endsWith(`/${ENV_FILE}`) && this.onChange.fire()));
  }

  private folder(): vscode.WorkspaceFolder | undefined {
    return vscode.workspace.workspaceFolders?.[0];
  }

  private fileUri(rel: string): vscode.Uri | undefined {
    const f = this.folder();
    return f ? vscode.Uri.joinPath(f.uri, rel) : undefined;
  }

  private configuredRequestsPath(): string {
    return vscode.workspace.getConfiguration("streamlord").get<string>("inspector.requestsFile", REQUESTS_FILE);
  }

  /** Relative path of the requests file, configurable; a path that leaves the workspace falls back to the default, and a save says so. */
  private requestsRel(): string {
    const configured = this.configuredRequestsPath();
    return requestsPathProblem(configured) === null && configured.trim().length > 0 ? configured : REQUESTS_FILE;
  }

  /** What is wrong with the path the setting names, or null; the default file is read meanwhile. */
  requestsPathProblem(): string | null {
    return requestsPathProblem(this.configuredRequestsPath());
  }

  /** The text of the requests file, or null when there is none. A file that is there and cannot be read throws: it is no missing file. */
  private async requestsText(): Promise<string | null> {
    const uri = this.fileUri(this.requestsRel());
    if (!uri) return null;
    try {
      return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
    } catch (e) {
      if (e instanceof vscode.FileSystemError && e.code === "FileNotFound") return null;
      throw new Error(`${this.requestsRel()} cannot be read: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /** The requests file; a file that is missing is empty, one that cannot be read says so in `error`, and an entry that is not read is named in `skipped`. */
  async readFile(): Promise<RequestsFile> {
    let text: string | null;
    try {
      text = await this.requestsText();
    } catch (e) {
      return { ...emptyRequestsFile(), error: (e as Error).message };
    }
    return text === null ? emptyRequestsFile() : parseRequestsFile(text, this.requestsRel());
  }

  /** The change under way, which the next one waits for: two at once would each write over what the other read. */
  private changing: Promise<void> | null = null;

  /**
   * Change the requests file as `edit` says of its text, which keeps what the file holds besides.
   * A file that cannot be read is left alone: writing over it would lose what it holds.
   */
  private change(edit: (text: string | null, path: string) => RequestsEdit): Promise<void> {
    // With none under way it starts at once, so it reads the file as it is when asked.
    const next = this.changing === null ? this.changeNow(edit) : this.changing.then(() => this.changeNow(edit));
    const done: Promise<void> = next
      .catch(() => undefined)
      .then(() => {
        if (this.changing === done) this.changing = null;
      });
    this.changing = done;
    return next;
  }

  private async changeNow(edit: (text: string | null, path: string) => RequestsEdit): Promise<void> {
    const problem = this.requestsPathProblem();
    if (problem) throw new Error(`${problem} Nothing was saved.`);
    const uri = this.fileUri(this.requestsRel());
    if (!uri) throw new Error("Open a folder to save requests; they are stored in the workspace.");
    const unreadable = (reason: string | null) => new Error(`${reason} Fix the file before saving, so the requests in it are not lost.`);
    let text: string | null;
    try {
      text = await this.requestsText();
    } catch (e) {
      throw unreadable((e as Error).message);
    }
    const next = edit(text, this.requestsRel());
    if (next.text === null) throw unreadable(next.error);
    if (next.text === (text ?? "")) return;
    await vscode.workspace.fs.createDirectory(vscode.Uri.joinPath(uri, ".."));
    await vscode.workspace.fs.writeFile(uri, Buffer.from(next.text, "utf8"));
    this.onChange.fire();
  }

  async saved(): Promise<SavedRequest[]> {
    return (await this.readFile()).requests;
  }

  async save(request: SavedRequest): Promise<void> {
    await this.change((text, path) => saveRequest(text, request, path));
  }

  async delete(name: string): Promise<void> {
    await this.change((text, path) => removeRequest(text, name, path));
  }

  recent(): SavedRequest[] {
    return this.context.workspaceState.get<SavedRequest[]>("streamlord.inspector.recent", []);
  }

  async addRecent(request: SavedRequest): Promise<void> {
    await this.context.workspaceState.update("streamlord.inspector.recent", pushRecent(this.recent(), request));
    await this.context.workspaceState.update("streamlord.inspector.last", request);
  }

  lastUsed(): SavedRequest | undefined {
    return this.context.workspaceState.get<SavedRequest>("streamlord.inspector.last");
  }

  /**
   * The variables `.streamlord/env.json` sets, `baseUrl` from a server started from the editor or
   * from the settings when it does not, and what is wrong with the file. An invalid file sets
   * nothing: a request is not sent half filled.
   */
  async environment(): Promise<{ vars: Variable[]; errors: string[] }> {
    const text = await this.envText();
    const env = parseEnv(text ?? "{}");
    const defaultUrl = vscode.workspace.getConfiguration("streamlord").get<string>("inspector.defaultUrl", "http://localhost:8080/");
    return { vars: mergeVariables(defaultUrl, env.errors.length ? parseEnv("{}") : env, this.running.current()), errors: env.errors };
  }

  /** The env file as an open editor has it, so Connect uses what is on screen, saved or not. */
  private openEnv(uri: vscode.Uri): vscode.TextDocument | undefined {
    return vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri.toString());
  }

  private async envText(): Promise<string | null> {
    const uri = this.fileUri(ENV_FILE);
    if (!uri) return null;
    const doc = this.openEnv(uri);
    if (doc) return doc.getText();
    try {
      return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
    } catch {
      return null;
    }
  }

  /**
   * Add `keys` to `.streamlord/env.json`, creating it with `baseUrl` when it is missing, and
   * return it to be opened. `extended` is false when the file is not a JSON object to add to.
   */
  async defineVariables(keys: string[]): Promise<{ uri: vscode.Uri; extended: boolean }> {
    const uri = this.fileUri(ENV_FILE);
    if (!uri) throw new Error(`Open a folder to keep variables; they live in ${ENV_FILE} in the workspace.`);
    const text = await this.envText();
    const baseUrl = (await this.environment()).vars.find((v) => v.name === "baseUrl")?.value ?? "";
    return this.write(uri, text, withEnvVariables(text, keys, baseUrl));
  }

  /** Set `baseUrl` in `.streamlord/env.json` to `url`, leaving the rest as written, and return it to be opened. */
  async useBaseUrl(url: string): Promise<{ uri: vscode.Uri; extended: boolean }> {
    const uri = this.fileUri(ENV_FILE);
    if (!uri) throw new Error(`Open a folder to keep variables; they live in ${ENV_FILE} in the workspace.`);
    const text = await this.envText();
    return this.write(uri, text, withBaseUrl(text, url));
  }

  /** Write `next` over the env file's `text`, or leave it when nothing changes; `extended` is false when it could not be changed. */
  private async write(uri: vscode.Uri, text: string | null, next: string | null): Promise<{ uri: vscode.Uri; extended: boolean }> {
    if (next === null) return { uri, extended: false };
    if (next === text) return { uri, extended: true };
    // An open editor gets an edit it can undo, not a write underneath its unsaved text.
    const doc = this.openEnv(uri);
    if (doc) {
      const edit = new vscode.WorkspaceEdit();
      edit.replace(uri, new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length)), next);
      await vscode.workspace.applyEdit(edit);
    } else {
      await vscode.workspace.fs.createDirectory(vscode.Uri.joinPath(uri, ".."));
      await vscode.workspace.fs.writeFile(uri, Buffer.from(next, "utf8"));
    }
    return { uri, extended: true };
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
    this.onChange.dispose();
  }
}
