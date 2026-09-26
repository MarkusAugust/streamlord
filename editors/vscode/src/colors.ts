import * as vscode from "vscode";
import { DARK_RULES, LIGHT_RULES, type Rule } from "./palette.ts";

/** Theme selector for the light overrides; the dark rules are the default. */
export const LIGHT_THEMES_KEY = "[*Light*]";

type TokenColors = { textMateRules?: Rule[] } & Record<string, unknown>;

const owned = (r: Rule) => r.name?.startsWith("Streamlord: ") ?? false;

function withoutOurs(current: TokenColors): TokenColors {
  const next: TokenColors = { ...current };
  const kept = (current.textMateRules ?? []).filter((r) => !owned(r));
  if (kept.length > 0) next.textMateRules = kept;
  else delete next.textMateRules;
  const light = current[LIGHT_THEMES_KEY] as TokenColors | undefined;
  if (light) {
    const lightKept = (light.textMateRules ?? []).filter((r) => !owned(r));
    const lightNext: TokenColors = { ...light };
    if (lightKept.length > 0) lightNext.textMateRules = lightKept;
    else delete lightNext.textMateRules;
    if (Object.keys(lightNext).length > 0) next[LIGHT_THEMES_KEY] = lightNext;
    else delete next[LIGHT_THEMES_KEY];
  }
  return next;
}

/** The customization object with both palettes merged in, preserving the user's own rules. */
export function withRecommendedColors(current: TokenColors): TokenColors {
  const base = withoutOurs(current);
  const light = (base[LIGHT_THEMES_KEY] as TokenColors | undefined) ?? {};
  return {
    ...base,
    textMateRules: [...(base.textMateRules ?? []), ...DARK_RULES],
    [LIGHT_THEMES_KEY]: { ...light, textMateRules: [...(light.textMateRules ?? []), ...LIGHT_RULES] },
  };
}

export async function applyRecommendedColors(): Promise<void> {
  const config = vscode.workspace.getConfiguration("editor");
  const current = (config.get<TokenColors>("tokenColorCustomizations") ?? {}) as TokenColors;
  await config.update("tokenColorCustomizations", withRecommendedColors(current), vscode.ConfigurationTarget.Global);
  void vscode.window.showInformationMessage("Streamlord: recommended colors written to your user settings (dark and light). Run “Streamlord: Remove recommended colors” to undo.");
}

export async function removeRecommendedColors(): Promise<void> {
  const config = vscode.workspace.getConfiguration("editor");
  const current = (config.get<TokenColors>("tokenColorCustomizations") ?? {}) as TokenColors;
  const next = withoutOurs(current);
  await config.update("tokenColorCustomizations", Object.keys(next).length > 0 ? next : undefined, vscode.ConfigurationTarget.Global);
  void vscode.window.showInformationMessage("Streamlord: recommended colors removed.");
}
