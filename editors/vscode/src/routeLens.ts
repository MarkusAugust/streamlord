import * as vscode from "vscode";
import { findRoutes } from "./routes.ts";

/** "Open in Stream Inspector" above every Ktor and Spring route in a Kotlin file. */
export class RouteLensProvider implements vscode.CodeLensProvider {
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    if (!vscode.workspace.getConfiguration("streamlord").get<boolean>("inspector.codeLens", true)) return [];
    return findRoutes(document.getText()).map((route) => {
      const position = document.positionAt(route.offset);
      return new vscode.CodeLens(new vscode.Range(position, position), {
        title: `$(broadcast) Open in Stream Inspector · ${route.method} ${route.path}`,
        tooltip: "Prefill the Stream Inspector with this route",
        command: "streamlord.inspector.openWith",
        arguments: [{ method: route.method, path: route.path }],
      });
    });
  }
}
