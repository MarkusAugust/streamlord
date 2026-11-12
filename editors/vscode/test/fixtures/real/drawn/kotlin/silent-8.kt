import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.html.*
fun f(q: String): String {
    val e = elements { p { +q } }
    return "<div>$e</div>"
}
