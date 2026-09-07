package top.wanxiang.app.harness.prompt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Loads packaged prompt assets and renders their required {{VARIABLES}} strictly. */
@Singleton
class PromptAssetLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val assetCache = ConcurrentHashMap<String, String>()

    fun read(path: String): String =
        assetCache.computeIfAbsent(path) {
            context.assets.open(path).bufferedReader().use { it.readText() }.trim()
        }

    fun render(path: String, variables: Map<String, String> = emptyMap()): String =
        renderTemplate(path, read(path), variables)

    fun renderTemplate(path: String, template: String, variables: Map<String, String>): String {
        val required = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()
        val missing = required - variables.keys
        check(missing.isEmpty()) {
            "Prompt asset $path is missing variables: ${missing.sorted().joinToString()}"
        }
        return required.fold(template) { rendered, name ->
            rendered.replace("{{$name}}", variables.getValue(name))
        }.trim()
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{([A-Z][A-Z0-9_]*)\\}\\}")
    }
}
