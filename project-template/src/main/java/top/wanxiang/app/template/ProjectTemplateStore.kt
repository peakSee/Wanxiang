package top.wanxiang.app.template

import top.wanxiang.app.core.common.files.SafeFileTree
import android.content.Context
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

data class InstalledProjectTemplate(
    val manifest: ProjectTemplateManifest,
    val directory: File,
    val previewFile: File? = null,
    val bundledAssetPath: String? = null,
) {
    val isBundled: Boolean get() = bundledAssetPath != null
}

/**
 * Repository for portable `.zip` project templates. Imported packages are data-only here:
 * lifecycle hooks are stored but never executed without an explicit trust decision by a caller.
 */
@Singleton
class ProjectTemplateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val templatesDir = File(context.filesDir, "linux-runtime/templates")
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

    fun list(): List<InstalledProjectTemplate> {
        val installed = templatesDir.walkTopDown()
            .filter { it.isDirectory && File(it, ProjectTemplateManifest.MANIFEST_PATH).isFile }
            .mapNotNull { directory -> runCatching { readInstalledTemplate(directory) }.getOrNull() }
            .toList()
        val bundled = discoverBundledDirectories().mapNotNull { relativePath ->
            runCatching {
                val manifest = context.assets.open("templates/$relativePath/${ProjectTemplateManifest.MANIFEST_PATH}")
                    .bufferedReader(Charsets.UTF_8).use { json.decodeFromString<ProjectTemplateManifest>(it.readText()) }
                validateManifest(manifest, allowBuiltinId = true)
                listOf(manifest.hooks.beforeCreate, manifest.hooks.afterCreate).filter(String::isNotBlank).forEach { hookPath ->
                    context.assets.open("templates/$relativePath/$hookPath").use { input ->
                        require(input.readBytes().size <= MAX_HOOK_BYTES) { "模板脚本过大：$hookPath" }
                    }
                }
                val syncedDirectory = File(templatesDir, relativePath)
                fun bundledPreview(path: String): File {
                    require(path.substringAfterLast('.', "").lowercase() in PREVIEW_EXTENSIONS) {
                        "模板预览图仅支持 PNG、JPEG、WebP 或 GIF"
                    }
                    val previewFile = File(syncedDirectory, path).takeIf(File::isFile) ?: run {
                        val suffix = path.substringAfterLast('.', "png")
                        val cacheName = path.substringBeforeLast('.').substringAfterLast('/').ifBlank { "preview" }
                        val cached = File(context.cacheDir, "template-previews/${manifest.id}-$cacheName.$suffix")
                        cached.parentFile?.mkdirs()
                        context.assets.open("templates/$relativePath/$path").use { input ->
                            cached.outputStream().use(input::copyTo)
                        }
                        cached
                    }
                    require(previewFile.length() <= MAX_PREVIEW_BYTES) { "模板预览图过大" }
                    validatePreviewDimensions(previewFile)
                    return previewFile
                }
                val preview = manifest.previewImage.takeIf(String::isNotBlank)?.let(::bundledPreview)
                InstalledProjectTemplate(
                    manifest = manifest,
                    directory = syncedDirectory,
                    previewFile = preview,
                    bundledAssetPath = "templates/$relativePath",
                )
            }.getOrNull()
        }
        return (installed + bundled)
            .distinctBy { it.manifest.id }
            .sortedWith(
            compareBy<InstalledProjectTemplate> { it.manifest.projectType.ordinal }
                .thenBy { it.manifest.category.sortOrder }
                .thenBy { it.manifest.category.name.lowercase() }
                .thenBy { it.manifest.name.lowercase() },
            )
    }

    fun find(id: String): InstalledProjectTemplate? = list().firstOrNull { it.manifest.id == id }

    fun hookScripts(id: String): List<Pair<String, String>> {
        val template = find(id) ?: error("模板不存在：$id")
        return listOf(template.manifest.hooks.beforeCreate, template.manifest.hooks.afterCreate)
            .filter(String::isNotBlank)
            .distinct()
            .map { relativePath ->
                val normalized = relativePath.replace('\\', '/')
                require(!normalized.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(normalized) &&
                    normalized.startsWith("template-hooks/") && normalized.split('/').none { it == ".." }
                ) {
                    "模板脚本路径无效"
                }
                val bytes = template.bundledAssetPath?.let { root ->
                    context.assets.open("$root/$normalized").use { it.readBytes() }
                } ?: File(template.directory, normalized).canonicalFile.also { file ->
                    require(file.path.startsWith(template.directory.canonicalPath + File.separator) && file.isFile) {
                        "模板脚本不存在：$relativePath"
                    }
                }.readBytes()
                require(bytes.size <= MAX_HOOK_BYTES) { "模板脚本过大：$relativePath" }
                relativePath to bytes.toString(Charsets.UTF_8)
            }
    }

    fun importZip(input: InputStream): InstalledProjectTemplate {
        templatesDir.mkdirs()
        val staging = File(templatesDir, ".import-${UUID.randomUUID()}").canonicalFile
        check(staging.mkdirs()) { "无法创建模板导入暂存目录" }
        try {
            extractSafely(input, staging)
            val roots = staging.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            val packageRoot = roots.singleOrNull()?.takeIf(File::isDirectory)?.let { nested ->
                if (File(nested, ProjectTemplateManifest.MANIFEST_PATH).isFile) nested else staging
            } ?: staging
            val installed = readInstalledTemplate(packageRoot)
            require(ID_PATTERN.matches(installed.manifest.id)) { "模板 id 不合法：${installed.manifest.id}" }
            require(!installed.manifest.id.startsWith(BUILTIN_ID_PREFIX)) { "模板 id 不能使用保留前缀：$BUILTIN_ID_PREFIX" }
            require(installed.manifest.schemaVersion == ProjectTemplateManifest.CURRENT_SCHEMA_VERSION) {
                "不支持的模板规范版本：${installed.manifest.schemaVersion}"
            }
            val typeDirectory = installed.manifest.projectType.name.lowercase()
            val target = File(templatesDir, "$typeDirectory/${installed.manifest.id}").canonicalFile
            check(target.path.startsWith(templatesDir.canonicalPath + File.separator)) { "模板目标路径越界" }
            target.parentFile?.mkdirs()
            check(!target.exists()) { "模板已存在：${installed.manifest.id}" }
            check(packageRoot.renameTo(target)) { "无法安装模板：${installed.manifest.id}" }
            return readInstalledTemplate(target)
        } finally {
            SafeFileTree.delete(staging)
        }
    }

    fun exportZip(id: String, output: OutputStream) {
        val template = find(id) ?: error("模板不存在：$id")
        ZipOutputStream(output.buffered()).use { zip ->
            fun write(relative: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry("${template.manifest.id}/$relative"))
                zip.write(bytes)
                zip.closeEntry()
            }
            val assetRoot = template.bundledAssetPath
            if (assetRoot != null) {
                fun visit(assetPath: String, relativePath: String) {
                    val children = context.assets.list(assetPath).orEmpty()
                    if (children.isEmpty()) {
                        context.assets.open(assetPath).use { write(relativePath, it.readBytes()) }
                    } else children.forEach { child ->
                        visit("$assetPath/$child", if (relativePath.isBlank()) child else "$relativePath/$child")
                    }
                }
                visit(assetRoot, "")
            } else {
                template.directory.walkTopDown().filter(File::isFile).forEach { file ->
                    write(file.relativeTo(template.directory).invariantSeparatorsPath, file.readBytes())
                }
            }
        }
    }

    fun delete(id: String) {
        val template = find(id) ?: error("模板不存在：$id")
        require(!template.isBundled) { "内置模板不能删除" }
        require(template.directory.path.startsWith(templatesDir.canonicalPath + File.separator)) { "模板目录越界" }
        SafeFileTree.delete(template.directory)
        check(!template.directory.exists()) { "模板删除失败：$id" }
    }

    private fun readInstalledTemplate(directory: File): InstalledProjectTemplate {
        val manifestFile = File(directory, ProjectTemplateManifest.MANIFEST_PATH)
        val manifest = json.decodeFromString<ProjectTemplateManifest>(manifestFile.readText(Charsets.UTF_8))
        validateManifest(manifest, allowBuiltinId = false)
        val canonicalDirectory = directory.canonicalFile
        fun installedPreview(relativePath: String): File {
            require(!File(relativePath).isAbsolute && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(relativePath.replace('\\', '/')) &&
                relativePath.replace('\\', '/').split('/').none { it == ".." }
            ) {
                "模板预览图路径越界"
            }
            require(relativePath.substringAfterLast('.', "").lowercase() in PREVIEW_EXTENSIONS) {
                "模板预览图仅支持 PNG、JPEG、WebP 或 GIF"
            }
            return File(canonicalDirectory, relativePath).canonicalFile.also { file ->
                require(file.path.startsWith(canonicalDirectory.path + File.separator) && file.isFile) {
                    "模板预览图不存在"
                }
                require(file.length() <= MAX_PREVIEW_BYTES) { "模板预览图过大" }
                validatePreviewDimensions(file)
            }
        }
        val preview = manifest.previewImage.takeIf(String::isNotBlank)?.let(::installedPreview)
        listOf(manifest.hooks.beforeCreate, manifest.hooks.afterCreate).filter(String::isNotBlank).forEach { hookPath ->
            val hook = File(canonicalDirectory, hookPath).canonicalFile
            require(hook.path.startsWith(canonicalDirectory.path + File.separator) && hook.isFile) {
                "模板脚本不存在：$hookPath"
            }
            require(hook.length() <= MAX_HOOK_BYTES) { "模板脚本过大：$hookPath" }
        }
        return InstalledProjectTemplate(manifest, canonicalDirectory, preview)
    }

    private fun validateManifest(manifest: ProjectTemplateManifest, allowBuiltinId: Boolean) {
        require(manifest.schemaVersion == ProjectTemplateManifest.CURRENT_SCHEMA_VERSION) {
            "不支持的模板规范版本：${manifest.schemaVersion}"
        }
        require(ID_PATTERN.matches(manifest.id)) { "模板 id 不合法：${manifest.id}" }
        require(allowBuiltinId || !manifest.id.startsWith(BUILTIN_ID_PREFIX)) { "模板 id 使用了保留前缀" }
        require(CATEGORY_ID_PATTERN.matches(manifest.category.id)) { "模板分类 id 不合法" }
        require(manifest.variables.map { it.name }.distinct().size == manifest.variables.size) { "模板变量名不能重复" }
        manifest.variables.forEach { variable ->
            require(VARIABLE_NAME_PATTERN.matches(variable.name)) { "模板变量名不合法：${variable.name}" }
            require(variable.name !in FIXED_VARIABLES || !variable.prompt) {
                "项目名称和路径由工坊统一提供，不能声明为动态字段：${variable.name}"
            }
            require(variable.validationRegex.isBlank() || runCatching { Regex(variable.validationRegex) }.isSuccess) {
                "模板变量正则无效：${variable.name}"
            }
            if (variable.inputType == ProjectTemplateInputType.SELECT) {
                require(variable.options.isNotEmpty()) { "选择变量必须提供选项：${variable.name}" }
                require(variable.options.map { it.value }.distinct().size == variable.options.size) {
                    "选择变量的选项值不能重复：${variable.name}"
                }
            }
            if (!variable.prompt && variable.required && variable.defaultValue.isBlank()) {
                require(variable.name in DERIVED_VARIABLES) { "隐藏必填变量必须提供默认值：${variable.name}" }
            }
        }
        fun validateTemplatePath(path: String) {
            val normalized = path.replace('\\', '/')
            require(normalized.isNotBlank() && !normalized.startsWith('/') &&
                !WINDOWS_ABSOLUTE_PATH.containsMatchIn(normalized) && normalized.split('/').none { it == ".." }
            ) { "模板校验路径无效：$path" }
        }
        manifest.validation.requiredFiles.forEach(::validateTemplatePath)
        manifest.validation.forbiddenFiles.forEach(::validateTemplatePath)
        manifest.validation.contentRules.forEach { rule -> validateTemplatePath(rule.path) }
        require(manifest.validation.requiredFiles.distinct().size == manifest.validation.requiredFiles.size) {
            "模板必需文件不能重复"
        }
        require(manifest.validation.forbiddenFiles.distinct().size == manifest.validation.forbiddenFiles.size) {
            "模板禁止文件不能重复"
        }
        listOf(manifest.hooks.beforeCreate, manifest.hooks.afterCreate).filter(String::isNotBlank).forEach { hook ->
            val normalized = hook.replace('\\', '/')
            require(!normalized.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(normalized) &&
                normalized.startsWith("template-hooks/") && normalized.split('/').none { it == ".." }
            ) {
                "模板脚本必须位于 template-hooks/ 目录"
            }
        }
    }

    private fun validatePreviewDimensions(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "模板预览图无法解码" }
        require(bounds.outWidth == PREVIEW_SIZE && bounds.outHeight == PREVIEW_SIZE) {
            "模板预览图必须为 ${PREVIEW_SIZE}×${PREVIEW_SIZE} 像素（1:1）"
        }
    }

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

    private fun extractSafely(input: InputStream, targetDir: File) {
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "模板包文件数量过多" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(name)) {
                    "模板包包含绝对路径"
                }
                require(name.isNotBlank() && name.split('/').none { it == ".." }) { "模板包包含越界路径" }
                val target = File(targetDir, name).canonicalFile
                require(target.path.startsWith(targetDir.canonicalPath + File.separator)) { "模板包包含越界路径" }
                if (entry.isDirectory) {
                    check(target.isDirectory || target.mkdirs()) { "无法创建模板目录" }
                } else {
                    check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            fileBytes += read
                            totalBytes += read
                            require(fileBytes <= MAX_FILE_BYTES && totalBytes <= MAX_TOTAL_BYTES) { "模板包体积过大" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(entries > 0) { "模板包为空" }
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
        val CATEGORY_ID_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
        val VARIABLE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*$")
        val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/")
        const val BUILTIN_ID_PREFIX = "builtin."
        val DERIVED_VARIABLES = setOf(
            "projectName", "appName", "packageName", "packagePath", "projectPath",
        )
        val FIXED_VARIABLES = setOf("projectName", "projectPath", "packagePath")
        val PREVIEW_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
        const val MAX_ENTRIES = 5_000
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024
        const val MAX_HOOK_BYTES = 1024L * 1024
        const val PREVIEW_SIZE = 270
    }
}
