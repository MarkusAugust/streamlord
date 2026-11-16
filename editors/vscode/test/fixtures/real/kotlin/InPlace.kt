import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

class LayoutContext(val pageId: String, val sectionId: String, val streamId: String)

// Each value marked where it stands: nothing to say.
fun pageHtml(k: LayoutContext, pageStyle: String? = null, mainHtml: Html): Html = interpolate {
    $$"""
    <div id="$${+k.pageId}" data-theme="light" data-ui-boundary="">
        <style>$${+Trusted(pageStyle.orEmpty())}</style>
        <div id="$${+k.sectionId}">
            $${+mainHtml}
        </div>
        <div id="$${+k.streamId}" hidden></div>
    </div>
    """.trimIndent()
}

fun rows(names: List<String>): Html = interpolate { """<ul>${names.joinToString("") { "<li>${+it}</li>" }}</ul>""" }

// A marked value where interpolate refuses one.
fun refusedInExpression(name: String): Html = interpolate { """<p data-text="${+name}"></p>""" }

fun refusedInStyle(css: String): Html = interpolate { """<style>${+css}</style>""" }

fun refusedForItsHost(src: String): Html = interpolate { """<script src="${+src}"></script>""" }

// A value written without the mark.
fun unmarked(title: String): Html = interpolate { """<p title="$title">${title}</p>""" }

fun unmarkedInManyDollars(title: String): Html = interpolate { $$"""<p data-text="$count">$$title</p>""" }
