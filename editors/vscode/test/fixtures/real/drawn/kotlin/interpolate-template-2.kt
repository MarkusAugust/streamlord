import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.html.*
fun f(q: String): Html {
    val e = elements { p { +q } }
    return interpolate("<div>%s</div>", e)
}
