import com.acme.interpolate
import io.github.markusaugust.streamlord.core.domain.interpolate as fmt

fun f(x: String): String {
    val t = fmt("<p>%s</p>", x)
    return "<div>$t</div>"
}
