import * as vscode from "vscode";
import { analyzeHtml, analyzeKotlin } from "./analyze.ts";
import { catalog } from "./catalog.ts";
import { IssueStore, StreamlordCodeActionProvider } from "./codeActions.ts";
import { applyRecommendedColors, removeRecommendedColors } from "./colors.ts";
import { StreamlordCompletionProvider } from "./completion.ts";
import type { Issue } from "./expression.ts";
import { StreamlordHoverProvider } from "./hover.ts";
import { Inspector } from "./inspector.ts";
import { RequestStore } from "./requestStore.ts";
import { RouteLensProvider } from "./routeLens.ts";
import type { Route } from "./routes.ts";
import { RunningServers } from "./serverLog.ts";
import { SignalIndex } from "./signalIndex.ts";

/** Languages that get HTML-side support, from the setting; Kotlin always gets the Kotlin side. */
function markupLanguages(): string[] {
  return vscode.workspace.getConfiguration("streamlord").get<string[]>("languages", ["html"]).filter((l) => l !== "kotlin");
}

export function activate(context: vscode.ExtensionContext): void {
  const config = () => vscode.workspace.getConfiguration("streamlord");
  const prefix = () => config().get<string>("attributePrefix", catalog.prefix);
  const markup = new Set(markupLanguages());
  const selector: vscode.DocumentSelector = [{ language: "kotlin" }, ...[...markup].map((language) => ({ language }))];
  const issues = new IssueStore();

  const diagnostics = vscode.languages.createDiagnosticCollection("streamlord");
  const signals = new SignalIndex();
  const running = new RunningServers();
  // A debug session's output is the one an extension may read, so that is where a server is seen to start.
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterTrackerFactory("*", {
      createDebugAdapterTracker: (session) => ({
        onDidSendMessage: (m: { type?: string; event?: string; body?: { category?: string; output?: string } }) => {
          if (m.type === "event" && m.event === "output" && m.body?.output && m.body.category !== "telemetry") running.feed(session.id, session.name, m.body.output);
        },
      }),
    }),
    vscode.debug.onDidTerminateDebugSession((session) => running.end(session.id)),
  );
  const store = new RequestStore(context, running);
  const inspector = new Inspector(context, store, running);
  const timers = new Map<string, NodeJS.Timeout>();

  const lint = (document: vscode.TextDocument) => {
    if (!config().get<boolean>("diagnostics.enabled", true)) {
      diagnostics.delete(document.uri);
      return;
    }
    let found: Issue[];
    if (document.languageId === "kotlin") {
      found = analyzeKotlin(document.getText(), { prefix: prefix(), checkHtmlAttributes: true });
    } else if (markup.has(document.languageId) && config().get<boolean>("diagnostics.html", true)) {
      found = analyzeHtml(document.getText(), { prefix: prefix(), checkHtmlAttributes: true });
    } else {
      diagnostics.delete(document.uri);
      issues.delete(document.uri);
      return;
    }
    issues.set(document.uri, found);
    diagnostics.set(
      document.uri,
      found.map((i) => {
        const d = new vscode.Diagnostic(new vscode.Range(document.positionAt(i.start), document.positionAt(i.end)), i.message, severity(i.severity));
        d.source = "streamlord";
        d.code = i.code && i.link ? { value: i.code, target: vscode.Uri.parse(i.link) } : i.code;
        return d;
      }),
    );
  };

  const schedule = (document: vscode.TextDocument) => {
    if (document.languageId !== "kotlin" && !markup.has(document.languageId)) return;
    const key = document.uri.toString();
    clearTimeout(timers.get(key));
    timers.set(key, setTimeout(() => lint(document), 250));
  };

  context.subscriptions.push(
    diagnostics,
    signals,
    store,
    vscode.languages.registerCodeLensProvider({ language: "kotlin" }, new RouteLensProvider()),
    vscode.commands.registerCommand("streamlord.inspector.openWith", (route: Route) => inspector.openWithRoute(route)),
    vscode.workspace.onDidOpenTextDocument(schedule),
    vscode.workspace.onDidChangeTextDocument((e) => schedule(e.document)),
    vscode.workspace.onDidCloseTextDocument((d) => {
      diagnostics.delete(d.uri);
      issues.delete(d.uri);
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("streamlord.languages")) void vscode.window.showInformationMessage("Streamlord: reload the window for the new language list to take effect.");
      else if (e.affectsConfiguration("streamlord")) vscode.workspace.textDocuments.forEach(lint);
    }),
    vscode.languages.registerCompletionItemProvider(selector, new StreamlordCompletionProvider(signals, prefix), "$", "@", "#", "_", ".", "-", ":"),
    vscode.languages.registerHoverProvider(selector, new StreamlordHoverProvider(prefix)),
    vscode.languages.registerCodeActionsProvider(selector, new StreamlordCodeActionProvider(issues), { providedCodeActionKinds: StreamlordCodeActionProvider.kinds }),
    vscode.commands.registerCommand("streamlord.inspector.open", () => inspector.open()),
    vscode.commands.registerCommand("streamlord.colors.apply", () => applyRecommendedColors()),
    vscode.commands.registerCommand("streamlord.colors.remove", () => removeRecommendedColors()),
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
