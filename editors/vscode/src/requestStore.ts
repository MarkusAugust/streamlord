import * as vscode from "vscode";
import { ENV_FILE, emptyRequestsFile, parseEnvFile, parseRequestsFile, pushRecent, remove, REQUESTS_FILE, serializeRequestsFile, upsert, type RequestsFile, type SavedRequest } from "./requests.ts";

/**
 * Where inspector requests live: saved ones in `.streamlord/inspector.json` in the workspace,
 * recent ones and the last-used request in the workspace state, variables in settings merged
 * with `.streamlord/env.json` (which is meant to be git-ignored, for tokens and local hosts).
 */
export class RequestStore implements vscode.Disposable {
  private readonly onChange = new vscode.EventEmitter<void>();
  readonly onDidChange = this.onChange.event;
  private readonly disposables: vscode.Disposable[] = [];

  constructor(private readonly context: vscode.ExtensionContext) {
    const watcher = vscode.workspace.createFileSystemWatcher("**/.streamlord/*.json");
    this.disposables.push(watcher, watcher.onDidChange(() => this.onChange.fire()), watcher.onDidCreate(() => this.onChange.fire()), watcher.onDidDelete(() => this.onChange.fire()));
    this.disposables.push(vscode.workspace.onDidChangeConfiguration((e) => e.affectsConfiguration("streamlord.inspector") && this.onChange.fire()));
  }

  private folder(): vscode.WorkspaceFolder | undefined {
    return vscode.workspace.workspaceFolders?.[0];
  }

  private fileUri(rel: string): vscode.Uri | undefined {
    const f = this.folder();
    return f ? vscode.Uri.joinPath(f.uri, rel) : undefined;
  }

  /** Relative path of the requests file, configurable. */
  private requestsRel(): string {
    return vscode.workspace.getConfiguration("streamlord").get<string>("inspector.requestsFile", REQUESTS_FILE);
  }

  async readFile(): Promise<RequestsFile> {
    const uri = this.fileUri(this.requestsRel());
    if (!uri) return emptyRequestsFile();
    try {
      return parseRequestsFile(Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8"));
    } catch {
      return emptyRequestsFile();
    }
  }

  private async writeFile(file: RequestsFile): Promise<void> {
    const uri = this.fileUri(this.requestsRel());
    if (!uri) throw new Error("Open a folder to save requests; they are stored in the workspace.");
    await vscode.workspace.fs.createDirectory(vscode.Uri.joinPath(uri, ".."));
    await vscode.workspace.fs.writeFile(uri, Buffer.from(serializeRequestsFile(file), "utf8"));
    this.onChange.fire();
  }

  async saved(): Promise<SavedRequest[]> {
    return (await this.readFile()).requests;
  }

  async save(request: SavedRequest): Promise<void> {
    await this.writeFile(upsert(await this.readFile(), request));
  }

  async delete(name: string): Promise<void> {
    await this.writeFile(remove(await this.readFile(), name));
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

  /** Settings first, `.streamlord/env.json` on top; `baseUrl` always has a value. */
  async variables(): Promise<Record<string, string>> {
    const fromSettings = vscode.workspace.getConfiguration("streamlord").get<Record<string, string>>("inspector.variables", {});
    let fromEnv: Record<string, string> = {};
    const uri = this.fileUri(ENV_FILE);
    if (uri) {
      try {
        fromEnv = parseEnvFile(Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8"));
      } catch {
        // no env file
      }
    }
    const defaultUrl = vscode.workspace.getConfiguration("streamlord").get<string>("inspector.defaultUrl", "http://localhost:8080/").replace(/\/$/, "");
    return { baseUrl: defaultUrl, ...fromSettings, ...fromEnv };
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
    this.onChange.dispose();
  }
}
