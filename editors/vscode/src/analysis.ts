/**
 * The analysis, which is Kotlin: streamlord-analysis compiled to JavaScript by the Gradle project
 * in ../analysis-js, the same code the IntelliJ plugin runs. This is the one module the adapters
 * reach it through; its functions and types are those of Port.kt and InspectorPort.kt there, and
 * `npm run analysis` builds it.
 */
export * from "../analysis-js/build/port/streamlord-analysis-js.mjs";
