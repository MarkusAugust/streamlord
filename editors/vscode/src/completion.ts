import * as vscode from "vscode";
import { complete, defaultPrefix, type CompletionItem, type SnippetText } from "./analysis.ts";
import type { SignalIndex } from "./signalIndex.ts";

const KINDS: Record<string, vscode.CompletionItemKind> = {
  signal: vscode.CompletionItemKind.Variable,
  scope: vscode.CompletionItemKind.Variable,
  action: vscode.CompletionItemKind.Function,
  option: vscode.CompletionItemKind.Property,
  attribute: vscode.CompletionItemKind.Property,
  event: vscode.CompletionItemKind.Event,
  modifier: vscode.CompletionItemKind.Keyword,
  value: vscode.CompletionItemKind.EnumMember,
  id: vscode.CompletionItemKind.Reference,
  class: vscode.CompletionItemKind.Color,
};

/** Completions for Datastar in markup, in Kotlin strings, in the DSL and its attributes form: the analysis's items, as VS Code shows them. */
export class StreamlordCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly signals: SignalIndex, private readonly prefix: () => string) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const prefix = this.prefix();
    const selectors = this.signals.allSelectors();
    // In plain HTML files VS Code's own HTML service offers the attribute names, from html-customdata.json, for the catalog's prefix.
    const names = !(document.languageId === "html" && prefix === defaultPrefix());
    const kotlin = document.languageId === "kotlin";
    return complete(document.getText(), document.offsetAt(position), kotlin, prefix, this.signals.allDefinitions(), [...selectors.ids], [...selectors.classes], names).map((i, rank) => item(i, rank, document, position));
  }
}

function item(i: CompletionItem, rank: number, document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem {
  // A tail, an option's default, shows right after the name.
  const out = new vscode.CompletionItem(i.tail != null ? { label: i.label, detail: i.tail } : i.label, KINDS[i.kind]);
  // Inserting ends at the caret, replacing at the end of the word there; the insert mode setting picks between them.
  const from = document.positionAt(i.replaceFrom);
  out.range = { inserting: new vscode.Range(from, position), replacing: new vscode.Range(from, document.positionAt(i.replaceTo)) };
  if (i.snippet != null) out.insertText = snippet(i.snippet);
  else if (i.insert != null) out.insertText = i.insert;
  out.detail = i.detail ?? undefined;
  if (i.doc != null) out.documentation = new vscode.MarkdownString(i.doc);
  out.filterText = i.filter ?? undefined;
  // VS Code orders by sortText; the analysis has put the items in order.
  out.sortText = String(rank).padStart(4, "0");
  return out;
}

/** The snippet in VS Code's syntax: each place a tab stop, numbered in the order the caret visits them. */
function snippet(s: SnippetText): vscode.SnippetString {
  const escape = (t: string) => t.replace(/[$}\\]/g, "\\$&");
  let out = "";
  let at = 0;
  s.places.forEach((p, n) => {
    out += escape(s.text.slice(at, p.start));
    const stop = n + 1;
    if (p.choices.length > 0) out += `\${${stop}|${p.choices.map((c) => c.replace(/[,|\\]/g, "\\$&")).join(",")}|}`;
    else out += p.end > p.start ? `\${${stop}:${escape(s.text.slice(p.start, p.end))}}` : `\${${stop}}`;
    at = p.end;
  });
  return new vscode.SnippetString(out + escape(s.text.slice(at)));
}
