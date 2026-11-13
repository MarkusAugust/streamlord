import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { withoutRecommendedColors, withRecommendedColors } from "../src/colors.ts";
import { DARK_RULES, LIGHT_RULES } from "../src/palette.ts";

/** The opt-in palette is written for the theme in use, by its kind, never as a rule every theme gets. */
describe("recommended colours", () => {
  const mine = { name: "mine", scope: ["comment"], settings: { foreground: "#123456" } };

  it("writes the light palette for a light theme and the dark one for a dark theme, by kind and not by name", () => {
    const light = withRecommendedColors({ textMateRules: [mine] }, { name: "Solarized Light", dark: false });
    assert.deepEqual(light, { textMateRules: [mine], "[Solarized Light]": { textMateRules: LIGHT_RULES } });
    const darkNamedNothing = withRecommendedColors({}, { name: "Monokai", dark: true });
    assert.deepEqual(darkNamedNothing, { "[Monokai]": { textMateRules: DARK_RULES } });
    // A light theme whose name does not say so still gets the light palette.
    assert.deepEqual(withRecommendedColors({}, { name: "Quiet", dark: false }), { "[Quiet]": { textMateRules: LIGHT_RULES } });
  });

  it("keeps another theme's palette, and replaces the old global and name-matched ones", () => {
    const old = { textMateRules: [mine, ...DARK_RULES], "[*Light*]": { textMateRules: LIGHT_RULES } };
    const next = withRecommendedColors(old, { name: "Default Dark Modern", dark: true });
    assert.deepEqual(next, { textMateRules: [mine], "[Default Dark Modern]": { textMateRules: DARK_RULES } });
    const both = withRecommendedColors(next, { name: "Default Light Modern", dark: false });
    assert.deepEqual(both["[Default Dark Modern]"], { textMateRules: DARK_RULES });
    assert.deepEqual(both["[Default Light Modern]"], { textMateRules: LIGHT_RULES });
  });

  it("removes every palette it wrote, and only those", () => {
    const applied = withRecommendedColors({ textMateRules: [mine], "[Monokai]": { textMateRules: [mine] } }, { name: "Monokai", dark: true });
    assert.deepEqual(withoutRecommendedColors(applied), { textMateRules: [mine], "[Monokai]": { textMateRules: [mine] } });
  });

  it("colours the scopes the grammars write", () => {
    const scopes = DARK_RULES.flatMap((r) => r.scope);
    assert.ok(scopes.includes("variable.other.enummember.modifier-arg.datastar"));
    assert.ok(!scopes.includes("constant.other.modifier-arg.datastar"));
    assert.deepEqual(LIGHT_RULES.map((r) => r.scope), DARK_RULES.map((r) => r.scope));
  });
});

describe("the recommended colours, applied again", () => {
  it("rewrites a palette written before, by the kind it was written for", async () => {
    const { refreshedRecommendedColors } = await import("../src/colors.ts");
    const stale = (rules: typeof DARK_RULES) => rules.map((r) => (r.name === "Streamlord: modifier argument" ? { ...r, scope: ["constant.other.modifier-arg.datastar"] } : r));
    const current = { "[Monokai]": { textMateRules: stale(DARK_RULES) }, "[Quiet Light]": { textMateRules: stale(LIGHT_RULES) } };
    assert.deepEqual(refreshedRecommendedColors(current), { "[Monokai]": { textMateRules: DARK_RULES }, "[Quiet Light]": { textMateRules: LIGHT_RULES } });
  });

  it("names the theme in use, a high contrast one auto-detection picked among them", async () => {
    const { themeInUse } = await import("../src/colors.ts");
    const settings = { colorTheme: "Monokai", preferredDarkColorTheme: "Dark+", preferredLightColorTheme: "Light+", preferredHighContrastColorTheme: "HC Black", preferredHighContrastLightColorTheme: "HC Light", autoDetectColorScheme: false, autoDetectHighContrast: true };
    assert.deepEqual(themeInUse("dark", settings), { name: "Monokai", dark: true });
    assert.deepEqual(themeInUse("highContrast", settings), { name: "HC Black", dark: true });
    assert.deepEqual(themeInUse("highContrastLight", settings), { name: "HC Light", dark: false });
    assert.deepEqual(themeInUse("highContrast", { ...settings, autoDetectHighContrast: false }), { name: "Monokai", dark: true });
    assert.deepEqual(themeInUse("light", { ...settings, autoDetectColorScheme: true }), { name: "Light+", dark: false });
  });
});
