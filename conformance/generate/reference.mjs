// Fetches the free Datastar client the generators read into datastar-reference/datastar-<version>/,
// from the release tag on GitHub: `npm run reference`. Streamlord's repository holds no Datastar code,
// only the tag and the checksum below. Moving to a new version is changing both and running this.
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const VERSION = '1.0.4'
// sha256 of the tag's archive. A tag that moved gives another, and the fixtures were made from this one.
const SHA256 = '6417660c11cfc3c820c3ca25bd96449cfb3df3134b9b4a63b13f0831883165fb'
const ARCHIVE = `https://github.com/starfederation/datastar/archive/refs/tags/v${VERSION}.tar.gz`

const here = dirname(fileURLToPath(import.meta.url))
const referenceRoot = resolve(here, '..', '..', 'datastar-reference')
const name = `datastar-${VERSION}`

mkdirSync(referenceRoot, { recursive: true })
const present = readdirSync(referenceRoot).filter((d) => d.startsWith('datastar-'))
if (present.includes(name)) {
  console.log(`${name} is already in ${referenceRoot}`)
  process.exit(0)
}
// The generators take the version from the one directory they find.
if (present.length > 0) throw new Error(`remove ${present.join(', ')} from ${referenceRoot} first: the generators read only one version`)

const response = await fetch(ARCHIVE)
if (!response.ok) throw new Error(`${ARCHIVE}: ${response.status} ${response.statusText}`)
const archive = Buffer.from(await response.arrayBuffer())
const sha = createHash('sha256').update(archive).digest('hex')
if (sha !== SHA256) throw new Error(`${ARCHIVE} has sha256 ${sha}, not ${SHA256}: the tag is not the release the fixtures were made from`)

const work = mkdtempSync(join(tmpdir(), 'datastar-reference-'))
try {
  const file = join(work, `${name}.tar.gz`)
  writeFileSync(file, archive)
  // Every entry lies under the one top directory, so nothing lands outside it.
  const entries = execFileSync('tar', ['-tzf', file], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).split('\n').filter(Boolean)
  const outside = entries.find((e) => !e.startsWith(`${name}/`) || e.split('/').includes('..'))
  if (outside !== undefined) throw new Error(`${ARCHIVE} holds ${outside}, outside ${name}/`)
  execFileSync('tar', ['-xzf', file, '-C', work])
  for (const needed of ['bundles/datastar.js', 'library/src/engine/engine.ts']) {
    if (!existsSync(join(work, name, needed))) throw new Error(`${ARCHIVE} has no ${needed}`)
  }
  renameSync(join(work, name), join(referenceRoot, name))
} finally {
  rmSync(work, { recursive: true, force: true })
}
console.log(`${name} fetched into ${referenceRoot}`)
