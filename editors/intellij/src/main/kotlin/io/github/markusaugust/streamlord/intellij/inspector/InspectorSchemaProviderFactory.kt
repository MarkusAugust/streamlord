package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/** The JSON schema of `.streamlord/inspector.json`, the same one the VS Code extension ships. */
class InspectorSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(Provider())

    private class Provider : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = file.name == "inspector.json" && file.parent?.name == ".streamlord"

        override fun getName(): String = "Streamlord inspector requests"

        override fun getSchemaFile(): VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(InspectorSchemaProviderFactory::class.java, "/inspector.schema.json")

        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}
