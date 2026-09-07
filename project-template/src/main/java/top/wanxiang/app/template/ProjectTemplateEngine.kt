package top.wanxiang.app.template

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class ProjectTemplateEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val templatesDir = File(context.filesDir, "linux-runtime/templates")
    private val json = Json { ignoreUnknownKeys = false }
    private val bundledDirectories: Map<String, String> by lazy {
        runCatching {
            discoverBundledDirectories().associateBy { relativePath ->
                val bytes = context.assets.open("templates/$relativePath/${ProjectTemplateManifest.MANIFEST_PATH}")
                    .use { it.readBytes() }
                json.decodeFromString<ProjectTemplateManifest>(bytes.toString(Charsets.UTF_8)).id
            }
        }.getOrDefault(emptyMap())
    }

    fun inspect(id: String): ProjectTemplateManifest {
        val source = resolveSource(id)
        val manifest = json.decodeFromString<ProjectTemplateManifest>(source.read(ProjectTemplateManifest.MANIFEST_PATH).toString(Charsets.UTF_8))
        require(manifest.id == id) { "模板标识不匹配：${manifest.id}" }
        require(manifest.schemaVersion == ProjectTemplateManifest.CURRENT_SCHEMA_VERSION) {
            "不支持的模板规范版本：${manifest.schemaVersion}"
        }
        return manifest
    }

    fun resolvedValues(manifest: ProjectTemplateManifest, supplied: Map<String, String>): Map<String, String> =
        manifest.variables.associate { variable ->
            variable.name to if (variable.name in supplied) supplied.getValue(variable.name) else variable.defaultValue
        } + supplied

    fun materialize(id: String, projectDir: File, values: Map<String, String>): ProjectTemplateManifest {
        val source = resolveSource(id)
        val manifest = inspect(id)
        val resolvedValues = resolvedValues(manifest, values)
        validateVariables(manifest, resolvedValues)

        fun replaceVariables(text: String): String {
            var result = text
            resolvedValues.forEach { (name, value) -> result = result.replace("{{$name}}", value) }
            val unresolved = PLACEHOLDER.find(result)?.value
            require(unresolved == null) { "模板包含未赋值变量：$unresolved" }
            return result
        }

        fun outputPath(relativePath: String): String {
            val packagePath = resolvedValues["packagePath"].orEmpty()
            return replaceVariables(
                relativePath
                    .replace("WANXIANG_PACKAGE_PATH", packagePath),
            ).removeSuffix(".template")
        }

        fun write(relativePath: String, bytes: ByteArray) {
            val normalizedPath = relativePath.replace('\\', '/')
            val previewPaths = listOf(manifest.previewImage)
                .filter(String::isNotBlank)
                .map { it.replace('\\', '/') }
                .toSet()
            if (relativePath == ProjectTemplateManifest.MANIFEST_PATH ||
                normalizedPath.startsWith("template-hooks/") ||
                normalizedPath in previewPaths
            ) return
            val target = File(projectDir, outputPath(relativePath)).canonicalFile
            require(target.path.startsWith(projectDir.canonicalPath + File.separator)) { "模板路径越界：$relativePath" }
            target.parentFile?.mkdirs()
            if (isTextFile(relativePath, target.name)) {
                target.writeText(replaceVariables(bytes.toString(Charsets.UTF_8)), Charsets.UTF_8)
            } else target.writeBytes(bytes)
        }

        val sourceFiles = source.files().toList()
        val validationPaths = manifest.validation.requiredFiles + manifest.validation.forbiddenFiles +
            manifest.validation.contentRules.map { it.path }
        require(
            (sourceFiles + validationPaths).none { "WANXIANG_PACKAGE_PATH" in it } ||
                resolvedValues["packagePath"].orEmpty().isNotBlank(),
        ) { "模板使用了 WANXIANG_PACKAGE_PATH，但没有声明 packageName 或 packagePath" }
        sourceFiles.forEach { relativePath -> write(relativePath, source.read(relativePath)) }
        return manifest
    }

    fun validateMaterialized(id: String, projectDir: File, values: Map<String, String>) {
        val manifest = inspect(id)
        val resolvedValues = resolvedValues(manifest, values)
        validateVariables(manifest, resolvedValues)
        fun replaceVariables(text: String): String {
            var result = text
            resolvedValues.forEach { (name, value) -> result = result.replace("{{$name}}", value) }
            val unresolved = PLACEHOLDER.find(result)?.value
            require(unresolved == null) { "模板包含未赋值变量：$unresolved" }
            return result
        }
        fun outputPath(relativePath: String): String = replaceVariables(
            relativePath.replace("WANXIANG_PACKAGE_PATH", resolvedValues["packagePath"].orEmpty()),
        ).removeSuffix(".template")
        validateOutput(manifest, projectDir, ::outputPath, ::replaceVariables)
    }

    fun readHook(id: String, relativePath: String): ByteArray {
        val normalized = relativePath.replace('\\', '/')
        require(!normalized.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(normalized) &&
            normalized.startsWith("template-hooks/") && normalized.split('/').none { it == ".." }
        ) {
            "模板脚本必须位于 template-hooks/ 目录"
        }
        return resolveSource(id).read(normalized)
    }

    private fun validateVariables(manifest: ProjectTemplateManifest, values: Map<String, String>) {
        manifest.variables.forEach { variable ->
            val value = values[variable.name].orEmpty()
            require(!variable.required || value.isNotBlank()) { "模板变量不能为空：${variable.label}" }
            if (value.isNotBlank() && variable.validationRegex.isNotBlank()) {
                // NOTE: validationRegex 来自模板清单（可能由外部导入），坏正则会抛
                // PatternSyntaxException；统一按"校验不通过"处理而非崩溃。
                val valid = runCatching { Regex(variable.validationRegex).matches(value) }.getOrDefault(false)
                require(valid) { "模板变量格式无效：${variable.label}" }
            }
            if (variable.inputType == ProjectTemplateInputType.SELECT && value.isNotBlank()) {
                require(variable.options.any { it.value == value }) { "模板变量选项无效：${variable.label}" }
            }
        }
    }

    private fun resolveSource(id: String): TemplateSource = bundledDirectories[id]?.let { relativePath ->
        AssetTemplateSource("templates/$relativePath")
    } ?: DirectoryTemplateSource(findInstalledDirectory(id))

    private fun discoverBundledDirectories(): List<String> {
        val result = mutableListOf<String>()
        fun visit(relativePath: String) {
            val assetPath = if (relativePath.isBlank()) "templates" else "templates/$relativePath"
            val children = context.assets.list(assetPath).orEmpty()
            if (ProjectTemplateManifest.MANIFEST_PATH in children) {
                result += relativePath
            } else children.forEach { child ->
                val childRelative = if (relativePath.isBlank()) child else "$relativePath/$child"
                if (context.assets.list("templates/$childRelative").orEmpty().isNotEmpty()) visit(childRelative)
            }
        }
        visit("")
        return result
    }

    private fun findInstalledDirectory(id: String): File = templatesDir.walkTopDown()
        .filter(File::isDirectory)
        .firstOrNull { directory ->
            val manifest = File(directory, ProjectTemplateManifest.MANIFEST_PATH)
            manifest.isFile && runCatching {
                json.decodeFromString<ProjectTemplateManifest>(manifest.readText(Charsets.UTF_8)).id == id
            }.getOrDefault(false)
        } ?: error("项目模板不可用：$id")

    private fun validateOutput(
        manifest: ProjectTemplateManifest,
        projectDir: File,
        outputPath: (String) -> String,
        replaceVariables: (String) -> String,
    ) {
        fun validatedFile(relativePath: String): File {
            val file = File(projectDir, outputPath(relativePath)).canonicalFile
            require(file.path.startsWith(projectDir.canonicalPath + File.separator)) {
                "模板校验路径越界：$relativePath"
            }
            return file
        }
        manifest.validation.requiredFiles.forEach { relativePath ->
            require(validatedFile(relativePath).isFile) { "模板缺少必需文件：${outputPath(relativePath)}" }
        }
        manifest.validation.forbiddenFiles.forEach { relativePath ->
            require(!validatedFile(relativePath).exists()) { "模板生成了禁止文件：${outputPath(relativePath)}" }
        }
        manifest.validation.contentRules.forEach { rule ->
            val relativePath = outputPath(rule.path)
            val file = validatedFile(rule.path)
            require(file.isFile) { "模板内容校验文件不存在：$relativePath" }
            val content = file.readText(Charsets.UTF_8)
            if (rule.equals.isNotEmpty()) {
                require(content.trim() == replaceVariables(rule.equals)) { "模板文件内容不符合要求：$relativePath" }
            }
            rule.contains.forEach { expected ->
                require(replaceVariables(expected) in content) { "模板文件缺少必需内容：$relativePath" }
            }
            rule.excludes.forEach { forbidden ->
                require(replaceVariables(forbidden) !in content) { "模板文件包含禁止内容：$relativePath" }
            }
        }
    }

    private fun isTextFile(relativePath: String, name: String): Boolean = relativePath.endsWith(".template") ||
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS || name in TEXT_FILE_NAMES

    private interface TemplateSource {
        fun read(relativePath: String): ByteArray
        fun files(): Sequence<String>
    }

    private inner class AssetTemplateSource(private val root: String) : TemplateSource {
        override fun read(relativePath: String): ByteArray =
            context.assets.open("$root/$relativePath").use { it.readBytes() }

        override fun files(): Sequence<String> = sequence {
            suspend fun SequenceScope<String>.visit(assetPath: String, relativePath: String) {
                val children = context.assets.list(assetPath).orEmpty()
                if (children.isEmpty()) yield(relativePath) else children.forEach { child ->
                    visit("$assetPath/$child", if (relativePath.isBlank()) child else "$relativePath/$child")
                }
            }
            visit(root, "")
        }
    }

    private class DirectoryTemplateSource(private val root: File) : TemplateSource {
        override fun read(relativePath: String): ByteArray {
            val file = File(root, relativePath).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) { "模板文件不存在：$relativePath" }
            return file.readBytes()
        }

        override fun files(): Sequence<String> = root.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{[A-Za-z][A-Za-z0-9_]*\\}\\}")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/")
        private val TEXT_EXTENSIONS = setOf(
            "c", "cc", "cpp", "css", "dart", "gradle", "h", "hpp", "html", "java", "js", "json",
            "kt", "kts", "md", "properties", "pro", "py", "rs", "sh", "toml", "ts", "txt", "xml",
            "yaml", "yml",
        )
        private val TEXT_FILE_NAMES = setOf(
            "Dockerfile", "Gemfile", "Makefile", "Podfile", "analysis_options.yaml", "gradlew", "pubspec.yaml",
            "xposed_init",
        )
    }
}
