package interpolationtests

import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

/*
 * Every position a Kotlin value can land in an HTML string, each with every
 * kind of value: `query`, text from the request; `n`, a number; `maybe`, a
 * number that may be null; `flag`, a boolean; `html`, markup interpolate
 * built; `variant`, a const val; and a literal.
 *
 * The rule is interpolate's: element text, a quoted attribute and a URL take
 * anything, a Datastar attribute a number or a boolean, `style` a number, and
 * the structure of the markup and code the browser runs nothing. Null only
 * goes where anything does. A const val and a literal are the author's own and
 * say nothing anywhere; markup is the author's own only in element text.
 *
 * The comment above each line says what the editor must say there: a code and
 * a severity, or nothing.
 */
object EveryPosition {
    fun elementText(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: nothing
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p>$query</p>
            <p>$n</p>
            <p>$maybe</p>
            <p>$flag</p>
            <p>$html</p>
            <p>$variant</p>
            <p>${"lit"}</p>
        </div>"""
    }

    fun quotedAttribute(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning (markup in an attribute is characters that can end it)
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p title="$query"></p>
            <p title="$n"></p>
            <p title="$maybe"></p>
            <p title="$flag"></p>
            <p title="$html"></p>
            <p title="$variant"></p>
            <p title="${"lit"}"></p>
        </div>"""
    }

    fun singleQuotedAttribute(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p title='$query'></p>
            <p title='$n'></p>
            <p title='$maybe'></p>
            <p title='$flag'></p>
            <p title='$html'></p>
            <p title='$variant'></p>
            <p title='${"lit"}'></p>
        </div>"""
    }

    fun partOfClassList(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p class="lt-paragraph $query"></p>
            <p class="lt-paragraph col-$n"></p>
            <p class="lt-paragraph col-$maybe"></p>
            <p class="lt-paragraph is-$flag"></p>
            <p class="lt-paragraph $html"></p>
            <p class="lt-paragraph $variant"></p>
            <p class="lt-paragraph ${"lit"}"></p>
        </div>"""
    }

    fun urlAfterPath(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning (the / settles the scheme)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="/x/$query">x</a>
            <a href="/x/$n">x</a>
            <a href="/x/$maybe">x</a>
            <a href="/x/$flag">x</a>
            <a href="/x/$html">x</a>
            <a href="/x/$variant">x</a>
            <a href="/x/${"lit"}">x</a>
        </div>"""
    }

    fun urlAfterFragment(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning (the # settles the scheme)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="#$query">x</a>
            <a href="#$n">x</a>
            <a href="#$maybe">x</a>
            <a href="#$flag">x</a>
            <a href="#$html">x</a>
            <a href="#$variant">x</a>
            <a href="#${"lit"}">x</a>
        </div>"""
    }

    fun urlAfterQuery(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning (the ? settles the scheme)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="?q=$query">x</a>
            <a href="?q=$n">x</a>
            <a href="?q=$maybe">x</a>
            <a href="?q=$flag">x</a>
            <a href="?q=$html">x</a>
            <a href="?q=$variant">x</a>
            <a href="?q=${"lit"}">x</a>
        </div>"""
    }

    fun urlAfterHttps(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning (https: settles the scheme)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="https://example.com/$query">x</a>
            <a href="https://example.com/$n">x</a>
            <a href="https://example.com/$maybe">x</a>
            <a href="https://example.com/$flag">x</a>
            <a href="https://example.com/$html">x</a>
            <a href="https://example.com/$variant">x</a>
            <a href="https://example.com/${"lit"}">x</a>
        </div>"""
    }

    fun urlWhole(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (the value decides the scheme)
        // $n: nothing (digits are a relative URL)
        // $maybe: nothing (digits or null, both relative)
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="$query">x</a>
            <a href="$n">x</a>
            <a href="$maybe">x</a>
            <a href="$flag">x</a>
            <a href="$html">x</a>
            <a href="$variant">x</a>
            <a href="${"lit"}">x</a>
        </div>"""
    }

    fun urlAfterJava(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (java + script: is javascript:)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="java$query">x</a>
            <a href="java$n">x</a>
            <a href="java$maybe">x</a>
            <a href="java$flag">x</a>
            <a href="java$html">x</a>
            <a href="java$variant">x</a>
            <a href="java${"lit"}">x</a>
        </div>"""
    }

    fun urlAfterSpace(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (the browser skips the leading space)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href=" $query">x</a>
            <a href=" $n">x</a>
            <a href=" $maybe">x</a>
            <a href=" $flag">x</a>
            <a href=" $html">x</a>
            <a href=" $variant">x</a>
            <a href=" ${"lit"}">x</a>
        </div>"""
    }

    fun urlAfterEntity(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (&#106; is a j, so the scheme is still open)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <a href="&#106;$query">x</a>
            <a href="&#106;$n">x</a>
            <a href="&#106;$maybe">x</a>
            <a href="&#106;$flag">x</a>
            <a href="&#106;$html">x</a>
            <a href="&#106;$variant">x</a>
            <a href="&#106;${"lit"}">x</a>
        </div>"""
    }

    fun svgAnimateValues(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (values on animate can set an href)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // $query after "/a;": unsafe-interpolation, warning (each entry of the list starts a URL)
        // language=HTML
        return """<svg><a>
            <animate attributeName="href" values="$query"/>
            <animate attributeName="href" values="$n"/>
            <animate attributeName="href" values="$maybe"/>
            <animate attributeName="href" values="$flag"/>
            <animate attributeName="href" values="$html"/>
            <animate attributeName="href" values="$variant"/>
            <animate attributeName="href" values="${"lit"}"/>
            <animate attributeName="href" values="/a;$query"/>
        </a></svg>"""
    }

    fun svgAnimateTo(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<svg><a>
            <set attributeName="href" to="$query"/>
            <set attributeName="href" to="$n"/>
            <set attributeName="href" to="$maybe"/>
            <set attributeName="href" to="$flag"/>
            <set attributeName="href" to="$html"/>
            <set attributeName="href" to="$variant"/>
            <set attributeName="href" to="${"lit"}"/>
        </a></svg>"""
    }

    fun svgAnimateFrom(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<svg><a>
            <animate attributeName="href" from="$query" to="/"/>
            <animate attributeName="href" from="$n" to="/"/>
            <animate attributeName="href" from="$maybe" to="/"/>
            <animate attributeName="href" from="$flag" to="/"/>
            <animate attributeName="href" from="$html" to="/"/>
            <animate attributeName="href" from="$variant" to="/"/>
            <animate attributeName="href" from="${"lit"}" to="/"/>
        </a></svg>"""
    }

    fun styleAttribute(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: nothing (a number is all style takes)
        // $maybe: unsafe-interpolation, warning (null is not a number)
        // $flag: unsafe-interpolation, warning (a boolean is not a number)
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p style="color: $query"></p>
            <p style="width: ${n}px"></p>
            <p style="width: ${maybe}px"></p>
            <p style="order: $flag"></p>
            <p style="color: $html"></p>
            <p style="color: $variant"></p>
            <p style="color: ${"lit"}"></p>
        </div>"""
    }

    fun datastarWhole(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: kotlin-interpolation, warning (a server value or the signal $query: the whole expression)
        // $n: kotlin-interpolation, hint (a number is safe; only the signal $n is asked about)
        // $maybe: kotlin-interpolation, warning
        // $flag: kotlin-interpolation, hint (a boolean is safe; only the signal $flag is asked about)
        // $html: kotlin-interpolation, warning
        // $variant: kotlin-interpolation, warning
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p data-text="$query"></p>
            <p data-text="$n"></p>
            <p data-text="$maybe"></p>
            <p data-text="$flag"></p>
            <p data-text="$html"></p>
            <p data-text="$variant"></p>
            <p data-text="${"lit"}"></p>
        </div>"""
    }

    fun datastarPart(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: kotlin-interpolation, warning (a server value or the signal $query: inside the expression)
        // $n: kotlin-interpolation, hint (a number is safe; only the signal $n is asked about)
        // $maybe: kotlin-interpolation, warning
        // $flag: kotlin-interpolation, hint (a boolean is safe; only the signal $flag is asked about)
        // $html: kotlin-interpolation, warning
        // $variant: kotlin-interpolation, warning
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p data-text="$query + '!'"></p>
            <p data-text="$n + 1"></p>
            <p data-text="$maybe + 1"></p>
            <p data-text="!$flag"></p>
            <p data-text="$html + '!'"></p>
            <p data-text="$variant + '!'"></p>
            <p data-text="${"lit"} + '!'"></p>
        </div>"""
    }

    fun datastarJsString(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (a ' ends the string and the rest runs)
        // $n: nothing
        // $maybe: unsafe-interpolation, warning (null is not a number)
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <button data-on:click="alert('$query')">x</button>
            <button data-on:click="alert('$n')">x</button>
            <button data-on:click="alert('$maybe')">x</button>
            <button data-on:click="alert('$flag')">x</button>
            <button data-on:click="alert('$html')">x</button>
            <button data-on:click="alert('$variant')">x</button>
            <button data-on:click="alert('${"lit"}')">x</button>
        </div>"""
    }

    fun datastarBraced(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // ${query}: unsafe-interpolation, warning
        // ${n}: nothing
        // ${maybe}: unsafe-interpolation, warning
        // ${flag}: nothing
        // ${html}: unsafe-interpolation, warning
        // ${variant}: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p data-text="${query}"></p>
            <p data-text="${n}"></p>
            <p data-text="${maybe}"></p>
            <p data-text="${flag}"></p>
            <p data-text="${html}"></p>
            <p data-text="${variant}"></p>
            <p data-text="${"lit"}"></p>
        </div>"""
    }

    fun signalPathBind(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (a path the client follows, __proto__ included)
        // $n: nothing
        // $maybe: unsafe-interpolation, warning
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <input data-bind="$query">
            <input data-bind="$n">
            <input data-bind="$maybe">
            <input data-bind="$flag">
            <input data-bind="$html">
            <input data-bind="$variant">
            <input data-bind="${"lit"}">
        </div>"""
    }

    fun signalPathRef(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: nothing
        // $maybe: unsafe-interpolation, warning
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p data-ref="$query"></p>
            <p data-ref="$n"></p>
            <p data-ref="$maybe"></p>
            <p data-ref="$flag"></p>
            <p data-ref="$html"></p>
            <p data-ref="$variant"></p>
            <p data-ref="${"lit"}"></p>
        </div>"""
    }

    fun signalPathIndicator(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: nothing
        // $maybe: unsafe-interpolation, warning
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p data-indicator="$query"></p>
            <p data-indicator="$n"></p>
            <p data-indicator="$maybe"></p>
            <p data-indicator="$flag"></p>
            <p data-indicator="$html"></p>
            <p data-indicator="$variant"></p>
            <p data-indicator="${"lit"}"></p>
        </div>"""
    }

    fun signalsObject(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // ${query}: unsafe-interpolation, warning
        // ${n}: nothing (a server-rendered initial signal)
        // ${maybe}: unsafe-interpolation, warning
        // ${flag}: nothing
        // ${html}: unsafe-interpolation, warning
        // ${variant}: nothing
        // ${"lit"}: nothing
        // $n: kotlin-interpolation, hint (the server value, a number, is safe; or the signal $n)
        // language=HTML
        return """<div>
            <p data-signals="{n: ${query}}"></p>
            <p data-signals="{n: ${n}}"></p>
            <p data-signals="{n: ${maybe}}"></p>
            <p data-signals="{n: ${flag}}"></p>
            <p data-signals="{n: ${html}}"></p>
            <p data-signals="{n: ${variant}}"></p>
            <p data-signals="{n: ${"lit"}}"></p>
            <p data-signals="{n: $n}"></p>
        </div>"""
    }

    fun signalFilter(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (a filter Datastar evaluates as a regular expression)
        // $n: nothing
        // $maybe: unsafe-interpolation, warning
        // $flag: nothing
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <pre data-json-signals="{include: /$query/}"></pre>
            <pre data-json-signals="{include: /$n/}"></pre>
            <pre data-json-signals="{include: /$maybe/}"></pre>
            <pre data-json-signals="{include: /$flag/}"></pre>
            <pre data-json-signals="{include: /$html/}"></pre>
            <pre data-json-signals="{include: /$variant/}"></pre>
            <pre data-json-signals="{include: /${"lit"}/}"></pre>
        </div>"""
    }

    fun datastarKey(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (it names the event: an attribute name)
        // $n: unsafe-interpolation, hint (interpolate refuses any value in an attribute name)
        // $maybe: unsafe-interpolation, hint
        // $flag: unsafe-interpolation, hint
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <button data-on:$query="@get('/x')">x</button>
            <button data-on:key$n="@get('/x')">x</button>
            <button data-on:key$maybe="@get('/x')">x</button>
            <button data-on:key$flag="@get('/x')">x</button>
            <button data-on:$html="@get('/x')">x</button>
            <button data-on:$variant="@get('/x')">x</button>
            <button data-on:${"lit"}="@get('/x')">x</button>
        </div>"""
    }

    fun scriptBody(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: unsafe-interpolation, warning (interpolate takes nothing in a script)
        // $maybe: unsafe-interpolation, warning
        // $flag: unsafe-interpolation, warning
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<script>
            const a = "$query";
            const b = $n;
            const c = $maybe;
            const d = $flag;
            const e = `$html`;
            const f = "$variant";
            const g = "${"lit"}";
        </script>"""
    }

    fun styleBody(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: unsafe-interpolation, warning
        // $maybe: unsafe-interpolation, warning
        // $flag: unsafe-interpolation, warning
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<style>
            .a { color: $query }
            .b { width: ${n}px }
            .c { width: ${maybe}px }
            .d { order: $flag }
            .e { content: "$html" }
            .f { color: $variant }
            .g { color: ${"lit"} }
        </style>"""
    }

    fun eventHandler(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: unsafe-interpolation, warning
        // $maybe: unsafe-interpolation, warning
        // $flag: unsafe-interpolation, warning
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <button onclick="alert('$query')">x</button>
            <button onclick="alert($n)">x</button>
            <button onclick="alert($maybe)">x</button>
            <button onclick="alert($flag)">x</button>
            <button onclick="alert('$html')">x</button>
            <button onclick="alert('$variant')">x</button>
            <button onclick="alert('${"lit"}')">x</button>
        </div>"""
    }

    fun srcdoc(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (srcdoc is parsed as a document)
        // $n: unsafe-interpolation, warning
        // $maybe: unsafe-interpolation, warning
        // $flag: unsafe-interpolation, warning
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <iframe srcdoc="<p>$query</p>"></iframe>
            <iframe srcdoc="<p>$n</p>"></iframe>
            <iframe srcdoc="<p>$maybe</p>"></iframe>
            <iframe srcdoc="<p>$flag</p>"></iframe>
            <iframe srcdoc="$html"></iframe>
            <iframe srcdoc="<p>$variant</p>"></iframe>
            <iframe srcdoc="<p>${"lit"}</p>"></iframe>
        </div>"""
    }

    fun unquotedValue(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning, with a fix that quotes the value
        // $n: unsafe-interpolation, hint, with the same fix (digits cannot start an attribute; interpolate still refuses)
        // $maybe: unsafe-interpolation, hint, with the fix
        // $flag: unsafe-interpolation, hint, with the fix
        // $html: unsafe-interpolation, warning, with the fix
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p title=$query></p>
            <p title=$n></p>
            <p title=$maybe></p>
            <p title=$flag></p>
            <p title=$html></p>
            <p title=$variant></p>
            <p title=${"lit"}></p>
        </div>"""
    }

    fun tagName(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning; the end tag that repeats it: nothing
        // $n: unsafe-interpolation, hint (interpolate refuses any value in a tag name)
        // $maybe: unsafe-interpolation, hint
        // $flag: unsafe-interpolation, hint
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <$query>x</$query>
            <h$n>x</h$n>
            <h$maybe>x</h$maybe>
            <x-$flag>x</x-$flag>
            <$html>x</$html>
            <$variant>x</$variant>
            <${"lit"}>x</${"lit"}>
        </div>"""
    }

    fun endTag(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning (an end tag no start tag named)
        // $n: unsafe-interpolation, hint
        // $maybe: unsafe-interpolation, hint
        // $flag: unsafe-interpolation, hint
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <b>x</$query>
            <b>x</h$n>
            <b>x</h$maybe>
            <b>x</x-$flag>
            <b>x</$html>
            <b>x</$variant>
            <b>x</${"lit"}>
        </div>"""
    }

    fun attributeName(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: unsafe-interpolation, hint
        // $maybe: unsafe-interpolation, hint
        // $flag: unsafe-interpolation, hint
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p $query="x"></p>
            <p a$n="x"></p>
            <p a$maybe="x"></p>
            <p a$flag="x"></p>
            <p $html="x"></p>
            <p $variant="x"></p>
            <p ${"lit"}="x"></p>
        </div>"""
    }

    fun partOfAttributeName(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: unsafe-interpolation, hint
        // $maybe: unsafe-interpolation, hint
        // $flag: unsafe-interpolation, hint
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p data-$query="x"></p>
            <p data-x$n="x"></p>
            <p data-x$maybe="x"></p>
            <p data-x$flag="x"></p>
            <p data-$html="x"></p>
            <p data-$variant="x"></p>
            <p data-${"lit"}="x"></p>
        </div>"""
    }

    fun attributeList(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unsafe-interpolation, warning
        // $n: unsafe-interpolation, hint
        // $maybe: unsafe-interpolation, hint
        // $flag: unsafe-interpolation, hint
        // $html: unsafe-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <p $query></p>
            <p $n></p>
            <p $maybe></p>
            <p $flag></p>
            <p $html></p>
            <p $variant></p>
            <p ${"lit"}></p>
        </div>"""
    }

    fun htmlComment(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning (escaping keeps a --> from ending the comment)
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: nothing (markup, written as it stands, as interpolate does)
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <!-- $query -->
            <!-- $n -->
            <!-- $maybe -->
            <!-- $flag -->
            <!-- $html -->
            <!-- $variant -->
            <!-- ${"lit"} -->
        </div>"""
    }

    fun textarea(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: nothing
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<div>
            <textarea>$query</textarea>
            <textarea>$n</textarea>
            <textarea>$maybe</textarea>
            <textarea>$flag</textarea>
            <textarea>$html</textarea>
            <textarea>$variant</textarea>
            <textarea>${"lit"}</textarea>
        </div>"""
    }

    fun title(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // $query: unescaped-interpolation, warning
        // $n: nothing
        // $maybe: nothing
        // $flag: nothing
        // $html: nothing
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<head>
            <title>$query</title>
            <title>$n</title>
            <title>$maybe</title>
            <title>$flag</title>
            <title>$html</title>
            <title>$variant</title>
            <title>${"lit"}</title>
        </head>"""
    }

    fun svgTitle(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // In an svg, a title holds elements, so their attributes are attributes.
        // $query in text: unescaped-interpolation, warning
        // $query in a title attribute: unescaped-interpolation, warning
        // ${query} in data-text: unsafe-interpolation, warning
        // $n: nothing
        // $maybe in data-text: unsafe-interpolation, warning
        // $flag: nothing
        // $html in a title attribute: unescaped-interpolation, warning
        // $variant: nothing
        // ${"lit"}: nothing
        // language=HTML
        return """<svg>
            <title>$query</title>
            <title><tspan title="$query">x</tspan></title>
            <title><tspan data-text="${query}">x</tspan></title>
            <title><tspan data-text="${n}">x</tspan></title>
            <title><tspan data-text="${maybe}">x</tspan></title>
            <title><tspan data-text="${flag}">x</tspan></title>
            <title><tspan title="$html">x</tspan></title>
            <title><tspan title="$variant">x</tspan></title>
            <title><tspan title="${"lit"}">x</tspan></title>
        </svg>"""
    }

    fun multiDollar(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // In a $$ literal one $ is text, so $open is a signal and every $$name is Kotlin's.
        // $$query: unescaped-interpolation, warning
        // $$n: nothing
        // $$maybe: nothing
        // $$flag: nothing
        // $$html: nothing
        // $$variant: nothing
        // $${"lit"}: nothing
        // $$query in data-text: unsafe-interpolation, warning
        // $$n in data-text: nothing
        // language=HTML
        return $$"""<div data-show="$open">
            <p>$$query</p>
            <p>$$n</p>
            <p>$$maybe</p>
            <p>$$flag</p>
            <p>$$html</p>
            <p>$$variant</p>
            <p>$${"lit"}</p>
            <p data-text="$open + '$$query'"></p>
            <p data-text="$open + $$n"></p>
        </div>"""
    }

    fun plusChain(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // query: unescaped-interpolation, warning
        // n: nothing
        // maybe: nothing
        // flag: nothing
        // html: nothing
        // variant: nothing
        // "lit": nothing
        // query in style: unsafe-interpolation, warning
        // maybe in style: unsafe-interpolation, warning
        return listOf(
            "<p>" + query + "</p>",
            "<p>" + n + "</p>",
            "<p>" + maybe + "</p>",
            "<p>" + flag + "</p>",
            "<p>" + html + "</p>",
            "<p>" + variant + "</p>",
            "<p>" + "lit" + "</p>",
            "<p style=\"color: " + query + "\"></p>",
            "<p style=\"width: " + maybe + "px\"></p>",
        ).joinToString("")
    }

    fun nestedLiterals(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // The outer template holds markup the author built, silent in element text; the literal inside is judged.
        // $query inside: unescaped-interpolation, warning
        // $n inside: nothing
        // $maybe inside: nothing
        // $flag inside: nothing
        // $html inside: nothing
        // $variant inside: nothing
        // ${"lit"} inside: nothing
        // The markup in a title attribute: unescaped-interpolation, warning; $query inside it: unescaped-interpolation, warning
        // language=HTML
        return """<div>
            ${if (flag) "<b>$query</b>" else ""}
            ${if (flag) "<b>$n</b>" else ""}
            ${if (flag) "<b>$maybe</b>" else ""}
            ${if (flag) "<b>$flag</b>" else ""}
            ${if (flag) "<b>$html</b>" else ""}
            ${if (flag) "<b>$variant</b>" else ""}
            ${if (flag) "<b>${"lit"}</b>" else ""}
            <p title="${if (flag) "<b>$query</b>" else ""}"></p>
        </div>"""
    }

    fun markupValues(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // ${Trusted(query)} in text: nothing (markup the author vouches for)
        // ${interpolate(...)} in text: nothing
        // $html in text: nothing
        // ${html.text} in text: nothing
        // ${Trusted(query)} in an attribute: unescaped-interpolation, warning
        // ${interpolate(...)} in an attribute: unescaped-interpolation, warning
        // ${html.text} in an attribute: unescaped-interpolation, warning
        // ${Trusted(query)} in data-text: unsafe-interpolation, warning
        // ${interpolate(...)} in a script: unsafe-interpolation, warning
        // language=HTML
        return """<div>
            <p>${Trusted(query)}</p>
            <p>${interpolate("<b>%s</b>", query)}</p>
            <p>$html</p>
            <p>${html.text}</p>
            <p title="${Trusted(query)}"></p>
            <p title="${interpolate("<b>%s</b>", query)}"></p>
            <p title="${html.text}"></p>
            <p data-text="${Trusted(query)}"></p>
            <script>const x = "${interpolate("<b>%s</b>", n)}"</script>
        </div>"""
    }

    fun ifBranches(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // Literal branches: nothing, in text and in style
        // A branch that is the query: unescaped-interpolation, warning in text; unsafe-interpolation, warning in style
        // Number branches: nothing, in text and in style
        // A branch that may be null: nothing in text; unsafe-interpolation, warning in style
        // A branch that is markup: nothing in text; unescaped-interpolation, warning in an attribute
        // language=HTML
        return """<div>
            <p>${if (flag) "on" else "off"}</p>
            <p style="color: ${if (flag) "red" else "blue"}"></p>
            <p>${if (flag) "nothing" else query}</p>
            <p style="color: ${if (flag) "red" else query}"></p>
            <p>${if (flag) n else 0}</p>
            <p style="width: ${if (flag) n else 0}px"></p>
            <p>${if (flag) maybe else 0}</p>
            <p style="width: ${if (flag) maybe else 0}px"></p>
            <p>${if (flag) html else ""}</p>
            <p title="${if (flag) html else ""}"></p>
        </div>"""
    }

    fun whenBranches(query: String, n: Int, maybe: Int?, flag: Boolean, html: Html): String {
        // Literal branches: nothing, in text and in style
        // A branch that is the query: unescaped-interpolation, warning in text; unsafe-interpolation, warning in style
        // Number branches: nothing in style
        // A branch that may be null: unsafe-interpolation, warning in style
        // language=HTML
        return """<div>
            <p>${when (n) { 0 -> "none"; 1 -> "one"; else -> "many" }}</p>
            <p style="color: ${when { flag -> "red"; else -> "blue" }}"></p>
            <p>${when (n) { 0 -> "none"; else -> query }}</p>
            <p style="color: ${when (n) { 0 -> "red"; else -> query }}"></p>
            <p style="width: ${when (n) { 0 -> 1; else -> n }}px"></p>
            <p style="width: ${when (n) { 0 -> 1; else -> maybe }}px"></p>
        </div>"""
    }
}
