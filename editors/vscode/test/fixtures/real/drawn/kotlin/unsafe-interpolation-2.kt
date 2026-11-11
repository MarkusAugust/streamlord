import io.github.markusaugust.streamlord.core.domain.*
fun f(q: String): Html {
    val name = Trusted("data-on:click")
    return interpolate("<button %s=\"%s\">Go</button>", name, q)
}
