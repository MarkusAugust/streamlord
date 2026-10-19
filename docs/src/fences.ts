/**
 * The body of the ```kotlin fence named `name` in a samples file such as `samples/front-page.md`.
 * docs-samples compiles every fence in that file, so a page that shows one shows code that
 * builds against the library as it is. A name that is not there fails the site's build, which
 * is the point: the alternative is a poster that quietly shows last month's API.
 */
export function fence(file: string, name: string): string {
  const lines = file.split("\n")
  const open = lines.findIndex(
    (line) =>
      line.startsWith("```kotlin") && line.split(" ").includes(`name=${name}`),
  )
  if (open < 0) throw new Error(`no kotlin fence named ${name}`)
  const close = lines.indexOf("```", open + 1)
  if (close < 0) throw new Error(`the fence named ${name} never closes`)
  return lines.slice(open + 1, close).join("\n")
}
