// What the generators share: where the free client's release tree is, the one way a file is read
// from it, its bundle as a script a happy-dom window runs, and how a fixture is written.
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, realpathSync } from 'node:fs'
import { dirname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { transformSync } from 'esbuild'

const here = dirname(fileURLToPath(import.meta.url))

/** The repository's conformance directory, where the fixtures live. */
export const conformance = resolve(here, '..')

const referenceRoot = resolve(here, '..', '..', 'datastar-reference')
const versions = readdirSync(referenceRoot).filter((d) => d.startsWith('datastar-'))
if (versions.length !== 1) throw new Error(`expected one datastar-<version> directory under ${referenceRoot}, found ${versions.length}`)

/** The Datastar version of the reference, from its directory's name. */
export const version = versions[0].slice('datastar-'.length)

/** The free client's release tree, `datastar-reference/datastar-<version>`. */
export const release = resolve(referenceRoot, versions[0])

/** A file of the free client's release tree, and never a path outside it. */
const readFreeClient = (relative) => {
  const root = realpathSync(release) + sep
  const file = realpathSync(resolve(release, relative))
  if (!file.startsWith(root)) throw new Error(`${relative} is outside the free client`)
  return readFileSync(file, 'utf8')
}

/**
 * A bundle of the free client, an ES module, as an IIFE that runs inside a happy-dom window, where
 * its exports land on `Datastar`, with the sha256 of the file. A module is strict, so the IIFE is
 * told: in sloppy mode the assignments that make the client throw on a __proto__ key would silently
 * do nothing.
 */
export const freeBundle = (file) => {
  const source = readFreeClient(`bundles/${file}`)
  return {
    code: `"use strict";\n${transformSync(source, { format: 'iife', globalName: 'Datastar' }).code}`,
    sha256: createHash('sha256').update(source).digest('hex'),
  }
}

/** ASCII only, so a byte order mark or a line separator in a case shows in a diff. */
export const ascii = (text) => text.replace(/[^\x00-\x7f]/g, (ch) => `\\u${ch.charCodeAt(0).toString(16).padStart(4, '0')}`)
