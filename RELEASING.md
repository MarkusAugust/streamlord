# Releasing

How changes reach a release, and how a release reaches Maven Central, the JetBrains Marketplace
and the VS Code Marketplace.

## The rule

`master` holds what has been released, in any channel. Everything else waits on a release
branch, `release/<next version>`, which carries `<next version>-SNAPSHOT` in
`gradle.properties` until the release commit.

Rulesets give the shape: `master` and `release/**` take pull requests, merged with a merge
commit, and only a repository admin can create, move or delete a release tag (`v*`,
`intellij-v*`, `vscode-v*`). They do not enforce it on the admin: the rulesets let an admin
bypass them, require no green check before a merge and no review, so a red pull request can
still be merged into `master`, which deploys. Merging is a decision taken on a green run, by
hand. The publishing secrets live in the `release` environment, not in the repository: the three
publish jobs and `verify-signing` run in it, and it takes the release tags, `master` and
`release/**`, the last two for `verify-signing`, which runs by hand. `RAILWAY_TOKEN` stays a
repository secret, for the demo deploy on `master`, and so does `VSCE_PAT` until VS Code
publishing moves to Microsoft Entra ID, before Azure DevOps retires global tokens on
1 December 2026. The environment asks for no reviewer, so any job a merged pull request adds to
`master` or a release branch with `environment: release` can read these secrets; with one
maintainer that is the trade-off taken.

## Day to day

1. Branch from the release branch: `fix/…`, `feat/…`, `docs/…`.
2. Open the pull request against the release branch. CI compares it with the branch tip, so it
   builds what the change touches.
3. Write the test first and commit it on its own, red, then the fix.
4. Record anything a user will notice under `## [Unreleased]` in `CHANGELOG.md`.

Every push to a release branch runs CI on its tip, including the native image of the demo
service. Nothing is pushed or deployed from it.

## A fix to what is already released

An editor fix or a documentation correction that cannot wait for the next release goes from
`master`, on a `hotfix/…` branch (not `release/…`, which the push trigger and the ruleset
treat as a release branch):

1. Pull request into `master`.
2. For an editor: the version bump and its changelog section in the same pull request, then the
   editor tag on the merge commit.
3. A pull request that merges `master` into the release branch. Never rebase it.

## Cutting a release

The tag goes on the release branch first; `master` gets the release only once Maven Central has
it.

1. If `master` has moved since the branch was cut, merge `origin/master` into the release branch
   through a pull request.
2. The release commit, `chore(release): <version>`:
   - `gradle.properties`: `version=<version>`.
   - `./gradlew :docs-samples:checkDocSamples`, and commit what it rewrites: the coordinates in
     the README and on the documentation pages. Do not search and replace the old version by
     hand; the coordinates are generated, and other libraries share version numbers with us.
   - `CHANGELOG.md`: rename `## [Unreleased]` to `## [<version>] - <date>` and open a new empty
     `## [Unreleased]` above it. The publish job takes the release notes from that section and
     refuses to publish without it.
   - `docs/src/content/docs/changelog.md`, and in `roadmap.md` take out what the release delivers.
   - `.github/dependabot.yml`: `target-branch` to the next release branch.
   In CI `checkDocSamples` fails on a page it would have rewritten, so a release commit that
   skips this step goes red on the tag rather than publishing pages that name the old release.
3. Editor releases that ship with it: `release(intellij): <version>` and
   `release(vscode): <version>`, each with its version bump and changelog section. The editor
   changelogs never carry an `[Unreleased]` section: the IntelliJ build takes the first `## `
   section as the Marketplace change notes.
4. The ABI gate: read every removed line in `git diff v<previous> -- '*/api/*.api'`. A removal
   is a binary break and needs a reason in the changelog.
5. Run `./gradlew build` locally on the release commit.
6. Push the tag `v<version>` on the release commit, on its own. CI builds everything, including
   the native image, then publishes every module to Maven Central and announces the release on
   GitHub.
7. Wait for `Publish to Maven Central` to go green and for
   `https://repo1.maven.org/maven2/io/github/markusaugust/streamlord/streamlord-core/maven-metadata.xml`
   to list the version.
8. Then the editor tags, `intellij-v<version>` and `vscode-v<version>`, on the same commit. The
   IntelliJ job refuses to publish unless `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY`
   and `JETBRAINS_PRIVATE_KEY_PASSWORD` are set, since without them the plugin went out unsigned
   while the workflow looked as if it signed it. All three are in the `release` environment; the
   certificate is self-signed and runs for a year, so renew it before October 2027. Not before:
   each tag runs on its own, and pushed together with `v<version>` the plugins could publish while
   Maven Central is still building or failing. Push at most three tags at a time; GitHub starts no
   workflow for a push of more.
9. Pull request from the release branch into `master`, merged with a merge commit. The tag is
   then reachable from `master`.

### When publishing fails

- Before anything was uploaded: delete the tag (locally and on GitHub), and the GitHub release
  if `gh release create` already ran. Fix on the release branch, then tag again. The editor tags
  have not been pushed yet.
- After an upload: the version number is spent. Maven Central never takes the same version
  twice, so the fix ships as the next patch version.

## After the merge

1. Create the next release branch from `master` straight away, since Dependabot now targets it.
2. On it, set `version=<next>-SNAPSHOT`.
3. Check that the documentation site deployed from `master`, and that
   `https://streamlord-live-production.up.railway.app/health` answers with the merge commit.

## Dependabot

Version updates go to the release branch (`target-branch` in `.github/dependabot.yml`).
Dependabot reads that file from `master` only, so a change to it goes to `master`.

Security updates ignore `target-branch` and every other option, ignore rules included, and open
against `master`. Retarget them to the release branch, or, when the fix belongs in a published
artifact, ship it as a patch release.
