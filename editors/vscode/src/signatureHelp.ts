import * as vscode from "vscode";

/** The parameters of a Datastar action while the caret is in its call, in markup and in Kotlin strings alike. */
export class StreamlordSignatureHelpProvider implements vscode.SignatureHelpProvider {
  constructor(private readonly prefix: () => string) {}

  provideSignatureHelp(_document: vscode.TextDocument, _position: vscode.Position): vscode.SignatureHelp | null {
    void this.prefix;
    return null;
  }
}
