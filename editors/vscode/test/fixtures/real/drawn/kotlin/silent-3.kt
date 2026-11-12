import io.github.markusaugust.streamlord.core.domain.Trusted as T
import io.github.markusaugust.streamlord.core.domain.interpolate

fun f(x: String): String {
    val t = `T`(x)
    return interpolate("<script>%s</script>", t).text
}
