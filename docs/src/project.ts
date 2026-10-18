/**
 * What the site says about Streamlord as a whole, each fact from where it is kept, so the
 * landing page and llms.txt cannot drift from the project or from each other.
 */

import versionCatalog from "../../gradle/libs.versions.toml?raw"
import gradleProperties from "../../gradle.properties?raw"
import differential from "../../streamlord-core/src/test/resources/client-merge-differential.json"

/** The one line under the name on the landing page. */
export const TAGLINE = "A Datastar toolchain for Kotlin."

/** What Streamlord is, in two sentences: the landing page's meta description. */
export const DESCRIPTION =
  "Datastar lives in strings your compiler never reads. Streamlord checks them in your editor, when the event is built, and before a byte reaches the browser, with adapters for Ktor and Spring."

/** The SDK's version, from `gradle.properties`: the release the coordinates on the pages name. */
export const VERSION = property("version")

/** The Maven group every module is published under, from `gradle.properties`. */
export const MAVEN_GROUP = property("group")

/**
 * The Datastar version the SDK and the editors are written against: the version in the
 * name of the one catalog, `catalog/datastar-<version>.json`, which a Datastar upgrade replaces.
 */
export const DATASTAR_VERSION = catalogVersion()

/** The Ktor the SDK is built and tested on, from the version catalog. */
export const KTOR_VERSION = catalogEntry("ktor")

/** The Spring Framework the SDK is built and tested on, from the version catalog. */
export const SPRING_VERSION = catalogEntry("spring")

/**
 * How many scenarios the core's signal merge is held to the Datastar client's own `mergePatch`
 * on: one entry each in the differential fixture the core's tests replay.
 */
export const MERGE_SCENARIOS: number = differential.length

/**
 * How many cases the two editors are held to judging alike: every case in the corpus both
 * extensions' test suites run. Counted from the fixture files, so the number cannot go stale.
 */
export const CORPUS_CASES: number = Object.values(
  import.meta.glob("../../editors/vscode/test/fixtures/corpus/*.json", {
    eager: true,
    import: "default",
  }),
).reduce((total, file) => total + corpusCases(file), 0)

function corpusCases(file: unknown): number {
  const cases = (file as { cases?: unknown[] }).cases
  if (!Array.isArray(cases)) throw new Error("a corpus file has no cases")
  return cases.length
}

function catalogEntry(name: string): string {
  const value = new RegExp(`^${name} = "(.+)"$`, "m").exec(versionCatalog)?.[1]
  if (!value) throw new Error(`libs.versions.toml has no ${name}`)
  return value
}

function property(name: string): string {
  const value = new RegExp(`^${name}=(.+)$`, "m").exec(gradleProperties)?.[1]
  if (!value) throw new Error(`gradle.properties has no ${name}`)
  return value.trim()
}

function catalogVersion(): string {
  const versions = Object.keys(
    import.meta.glob("../../catalog/datastar-*.json"),
  ).flatMap((path) => /datastar-(.+)\.json$/.exec(path)?.[1] ?? [])
  if (versions.length !== 1) {
    throw new Error(
      `expected one catalog/datastar-<version>.json, found ${versions.length}`,
    )
  }
  return versions[0]
}
