import * as vscode from "vscode";
import type { Issue } from "./expression.ts";

/** The issues of the last lint per document, so quick fixes can find their edits. */
export class IssueStore {
  private readonly byUri = new Map<string, Issue[]>();
  set(uri: vscode.Uri, issues: Issue[]): void {
    this.byUri.set(uri.toString(), issues);
  }
  get(uri: vscode.Uri): Issue[] {
    return this.byUri.get(uri.toString()) ?? [];
  }
  delete(uri: vscode.Uri): void {
    this.byUri.delete(uri.toString());
  }
}

/** Lightbulb fixes for the diagnostics that carry one. */
export class StreamlordCodeActionProvider implements vscode.CodeActionProvider {
  static readonly kinds = [vscode.CodeActionKind.QuickFix];

  constructor(private readonly issues: IssueStore) {}

  provideCodeActions(document: vscode.TextDocument, range: vscode.Range | vscode.Selection, context: vscode.CodeActionContext): vscode.CodeAction[] {
    const actions: vscode.CodeAction[] = [];
    const from = document.offsetAt(range.start);
    const to = document.offsetAt(range.end);
    for (const issue of this.issues.get(document.uri)) {
      if (!issue.fixes?.length || issue.end < from || issue.start > to) continue;
      const diagnostic = context.diagnostics.find((d) => d.source === "streamlord" && document.offsetAt(d.range.start) === issue.start);
      issue.fixes.forEach((fix, i) => {
        const action = new vscode.CodeAction(fix.title, vscode.CodeActionKind.QuickFix);
        action.edit = new vscode.WorkspaceEdit();
        action.edit.replace(document.uri, new vscode.Range(document.positionAt(fix.start), document.positionAt(fix.end)), fix.text);
        if (diagnostic) action.diagnostics = [diagnostic];
        action.isPreferred = i === 0;
        actions.push(action);
      });
    }
    return actions;
  }
}
