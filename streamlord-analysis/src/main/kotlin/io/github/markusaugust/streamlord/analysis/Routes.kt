package io.github.markusaugust.streamlord.analysis

/*
 * Finds HTTP routes in Kotlin source for the "Open in Stream Inspector" gutter entry.
 * Ktor: `route("/api") { get("/x") { } }`, verbs without a path inherit the enclosing route.
 * Spring: `@RequestMapping("/api")` on the class, `@GetMapping("/x")` and friends on methods.
 */

public enum class Framework { KTOR, SPRING }

public data class Route(
    val method: String,
    val path: String,
    /** Source offset of the verb or annotation, for the marker position. */
    val offset: Int,
    val framework: Framework,
)

private val KTOR_VERBS = setOf("get", "post", "put", "patch", "delete")
private val SPRING_MAPPINGS =
    mapOf(
        "GetMapping" to "GET",
        "PostMapping" to "POST",
        "PutMapping" to "PUT",
        "PatchMapping" to "PATCH",
        "DeleteMapping" to "DELETE",
        "RequestMapping" to null,
    )

// `map.get("key")` and `client.post("https://...")` have the shape of a route and are none. A verb
// counts when it is not called on a receiver, and it either takes a lambda or its path starts with
// a slash. The path may be followed by more arguments: `route("/v1", HttpMethod.Get) { }`.
private val KTOR_CALL =
    Regex(
        """\b(route|get|post|put|patch|delete)\s*(?:\(\s*"([^"\n]*)"\s*(?:,[^(){}\n]*)?\)\s*)?\{|\b(get|post|put|patch|delete)\s*\(\s*"(/[^"\n]*)"\s*\)""",
    )
private val SPRING_ANNOTATION =
    Regex("""@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\s*(?:\(([^)]*)\))?""")

// Any modifier may stand between the annotation and `class`, and Spring reads a mapping on an interface too.
private val ON_CLASS =
    Regex(
        """^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|internal|private|protected|open|final|abstract|sealed|data|inner)\s+)*(?:class|interface)\b""",
    )
private val NAMED_PATH = Regex("""(?:value|path)\s*=\s*(?:\[\s*)?"([^"]*)"""")
private val POSITIONAL_PATH = Regex("""^\s*(?:\[\s*)?"([^"]*)"""")
private val REQUEST_METHOD = Regex("""RequestMethod\.([A-Z]+)""")
private val SLASHES = Regex("""/{2,}""")

public fun findRoutes(src: String): List<Route> = (findKtorRoutes(src) + findSpringRoutes(src)).sortedBy { it.offset }

private fun joinPath(
    prefix: String,
    path: String,
): String {
    val joined = SLASHES.replace("$prefix/$path", "/")
    return if (joined.length > 1) joined.removeSuffix("/") else joined.ifEmpty { "/" }
}

private class Frame(
    val prefix: String,
    val depth: Int,
)

private fun findKtorRoutes(src: String): List<Route> {
    val routes = ArrayList<Route>()
    val mask = lexKotlin(src).mask
    val stack = ArrayList<Frame>()
    var depth = 0
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (!mask[i]) {
            i++
            continue
        }
        if (c == '{') {
            depth++
            i++
            continue
        }
        if (c == '}') {
            depth--
            while (stack.isNotEmpty() && stack.last().depth > depth) stack.removeAt(stack.size - 1)
            i++
            continue
        }
        if (!c.isIdentStart() || (i > 0 && src[i - 1].isIdentPart())) {
            i++
            continue
        }
        val m = KTOR_CALL.matchAt(src, i)
        if (m == null || hasReceiver(src, i)) {
            i++
            continue
        }
        val prefix = stack.lastOrNull()?.prefix ?: ""
        if (m.groups[1] != null) {
            val name = m.groupValues[1]
            val path = m.groupValues[2]
            if (name == "route") {
                stack += Frame(joinPath(prefix, path), depth + 1)
            } else if (name in KTOR_VERBS && (path.isNotEmpty() || stack.isNotEmpty())) {
                routes += Route(name.uppercase(), joinPath(prefix, path), m.range.first, Framework.KTOR)
            }
            depth++
            i = m.range.last + 1
            continue
        }
        if (m.groups[3] != null) {
            routes += Route(m.groupValues[3].uppercase(), joinPath(prefix, m.groupValues[4]), m.range.first, Framework.KTOR)
            i = m.range.last + 1
            continue
        }
        i++
    }
    return routes
}

/** Whether the call at [start] is made on something: `client.post(...)`, also with the dot on the line above. */
private fun hasReceiver(
    src: String,
    start: Int,
): Boolean {
    var i = start - 1
    while (i >= 0 && src[i] in " \t\r\n") i--
    return i >= 0 && src[i] == '.'
}

private fun annotationPath(args: String): String =
    NAMED_PATH.find(args)?.groupValues?.get(1) ?: POSITIONAL_PATH.find(args)?.groupValues?.get(1) ?: ""

private fun annotationMethod(args: String): String? = REQUEST_METHOD.find(args)?.groupValues?.get(1)

private fun findSpringRoutes(src: String): List<Route> {
    val routes = ArrayList<Route>()
    val mask = lexKotlin(src).mask
    var classPrefix = ""
    for (m in SPRING_ANNOTATION.findAll(src)) {
        if (!mask[m.range.first]) continue
        val name = m.groupValues[1]
        val args = m.groupValues[2]
        val after = src.substring(m.range.last + 1, minOf(src.length, m.range.last + 201))
        if (ON_CLASS.containsMatchIn(after)) {
            if (name == "RequestMapping") classPrefix = annotationPath(args)
            continue
        }
        val method = if (name == "RequestMapping") annotationMethod(args) ?: "GET" else SPRING_MAPPINGS[name] ?: "GET"
        routes += Route(method, joinPath(classPrefix, annotationPath(args)), m.range.first, Framework.SPRING)
    }
    return routes
}
