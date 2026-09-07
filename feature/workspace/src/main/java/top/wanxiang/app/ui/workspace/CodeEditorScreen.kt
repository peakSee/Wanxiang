package top.wanxiang.app.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import top.wanxiang.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wanxiang.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.feature.workspace.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.RuntimeAlertDialog

private val EditorBackground = Color(0xFF0F1117)
private val EditorGutterBackground = Color(0xFF181A22)
private val EditorText = Color(0xFFE2E2E9)
private val EditorGutterText = Color(0xFF8E9099)
private val EditorAccent = Color(0xFFBAC3FF)
private val EditorWarning = Color(0xFFFFB5A0)
private const val MIN_EDITOR_FONT_SIZE_SP = 10f
private const val MAX_EDITOR_FONT_SIZE_SP = 28f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CodeEditorScreen(
    projectName: String,
    relativePath: String,
    onBack: () -> Unit,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val fileContent by viewModel.fileContent.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val loading by viewModel.loadingFiles.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var wordWrap by rememberSaveable { mutableStateOf(false) }
    var editorFontSizeSp by rememberSaveable { mutableStateOf(13f) }
    val editorState = remember { TextFieldState() }

    val fileName = relativePath.substringAfterLast('/')
    val extension = relativePath.substringAfterLast('.', "")
    var highlightedRanges by remember(extension) {
        mutableStateOf<List<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>>(emptyList())
    }

    LaunchedEffect(projectName, relativePath) {
        viewModel.openFile(projectName, relativePath)
    }

    LaunchedEffect(fileContent) {
        if (editorState.text.toString() != fileContent) {
            editorState.setTextAndPlaceCursorAtEnd(fileContent)
        }
    }

    LaunchedEffect(editorState) {
        snapshotFlow { editorState.text.toString() }.collect { editedText ->
            if (editedText != fileContent) viewModel.onContentChanged(editedText)
        }
    }

    LaunchedEffect(editorState, extension) {
        snapshotFlow { editorState.text.toString() }.collectLatest { text ->
            highlightedRanges = withContext(Dispatchers.Default) {
                SyntaxHighlighter.highlight(text, extension).spanStyles
            }
        }
    }

    val attemptBack = {
        if (isDirty) {
            showUnsavedDialog = true
        } else {
            viewModel.closeFile()
            onBack()
        }
    }

    BackHandler(onBack = attemptBack)

    val copyAll = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(fileName, fileContent))
        Toast.makeText(context, context.getString(R.string.workspace_code_copied), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = fileName,
                onBack = attemptBack,
                statusText = "/workspace/$projectName/$relativePath",
                actions = {
                    IconButton(onClick = copyAll, contentDescription = stringResource(R.string.workspace_cd_copy)) {
                        RuntimeIcon(RuntimeIconName.Copy, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isDirty) {
                        IconButton(
                            onClick = { showDiscardDialog = true },
                            contentDescription = stringResource(R.string.workspace_cd_discard),
                        ) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(
                        onClick = { viewModel.saveFile() },
                        enabled = isDirty && !isSaving,
                        contentDescription = stringResource(R.string.workspace_cd_save),
                    ) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                        else RuntimeIcon(RuntimeIconName.Save, Modifier.size(20.dp), tint = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // 提示信息
            message?.let { notice ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    NoticeBanner(text = notice, isError = messageIsError)
                }
            }

            if (loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = EditorAccent)
                }
            } else {
                // 代码编辑核心区域
                val lines = remember(fileContent) { fileContent.split('\n') }
                val lineCount = lines.size
                val scrollState = rememberScrollState()
                val horizontalScrollState = rememberScrollState()

                // 高亮语法生成
                val syntaxTransformation = remember(extension, highlightedRanges) {
                    OutputTransformation {
                        val currentLength = toString().length
                        highlightedRanges.forEach { range ->
                            if (range.start < currentLength && range.end <= currentLength) {
                                addStyle(range.item, range.start, range.end)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(EditorBackground)
                        // One vertical scroll owner keeps the gutter and editor on
                        // exactly the same content coordinate while dragging.
                        .verticalScroll(scrollState)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var zoomChanged = false
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f) {
                                            editorFontSizeSp = (editorFontSizeSp * zoom).coerceIn(
                                                MIN_EDITOR_FONT_SIZE_SP,
                                                MAX_EDITOR_FONT_SIZE_SP,
                                            )
                                            zoomChanged = true
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (zoomChanged) {
                                    editorFontSizeSp = editorFontSizeSp.coerceIn(
                                        MIN_EDITOR_FONT_SIZE_SP,
                                        MAX_EDITOR_FONT_SIZE_SP,
                                    )
                                }
                            }
                        },
                ) {
                    // 行号列
                    val gutterWidth = (lineCount.toString().length * 10 + 24).dp.coerceAtLeast(42.dp)
                    val editorLineHeight = (editorFontSizeSp * 1.54f).sp
                    val editorPlatformStyle = PlatformTextStyle(includeFontPadding = false)
                    Column(
                        modifier = Modifier
                            .width(gutterWidth)
                            .background(EditorGutterBackground)
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = (1..lineCount).joinToString("\n"),
                            style = TextStyle(
                                color = EditorGutterText,
                                fontSize = editorFontSizeSp.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = editorLineHeight,
                                platformStyle = editorPlatformStyle,
                            ),
                            textAlign = TextAlign.End,
                        )
                    }

                    // 垂直分割线
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF1E2638)),
                    )

                    // 编辑区
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (!wordWrap) Modifier.horizontalScroll(horizontalScrollState)
                                else Modifier,
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            state = editorState,
                            modifier = if (wordWrap) {
                                Modifier.fillMaxWidth()
                            } else {
                                // Keep logical source lines on one visual line so the
                                // gutter remains one-to-one with the code rows.
                                Modifier.layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(maxWidth = Constraints.Infinity),
                                    )
                                    layout(placeable.width, placeable.height) {
                                        placeable.place(0, 0)
                                    }
                                }
                            },
                            textStyle = TextStyle(
                                color = EditorText,
                                fontSize = editorFontSizeSp.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = editorLineHeight,
                                platformStyle = editorPlatformStyle,
                            ),
                            cursorBrush = SolidColor(EditorAccent),
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            outputTransformation = syntaxTransformation,
                        )
                    }
                }
            }

            // 底部状态栏
            EditorStatusBar(
                extension = extension,
                lineCount = remember(fileContent) { fileContent.count { it == '\n' } + 1 },
                charCount = fileContent.length,
                isDirty = isDirty,
                wordWrap = wordWrap,
                onToggleWrap = { wordWrap = !wordWrap },
            )
        }
    }

    // 未保存退出提示
    if (showUnsavedDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.workspace_unsaved_title)) },
            text = { Text(stringResource(R.string.workspace_unsaved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveFile(onSuccess = {
                            showUnsavedDialog = false
                            viewModel.closeFile()
                            onBack()
                        })
                    },
                ) { Text(stringResource(R.string.workspace_save_and_exit)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        viewModel.closeFile()
                        onBack()
                    },
                ) { Text(stringResource(R.string.workspace_discard_changes), color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    // 顶栏「放弃修改」二次确认：撤销全部未保存编辑属于破坏性操作
    if (showDiscardDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.workspace_discard_edits_title)) },
            text = { Text(stringResource(R.string.workspace_discard_edits_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.resetContent()
                    },
                ) { Text(stringResource(R.string.workspace_discard_edits_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.workspace_keep_editing))
                }
            },
        )
    }
}

@Composable
private fun EditorStatusBar(
    extension: String,
    lineCount: Int,
    charCount: Int,
    isDirty: Boolean,
    wordWrap: Boolean,
    onToggleWrap: () -> Unit,
) {
    Surface(
        color = EditorGutterBackground,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 语言类型
            Text(
                text = if (extension.isNotBlank()) extension.uppercase() else "PLAIN TEXT",
                style = MaterialTheme.typography.labelSmall,
                color = EditorAccent,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "•",
                color = EditorGutterText,
                style = MaterialTheme.typography.labelSmall,
            )

            // 行数与字数
            Text(
                text = stringResource(R.string.workspace_editor_stats, lineCount, charCount),
                style = MaterialTheme.typography.labelSmall,
                color = EditorGutterText,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.weight(1f))

            // 自动换行切换
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (wordWrap) EditorAccent.copy(alpha = 0.2f) else Color.Transparent,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onToggleWrap),
            ) {
                Text(
                    text = stringResource(if (wordWrap) R.string.workspace_word_wrap_on else R.string.workspace_word_wrap_off),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wordWrap) EditorAccent else EditorGutterText,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 保存状态
            Text(
                text = stringResource(if (isDirty) R.string.workspace_unsaved else R.string.workspace_saved),
                style = MaterialTheme.typography.labelSmall,
                color = if (isDirty) EditorWarning else EditorAccent,
            )
        }
    }
}
