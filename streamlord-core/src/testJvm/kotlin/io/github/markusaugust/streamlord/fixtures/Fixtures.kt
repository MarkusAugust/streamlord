package io.github.markusaugust.streamlord.fixtures

import java.io.File

/*
 * How a test reads the files it is held to: the catalog, conformance/ and the editor fixtures, each
 * named by its path from the repository root. The tests of core and of the analysis compile this
 * file on the JVM, where a test runs in its module's folder; the JavaScript build has its own.
 */

/** The text of the file at [path]. */
internal fun fixtureText(path: String): String = File("..", path).readText()

/** The names of the files and folders in [folder], sorted. */
internal fun fixtureNames(folder: String): List<String> = File("..", folder).list()!!.sorted()
