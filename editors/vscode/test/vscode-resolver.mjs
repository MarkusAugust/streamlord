const MOCK = new URL("./vscode-mock.ts", import.meta.url).href;

export async function resolve(specifier, context, nextResolve) {
  if (specifier === "vscode") return { url: MOCK, shortCircuit: true };
  return nextResolve(specifier, context);
}
