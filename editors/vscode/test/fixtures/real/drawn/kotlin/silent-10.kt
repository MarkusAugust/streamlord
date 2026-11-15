import io.github.markusaugust.streamlord.core.domain.Trusted as T
import io.github.markusaugust.streamlord.core.domain.interpolate

fun f(x: String) = interpolate("<script>%s</script>", T(x))
