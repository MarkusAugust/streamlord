import * as vscode from "vscode";
import { actionCallAt, catalog, expressionAtCaret } from "./analysis.ts";

/** The parameters of a Datastar action while the caret is in its call, in markup and in Kotlin strings alike. */
export class StreamlordSignatureHelpProvider implements vscode.SignatureHelpProvider {
  constructor(private readonly prefix: () => string) {}

  provideSignatureHelp(document: vscode.TextDocument, position: vscode.Position): vscode.SignatureHelp | null {
    const src = document.getText();
    const offset = document.offsetAt(position);
    const expression = expressionAtCaret(src, offset, document.languageId === "kotlin", this.prefix());
    if (!expression?.allowActions) return null;
    const call = actionCallAt(expression.before, expression.before.length);
    const action = call ? catalog().actions.find((a) => a.name === call.action) : undefined;
    if (!call || !action) return null;
    const signature = new vscode.SignatureInformation(action.signature, new vscode.MarkdownString(action.doc));
    signature.parameters = action.parameters.map((p) => new vscode.ParameterInformation(p));
    const help = new vscode.SignatureHelp();
    help.signatures = [signature];
    help.activeSignature = 0;
    help.activeParameter = Math.min(call.argument, Math.max(0, action.parameters.length - 1));
    return help;
  }
}
