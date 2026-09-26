import * as vscode from "vscode";
import { collectSignals } from "./signals.ts";

/** Workspace-wide index of signal names, kept fresh on save. */
export class SignalIndex implements vscode.Disposable {
  private readonly byFile = new Map<string, Set<string>>();
  private readonly disposables: vscode.Disposable[] = [];

  constructor() {
    this.disposables.push(
      vscode.workspace.onDidSaveTextDocument((d) => this.update(d)),
      vscode.workspace.onDidChangeTextDocument((e) => this.update(e.document)),
      vscode.workspace.onDidDeleteFiles((e) => e.files.forEach((f) => this.byFile.delete(f.toString()))),
    );
  }

  async rebuild(): Promise<number> {
    this.byFile.clear();
    const files = await vscode.workspace.findFiles("**/*.{kt,html}", "**/{build,node_modules,.gradle,dist,out,target}/**", 5000);
    await Promise.all(
      files.map(async (uri) => {
        try {
          const bytes = await vscode.workspace.fs.readFile(uri);
          this.index(uri.toString(), Buffer.from(bytes).toString("utf8"), uri.path.endsWith(".kt") ? "kotlin" : "html");
        } catch {
          // unreadable file: skip
        }
      }),
    );
    return this.all().size;
  }

  update(document: vscode.TextDocument): void {
    if (document.languageId !== "kotlin" && document.languageId !== "html") return;
    this.index(document.uri.toString(), document.getText(), document.languageId === "kotlin" ? "kotlin" : "html");
  }

  private index(key: string, text: string, language: "kotlin" | "html"): void {
    this.byFile.set(key, collectSignals(text, language));
  }

  all(): Set<string> {
    const out = new Set<string>();
    for (const s of this.byFile.values()) for (const n of s) out.add(n);
    return out;
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
  }
}
