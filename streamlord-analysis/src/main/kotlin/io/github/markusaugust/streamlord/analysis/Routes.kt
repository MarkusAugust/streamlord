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
    /** Query parameters the handler reads: Spring's `@RequestParam`, Ktor's `queryParameters["x"]`. */
    val query: List<RouteParam> = emptyList(),
    /** Headers the handler reads by name: Spring's `@RequestHeader("X-Id")`. */
    val headers: List<RouteParam> = emptyList(),
)

/**
 * One parameter a handler reads. [required] when the framework refuses the request without it:
 * a Spring parameter with no `required = false`, no `defaultValue` and a type that is not
 * nullable. Ktor reads a query parameter as nullable, so none it reads is required.
 */
public data class RouteParam(
    val name: String,
    val required: Boolean,
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
    Regex("""@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\b\s*(?:\(([^)]*)\))?""")

private val MODIFIERS = setOf("public", "internal", "private", "protected", "open", "final", "abstract", "sealed", "data", "inner")
private val DECLARATIONS = setOf("class", "interface", "object", "fun", "val", "var", "typealias")
private val CLASS_KEYWORD = Regex("""\b(?:class|interface)\b""")
private val WORD = Regex("""\w+""")
private val NAMED_PATH = Regex("""(?:value|path)\s*=\s*(?:\[\s*)?"([^"]*)"""")
private val POSITIONAL_PATH = Regex("""^\s*(?:\[\s*)?"([^"]*)"""")
private val REQUEST_METHOD = Regex("""RequestMethod\.([A-Z]+)""")
private val SLASHES = Regex("""/{2,}""")

public fun findRoutes(src: String): List<Route> = (findKtorRoutes(src) + findSpringRoutes(src)).sortedBy { it.offset }

private fun joinPath(
    prefix: String,
    path: String,
): String {
    // Spring and Ktor both accept a mapping without its leading slash, `@RequestMapping("api")`,
    // and the inspector puts the path straight after {{baseUrl}}: http://localhost:8080api.
    val joined = SLASHES.replace("/$prefix/$path", "/")
    return if (joined.length > 1) joined.removeSuffix("/") else joined
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
                val full = joinPath(prefix, path)
                val query = ktorQuery(src, mask, m.range.last, full)
                routes += Route(name.uppercase(), full, m.range.first, Framework.KTOR, query = query)
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

private val KTOR_QUERY = Regex("""\b(?:queryParameters|parameters)\s*(?:\[\s*"([^"\n]+)"\s*]|\.get\(\s*"([^"\n]+)"\s*\))""")
private val PATH_NAME = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)""")

/**
 * The query parameters read in the handler whose body opens at [open], in the order they are
 * read. `call.parameters` holds the path parameters too, so a name the path declares is not one.
 */
private fun ktorQuery(
    src: String,
    mask: BooleanArray,
    open: Int,
    path: String,
): List<RouteParam> {
    var depth = 0
    var close = src.length
    for (k in open until src.length) {
        if (!mask[k]) continue
        if (src[k] == '{') {
            depth++
        } else if (src[k] == '}' && --depth == 0) {
            close = k
            break
        }
    }
    val inPath = PATH_NAME.findAll(path).map { it.groupValues[1] }.toSet()
    return KTOR_QUERY
        .findAll(src.substring(open, close))
        .filter { mask[open + it.range.first] }
        .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .filter { it !in inPath }
        .distinct()
        .map { RouteParam(it, required = false) }
        .toList()
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

/** The offset just past the annotation at [at], arguments included, whatever parentheses its strings hold. */
private fun annotationEnd(
    src: String,
    mask: BooleanArray,
    at: Int,
): Int {
    var i = at + 1
    while (i < src.length && (src[i].isLetterOrDigit() || src[i] in "_.:")) i++
    var j = i
    while (j < src.length && src[j].isWhitespace()) j++
    if (j >= src.length || src[j] != '(') return i
    var depth = 0
    while (j < src.length) {
        if (mask[j]) {
            if (src[j] == '(') {
                depth++
            } else if (src[j] == ')' && --depth == 0) {
                return j + 1
            }
        }
        j++
    }
    return src.length
}

/** The offset of `class` or `interface` when what follows [from] is the rest of a class header, else -1. */
private fun annotatedClass(
    src: String,
    mask: BooleanArray,
    from: Int,
): Int {
    var i = from
    while (true) {
        while (i < src.length && (!mask[i] || src[i].isWhitespace())) i++
        if (i < src.length && src[i] == '@') {
            i = annotationEnd(src, mask, i)
            continue
        }
        val word = WORD.matchAt(src, i)?.value
        if (word == "class" || word == "interface") return i
        if (word == null || word !in MODIFIERS) return -1
        i += word.length
    }
}

private class ClassBody(
    val keyword: Int,
    val open: Int,
    val close: Int,
)

/** Every class and interface with a body. A header ends at the first top level `{`, or at the next declaration when the class has none. */
private fun classBodies(
    src: String,
    mask: BooleanArray,
): List<ClassBody> {
    val bodies = ArrayList<ClassBody>()
    for (m in CLASS_KEYWORD.findAll(src)) {
        val at = m.range.first
        if (!mask[at] || src.regionMatches(at - 2, "::", 0, 2)) continue
        var parens = 0
        var open = -1
        var i = m.range.last + 1
        while (i < src.length && open < 0) {
            val c = src[i]
            if (!mask[i]) {
                i++
                continue
            }
            if (c == '(') {
                parens++
            } else if (c == ')') {
                parens--
            } else if (c == '{' && parens == 0) {
                open = i
            } else if (parens == 0 && c.isIdentStart() && !src[i - 1].isIdentPart()) {
                val word = WORD.matchAt(src, i)?.value ?: ""
                if (word in DECLARATIONS) break
                i += maxOf(0, word.length - 1)
            }
            i++
        }
        if (open < 0) continue
        var depth = 0
        var close = src.length
        for (k in open until src.length) {
            if (!mask[k]) continue
            if (src[k] == '{') {
                depth++
            } else if (src[k] == '}' && --depth == 0) {
                close = k
                break
            }
        }
        bodies += ClassBody(at, open, close)
    }
    return bodies
}

private fun findSpringRoutes(src: String): List<Route> {
    val routes = ArrayList<Route>()
    val mask = lexKotlin(src).mask
    val bodies = classBodies(src, mask)
    // A method takes the mapping of the class it stands in, and only that one: a class without
    // @RequestMapping has no prefix, and Spring does not join the mapping of an enclosing class.
    val prefixes = HashMap<Int, String>()
    for (m in SPRING_ANNOTATION.findAll(src)) {
        val at = m.range.first
        if (!mask[at]) continue
        val name = m.groupValues[1]
        val args = m.groupValues[2]
        val target = annotatedClass(src, mask, annotationEnd(src, mask, at))
        if (target >= 0) {
            if (name == "RequestMapping") prefixes[target] = annotationPath(args)
            continue
        }
        val owner = bodies.lastOrNull { it.open < at && at < it.close }
        val prefix = owner?.let { prefixes[it.keyword] } ?: ""
        val method = if (name == "RequestMapping") annotationMethod(args) ?: "GET" else SPRING_MAPPINGS[name] ?: "GET"
        val params = handlerParameters(src, mask, annotationEnd(src, mask, at)).mapNotNull(::springParam)
        routes +=
            Route(
                method,
                joinPath(prefix, annotationPath(args)),
                at,
                Framework.SPRING,
                query = params.filter { it.first == "RequestParam" }.map { it.second },
                headers = params.filter { it.first == "RequestHeader" }.map { it.second },
            )
    }
    return routes
}

/**
 * The parameters of the function the mapping at [from] annotates, each as written, or none when
 * the next thing in the code is not a function. Split at the top level only: `Map<String, String>`
 * and `@RequestParam(required = false)` hold commas and parentheses of their own.
 */
private fun handlerParameters(
    src: String,
    mask: BooleanArray,
    from: Int,
): List<String> {
    var i = from
    while (i < src.length) {
        if (mask[i]) {
            if (src[i] == '{' || src[i] == '}') return emptyList()
            if (src.startsWith("fun", i) && !src[i - 1].isIdentPart() && !src.getOrElse(i + 3) { ' ' }.isIdentPart()) break
        }
        i++
    }
    while (i < src.length && !(mask[i] && src[i] == '(')) i++
    if (i >= src.length) return emptyList()
    val params = ArrayList<String>()
    var depth = 0
    var start = i + 1
    while (i < src.length) {
        if (mask[i]) {
            when (src[i]) {
                '(', '[', '{', '<' -> {
                    depth++
                }

                ')', ']', '}', '>' -> {
                    if (--depth == 0) {
                        params += src.substring(start, i)
                        break
                    }
                }

                ',' -> {
                    if (depth == 1) {
                        params += src.substring(start, i)
                        start = i + 1
                    }
                }
            }
        }
        i++
    }
    return params.map { it.trim() }.filter { it.isNotEmpty() }
}

private val PARAM_ANNOTATION = Regex("""@(RequestParam|RequestHeader)\b\s*(?:\(([^)]*)\))?""")
private val ANY_ANNOTATION = Regex("""@[\w.:]+(?:\s*\([^)]*\))?""")
private val DECLARATION = Regex("""^(?:\w+\s+)*(\w+)\s*:\s*([^=]+?)\s*(?:=.*)?$""", RegexOption.DOT_MATCHES_ALL)
private val NAMED_ARG = Regex("""(?:value|name)\s*=\s*"([^"]*)"""")
private val NOT_REQUIRED = Regex("""required\s*=\s*false|defaultValue\s*=""")

/** Every parameter, in a type that takes them all. */
private val ALL_OF_THEM = Regex("""^(?:[\w.]*\.)?(?:Map|MultiValueMap|HttpHeaders)\b""")

/** A `@RequestParam` or `@RequestHeader` parameter: which of the two, its name on the wire and whether it is required. */
private fun springParam(text: String): Pair<String, RouteParam>? {
    val annotation = PARAM_ANNOTATION.find(text) ?: return null
    val args = annotation.groupValues[2]
    val declaration = DECLARATION.find(ANY_ANNOTATION.replace(text, " ").trim()) ?: return null
    val type = declaration.groupValues[2].trim()
    if (ALL_OF_THEM.containsMatchIn(type)) return null
    val name =
        NAMED_ARG.find(args)?.groupValues?.get(1)
            ?: POSITIONAL_PATH.find(args)?.groupValues?.get(1)
            ?: declaration.groupValues[1]
    val required = !NOT_REQUIRED.containsMatchIn(args) && !type.endsWith("?")
    return annotation.groupValues[1] to RouteParam(name, required)
}
