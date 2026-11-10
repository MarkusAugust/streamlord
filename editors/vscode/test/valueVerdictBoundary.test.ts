import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import ts from "typescript";

/**
 * Which fact a value has is asked in valueVerdict.ts, and what a literal's text opens with in
 * valueTypes.ts, each with its Kotlin twin, so a rule is given once and named. A copy anywhere
 * else drifts from them, as the tag starts did. When this fails, call the function there, or add
 * one there; do not widen the test.
 */
const SRC = new URL("../src/", import.meta.url);
const files = readdirSync(SRC, { recursive: true, encoding: "utf8" }).filter((f) => f.endsWith(".ts")).sort();

/** Strings only a fact has: ArgKind and typeof share the rest. */
const FACT_ONLY = new Set(["constant", "nullable", "nullable-string"]);
const FACTS = new Set([...FACT_ONLY, "string", "number", "unsigned", "boolean", "markup"]);

// The options the extension is typechecked with, so the facts' types are the ones it sees.
const configPath = new URL("../tsconfig.json", import.meta.url).pathname;
const config = ts.parseJsonConfigFileContent(ts.readConfigFile(configPath, ts.sys.readFile).config, ts.sys, new URL("..", import.meta.url).pathname);
const program = ts.createProgram(
  files.map((f) => new URL(f, SRC).pathname),
  config.options,
);
const checker = program.getTypeChecker();

/** Whether `e` holds a fact: its type as declared, before an `if` narrows it, has a string only a fact has. */
function holdsFact(e: ts.Expression): boolean {
  const name = ts.isPropertyAccessExpression(e) ? e.name : e;
  const symbol = ts.isIdentifier(name) || ts.isPrivateIdentifier(name) ? checker.getSymbolAtLocation(name) : undefined;
  const type = symbol ? checker.getTypeOfSymbol(symbol) : checker.getTypeAtLocation(e);
  const members = type.isUnion() ? type.types : [type];
  return members.some((t) => t.isStringLiteral() && FACT_ONLY.has(t.value));
}

/** A first tag as a regex writes it: whitespace from the start and a `<`, `<(?:[A-Za-z]`, `<[a-zA-Z]`, or the placeholder as a tag name. */
const TAG_START = /\^(?:\\s|\[[^\]]*\])\*<|<\(\?:\[(?:A-Za-z|a-zA-Z|a-z)|<\[(?:A-Za-z|a-zA-Z)\]|__kt__\(\?=/;

const factLiteral = (e: ts.Expression) => ts.isStringLiteral(e) && FACTS.has(e.text);

function comparisons(file: string): string[] {
  const source = program.getSourceFile(new URL(file, SRC).pathname)!;
  const out: string[] = [];
  const report = (n: ts.Node) => out.push(`${file}:${source.getLineAndCharacterOfPosition(n.getStart()).line + 1}: ${n.getText().split("\n")[0]}`);
  const visit = (n: ts.Node) => {
    if (ts.isBinaryExpression(n) && [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken, ts.SyntaxKind.EqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsToken].includes(n.operatorToken.kind)) {
      if ((factLiteral(n.right) && holdsFact(n.left)) || (factLiteral(n.left) && holdsFact(n.right))) report(n);
    }
    // `["number", "boolean"].includes(fact)`, `new Set([...]).has(fact)`.
    if (ts.isCallExpression(n) && ts.isPropertyAccessExpression(n.expression) && ["includes", "has", "indexOf"].includes(n.expression.name.text) && n.arguments.some(holdsFact)) report(n);
    if (ts.isSwitchStatement(n) && holdsFact(n.expression)) {
      for (const clause of n.caseBlock.clauses) if (ts.isCaseClause(clause) && factLiteral(clause.expression)) report(clause);
    }
    ts.forEachChild(n, visit);
  };
  visit(source);
  return out;
}

describe("value verdict boundary", () => {
  it("only the verdict asks which fact a value has", () => {
    assert.ok(files.includes("valueVerdict.ts"), `${SRC.pathname} has no valueVerdict.ts; update the path in this test`);
    const found = files.filter((f) => f !== "valueVerdict.ts" && f !== "valueTypes.ts").flatMap(comparisons);
    assert.equal(found.join("\n"), "");
  });

  it("only the value types say what a literal opens with", () => {
    const found = files
      .filter((f) => f !== "valueTypes.ts")
      .flatMap((f) => readFileSync(new URL(f, SRC), "utf8").split("\n").flatMap((line, n) => (TAG_START.test(line) ? [`${f}:${n + 1}: ${line.trim()}`] : [])));
    assert.equal(found.join("\n"), "");
  });
});
