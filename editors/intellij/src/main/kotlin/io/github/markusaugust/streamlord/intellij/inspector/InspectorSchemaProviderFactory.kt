package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * The JSON schemas of `.streamlord/inspector.json` and `.streamlord/env.json`, the same ones the
 * VS Code extension ships.
 */
class InspectorSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf(
            Provider("inspector.json", "Streamlord inspector requests", "/inspector.schema.json"),
            Provider("env.json", "Streamlord inspector variables", "/env.schema.json"),
        )

    private class Provider(
        private val fileName: String,
        private val title: String,
        private val resource: String,
    ) : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = file.name == fileName && file.parent?.name == ".streamlord"

        override fun getName(): String = title

        override fun getSchemaFile(): VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(InspectorSchemaProviderFactory::class.java, resource)

        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}
