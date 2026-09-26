import * as vscode from "vscode";

/**
 * The recommended palette for Datastar tokens. Themes color only a handful of TextMate scopes,
 * so the grammar's fine-grained scopes need explicit rules to show. These are written into
 * `editor.tokenColorCustomizations` on request, and can be removed again with one command.
 */
export const RECOMMENDED_RULES: { name: string; scope: string[]; settings: { foreground?: string; fontStyle?: string } }[] = [
  { name: "Streamlord: signal", scope: ["variable.other.constant.signal.datastar"], settings: { foreground: "#4FC1FF", fontStyle: "bold" } },
  { name: "Streamlord: signal sigil", scope: ["punctuation.definition.signal.datastar"], settings: { foreground: "#4FC1FF" } },
  { name: "Streamlord: signal path", scope: ["variable.other.property.signal-path.datastar"], settings: { foreground: "#9CDCFE" } },
  { name: "Streamlord: backend action", scope: ["entity.name.function.action.backend.datastar"], settings: { foreground: "#DCDCAA", fontStyle: "bold" } },
  { name: "Streamlord: action", scope: ["entity.name.function.action.datastar"], settings: { foreground: "#DCDCAA" } },
  { name: "Streamlord: action sigil", scope: ["punctuation.definition.action.datastar"], settings: { foreground: "#C586C0" } },
  { name: "Streamlord: el / evt / patch", scope: ["variable.language.scope.datastar"], settings: { foreground: "#569CD6", fontStyle: "italic" } },
  { name: "Streamlord: globals", scope: ["support.class.global.datastar"], settings: { foreground: "#4EC9B0" } },
  { name: "Streamlord: option keys", scope: ["support.type.property-name.option.datastar"], settings: { foreground: "#C586C0" } },
  { name: "Streamlord: object keys", scope: ["variable.other.property.key.datastar"], settings: { foreground: "#9CDCFE" } },
  { name: "Streamlord: property", scope: ["variable.other.property.datastar"], settings: { foreground: "#9CDCFE" } },
  { name: "Streamlord: method", scope: ["entity.name.function.method.datastar"], settings: { foreground: "#DCDCAA" } },
  { name: "Streamlord: constants", scope: ["constant.language.datastar"], settings: { foreground: "#569CD6" } },
  { name: "Streamlord: control", scope: ["keyword.control.datastar", "storage.type.function.arrow.datastar"], settings: { foreground: "#C586C0" } },
  { name: "Streamlord: numbers and durations", scope: ["constant.numeric.datastar", "constant.numeric.duration.datastar"], settings: { foreground: "#B5CEA8" } },
  { name: "Streamlord: strings", scope: ["string.quoted.single.datastar", "string.quoted.double.datastar", "string.template.datastar"], settings: { foreground: "#CE9178" } },
  { name: "Streamlord: url", scope: ["string.other.link.url.datastar"], settings: { foreground: "#E8AB53", fontStyle: "underline" } },
  { name: "Streamlord: regex", scope: ["string.regexp.datastar"], settings: { foreground: "#D16969" } },
  { name: "Streamlord: assignment", scope: ["keyword.operator.assignment.datastar", "keyword.operator.increment.datastar"], settings: { foreground: "#D4D4D4" } },
  { name: "Streamlord: comparison", scope: ["keyword.operator.comparison.datastar"], settings: { foreground: "#569CD6" } },
  { name: "Streamlord: kotlin dollar escape", scope: ["constant.character.escape.dollar.kotlin"], settings: { foreground: "#D7BA7D" } },
  { name: "Streamlord: attribute prefix", scope: ["entity.other.attribute-name.datastar.prefix"], settings: { foreground: "#808080" } },
  { name: "Streamlord: attribute plugin", scope: ["entity.other.attribute-name.datastar.plugin"], settings: { foreground: "#C586C0", fontStyle: "bold" } },
  { name: "Streamlord: attribute key", scope: ["entity.other.attribute-name.datastar.key"], settings: { foreground: "#DCDCAA" } },
  { name: "Streamlord: modifier", scope: ["keyword.other.modifier.datastar"], settings: { foreground: "#569CD6" } },
  { name: "Streamlord: modifier sigil", scope: ["punctuation.definition.modifier.datastar", "punctuation.separator.key.datastar"], settings: { foreground: "#808080" } },
  { name: "Streamlord: modifier argument", scope: ["constant.other.modifier-arg.datastar"], settings: { foreground: "#4EC9B0" } },
];

type TokenColors = { textMateRules?: { name?: string; scope: string | string[]; settings: Record<string, string> }[] } & Record<string, unknown>;

const OWNED = (name?: string) => name?.startsWith("Streamlord: ") ?? false;

export async function applyRecommendedColors(): Promise<void> {
  const config = vscode.workspace.getConfiguration("editor");
  const current = (config.get<TokenColors>("tokenColorCustomizations") ?? {}) as TokenColors;
  const kept = (current.textMateRules ?? []).filter((r) => !OWNED(r.name));
  const next: TokenColors = { ...current, textMateRules: [...kept, ...RECOMMENDED_RULES] };
  await config.update("tokenColorCustomizations", next, vscode.ConfigurationTarget.Global);
  void vscode.window.showInformationMessage("Streamlord: recommended colors written to your user settings. Run “Streamlord: Remove recommended colors” to undo.");
}

export async function removeRecommendedColors(): Promise<void> {
  const config = vscode.workspace.getConfiguration("editor");
  const current = (config.get<TokenColors>("tokenColorCustomizations") ?? {}) as TokenColors;
  const kept = (current.textMateRules ?? []).filter((r) => !OWNED(r.name));
  const next: TokenColors = { ...current };
  if (kept.length > 0) next.textMateRules = kept;
  else delete next.textMateRules;
  await config.update("tokenColorCustomizations", Object.keys(next).length > 0 ? next : undefined, vscode.ConfigurationTarget.Global);
  void vscode.window.showInformationMessage("Streamlord: recommended colors removed.");
}
