package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The JavaScript the parser must accept, and where it must object. */
class JsParserTest {
    private fun ok(vararg sources: String) {
        for (s in sources) assertNull(JsParser.error(s), "expected no error in: $s")
    }

    private fun bad(
        source: String,
        pos: Int? = null,
        message: String? = null,
    ) {
        val e = assertNotNull(JsParser.error(source), "expected an error in: $source")
        if (pos != null) assertEquals(pos, e.pos, "position in: $source (${e.message})")
        if (message != null) assertEquals(message, e.message, "message in: $source")
    }

    @Test
    fun `accepts datastar expressions`() {
        ok(
            "\$count++",
            "\$count ++",
            "\$open = !\$open",
            "_post('/x', {headers: {'X-A': '1'}})",
            "evt.key === 'Escape' && (\$open = false)",
            "_peek(() => \$a.b)",
            "\$items.filter(i => i.done).length",
            "\$user?.name ?? 'anon'",
            "\$a ||= 1; \$b &&= 2; \$c ??= 3",
            "el.classList.toggle('x', \$on)",
            "`Hello \${\$name}, \${1 + 2}`",
            "({count: 0, name: '', nested: {a: [1, 2, 3]}}); [1, 2]",
            "{count: 0}",
            "_get('/x', {filterSignals: {include: /^form\\./, exclude: /_$/}})",
            "return \$x > 1 ? 'a' : 'b'",
            "if (\$a) { \$b = 1 } else \$b = 2",
            "for (const x of \$items) console.log(x)",
            "for (let i = 0; i < 3; i++) {}",
            "for (const k in obj) {}",
            "while (\$n < 3) \$n++",
            "do { \$n-- } while (\$n)",
            "switch (\$x) { case 1: break; default: \$y = 2 }",
            "try { risky() } catch (e) { \$err = e.message } finally { \$busy = false }",
            "try { risky() } catch { }",
            "typeof \$x === 'string' && !!\$y",
            "new Date().toISOString()",
            "new Intl.NumberFormat('en').format(\$n)",
            "function f(a, b = 1, ...rest) { return a }",
            "const {a, b: c = 2, ...others} = \$obj; const [x, , y = 3] = \$arr",
            "async () => { await x() }",
            "async function g() {}",
            "\$total = \$items.reduce((sum, i) => sum + i.price * i.qty, 0)",
            "\$n = (-\$m) ** 2 + (2 ** 3 ** 2)",
            "\$v = 0x1f + 0b101 + 0o17 + 1_000 + 1e3 + .5 + 5. + 10n",
            "a?.b?.[0]?.(1)",
            "\$s = 'it\\'s'; \$t = \"q\\\"q\"",
            "label: for (;;) { break label }",
            "class A extends B { static x = 1; #p = 2; get v() { return this.#p } m() {} static { } }",
            "(a, b) => a + b",
            "x => x * 2",
            "({a} = \$o)",
            "[\$a, \$b] = [\$b, \$a]",
            "\$x = \$y = 3",
            "void 0; delete \$o.k; \$n = +\$s; \$f = ~\$g",
            "i++; --j; k--",
            "a\n++b",
            "x = a / b / c",
            "x = (a) / 2",
            "let y = 1, z",
            "\$a = b ? c : d ? e : f",
            "\$q = `a${'$'}{`b${'$'}{c}`}`",
            "\$r = /[/]+/g.test(x)",
            "obj = {get: 1, set: 2, async: 3, static: 4, if: 5, 'k': 6, 7: 8, [k]: 9, m() {}, get g() {}, set s(v) {}, async am() {}, *gen() {}}",
            "throw new Error('x')",
            "\$open = \$el.contains(evt.target) ? \$open : false",
            "a = b\n(c)",
            "  \$count++  ",
            "// a comment\n\$x = 1 /* and another */",
        )
    }

    @Test
    fun `objects with acorn-like positions`() {
        bad("_post('/x'", pos = 10)
        bad("\$x +", pos = 4)
        bad("\$count +", pos = 8)
        bad("{count: }", pos = 8)
        bad("{n: , m: 1}", pos = 4)
        bad("[, 1] = 2", pos = 3, message = "Assigning to rvalue")
        bad("({a: 1} = 2)", message = "Assigning to rvalue")
        bad("({a: f()} = 2)", message = "Assigning to rvalue")
        bad("{count: 0, name: ''}", pos = 15)
        bad("< 1", pos = 0)
        bad("'Hello, ' + ", pos = 12)
        bad("1 = 2", pos = 0, message = "Assigning to rvalue")
        bad("a + b = c", pos = 0, message = "Assigning to rvalue")
        bad("f() = 1", pos = 0, message = "Assigning to rvalue")
        bad("(1)++", pos = 1, message = "Assigning to rvalue")
        bad("++1", pos = 2, message = "Assigning to rvalue")
        bad("'unterminated", pos = 0, message = "Unterminated string constant")
        bad("`unterminated", pos = 1, message = "Unterminated template")
        bad("/unterminated", pos = 1, message = "Unterminated regular expression")
        bad("/x/gg", message = "Duplicate regular expression flag")
        bad("/* open", message = "Unterminated comment")
        bad("a b", pos = 2)
        bad("if x", pos = 3)
        bad("x = ;", pos = 4)
        bad("f(,)", pos = 2)
        bad("{a: 1,, b: 2}", pos = 6)
        bad("a ? b", pos = 5)
        bad("a.", pos = 2)
        bad("a.1", pos = 1)
        bad("obj?.`x`")
        bad("x = 1a", message = "Identifier directly after number")
        bad("if (a) else b", pos = 7, message = "Unexpected keyword 'else'")
        bad("try {}", message = "Missing catch or finally clause")
        bad("-x ** 2", message = "Unary operator used immediately before exponentiation expression")
        bad("`\${}`")
        bad("`\${a +}`", pos = 6)
        bad("@post('/x')", pos = 0)
        bad("import x from 'y'")
        bad("x = 3 + ", pos = 8)
        bad("() ", pos = 1)
        bad("(a, b) => ", pos = 10)
        bad("class {}", pos = 6)
        bad("function () {}", pos = 9)
        bad("throw\nx", message = "Illegal newline after throw")
    }

    @Test
    fun `template expressions are validated at their offset`() {
        val e = JsParser.error("\$a = `x \${1 +} y`")!!
        assertEquals(13, e.pos)
    }

    @Test
    fun `a slash after a postfix operator divides`() {
        ok("\$a++ / 2", "\$a-- / 2 / 3", "x = a++ /b/ c", "a\n/b/g")
        bad("x = a++ /b/")
    }

    @Test
    fun `a slash where a statement begins opens a regular expression`() {
        ok(
            "if (\$a) /x/.test(\$b) && _get('/y')",
            "while (a) /x/.test(b)",
            "for (;;) /x/.test(b)",
            "with (a) /x/.test(b)",
            "function f() {} /b/",
            "x = /=/",
        )
    }

    @Test
    fun `accepts dynamic import`() {
        ok("import('/x.js').then(m => m.go())", "import('a', {with: {type: 'json'}})", "import('a',)")
        bad("import x from 'y'", pos = 0, message = "'import' is not allowed in a Datastar expression")
        bad("import('a', 'b', 'c')")
        bad("import.meta", message = "Cannot use 'import.meta' outside a module")
        bad("new import('x')")
    }

    @Test
    fun `the right side of for-in is a full expression`() {
        ok("for (let x in y, z) {}", "for (x in y, z) {}")
        bad("for (let x of y, z) {}")
    }

    @Test
    fun `identifiers may use characters beyond the basic plane`() {
        ok("𝒳 = 1", "\$a + 𠮷", "a.𝒳", "é́ = 1", "\\u0061b = 1", "var \\u{1d4b3}")
        bad("a\\u0020b", message = "Invalid Unicode escape")
        bad("v\\u0061r x", message = "Escape sequence in keyword var")
    }

    @Test
    fun `nullish coalescing does not mix with logical operators`() {
        ok("(\$a ?? \$b) || \$c", "\$a ?? (\$b || \$c)", "\$a ?? \$b ?? \$c", "\$a || \$b && \$c", "(\$a && \$b) ?? \$c")
        bad("\$a ?? \$b || \$c", pos = 9)
        bad("\$a || \$b ?? \$c", pos = 9)
        bad("\$a && \$b ?? \$c", pos = 9)
        bad("\$a ?? \$b && \$c", pos = 9)
    }

    @Test
    fun `an expression that stops short is said to end`() {
        // Where the text is over there is no token to be unexpected.
        bad("a +", pos = 3, message = "Unexpected end of input")
        bad("const x", pos = 7, message = "Unexpected end of input")
        bad("f('/x'", pos = 6, message = "Unexpected end of input")
        bad("a + )", pos = 4, message = "Unexpected token")
        val inMarkup = Analyzer().analyzeHtml("<button data-on:click=\"\$draft +\"></button>")
        assertEquals(listOf("Datastar expression: Unexpected end of input"), inMarkup.map { it.message })
        val inKotlin = Analyzer().analyzeKotlin("fun f() = \"<div data-on:click=\\\"@post('/x'\\\"></div>\"")
        assertEquals(listOf("Datastar expression: Unexpected end of input"), inKotlin.map { it.message })
    }

    @Test
    fun `a value that stops short is said to end, though the client closes a parenthesis after it`() {
        fun said(value: String) = Analyzer().analyzeHtml("<span data-text=\"$value\"></span>").map { it.message to it.start }
        // The client compiles a value as `return (...);`, and its `)` is no token of the text.
        assertEquals(listOf("Datastar expression: Unexpected end of input" to 20), said("1 +"))
        assertEquals(listOf("Datastar expression: Unexpected end of input" to 19), said("(1"))
        assertEquals(listOf("Datastar expression: Unexpected end of input" to 25), said("f(); 1 +"))
        assertEquals(listOf("Datastar expression: Unexpected token" to 21), said("1 + )"))
        assertEquals(listOf("Datastar expression: Unexpected token" to 19), said("1)"))
    }

    @Test
    fun `declarations need their initializer`() {
        ok("for (const x of y) {}", "for (const x in y) {}", "let a", "var [a] = b", "for (const [a, b] of c) {}")
        bad("const x", pos = 7)
        bad("const a = 1, b")
        bad("let {a}", message = "Complex binding patterns require an initialization value")
        bad("let [a]")
        bad("let let = 1", message = "let is disallowed as a lexically bound name")
        bad("for (const x = 1 of y) {}")
    }

    @Test
    fun `a name is declared once in its scope`() {
        ok("var x; var x", "let x; { let x }", "function f(a, a) {}", "try {} catch (e) { var e }", "let x = 1; x = 2")
        bad("let x = 1; let x = 2", pos = 15, message = "Identifier 'x' has already been declared")
        bad("var x; let x")
        bad("function f(a) { let a }")
        bad("(a, a) => 1", pos = 4, message = "Argument name clash")
        bad("({a, b: [a]}) => 1")
        bad("x = {m(a, a) {}}")
    }

    @Test
    fun `break and continue need a target`() {
        ok(
            "while (1) break",
            "for (;;) continue",
            "switch (\$x) { case 1: break }",
            "a: { break a }",
            "a: for (;;) { continue a }",
            "for (;;) switch (\$x) { case 1: continue }",
        )
        bad("break", pos = 0, message = "Unsyntactic break")
        bad("continue", pos = 0, message = "Unsyntactic continue")
        bad("break a")
        bad("switch (\$x) { case 1: continue }")
        bad("a: { continue a }")
        bad("while (1) { () => { break } }")
        bad("a: a: 1", message = "Label 'a' is already declared")
    }

    @Test
    fun `regular expression bodies are validated`() {
        ok(
            "/[/]+(?:a|b)(?<n>x)\\k<n>/",
            "/a{2,3}?/",
            "/\\p{Script=Greek}\\p{Lu}/u",
            "/[\\p{L}--[a-z]]/v",
            "/(?<a>x)|(?<a>y)/",
            "/(?i:a)/",
            "/\\1(a)/",
            "/}{/",
        )
        bad("/(/", pos = 1, message = "Invalid regular expression: /(/: Unterminated group")
        bad("/[/", pos = 1, message = "Unterminated regular expression")
        bad("/a]/u", message = "Invalid regular expression: /a]/: Lone quantifier brackets")
        bad("/a**/", message = "Invalid regular expression: /a**/: Nothing to repeat")
        bad("/+/")
        bad("/a{2,1}/")
        bad("/[z-a]/")
        bad("/)/")
        bad("/(?<a>x)(?<a>y)/")
        bad("/\\1/u")
        bad("/{/u")
        bad("/\\p{Nope}/u")
        bad("/a/x", message = "Invalid regular expression flag")
        bad("/a/uv")
    }

    @Test
    fun `escapes in strings and templates are validated`() {
        ok("'\\u0041\\x41\\u{10FFFF}\\0\\8'", "`\\u{1}\${a}\\x41`", "tag`\\u00\${a}\\xZ`", "'a\\\nb'")
        bad("'\\u00'", pos = 3, message = "Bad character escape sequence")
        bad("'\\x1'")
        bad("'\\u{110000}'", message = "Code point out of bounds")
        bad("'\\u{}'")
        bad("`\\u00`", pos = 1, message = "Bad escape sequence in untagged template literal")
        bad("`\\07`")
    }

    @Test
    fun `numeric literals are validated`() {
        ok("1_000.5_5e1_0", "0b1_0n + 0o7_7 + 0xF_Fn", "08.5 + 09 + 017", "1..toString()", "0n")
        bad("1.5n", pos = 3, message = "Identifier directly after number")
        bad("1e3n")
        bad("01n")
        bad("1__0", pos = 2, message = "Numeric separator must be exactly one underscore")
        bad("1_", pos = 1, message = "Numeric separator is not allowed at the last of digits")
        bad("0_1", pos = 1, message = "Numeric separator is not allowed in legacy octal numeric literals")
        bad("0x_1")
        bad("0b2", pos = 2, message = "Expected number in radix 2")
    }

    @Test
    fun `a switch has one default and starts with a clause`() {
        ok("switch (\$x) { case 1: case 2: break; default: }")
        bad("switch (\$x) { default: default: }", message = "Multiple default clauses")
        bad("switch (\$x) { y }")
    }

    @Test
    fun `patterns and values are told apart`() {
        ok(
            "({a = 1} = \$o)",
            "[{a = 1}] = \$o",
            "({a = 1}) => a",
            "(\$a) = 1",
            "(\$a.b) = 1",
            "[...a] = \$b",
            "({__proto__: a, __proto__: b} = \$o)",
        )
        bad("({a = 1})", pos = 4, message = "Shorthand property assignments are valid only in destructuring patterns")
        bad("x = {a = 1}")
        bad("x = {__proto__: 1, __proto__: 2}", message = "Redefinition of __proto__ property")
        bad("(a, b) = 1")
        bad("([a]) = 1")
        bad("this = 1")
        bad("[...a,] = c", message = "Comma is not permitted after the rest element")
        bad("({a: b.c}) => 1")
        bad("a?.b = 1", message = "Optional chaining cannot appear in left-hand side")
        bad("for (async of x) {}")
    }

    @Test
    fun `functions, classes and meta properties are checked in context`() {
        ok(
            "function f() { return new.target }",
            "class A extends B { constructor() { super(); super.x } }",
            "class A { #x; m() { return #x in this && this.#x } }",
            "x = {get a() { return 1 }, set a(v) {}}",
            "async () => await x",
            "function* g() { yield 1 }",
            "with (\$a) { b }",
        )
        // The top level is the body of the function Datastar compiles, where new.target is allowed.
        ok("new.target", "() => new.target")
        bad("super.x", message = "'super' keyword outside a method")
        bad("a.#x", message = "Private field '#x' must be declared in an enclosing class")
        bad("class A { constructor() {} constructor() {} }", message = "Duplicate constructor in the same class")
        bad("class A { m() { with (a) {} } }", message = "'with' in strict mode")
        bad("x = {get a(b) {}}", message = "getter should have no params")
        bad("x = {set a() {}}", message = "setter should have exactly one param")
        bad("new a?.b()")
        bad("() => await x")
        // Datastar compiles with Function(), which is not async: the browser refuses a top-level await too.
        bad("await _post('/save'); \$done = true", message = "Unexpected token")
        bad("enum = 1", message = "The keyword 'enum' is reserved")
    }

    @Test
    fun `comments end where acorn ends them`() {
        ok("<!-- x", "a\n--> b", "// c\u2028 1", "a --> b")
        bad("// c\u2028 1 +")
        bad("/* open", pos = 0, message = "Unterminated comment")
    }

    /** The last expression of [source]: each node's kind, its name or the literal as written, and the nodes it holds. */
    private fun tree(source: String): String {
        val parsed = JsParser.parse(source)
        assertNull(parsed.error, source)

        fun show(n: JsNode?): String {
            if (n == null) return "_"
            val literal = n.type in setOf(JsKind.NUMBER, JsKind.STRING, JsKind.REGEX) || (n.type == JsKind.TEMPLATE && n.items.isEmpty())
            val name =
                if (literal) {
                    source.substring(n.start, n.end)
                } else if (n.type == JsKind.PROPERTY) {
                    n.key?.name.orEmpty()
                } else {
                    n.name
                }
            val held = listOfNotNull(n.inner) + n.items
            return n.type.name.lowercase() + (if (name.isEmpty()) "" else ":$name") +
                if (held.isEmpty()) "" else held.joinToString(" ", "(", ")") { show(it) }
        }
        return show(parsed.nodes.last())
    }

    @Test
    fun `an expression is read into the tree of its parts`() {
        assertEquals("call(ident:f number:1 string:'a' regex:/x/ template:`t` word:null)", tree("f(1, 'a', /x/, `t`, null)"))
        assertEquals("binary:+(ident:a binary:*(ident:b ident:c))", tree("a + b * c"))
        assertEquals("binary:-(binary:-(ident:a ident:b) ident:c)", tree("a - b - c"))
        assertEquals("binary:**(number:2 binary:**(number:3 number:2))", tree("2 ** 3 ** 2"))
        assertEquals("unary:+(unary:-(number:1n))", tree("+-1n"))
        assertEquals("unary:typeof(update:++(ident:a))", tree("typeof a++"))
        assertEquals("conditional(ident:a string:'b' sequence(ident:c ident:d))", tree("a ? 'b' : (c, d)"))
        assertEquals("assign:+=(member:b(ident:a) number:1)", tree("a.b += 1"))
        assertEquals("chain(call(member:c(member(ident:a ident:b))))", tree("a[b]?.c()"))
        assertEquals("new(member:B(ident:A) number:1)", tree("new A.B(1)"))
        assertEquals("template(ident:a binary:+(ident:b number:1))", tree("`x\${a}y\${b + 1}`"))
        assertEquals("array(number:1 _ spread(ident:a))", tree("[1, , ...a]"))
    }

    @Test
    fun `a function called at once is a call, and a function is one however it is written`() {
        assertEquals("assign:=(ident:x call(function))", tree("x = function(){ return 1 }()"))
        assertEquals("assign:=(ident:x call(arrow number:1))", tree("x = (() => 2)(1)"))
        assertEquals("assign:=(ident:x member:name(function))", tree("x = function(){}.name"))
        assertEquals("assign:=(ident:x function)", tree("x = async function*(){}"))
        assertEquals("assign:=(ident:x arrow)", tree("x = async a => a"))
        assertEquals("assign:=(ident:x class)", tree("x = class {}"))
    }

    @Test
    fun `an object is read into its properties, each with its key and what it holds`() {
        assertEquals(
            "object(property:a(number:1) property:b c(ident:x) property:d(ident:d) property:e(function) property:f(function) " +
                "spread(ident:g) property(number:2))",
            tree("({a: 1, 'b c': x, d, e() {}, get f() { return 1 }, ...g, [h]: 2})"),
        )
    }

    @Test
    fun `a node runs from its first character to its last, without the parentheses around it`() {
        val source = "f( (1) , /*c*/ 'a' /*d*/, (b, c) )"
        val call = JsParser.parse(source).nodes.last()
        assertEquals(listOf("1", "'a'", "b, c"), call.items.map { source.substring(it!!.start, it.end) })
        assertEquals(source, source.substring(call.start, call.end))
    }

    @Test
    fun `every node stands after the nodes it holds`() {
        val source = "x = a ? [1, {b: `\${c}`, d() {}}] : f(g => -g.h, new I(...j)); k++ || (l, m)"
        val nodes = JsParser.parse(source).nodes
        assertEquals(true, nodes.any { it.type == JsKind.SEQUENCE })
        for ((at, node) in nodes.withIndex()) {
            for (held in listOfNotNull(node.inner) + node.items.filterNotNull()) {
                assertEquals(true, nodes.indexOf(held) in 0 until at, "${held.type} of ${node.type} at ${held.start}")
            }
        }
    }

    @Test
    fun `text that does not parse leaves its error and no node`() {
        val parsed = JsParser.parse("f(1, ")
        assertEquals("Unexpected end of input", parsed.error?.message)
        assertEquals(emptyList(), parsed.nodes)
    }

    private fun finished(source: String) = JsParser.parse(source).finished.map { it.type to source.substring(it.start, it.end) }

    @Test
    fun `what was read to its end before the error is handed out, and nothing after it`() {
        assertEquals(listOf(JsKind.IDENT to "f", JsKind.NUMBER to "1"), finished("f(1, "))
        assertEquals(JsKind.BINARY to "a == 'b'", finished("if (a == 'b'").last())
        // The comparison is not read to its end while what it compares with may go on.
        assertEquals(emptyList(), finished("if (a == 'b' +").filter { it.first == JsKind.BINARY })
        assertEquals(emptyList(), finished("x = ( ; a == 'b'").filter { it.first == JsKind.BINARY })
        val whole = JsParser.parse("a == 'b'")
        assertEquals(whole.nodes, whole.finished)
    }

    @Test
    fun `a case is a node at its colon, with what it compares and what the switch is on`() {
        val source = "switch (a.b) { case 'c': f(); default: g(); case (d) }"
        val parsed = JsParser.parse("switch (a.b) { case 'c': f(); default: g(); case (d): }")
        val cases = parsed.nodes.filter { it.type == JsKind.CASE }
        assertEquals(
            listOf("'c'" to "a.b", "d" to "a.b"),
            cases.map { c ->
                listOf(c.inner!!, c.items[0]!!).map { source.substring(it.start, it.end) }.let {
                    it[0] to
                        it[1]
                }
            },
        )
        // Before its colon it is none yet, and at the colon it is one whatever follows.
        assertEquals(1, JsParser.parse(source).finished.count { it.type == JsKind.CASE })
        assertEquals(1, JsParser.parse("switch (a) { case 'c': @").finished.count { it.type == JsKind.CASE })
    }

    @Test
    fun `an error says where the token before it starts`() {
        assertEquals(0 to 6, JsParser.error("await f")?.let { it.after to it.pos })
        assertEquals(0 to 12, JsParser.error("await /*c*/ 'abc")?.let { it.after to it.pos })
        assertEquals(-1, JsParser.error("(".repeat(200_000))?.after)
    }

    @Test
    fun `one expression is read whole, its last node the whole of it`() {
        val source = " {a: 1}, b // c"
        val whole = JsParser.expression(source).nodes.last()
        assertEquals(JsKind.SEQUENCE to "{a: 1}, b", whole.type to source.substring(whole.start, whole.end))
        assertEquals("Unexpected token", JsParser.expression("1) + (2").error?.message)
        assertEquals("Unexpected token", JsParser.expression("a; b").error?.message)
        assertEquals("Unexpected end of input", JsParser.expression("").error?.message)
    }

    @Test
    fun `a value is read from where it starts and as far as it goes`() {
        val source = "{t: 1 + 2, u: () => (, v: 'x'}"
        val value = JsParser.expression(source, 4).nodes.last()
        assertEquals(JsKind.BINARY to "1 + 2", value.type to source.substring(value.start, value.end))
        assertEquals(
            "'x'",
            JsParser
                .expression(source, 26)
                .nodes
                .last()
                .let { source.substring(it.start, it.end) },
        )
        assertEquals(emptyList(), JsParser.expression(source, 14).nodes)
        // A line break before the value is none of its own: `-->` opens a comment only at the start of a line.
        assertEquals(
            JsKind.BINARY,
            JsParser
                .expression("a\n: b-->c", 4)
                .nodes
                .last()
                .type,
        )
    }
}
