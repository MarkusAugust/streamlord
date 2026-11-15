import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

fun f(x: String) = interpolate(
    "<script>%s</script>",
    Trusted(x) // id
)
