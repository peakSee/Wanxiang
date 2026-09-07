package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/** Versioned envelope for future remote registries; the legacy JSON array remains readable. */
@Serializable
data class ToolRegistryDocument(
    val schemaVersion: Int = 1,
    val version: Int = 1,
    val tools: List<ToolManifest> = emptyList(),
)
