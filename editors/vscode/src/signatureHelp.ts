import * as vscode from "vscode";
import { signatureHelp } from "./analysis.ts";

/** The parameters of a Datastar action while the caret is in its call, in markup and in Kotlin strings alike. */
export class StreamlordSignatureHelpProvider implements vscode.SignatureHelpProvider {
  constructor(private readonly prefix: () => string) {}

  provideSignatureHelp(document: vscode.TextDocument, position: vscode.Position): vscode.SignatureHelp | null {
    const found = signatureHelp(document.getText(), document.offsetAt(position), document.languageId === "kotlin", this.prefix());
    if (!found) return null;
    const signature = new vscode.SignatureInformation(found.label, new vscode.MarkdownString(found.doc));
    signature.parameters = found.parameters.map((p) => new vscode.ParameterInformation(p as [number, number]));
    const help = new vscode.SignatureHelp();
    help.signatures = [signature];
    help.activeSignature = 0;
    help.activeParameter = found.active;
    return help;
  }
}
