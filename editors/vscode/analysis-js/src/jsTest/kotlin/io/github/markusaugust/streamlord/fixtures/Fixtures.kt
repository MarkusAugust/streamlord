package io.github.markusaugust.streamlord.fixtures

// How a test reads the files it is held to on Node, where it runs somewhere under the build folder.

@JsModule("node:fs")
private external object Fs {
    fun readFileSync(
        path: String,
        encoding: String,
    ): String

    fun readdirSync(path: String): Array<String>

    fun existsSync(path: String): Boolean
}

private val root: String by lazy {
    var dir = js("process.cwd()").unsafeCast<String>()
    while (!Fs.existsSync("$dir/settings.gradle.kts")) dir = dir.substringBeforeLast('/')
    dir
}

/** The text of the file at [path]. */
internal fun fixtureText(path: String): String = Fs.readFileSync("$root/$path", "utf8")

/** The names of the files and folders in [folder], sorted. */
internal fun fixtureNames(folder: String): List<String> = Fs.readdirSync("$root/$folder").sorted()
