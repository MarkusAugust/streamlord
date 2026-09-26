/**
 * The recommended palettes for the Datastar-specific tokens. Everything that is ordinary
 * JavaScript (methods, numbers, strings, constants, control keywords, regex, operators) uses the
 * standard TextMate scopes and therefore already gets the theme's TypeScript colors; only the
 * concepts Datastar adds need rules. The dark palette follows Dark+ conventions, the light
 * palette Light+, and both avoid the function yellow.
 *
 * Reading guide, dark: signals are const-blue (state you read and write), actions are purple
 * (control flow that reaches the server), `el`/`evt`/`patch` are keyword-blue italic (like
 * `this`), option keys are property-blue in bold, attribute plugins are purple, keys teal,
 * modifiers keyword-blue, durations number-green.
 */

export interface Rule {
  name: string;
  scope: string[];
  settings: { foreground?: string; fontStyle?: string };
}

const rules = (c: {
  signal: string;
  property: string;
  action: string;
  scopeVar: string;
  type: string;
  keyword: string;
  number: string;
  muted: string;
  escape: string;
}): Rule[] => [
  { name: "Streamlord: signal", scope: ["variable.other.constant.signal.datastar", "punctuation.definition.signal.datastar"], settings: { foreground: c.signal, fontStyle: "bold" } },
  { name: "Streamlord: signal path", scope: ["variable.other.property.signal-path.datastar"], settings: { foreground: c.property } },
  { name: "Streamlord: backend action", scope: ["entity.name.function.action.backend.datastar", "punctuation.definition.action.datastar"], settings: { foreground: c.action, fontStyle: "bold" } },
  { name: "Streamlord: action", scope: ["entity.name.function.action.datastar"], settings: { foreground: c.action } },
  { name: "Streamlord: el / evt / patch", scope: ["variable.language.scope.datastar"], settings: { foreground: c.scopeVar, fontStyle: "italic" } },
  { name: "Streamlord: option keys", scope: ["support.type.property-name.option.datastar"], settings: { foreground: c.property, fontStyle: "bold" } },
  { name: "Streamlord: object keys", scope: ["variable.other.property.key.datastar"], settings: { foreground: c.property } },
  { name: "Streamlord: identifiers", scope: ["variable.other.readwrite.datastar"], settings: { foreground: c.property } },
  { name: "Streamlord: duration", scope: ["constant.numeric.duration.datastar"], settings: { foreground: c.number } },
  { name: "Streamlord: kotlin dollar escape", scope: ["constant.character.escape.dollar.kotlin"], settings: { foreground: c.escape } },
  { name: "Streamlord: attribute prefix", scope: ["entity.other.attribute-name.datastar.prefix"], settings: { foreground: c.muted } },
  { name: "Streamlord: attribute plugin", scope: ["entity.other.attribute-name.datastar.plugin"], settings: { foreground: c.action, fontStyle: "bold" } },
  { name: "Streamlord: attribute key", scope: ["entity.other.attribute-name.datastar.key"], settings: { foreground: c.type } },
  { name: "Streamlord: modifier", scope: ["keyword.other.modifier.datastar"], settings: { foreground: c.keyword } },
  { name: "Streamlord: modifier sigils", scope: ["punctuation.definition.modifier.datastar", "punctuation.separator.key.datastar"], settings: { foreground: c.muted } },
  { name: "Streamlord: modifier argument", scope: ["constant.other.modifier-arg.datastar"], settings: { foreground: c.type } },
];

/** Dark+ / Dark Modern. */
export const DARK_RULES: Rule[] = rules({
  signal: "#4FC1FF",
  property: "#9CDCFE",
  action: "#C586C0",
  scopeVar: "#569CD6",
  type: "#4EC9B0",
  keyword: "#569CD6",
  number: "#B5CEA8",
  muted: "#808080",
  escape: "#D7BA7D",
});

/** Light+ / Light Modern. */
export const LIGHT_RULES: Rule[] = rules({
  signal: "#0070C1",
  property: "#001080",
  action: "#AF00DB",
  scopeVar: "#0000FF",
  type: "#267F99",
  keyword: "#0000FF",
  number: "#098658",
  muted: "#8E8E8E",
  escape: "#EE0000",
});
