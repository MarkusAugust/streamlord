import io.github.markusaugust.streamlord.core.domain.*
fun f(): Html {
    val cdn = Trusted("https://cdn.example.com/x.js")
    return interpolate("<script src=\"%s\"></script>", cdn)
}
