import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

// A Kotlin template or a %s leaves the rest of the call checked; a comma at the top of postExpr's expression.
fun t(id: Int) = "<button data-on:click=\"@get('/a/$id', {retyr: 'never'})\">x</button>"

fun u(id: Int) = interpolate("<button data-on:click=\"@get('/a/%s', {retyr: 'never'})\">x</button>", id).text

fun FlowContent.v() {
    button { dataOnClick(postExpr("'/a', {retyr: 1}")) }
}
