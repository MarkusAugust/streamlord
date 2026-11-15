import * as vscode from "vscode";
import { DARK_RULES, LIGHT_RULES, type Rule } from "./palette.ts";

/** Where earlier versions wrote the light palette, for themes with "Light" in the name; read only to remove it. */
const OLD_LIGHT_KEY = "[*Light*]";

type TokenColors = { textMateRules?: Rule[] } & Record<string, unknown>;

/** The theme the palette is written for: its name, which VS Code keys a theme's rules by, and its kind. */
export interface Theme {
  name: string;
  dark: boolean;
}

const owned = (r: Rule) => r.name?.startsWith("Streamlord: ") ?? false;

/** [colors] without the palette's rules, or undefined when nothing is left. */
function withoutOwned(colors: TokenColors | undefined): TokenColors | undefined {
  if (!colors) return undefined;
  const { textMateRules, ...other } = colors;
  const rules = (textMateRules ?? []).filter((r) => !owned(r));
  const next: TokenColors = { ...other, ...(rules.length > 0 ? { textMateRules: rules } : {}) };
  return Object.keys(next).length > 0 ? next : undefined;
}

/** Put [value] at [key], or take the key away when it is undefined. */
function set(colors: TokenColors, key: string, value: unknown): TokenColors {
  const { [key]: _, ...rest } = colors;
  return value === undefined ? rest : { ...rest, [key]: value };
}

/** The customization without any palette Streamlord wrote: every theme's, and the global and name-matched ones of earlier versions. */
export function withoutRecommendedColors(current: TokenColors): TokenColors {
  let next = withoutOwned(current) ?? {};
  for (const key of Object.keys(next)) {
    if (key.startsWith("[")) next = set(next, key, withoutOwned(next[key] as TokenColors));
  }
  return next;
}

/**
 * The customization with the palette written for [theme] alone, by its kind: the light palette
 * for a light theme and the dark one for a dark theme, whatever either is called. A palette
 * written for another theme stays; the user's own rules are kept.
 */
export function withRecommendedColors(current: TokenColors, theme: Theme): TokenColors {
  const key = `[${theme.name}]`;
  let next = withoutOwned(current) ?? {};
  next = set(next, OLD_LIGHT_KEY, withoutOwned(next[OLD_LIGHT_KEY] as TokenColors | undefined));
  const own = withoutOwned(next[key] as TokenColors | undefined) ?? {};
  return set(next, key, { ...own, textMateRules: [...(own.textMateRules ?? []), ...(theme.dark ? DARK_RULES : LIGHT_RULES)] });
}

const hasOwned = (colors: unknown) => ((colors as TokenColors | undefined)?.textMateRules ?? []).some(owned);

/** The settings that name the theme in use. */
export interface ThemeSettings {
  colorTheme?: string;
  preferredDarkColorTheme?: string;
  preferredLightColorTheme?: string;
  preferredHighContrastColorTheme?: string;
  preferredHighContrastLightColorTheme?: string;
  autoDetectColorScheme?: boolean;
  autoDetectHighContrast?: boolean;
}

export type ThemeKind = "dark" | "light" | "highContrast" | "highContrastLight";

/**
 * The theme in use, from its kind and the settings: a high contrast theme is the one auto-detection
 * picked when it is on, as it is by default; a dark or light one the one it picked for the system's
 * scheme when that is on; else the chosen one.
 */
export function themeInUse(kind: ThemeKind, settings: ThemeSettings): Theme {
  const dark = kind === "dark" || kind === "highContrast";
  const picked =
    kind === "highContrast" || kind === "highContrastLight"
      ? (settings.autoDetectHighContrast ?? true)
        ? kind === "highContrast"
          ? settings.preferredHighContrastColorTheme
          : settings.preferredHighContrastLightColorTheme
        : undefined
      : settings.autoDetectColorScheme
        ? dark
          ? settings.preferredDarkColorTheme
          : settings.preferredLightColorTheme
        : undefined;
  return { name: picked ?? settings.colorTheme ?? (dark ? "Default Dark Modern" : "Default Light Modern"), dark };
}

/** The palettes already written, each rewritten as this version has it: a scope renamed since is followed. */
export function refreshedRecommendedColors(current: TokenColors): TokenColors {
  let next = current;
  for (const [key, value] of Object.entries(current)) {
    const rules = (value as TokenColors | undefined)?.textMateRules;
    if (!key.startsWith("[") || !rules?.some(owned)) continue;
    // A palette is the light one when its signal has the light colour.
    const signal = (palette: Rule[]) => palette.find((r) => r.name === "Streamlord: signal")?.settings.foreground;
    const light = rules.some((r) => r.name === "Streamlord: signal" && r.settings.foreground === signal(LIGHT_RULES));
    next = withRecommendedColors(next, { name: key.slice(1, -1), dark: !light });
  }
  return next;
}

function activeTheme(): Theme {
  const kinds: Record<number, ThemeKind> = {
    [vscode.ColorThemeKind.Dark]: "dark",
    [vscode.ColorThemeKind.Light]: "light",
    [vscode.ColorThemeKind.HighContrast]: "highContrast",
    [vscode.ColorThemeKind.HighContrastLight]: "highContrastLight",
  };
  const workbench = vscode.workspace.getConfiguration("workbench");
  const window = vscode.workspace.getConfiguration("window");
  return themeInUse(kinds[vscode.window.activeColorTheme.kind] ?? "dark", {
    colorTheme: workbench.get<string>("colorTheme"),
    preferredDarkColorTheme: workbench.get<string>("preferredDarkColorTheme"),
    preferredLightColorTheme: workbench.get<string>("preferredLightColorTheme"),
    preferredHighContrastColorTheme: workbench.get<string>("preferredHighContrastColorTheme"),
    preferredHighContrastLightColorTheme: workbench.get<string>("preferredHighContrastLightColorTheme"),
    autoDetectColorScheme: window.get<boolean>("autoDetectColorScheme"),
    autoDetectHighContrast: window.get<boolean>("autoDetectHighContrast"),
  });
}

/** The user's own setting, not the value merged across scopes: a workspace's rules must not be copied into user settings. */
function userTokenColors(): TokenColors {
  return (vscode.workspace.getConfiguration("editor").inspect<TokenColors>("tokenColorCustomizations")?.globalValue ?? {}) as TokenColors;
}

async function write(next: TokenColors): Promise<void> {
  const value = Object.keys(next).length > 0 ? next : undefined;
  await vscode.workspace.getConfiguration("editor").update("tokenColorCustomizations", value, vscode.ConfigurationTarget.Global);
}

export async function applyRecommendedColors(): Promise<void> {
  const theme = activeTheme();
  await write(withRecommendedColors(refreshedRecommendedColors(userTokenColors()), theme));
  void vscode.window.showInformationMessage(
    `Streamlord: recommended colors written to your user settings for ${theme.name}, and for each theme you switch to. Run “Streamlord: Remove recommended colors” to undo.`,
  );
}

/** Once the user applied the palette, a theme switched to gets the palette of its own kind; never before. */
export async function followThemeChange(): Promise<void> {
  const current = userTokenColors();
  const theme = activeTheme();
  const appliedSomewhere = Object.entries(current).some(([key, value]) => key.startsWith("[") && hasOwned(value));
  if (!appliedSomewhere || hasOwned(current[`[${theme.name}]`])) return;
  await write(withRecommendedColors(current, theme));
}

export async function removeRecommendedColors(): Promise<void> {
  await write(withoutRecommendedColors(userTokenColors()));
  void vscode.window.showInformationMessage("Streamlord: recommended colors removed.");
}
