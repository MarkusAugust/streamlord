import * as vscode from "vscode";
import { analyzeHtml, analyzeKotlin } from "./analyze.ts";
import { catalog } from "./catalog.ts";
import { StreamlordCompletionProvider } from "./completion.ts";
import type { Issue } from "./expression.ts";
import { StreamlordHoverProvider } from "./hover.ts";
import { Inspector } from "./inspector.ts";
import { SignalIndex } from "./signalIndex.ts";

const SELECTOR: vscode.DocumentSelector = [{ language: "kotlin" }, { language: "html" }];

export function activate(context: vscode.ExtensionContext): void {
  const config = () => vscode.workspace.getConfiguration("streamlord");
  const prefix = () => config().get<string>("attributePrefix", catalog.prefix);

  const diagnostics = vscode.languages.createDiagnosticCollection("streamlord");
  const signals = new SignalIndex();
  const inspector = new Inspector(context);
  const timers = new Map<string, NodeJS.Timeout>();

  const lint = (document: vscode.TextDocument) => {
    if (!config().get<boolean>("diagnostics.enabled", true)) {
      diagnostics.delete(document.uri);
      return;
    }
    let issues: Issue[];
    if (document.languageId === "kotlin") {
      issues = analyzeKotlin(document.getText(), { prefix: prefix(), checkHtmlAttributes: true });
    } else if (document.languageId === "html" && config().get<boolean>("diagnostics.html", true)) {
      issues = analyzeHtml(document.getText(), { prefix: prefix(), checkHtmlAttributes: true });
    } else {
      diagnostics.delete(document.uri);
      return;
    }
    diagnostics.set(
      document.uri,
      issues.map((i) => {
        const d = new vscode.Diagnostic(new vscode.Range(document.positionAt(i.start), document.positionAt(i.end)), i.message, severity(i.severity));
        d.source = "streamlord";
        d.code = i.code;
        if (i.severity === "hint") d.tags = [];
        return d;
      }),
    );
  };

  const schedule = (document: vscode.TextDocument) => {
    if (document.languageId !== "kotlin" && document.languageId !== "html") return;
    const key = document.uri.toString();
    clearTimeout(timers.get(key));
    timers.set(key, setTimeout(() => lint(document), 250));
  };

  context.subscriptions.push(
    diagnostics,
    signals,
    vscode.workspace.onDidOpenTextDocument(schedule),
    vscode.workspace.onDidChangeTextDocument((e) => schedule(e.document)),
    vscode.workspace.onDidCloseTextDocument((d) => diagnostics.delete(d.uri)),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("streamlord")) vscode.workspace.textDocuments.forEach(lint);
    }),
    vscode.languages.registerCompletionItemProvider(SELECTOR, new StreamlordCompletionProvider(signals, prefix), "$", "@", "#", "_", ".", "-", ":"),
    vscode.languages.registerHoverProvider(SELECTOR, new StreamlordHoverProvider(prefix)),
    vscode.commands.registerCommand("streamlord.inspector.open", () => inspector.open()),
    vscode.commands.registerCommand("streamlord.reindexSignals", async () => {
      const n = await signals.rebuild();
      void vscode.window.showInformationMessage(`Streamlord: indexed ${n} signal names.`);
    }),
  );

  vscode.workspace.textDocuments.forEach(schedule);
  void signals.rebuild();
}

export function deactivate(): void {
  // nothing to release beyond context.subscriptions
}

function severity(s: Issue["severity"]): vscode.DiagnosticSeverity {
  switch (s) {
    case "error":
      return vscode.DiagnosticSeverity.Error;
    case "warning":
      return vscode.DiagnosticSeverity.Warning;
    case "info":
      return vscode.DiagnosticSeverity.Information;
    default:
      return vscode.DiagnosticSeverity.Hint;
  }
}
