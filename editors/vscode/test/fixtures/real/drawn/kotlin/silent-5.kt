import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

fun f(x: String, y: String) = interpolate("<script>%s</script><p>%s</p>", Trusted(x) /* id */, y)
