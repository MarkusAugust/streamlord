package forge.values

import io.github.markusaugust.streamlord.core.domain.interpolate

// interpolate reads the prefixes it is given, and those alone.
fun aliased(name: String) = interpolate(listOf("data-x-"), "<p data-x-text=\"%s\">%s</p>", name, name)

fun unaliased(name: String) = interpolate(listOf("data-x-"), "<p data-text=\"%s\">x</p>", name)

// A list, a set or an array is one value.
fun listed(a: String) = interpolate("<p>%s</p>", listOf(a))

fun counted(a: String, b: String) = interpolate("<p>%s, %s</p>", listOf(a, b))

fun arrayed(a: String, n: Int) = interpolate("<p title=\"%s\">%s</p>", arrayOf(a), intArrayOf(n, 2))

fun spread(xs: Array<String>) = interpolate("<p>%s, %s</p>", *xs)
