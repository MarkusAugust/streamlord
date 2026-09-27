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
            "await _post('/save'); \$done = true",
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
        bad("[, 1] = 2", pos = 0, message = "Assigning to rvalue")
        bad("({a: 1} = 2)", message = "Assigning to rvalue")
        bad("({a: f()} = 2)", message = "Assigning to rvalue")
        bad("{count: 0, name: ''}", pos = 15)
        bad("< 1", pos = 0)
        bad("'Hello, ' + ", pos = 12)
        bad("1 = 2", pos = 0, message = "Assigning to rvalue")
        bad("a + b = c", pos = 0, message = "Assigning to rvalue")
        bad("f() = 1", pos = 0, message = "Assigning to rvalue")
        bad("(1)++", pos = 0, message = "Assigning to rvalue")
        bad("++1", pos = 2, message = "Assigning to rvalue")
        bad("'unterminated", pos = 0, message = "Unterminated string constant")
        bad("`unterminated", pos = 0, message = "Unterminated template")
        bad("/unterminated", pos = 0, message = "Unterminated regular expression")
        bad("/x/gg", message = "Invalid regular expression flag")
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
}
