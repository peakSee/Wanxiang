package top.wanxiang.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wanxiang.app.feature.chat.R
import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.ToolCall
import top.wanxiang.app.harness.ToolResult
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName

private val DiffAddedBg = Color(0xFF0E2E1E)
private val DiffAddedText = Color(0xFF7EE787)
private val DiffRemovedBg = Color(0xFF38141B)
private val DiffRemovedText = Color(0xFFFFA198)
private val TerminalBoxBg = Color(0xFF070B12)

@Composable
fun ToolDiffView(
    call: ToolCall,
    result: ToolResult?,
    workspace: String = "",
    onOpenFile: ((projectName: String, relativePath: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF090D14), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (call.tool) {
            HarnessTool.EDIT -> EditToolDiff(call, result, workspace, onOpenFile)
            HarnessTool.WRITE -> WriteToolDiff(call, result, workspace, onOpenFile)
            HarnessTool.READ -> ReadToolDiff(call, result, workspace, onOpenFile)
            HarnessTool.BASE -> BaseToolDiff(call, result)
            HarnessTool.PROCESS, HarnessTool.HOST, HarnessTool.DOWNLOAD, HarnessTool.MEMORY, HarnessTool.PLAN, HarnessTool.SCRATCHPAD,
            HarnessTool.HISTORY_SEARCH, HarnessTool.HISTORY_READ, HarnessTool.BUILD_SCRIPT, HarnessTool.SUBAGENT, HarnessTool.MCP,
            HarnessTool.LOAD_RULE -> BaseToolDiff(call, result)
        }
    }
}

@Composable
private fun EditToolDiff(
    call: ToolCall,
    result: ToolResult?,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)?,
) {
    val path = call.args["path"]?.jsonPrimitive?.content.orEmpty()
    val oldText = call.args["oldText"]?.jsonPrimitive?.content.orEmpty()
    val newText = call.args["newText"]?.jsonPrimitive?.content.orEmpty()

    // 顶部路径与在编辑器中打开/产物预览按钮
    FilePathHeader(path = path, workspace = workspace, onOpenFile = onOpenFile, previewContent = newText.ifEmpty { oldText })

    val oldLines = remember(oldText) { if (oldText.isEmpty()) emptyList() else oldText.split('\n') }
    val newLines = remember(newText) { if (newText.isEmpty()) emptyList() else newText.split('\n') }

    // Diff 区域：LazyColumn 虚拟化渲染，大 diff 只组合可见行，不再整块全量重组
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF06090F), RoundedCornerShape(6.dp))
            .heightIn(max = 320.dp)
            .padding(vertical = 4.dp),
    ) {
        itemsIndexed(oldLines, key = { index, _ -> "old-$index" }) { _, line ->
            DiffLine(text = "- $line", background = DiffRemovedBg, foreground = DiffRemovedText)
        }
        itemsIndexed(newLines, key = { index, _ -> "new-$index" }) { _, line ->
            DiffLine(text = "+ $line", background = DiffAddedBg, foreground = DiffAddedText)
        }
    }

    // 执行状态提示
    result?.let {
        if (!it.success) {
            Text(
                text = it.output,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DiffLine(text: String, background: Color, foreground: Color) {
    Text(
        text = text,
        color = foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun WriteToolDiff(
    call: ToolCall,
    result: ToolResult?,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)?,
) {
    val path = call.args["path"]?.jsonPrimitive?.content.orEmpty()
    val content = call.args["content"]?.jsonPrimitive?.content.orEmpty()

    FilePathHeader(path = path, workspace = workspace, onOpenFile = onOpenFile, previewContent = content)

    val lineCount = remember(content) { content.count { it == '\n' } + 1 }
    Text(
        text = stringResource(R.string.chat_full_file_write, lineCount, content.length),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // 内容预览（前 10 行）
    val preview = content.lines().take(10).joinToString("\n") +
        if (lineCount > 10) stringResource(R.string.chat_total_lines, lineCount) else ""

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF06090F), RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(
            text = preview,
            color = Color(0xFFDCE6F5),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }

    result?.let {
        if (!it.success) {
            Text(it.output, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReadToolDiff(
    call: ToolCall,
    result: ToolResult?,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)?,
) {
    val path = call.args["path"]?.jsonPrimitive?.content.orEmpty()
    FilePathHeader(path = path, workspace = workspace, onOpenFile = onOpenFile, previewContent = result?.output)

    result?.let { res ->
        if (res.success) {
            val preview = res.output.lines().take(8).joinToString("\n") +
                if (res.output.lines().size > 8) stringResource(R.string.chat_characters_read, res.output.length) else ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF06090F), RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(
                    text = preview,
                    color = Color(0xFF8FA1BA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        } else {
            Text(res.output, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BaseToolDiff(
    call: ToolCall,
    result: ToolResult?,
) {
    val command = call.args["command"]?.jsonPrimitive?.content
        ?: call.args["cmd"]?.jsonPrimitive?.content
        ?: call.args["text"]?.jsonPrimitive?.content
        ?: call.args.toString()

    // 命令行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF06090F), RoundedCornerShape(4.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$",
            color = Color(0xFF7EE787),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = command,
            color = Color(0xFFDCE6F5),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }

    // 执行输出
    result?.let { res ->
        val output = res.output.trim()
        if (output.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBoxBg, RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = output.take(2000) + if (output.length > 2000) stringResource(R.string.chat_output_truncated) else "",
                    color = if (res.success) Color(0xFFDCE6F5) else Color(0xFFFF8896),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FilePathHeader(
    path: String,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)?,
    previewContent: String? = null,
) {
    val context = LocalContext.current
    var showPreviewSheet by remember { mutableStateOf(false) }

    val defaultFileName = remember(path) {
        val raw = path.substringAfterLast('/')
        if (raw.isNotBlank()) raw else "file.txt"
    }
    val extension = remember(path) { path.substringAfterLast('.', "").lowercase() }
    val mimeType = remember(extension) {
        when (extension) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "txt", "kt", "java", "py", "c", "cpp", "h", "rs", "go", "js", "ts", "sh", "yaml", "yml", "gradle" -> "text/plain"
            else -> "text/plain"
        }
    }

    // SAF 外部目录导出选择器
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType),
    ) { uri ->
        if (uri != null && previewContent != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(previewContent.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: error("无法打开目标文件流")
            }.onSuccess {
                Toast.makeText(context, context.getString(R.string.chat_artifact_export_success), Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, context.getString(R.string.chat_artifact_export_failed, err.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showPreviewSheet && !previewContent.isNullOrEmpty()) {
        top.wanxiang.app.ui.chat.artifact.ArtifactPreviewSheet(
            title = path.substringAfterLast('/'),
            content = previewContent,
            relativePath = path,
            workspace = workspace,
            onDismiss = { showPreviewSheet = false },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            RuntimeIcon(RuntimeIconName.File, Modifier.size(15.dp), tint = Color(0xFF8FA1BA))
            Text(
                text = path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFDCE6F5),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 解析工作区项目名与相对路径，提供跳转
        val projectName = remember(workspace, path) {
            when {
                workspace.startsWith("/workspace/") -> workspace.removePrefix("/workspace/").substringBefore('/')
                path.startsWith("/workspace/") -> path.removePrefix("/workspace/").substringBefore('/')
                else -> ""
            }
        }
        val relativePath = remember(workspace, path, projectName) {
            when {
                path.startsWith("/workspace/$projectName/") -> path.removePrefix("/workspace/$projectName/")
                path.startsWith("/workspace/") -> path.substringAfter("/workspace/")
                path.startsWith("/") -> path.removePrefix("/")
                else -> path
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!previewContent.isNullOrEmpty()) {
                Surface(
                    color = Color(0xFF1B2C47),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.minimumInteractiveComponentSize().clickable { showPreviewSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Document, Modifier.size(12.dp), tint = Color(0xFF79C0FF))
                        Text(
                            text = stringResource(R.string.chat_artifact_preview),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF79C0FF),
                        )
                    }
                }

                Surface(
                    color = Color(0xFF1B2C47),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.minimumInteractiveComponentSize().clickable {
                        exportDocumentLauncher.launch(defaultFileName)
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Download, Modifier.size(12.dp), tint = Color(0xFF79C0FF))
                        Text(
                            text = stringResource(R.string.chat_artifact_export_short),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF79C0FF),
                        )
                    }
                }
            }

            if (projectName.isNotBlank() && onOpenFile != null) {
                Surface(
                    color = Color(0xFF1E283D),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.minimumInteractiveComponentSize().clickable { onOpenFile(projectName, relativePath) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Code, Modifier.size(12.dp), tint = Color(0xFF7EE787))
                        Text(
                            text = stringResource(R.string.chat_open_editor),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF7EE787),
                        )
                    }
                }
            }
        }
    }
}
