package forge.views

import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

// A Datastar attribute written twice on an element of a string: the browser keeps the first.
val repeatedInAString = "<div data-show=\"true\" data-show=\"false\"></div>"

val repeatedInARawString = """<p data-text="1" data-text="2"></p>"""

// Nothing is lost here: other names, and the same value again.
val writtenOnce = "<div data-class:a=\"true\" data-class:b=\"false\" data-show=\"true\" data-show=\"true\"></div>"

// The DSL refuses the second call as the page renders, and the editors say nothing of it.
fun FlowContent.repeatedInTheDsl() {
    div {
        dataShow("true")
        dataShow("false")
    }
}
