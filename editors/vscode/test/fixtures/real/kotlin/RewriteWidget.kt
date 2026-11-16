package forge.rewrite

import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

class LayoutContext(val pageId: String, val sectionId: String, val streamId: String)

// A trim call after the literal, a value in the body of a <style>, and four values interpolate keeps.
fun pageHtml(k: LayoutContext, pageStyle: String? = null, mainHtml: String): String =
    """
    <div id="${k.pageId}" data-theme="light" data-ui-boundary="">
        <style>${pageStyle.orEmpty()}</style>
        <div id="${k.sectionId}">
            $mainHtml
        </div>
        <div id="${k.streamId}" hidden></div>
    </div>
    """.trimIndent()
