import * as vscode from "vscode";
import { rewriteAt, type Fix, type Issue } from "./analysis.ts";

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

/** The lightbulb: the fixes of the diagnostics that carry one, and in an HTML string of a Kotlin file the rewrite to interpolate as a refactoring. */
export class StreamlordCodeActionProvider implements vscode.CodeActionProvider {
  static readonly kinds = [vscode.CodeActionKind.QuickFix, vscode.CodeActionKind.RefactorRewrite];

  /** `read` gives what the diagnostics were made with: the attribute prefix and the workspace's constants. */
  constructor(
    private readonly issues: IssueStore,
    private readonly read: () => { prefix: string; constants: string[] },
  ) {}

  provideCodeActions(document: vscode.TextDocument, range: vscode.Range | vscode.Selection, context: vscode.CodeActionContext): vscode.CodeAction[] {
    const actions: vscode.CodeAction[] = [];
    const from = document.offsetAt(range.start);
    const to = document.offsetAt(range.end);
    const action = (fix: Fix, kind: vscode.CodeActionKind) => {
      const a = new vscode.CodeAction(fix.title, kind);
      a.edit = new vscode.WorkspaceEdit();
      a.edit.replace(document.uri, new vscode.Range(document.positionAt(fix.start), document.positionAt(fix.end)), fix.text);
      for (const e of fix.edits ?? []) a.edit.replace(document.uri, new vscode.Range(document.positionAt(e.start), document.positionAt(e.end)), e.text);
      actions.push(a);
      return a;
    };
    for (const issue of this.issues.get(document.uri)) {
      if (!issue.fixes?.length || issue.end < from || issue.start > to) continue;
      const diagnostic = context.diagnostics.find((d) => d.source === "streamlord" && document.offsetAt(d.range.start) === issue.start);
      issue.fixes.forEach((fix, i) => {
        const a = action(fix, vscode.CodeActionKind.QuickFix);
        if (diagnostic) a.diagnostics = [diagnostic];
        a.isPreferred = i === 0 && fix.preferred !== false;
      });
    }
    // At a caret: over a selection the editor asks for what applies to all of it.
    if (document.languageId === "kotlin" && from === to) {
      const { prefix, constants } = this.read();
      const rewrite = rewriteAt(document.getText(), from, prefix, constants);
      if (rewrite) action(rewrite, vscode.CodeActionKind.RefactorRewrite);
    }
    return actions;
  }
}
