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
const files = readdirSync(SRC).filter((f) => f.endsWith(".ts")).sort();

/** Strings only a fact has: ArgKind and typeof share the rest. */
const FACT_ONLY = new Set(["constant", "nullable", "nullable-string"]);
const FACTS = new Set([...FACT_ONLY, "string", "number", "unsigned", "boolean", "markup"]);

const program = ts.createProgram(
  files.map((f) => new URL(f, SRC).pathname),
  { target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.NodeNext, moduleResolution: ts.ModuleResolutionKind.NodeNext, allowImportingTsExtensions: true, noEmit: true, strict: true },
);
const checker = program.getTypeChecker();

/** Whether `e` holds a fact: its type as declared, before an `if` narrows it, has a string only a fact has. */
function holdsFact(e: ts.Expression): boolean {
  const symbol = ts.isIdentifier(e) ? checker.getSymbolAtLocation(e) : undefined;
  const type = symbol ? checker.getTypeOfSymbol(symbol) : checker.getTypeAtLocation(e);
  const members = type.isUnion() ? type.types : [type];
  return members.some((t) => t.isStringLiteral() && FACT_ONLY.has(t.value));
}

const factLiteral = (e: ts.Expression) => ts.isStringLiteral(e) && FACTS.has(e.text);

function comparisons(file: string): string[] {
  const source = program.getSourceFile(new URL(file, SRC).pathname)!;
  const out: string[] = [];
  const report = (n: ts.Node) => out.push(`${file}:${source.getLineAndCharacterOfPosition(n.getStart()).line + 1}: ${n.getText().split("\n")[0]}`);
  const visit = (n: ts.Node) => {
    if (ts.isBinaryExpression(n) && [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken, ts.SyntaxKind.EqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsToken].includes(n.operatorToken.kind)) {
      if ((factLiteral(n.right) && holdsFact(n.left)) || (factLiteral(n.left) && holdsFact(n.right))) report(n);
    }
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
      .flatMap((f) => readFileSync(new URL(f, SRC), "utf8").split("\n").flatMap((line, n) => (line.includes("<(?:[A-Za-z]") ? [`${f}:${n + 1}: ${line.trim()}`] : [])));
    assert.equal(found.join("\n"), "");
  });
});
