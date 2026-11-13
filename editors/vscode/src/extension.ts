import * as vscode from "vscode";
import { defaultPrefix, type Issue, type Route } from "./analysis.ts";
import { IssueStore, StreamlordCodeActionProvider } from "./codeActions.ts";
import { applyRecommendedColors, followThemeChange, removeRecommendedColors } from "./colors.ts";
import { StreamlordCompletionProvider } from "./completion.ts";
import { StreamlordHoverProvider } from "./hover.ts";
import { Inspector } from "./inspector.ts";
import { Lint } from "./lint.ts";
import { RequestStore } from "./requestStore.ts";
import { RouteLensProvider } from "./routeLens.ts";
import { RunningServers } from "./serverLog.ts";
import { StreamlordSignatureHelpProvider } from "./signatureHelp.ts";
import { SignalIndex } from "./signalIndex.ts";
import { SignalNavigationProvider } from "./signalNavigation.ts";

/** Languages that get HTML-side support, from the setting; Kotlin always gets the Kotlin side. */
function markupLanguages(): string[] {
  return vscode.workspace.getConfiguration("streamlord").get<string[]>("languages", ["html"]).filter((l) => l !== "kotlin");
}

export function activate(context: vscode.ExtensionContext): void {
  const config = () => vscode.workspace.getConfiguration("streamlord");
  const prefix = () => config().get<string>("attributePrefix", defaultPrefix());
  const markup = new Set(markupLanguages());
  const selector: vscode.DocumentSelector = [{ language: "kotlin" }, ...[...markup].map((language) => ({ language }))];
  const issues = new IssueStore();

  const diagnostics = vscode.languages.createDiagnosticCollection("streamlord");
  const log = vscode.window.createOutputChannel("Streamlord");
  const checks = new Lint((line) => log.appendLine(line));
  const signals = new SignalIndex(markup, prefix);
  const navigation = new SignalNavigationProvider(signals, prefix);
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
    const kotlin = document.languageId === "kotlin";
    if (!kotlin && !(markup.has(document.languageId) && config().get<boolean>("diagnostics.html", true))) {
      diagnostics.delete(document.uri);
      issues.delete(document.uri);
      return;
    }
    const signalsDefined = defined(document);
    const others = signalsDefined && kotlin ? signals.driftExcept(document.uri) : null;
    const found = checks.issues(document.uri.toString(), document.getText(), kotlin, prefix(), kotlin ? signals.allConstants() : [], signalsDefined, others);
    issues.set(document.uri, found);
    diagnostics.set(
      document.uri,
      found.map((i) => {
        const d = new vscode.Diagnostic(new vscode.Range(document.positionAt(i.start), document.positionAt(i.end)), i.message, severity(i.severity));
        d.source = "streamlord";
        d.code = i.code && i.link ? { value: i.code, target: vscode.Uri.parse(i.link) } : (i.code ?? undefined);
        return d;
      }),
    );
  };

  /**
   * The signals the workspace defines, which a `$name` is checked against, or null while the check
   * is off: until the workspace has been read once, and when it is turned off. The check of what
   * the server reads against what the pages declare goes with it.
   */
  const defined = (document: vscode.TextDocument): string[] | null => {
    if (!signals.ready || !config().get<boolean>("diagnostics.unknownSignals", true)) return null;
    // The index reads this version of the document once; a lint right after an edit finds it read.
    signals.update(document);
    signals.readDrift(document);
    return signals.allDefinitions();
  };

  const schedule = (document: vscode.TextDocument) => {
    if (document.languageId !== "kotlin" && !markup.has(document.languageId)) return;
    const key = document.uri.toString();
    clearTimeout(timers.get(key));
    timers.set(key, setTimeout(() => lint(document), 250));
  };

  context.subscriptions.push(
    diagnostics,
    log,
    signals,
    store,
    vscode.languages.registerCodeLensProvider({ language: "kotlin" }, new RouteLensProvider()),
    vscode.commands.registerCommand("streamlord.inspector.openWith", (route: Route) => inspector.openWithRoute(route)),
    vscode.workspace.onDidOpenTextDocument(schedule),
    // A signal defined or removed in one file changes what the others may read; the first read of the workspace is such a change.
    signals.onDidChangeDefinitions(() => vscode.workspace.textDocuments.forEach(schedule)),
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
    vscode.languages.registerSignatureHelpProvider(selector, new StreamlordSignatureHelpProvider(prefix), "(", ","),
    vscode.languages.registerDefinitionProvider(selector, navigation),
    vscode.languages.registerReferenceProvider(selector, navigation),
    vscode.languages.registerRenameProvider(selector, navigation),
    vscode.languages.registerCodeActionsProvider(selector, new StreamlordCodeActionProvider(issues, () => ({ prefix: prefix(), constants: signals.allConstants() })), { providedCodeActionKinds: StreamlordCodeActionProvider.kinds }),
    vscode.commands.registerCommand("streamlord.inspector.open", () => inspector.open()),
    vscode.commands.registerCommand("streamlord.colors.apply", () => applyRecommendedColors()),
    vscode.commands.registerCommand("streamlord.colors.remove", () => removeRecommendedColors()),
    vscode.window.onDidChangeActiveColorTheme(() => void followThemeChange()),
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
