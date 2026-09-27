package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.markusaugust.streamlord.analysis.Route
import io.github.markusaugust.streamlord.analysis.findRoutes
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtFile

/** "Open in Stream Inspector" in the gutter of every Ktor route (`get("/feed")`) and Spring mapping (`@GetMapping("/feed")`). */
class RouteLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is LeafPsiElement) return null
        val file = element.containingFile as? KtFile ?: return null
        if (!StreamlordSettings.getInstance(file.project).state.routeMarkers) return null
        val offset = element.textRange.startOffset
        val route = routesOf(file).firstOrNull { it.offset == offset } ?: return null
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Lightning,
            { "Open in Stream Inspector · ${route.method} ${route.path}" },
            { _, _ -> InspectorService.getInstance(file.project).openWithRoute(route) },
            GutterIconRenderer.Alignment.LEFT,
            { "Open in Stream Inspector · ${route.method} ${route.path}" },
        )
    }

    private fun routesOf(file: KtFile): List<Route> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(findRoutes(file.text), file)
        }
}
