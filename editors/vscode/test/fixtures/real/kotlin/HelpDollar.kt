import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

// A signal written after Kotlin's dollar, and an expression helper.
fun dollar() = "<div data-on:click=\"${'$'}myCount++\"></div>"

fun FlowContent.helper(id: Int) {
    button { dataOnClick(getExpr("'/a/' + ${'$'}id")) }
}
