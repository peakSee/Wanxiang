package top.wkbin.taixu.ui.terminal

import top.wkbin.taixu.ui.components.RuntimeAlertDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import top.wkbin.taixu.feature.terminal.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.SpotlightGuideOverlay
import top.wkbin.taixu.ui.components.rememberSpotlightAnchor
import top.wkbin.taixu.ui.components.spotlightAnchor
import top.wkbin.taixu.runtime.terminal.TerminalLine
import top.wkbin.taixu.runtime.terminal.TerminalSessionHandle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private const val MIN_TERMINAL_FONT_SIZE_SP = 10f
private const val MAX_TERMINAL_FONT_SIZE_SP = 24f

/**
 * Returns the text committed by the IME since the previous editor value.
 *
 * The hidden editor must retain its value while the keyboard is open. Clearing it
 * after every character makes some IMEs hide/disable the Send action. Composition
 * text is not part of the terminal input until the IME commits it, so when a
 * composition ends we diff from the text that existed before that composition.
 */
private fun terminalInputDelta(previous: TextFieldValue, current: TextFieldValue): String {
    if (current.text.isEmpty()) return ""

    val committedPrefix = previous.composition?.let { composition ->
        previous.text.substring(0, composition.start.coerceIn(0, previous.text.length))
    } ?: previous.text

    return if (current.text.startsWith(committedPrefix)) {
        current.text.removePrefix(committedPrefix)
    } else {
        // The IME may replace the whole value when committing a composition.
        // In that case the new value is the only safe payload to forward.
        current.text
    }
}

/**
 * 太墟 · 矩阵控制台 (Matrix Terminal)
 * 基于原生 Linux PTY，集成开发者快捷辅助按键栏
 */
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    project: String = "",
    showBackButton: Boolean = true,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val cursor by viewModel.cursor.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val handles by viewModel.handles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val distributionName by viewModel.distributionName.collectAsStateWithLifecycle()
    val installedDistros by viewModel.installedDistros.collectAsStateWithLifecycle()
    val configuredFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val colorScheme by viewModel.terminalColorScheme.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.terminalHapticsEnabled.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    var fontSizeSp by remember { mutableFloatStateOf(configuredFontSize.toFloat()) }
    var terminalPxWidth by remember { mutableFloatStateOf(0f) }
    var terminalPxHeight by remember { mutableFloatStateOf(0f) }
    var sessionToClose by remember { mutableStateOf<String?>(null) }

    val sysSurfaceLowest = MaterialTheme.colorScheme.surfaceContainerLowest
    val sysSurfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val sysOnSurface = MaterialTheme.colorScheme.onSurface
    val sysOutline = MaterialTheme.colorScheme.outlineVariant
    val (termBg, termHeaderBg, termTextDefault, termBorder) = remember(colorScheme, sysSurfaceLowest, sysSurfaceHigh, sysOnSurface, sysOutline) {
        when (colorScheme) {
            "matrix" -> listOf(Color(0xFF0A0F0D), Color(0xFF101B14), Color(0xFF10B981), Color(0xFF1A3324))
            "amber" -> listOf(Color(0xFF140F0A), Color(0xFF1F170F), Color(0xFFF59E0B), Color(0xFF3B2B1B))
            "aurora" -> listOf(Color(0xFF0D1424), Color(0xFF141F36), Color(0xFF38BDF8), Color(0xFF1E3A5F))
            else -> listOf(sysSurfaceLowest, sysSurfaceHigh, sysOnSurface, sysOutline)
        }
    }

    val (terminalCharWidth, terminalLineHeight) = remember(fontSizeSp) {
        val paint = android.graphics.Paint().apply {
            textSize = with(density) { fontSizeSp.sp.toPx() }
            typeface = android.graphics.Typeface.MONOSPACE
            isAntiAlias = true
        }
        val charWidth = paint.measureText("M")
        val lineHeight = paint.fontMetrics.run { descent - ascent }
        charWidth to lineHeight
    }

    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }

    val copyScreen = {
        val text = screen.joinToString("\n") { line ->
            line.cells.joinToString("") { it.character }.trimEnd()
        }
        if (text.isNotBlank()) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_clipboard_label), text))
            Toast.makeText(context, context.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
        }
    }

    val pasteToTerminal = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank()) viewModel.pasteText(text)
    }

    val terminalFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var visibleRows by remember { mutableStateOf(0) }
    var inputFocused by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }
    var followOutput by remember { mutableStateOf(true) }
    var showSessions by remember { mutableStateOf(false) }

    // 首次进入引导：高亮顶栏「会话列表」按钮
    val firstUseGuidesShown by viewModel.firstUseGuidesShown.collectAsStateWithLifecycle()
    val sessionsAnchor = rememberSpotlightAnchor()
    var showCreateSession by remember { mutableStateOf(false) }

    LaunchedEffect(project) {
        viewModel.initialize(project)
    }

    LaunchedEffect(configuredFontSize) {
        fontSizeSp = configuredFontSize.toFloat()
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (isScrolling, canScrollForward) ->
                if (isScrolling) followOutput = !canScrollForward
            }
    }

    LaunchedEffect(fontSizeSp, terminalPxWidth, terminalPxHeight, activeId) {
        if (terminalPxWidth > 0f && terminalPxHeight > 0f) {
            // Pinch gestures can emit many frames per second. Text updates immediately;
            // native PTY resize follows the latest dimensions after a short debounce.
            delay(80)
            val renderWidthPx = terminalPxWidth - with(density) { 24.dp.toPx() }
            val rows = (terminalPxHeight / terminalLineHeight).toInt().coerceIn(5, 200)
            visibleRows = rows
            viewModel.resize(
                columns = (renderWidthPx / terminalCharWidth).toInt().coerceIn(20, 400),
                rows = rows,
            )
        }
    }

    LaunchedEffect(screen.size, cursor.row, cursor.column, followOutput, activeId) {
        val lastIndex = screen.lastIndex
        if (followOutput && lastIndex >= 0) {
            listState.scrollToItem(lastIndex)
        }
    }

    LaunchedEffect(visibleRows, inputFocused) {
        if (inputFocused && screen.isNotEmpty()) {
            followOutput = true
            withFrameNanos { }
            // 等帧期间会话可能重建（如 root 环境初始化）导致 screen 清空，
            // 此时 scrollToItem(-1) 会抛 Index should be non-negative，需重新校验。
            val lastIndex = screen.lastIndex
            if (lastIndex >= 0) {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    LaunchedEffect(inputFocused) {
        if (inputFocused) {
            doShowKeyboard(view, context)
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val navigateBack = {
        if (!isLeaving) {
            isLeaving = true
            if (inputFocused) {
                keyboard?.hide()
                focusManager.clearFocus(force = true)
                inputFocused = false
                // Let the IME begin its close animation before removing the terminal page.
                coroutineScope.launch {
                    delay(180)
                    onBack()
                }
            } else {
                onBack()
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = inputFocused) {
        keyboard?.hide()
        focusManager.clearFocus()
        inputFocused = false
    }

    LaunchedEffect(Unit) {
        terminalFocusRequester.requestFocus()
    }

    // 首次引导遮罩与 Scaffold 放在同一 Box 下（同层兄弟节点），保证聚光灯坐标与按钮 boundsInRoot 同一参照系
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = if (project.isNotBlank()) stringResource(R.string.terminal_project_title, project) else stringResource(R.string.terminal_console_title),
                // Agent 内嵌终端面板不属于导航栈节点，隐藏返回箭头避免「点了没反应」
                onBack = if (showBackButton) navigateBack else null,
                statusText = stringResource(R.string.terminal_engine_status, distributionName),
            ) {
                IconButton(
                    onClick = { showSessions = true },
                    modifier = Modifier.spotlightAnchor(sessionsAnchor),
                    contentDescription = stringResource(R.string.terminal_sessions_desc),
                ) {
                    RuntimeIcon(RuntimeIconName.List, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 终端主窗口
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = termBg,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, termBorder),
            ) {
                Column {
                    // 终端窗口装饰条
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(termHeaderBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TerminalDot(Color(0xFFFF3366))
                            TerminalDot(Color(0xFFFFB300))
                            TerminalDot(Color(0xFF00E676))
                        }
                        Text(
                            stringResource(
                                R.string.terminal_pty_header,
                                distributionName.substringBefore(" (").uppercase(),
                                Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: "UNKNOWN",
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.terminal_copy),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = copyScreen)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = termTextDefault.copy(alpha = 0.6f),
                            )
                            Text(
                                stringResource(R.string.terminal_paste),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = pasteToTerminal)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = termTextDefault.copy(alpha = 0.6f),
                            )
                        }
                    }

                    // 终端渲染区域
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var zoomChanged = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressedPointers = event.changes.count { it.pressed }
                                        if (pressedPointers >= 2) {
                                            val zoom = event.calculateZoom()
                                            if (zoom != 1f) {
                                                fontSizeSp = (fontSizeSp * zoom).coerceIn(
                                                    MIN_TERMINAL_FONT_SIZE_SP,
                                                    MAX_TERMINAL_FONT_SIZE_SP,
                                                )
                                                zoomChanged = true
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (zoomChanged) {
                                        viewModel.setTerminalFontSize(fontSizeSp.roundToInt())
                                    }
                                }
                            }
                            .onSizeChanged { size ->
                                terminalPxWidth = size.width.toFloat()
                                terminalPxHeight = size.height.toFloat()
                            },
                    ) {
                        when {
                            error != null -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Button(
                                    onClick = { viewModel.retryInitialize(project) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.terminal_retry))
                                }
                            }
                            screen.isEmpty() || screen.all { it.cells.isEmpty() } -> Text(
                                stringResource(R.string.terminal_starting),
                                Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                            )
                            else -> LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        followOutput = true
                                        terminalFocusRequester.requestFocus()
                                        keyboard?.show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                state = listState,
                            ) {
                                itemsIndexed(
                                    screen,
                                    key = { index, line -> "$index-${line.cells.hashCode()}" },
                                ) { index, line ->
                                    TerminalLineRow(
                                        line = line,
                                        showCursor = cursor.visible && index == cursor.row,
                                        cursorColumn = cursor.column,
                                        fontSizeSp = fontSizeSp,
                                        termBg = termBg,
                                        termTextDefault = termTextDefault,
                                        termCursor = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        // 透明输入锚点
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                val previousValue = textFieldValue
                                if (newValue.composition != null) {
                                    // 输入法正在组合输入（例如拼音输入中），暂不提交到终端
                                    textFieldValue = newValue
                                } else {
                                    // 输入法已确认提交或直接输入字符
                                    val textToSend = terminalInputDelta(previousValue, newValue)
                                    if (textToSend.isNotEmpty()) {
                                        viewModel.sendText(textToSend)
                                    }
                                    // 保留已提交文本，避免 IME 因编辑器瞬间变空而收起发送动作。
                                    textFieldValue = newValue
                                }
                            },
                            modifier = Modifier
                                .size(1.dp)
                                .align(Alignment.BottomStart)
                                .alpha(0f)
                                .focusRequester(terminalFocusRequester)
                                .onFocusChanged { inputFocused = it.isFocused }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when {
                                            event.key == Key.Enter -> {
                                                if (textFieldValue.composition == null) {
                                                    viewModel.sendText("\r")
                                                    textFieldValue = TextFieldValue("")
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                            event.key == Key.Backspace && !event.isCtrlPressed -> {
                                                if (textFieldValue.composition == null) {
                                                    viewModel.sendText("\u007f")
                                                    true
                                                } else {
                                                    // Let the IME edit an active composition (for example pinyin).
                                                    false
                                                }
                                            }
                                            else -> viewModel.onTerminalKey(event)
                                        }
                                    } else false
                                },
                            textStyle = TextStyle(color = Color.Transparent, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color.Transparent),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Send,
                            ),
                            keyboardActions = KeyboardActions(onSend = {
                                if (textFieldValue.composition == null) {
                                    viewModel.sendText("\r")
                                    textFieldValue = TextFieldValue("")
                                }
                            }),
                            decorationBox = { innerTextField -> innerTextField() },
                        )
                    }
                }
            }

            // 移动端开发者辅助键盘条（Horizontal Key Strip with Haptics）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExtraKey("ESC", isAccent = true, hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u001B") }
                ExtraKey("Tab", isAccent = true, hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u0009") }
                ExtraKey("Ctrl+C", isDanger = true, hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u0003") }
                ExtraKey("Ctrl+D", hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u0004") }
                ExtraKey("Ctrl+Z", hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u001A") }
                ExtraKey("↑", hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u001B[A") }
                ExtraKey("↓", hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u001B[B") }
                ExtraKey("←", hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u001B[D") }
                ExtraKey("→", hapticsEnabled = hapticsEnabled) { viewModel.sendText("\u001B[C") }
                ExtraKey("|", hapticsEnabled = hapticsEnabled) { viewModel.sendText("|") }
                ExtraKey("/", hapticsEnabled = hapticsEnabled) { viewModel.sendText("/") }
                ExtraKey("-", hapticsEnabled = hapticsEnabled) { viewModel.sendText("-") }
                ExtraKey("~", hapticsEnabled = hapticsEnabled) { viewModel.sendText("~") }
                ExtraKey("$", hapticsEnabled = hapticsEnabled) { viewModel.sendText("$") }
                ExtraKey("&&", hapticsEnabled = hapticsEnabled) { viewModel.sendText(" && ") }
                ExtraKey("cd ..", hapticsEnabled = hapticsEnabled) { viewModel.sendText("cd ..\r") }
                ExtraKey("Clear", hapticsEnabled = hapticsEnabled) { viewModel.sendText("clear\r") }
            }
        }
    }

    // 首次进入引导：高亮顶栏「会话列表」按钮
    if ("terminal_sessions" !in firstUseGuidesShown) {
        SpotlightGuideOverlay(
            anchor = sessionsAnchor,
            title = stringResource(R.string.terminal_guide_title),
            message = stringResource(R.string.terminal_guide_message),
            onDismiss = { viewModel.markFirstUseGuideShown("terminal_sessions") },
        )
    }
    } // Box

    if (showSessions) {
        SessionListDialog(
            handles = handles,
            activeId = activeId,
            onDismiss = { showSessions = false },
            onSwitch = { id -> viewModel.switchSession(id); showSessions = false },
            onCreate = {
                showSessions = false
                showCreateSession = true
            },
            onClose = { id -> showSessions = false; sessionToClose = id },
        )
    }

    // 关闭会话二次确认：会话关闭会杀死其中运行的所有进程
    sessionToClose?.let { closingId ->
        CloseSessionConfirmDialog(
            onConfirm = {
                sessionToClose = null
                val wasOnlySession = handles.size == 1
                viewModel.closeSession(closingId) { success ->
                    if (success && wasOnlySession) {
                        Toast.makeText(context, context.getString(R.string.terminal_reset_complete), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { sessionToClose = null },
        )
    }

    if (showCreateSession) {
        CreateTerminalDialog(
            workspaces = workspaces,
            installedDistros = installedDistros,
            nextSessionIndex = handles.size + 1,
            onDismiss = { showCreateSession = false },
            onCreate = { label, workDir, distroId ->
                showCreateSession = false
                viewModel.createSession(label, workDir, distroId)
            },
        )
    }
}

@Composable
private fun CreateTerminalDialog(
    workspaces: List<top.wkbin.taixu.runtime.WorkspaceProject>,
    installedDistros: List<top.wkbin.taixu.core.model.InstalledDistro> = emptyList(),
    nextSessionIndex: Int,
    onDismiss: () -> Unit,
    onCreate: (label: String, workingDirectory: String, distroId: String?) -> Unit,
) {
    val defaultLabel = stringResource(R.string.terminal_default_label, nextSessionIndex)
    var label by remember(nextSessionIndex) { mutableStateOf(defaultLabel) }
    var selectedDir by remember { mutableStateOf("/root") }
    var selectedDistroId by remember { mutableStateOf(installedDistros.firstOrNull { it.isActive }?.id ?: installedDistros.firstOrNull()?.id ?: "ubuntu") }
    val quickLabels = listOf(
        stringResource(R.string.terminal_quick_main),
        stringResource(R.string.terminal_quick_build),
        stringResource(R.string.terminal_quick_service),
        stringResource(R.string.terminal_quick_git),
        stringResource(R.string.terminal_quick_python),
    )

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.terminal_new_session), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (installedDistros.size > 1) {
                    Text(stringResource(R.string.terminal_target_distribution), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        installedDistros.forEach { d ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (selectedDistroId == d.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedDistroId == d.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                                ),
                                modifier = Modifier.clickable { selectedDistroId = d.id },
                            ) {
                                Text(
                                    d.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedDistroId == d.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                Text(stringResource(R.string.terminal_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text(stringResource(R.string.terminal_label_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 快捷预设标签
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickLabels.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (label == tag) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                if (label == tag) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                            ),
                            modifier = Modifier.clickable { label = tag },
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (label == tag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(stringResource(R.string.terminal_initial_cwd), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        TerminalDirOption(
                            name = stringResource(R.string.terminal_root_directory),
                            path = "/root",
                            selected = selectedDir == "/root",
                            onSelect = { selectedDir = "/root" },
                        )
                    }
                    items(workspaces.size) { index ->
                        val ws = workspaces[index]
                        TerminalDirOption(
                            name = ws.name,
                            path = ws.linuxPath,
                            selected = selectedDir == ws.linuxPath,
                            onSelect = { selectedDir = ws.linuxPath },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(label.ifBlank { defaultLabel }, selectedDir, selectedDistroId) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.terminal_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
        },
    )
}

@Composable
private fun TerminalDirOption(
    name: String,
    path: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal))
            Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionListDialog(
    handles: List<TerminalSessionHandle>,
    activeId: String?,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreate: () -> Unit,
    onClose: (String) -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.terminal_sessions), fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        stringResource(R.string.terminal_active_sessions, handles.size),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (handles.size == 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Alert, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.terminal_only_session_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(handles.size) { index ->
                        val handle = handles[index]
                        val active = handle.id == activeId
                        val distroName = runCatching {
                            top.wkbin.taixu.runtime.DistributionCatalog.require(handle.distributionId).displayName
                        }.getOrDefault(handle.distributionId)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSwitch(handle.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape).background(
                                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            handle.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                distroName,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                        if (active) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    stringResource(R.string.terminal_current),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        handle.workingDirectory,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (handles.size == 1) {
                                    IconButton(
                                        onClick = { onClose(handle.id) },
                                        contentDescription = stringResource(R.string.terminal_reset_session_desc),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.PowerSettingsNew, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                    }
                                } else {
                                    IconButton(
                                        onClick = { onClose(handle.id) },
                                        contentDescription = stringResource(R.string.terminal_close_session_desc),
                                    ) {
                                        RuntimeIcon(RuntimeIconName.Close, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCreate, shape = RoundedCornerShape(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(R.string.terminal_new))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_close)) }
        },
    )
}

/**
 * 关闭终端会话二次确认弹窗（样式对齐 DistroManagementScreen 的倒计时确认弹窗）：
 * 关闭会杀死会话中运行的所有进程，属破坏性操作。
 */
@Composable
private fun CloseSessionConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.terminal_close_confirm_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.terminal_close_confirm_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = countdown == 0,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = if (countdown > 0) stringResource(R.string.terminal_close_confirm_countdown, countdown)
                    else stringResource(R.string.terminal_close_confirm),
                    color = if (countdown == 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onError.copy(alpha = 0.6f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
        },
    )
}

@Composable
private fun ExtraKey(
    label: String,
    isAccent: Boolean = false,
    isDanger: Boolean = false,
    hapticsEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg = when {
        isDanger -> MaterialTheme.colorScheme.errorContainer
        isAccent -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textCol = when {
        isDanger -> MaterialTheme.colorScheme.onErrorContainer
        isAccent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderCol = when {
        isDanger -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        isAccent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = {
                if (hapticsEnabled) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
                onClick()
            }),
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderCol),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            ),
            color = textCol,
        )
    }
}

@Composable
private fun TerminalDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun TerminalLineRow(
    line: TerminalLine,
    showCursor: Boolean,
    cursorColumn: Int,
    fontSizeSp: Float = 13.5f,
    termBg: Color,
    termTextDefault: Color,
    termCursor: Color,
) {
    val annotatedLine = remember(line, showCursor, cursorColumn, fontSizeSp, termBg, termTextDefault, termCursor) {
        buildAnnotatedString {
            line.cells.forEachIndexed { cellIndex, cell ->
                val isCursor = showCursor && cellIndex == cursorColumn
                withStyle(
                    SpanStyle(
                        color = if (isCursor) termBg else (cell.foreground?.let(::Color) ?: termTextDefault),
                        background = if (isCursor) termCursor else (cell.background?.let(::Color) ?: Color.Unspecified),
                        fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = when {
                            cell.underline && cell.strikeThrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            cell.underline -> TextDecoration.Underline
                            cell.strikeThrough -> TextDecoration.LineThrough
                            else -> TextDecoration.None
                        },
                    ).let { style -> if (cell.inverse) style.copy(color = cell.background?.let(::Color) ?: termBg, background = cell.foreground?.let(::Color) ?: termTextDefault) else style },
                ) { append(cell.character) }
            }
            if (showCursor && cursorColumn >= line.cells.size) {
                repeat(cursorColumn - line.cells.size) { append(" ") }
                withStyle(SpanStyle(color = termBg, background = termCursor)) { append(" ") }
            }
        }
    }
    Text(
        text = annotatedLine,
        fontFamily = FontFamily.Monospace,
        color = termTextDefault,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.35f).sp,
        ),
    )
}

private fun doShowKeyboard(view: View, context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.showSoftInput(view, 0)
}


