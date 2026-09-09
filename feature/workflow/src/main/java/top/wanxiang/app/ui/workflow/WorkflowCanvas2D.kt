package top.wanxiang.app.ui.workflow

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconButton
import top.wanxiang.app.ui.components.RuntimeIconName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import top.wanxiang.app.core.model.workflow.NodeRunStatus
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowNode
import top.wanxiang.app.core.model.workflow.WorkflowNodeType
import top.wanxiang.app.core.model.workflow.WorkflowRuntimeState

private val NodeWidth = 228.dp
private val NodeHeight = 118.dp
private val GridSize = 32.dp
private const val MinScale = 0.30f
private const val MaxScale = 2.40f

/**
 * 2D 工作流画布。
 * 使用基于视口的安全投影变换（Viewport Screen-Space Projection）：
 * 不为整张无限世界分配超大 RenderNode（避免超过移动端 GPU GL_MAX_TEXTURE_SIZE 4096px 导致 HWUI 丢弃图层），
 * 节点与连线均在视口坐标系内精确计算与布局，手势捕获范围严格契合卡片，支持流畅缩放平移。
 */
@Composable
fun WorkflowCanvas2D(
    definition: WorkflowDefinition,
    state: WorkflowRuntimeState?,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    selectedNodeId: String? = null,
    connectionSourceId: String? = null,
    onNodeSelected: (String?) -> Unit = {},
    onNodeClicked: (String) -> Unit = onNodeSelected,
    onCanvasTapped: () -> Unit = { onNodeSelected(null) },
    onNodeMoved: (String, Float, Float) -> Unit = { _, _, _ -> },
    onConnectionRequested: (String, String) -> Unit = { _, _ -> },
    onBeginConnection: (String) -> Unit = {},
    onRemoveNode: (String) -> Unit = {},
    onConfigureNode: (String) -> Unit = {},
) {
    val density = LocalDensity.current
    val nodeWidthPx = with(density) { NodeWidth.toPx() }
    val nodeHeightPx = with(density) { NodeHeight.toPx() }
    val gridSizePx = with(density) { GridSize.toPx() }
    val fitPaddingPx = with(density) { 40.dp.toPx() }
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val positions = remember(definition.id, density) {
        mutableStateMapOf<String, Offset>().apply {
            definition.nodes.forEachIndexed { index, node ->
                this[node.id] = nodePosition(node, index, density.density)
            }
        }
    }
    // 同步保证 positions 包含当前 definition 中的所有节点（无需等待异步 LaunchedEffect）
    definition.nodes.forEachIndexed { index, node ->
        if (!positions.containsKey(node.id)) {
            positions[node.id] = nodePosition(node, index, density.density)
        }
    }
    LaunchedEffect(definition.nodes, density) {
        positions.keys.retainAll(definition.nodes.mapTo(mutableSetOf()) { it.id })
    }

    val resolvedPositions = resolveNodePositions(definition.nodes, positions, density.density)
    val bounds = contentBounds(resolvedPositions.values, nodeWidthPx, nodeHeightPx)

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(definition.id) { mutableFloatStateOf(1f) }
    var pan by remember(definition.id) { mutableStateOf(Offset.Zero) }

    fun fitToContent() {
        val transform = fitTransform(bounds, viewportSize, fitPaddingPx, MinScale, 1.35f) ?: return
        scale = transform.scale
        pan = transform.pan
    }

    fun zoomBy(factor: Float) {
        val centroid = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val transformed = zoomAround(CanvasTransform(scale, pan), centroid, factor, MinScale, MaxScale)
        scale = transformed.scale
        pan = transformed.pan
    }

    LaunchedEffect(definition.id, viewportSize) {
        if (viewportSize != IntSize.Zero) fitToContent()
    }

    // 当添加节点时，重新适配视口，确保新加入的节点完整呈现在可视区域中
    var prevNodeCount by remember(definition.id) { mutableStateOf(definition.nodes.size) }
    LaunchedEffect(definition.nodes.size) {
        val currentCount = definition.nodes.size
        if (currentCount > prevNodeCount && viewportSize != IntSize.Zero) {
            fitToContent()
        }
        prevNodeCount = currentCount
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val labelBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val labelTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inactiveEdgeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.46f)

    Box(
        modifier = modifier
            .clipToBounds()
            .background(surfaceColor)
            .onSizeChanged { viewportSize = it }
            .pointerInput(definition.id) {
                detectTransformGestures { centroid, gesturePan, zoom, _ ->
                    val transformed = zoomAround(CanvasTransform(scale, pan), centroid, zoom, MinScale, MaxScale)
                    scale = transformed.scale
                    pan = transformed.pan + gesturePan
                }
            }
            .pointerInput(definition.id) {
                detectTapGestures(
                    onDoubleTap = { fitToContent() },
                    onTap = { onCanvasTapped() },
                )
            },
    ) {
        // 背景点阵
        Canvas(Modifier.fillMaxSize()) {
            val spacing = gridSizePx * scale
            if (spacing >= 8f) {
                var x = positiveModulo(pan.x, spacing)
                while (x <= size.width) {
                    var y = positiveModulo(pan.y, spacing)
                    while (y <= size.height) {
                        drawCircle(gridColor, radius = (1.25f * scale).coerceIn(0.8f, 2.4f), center = Offset(x, y))
                        y += spacing
                    }
                    x += spacing
                }
            }
        }

        // 连线层：在视口屏幕坐标系中渲染，绝无纹理尺寸超限问题
        WorkflowEdges(
            definition = definition,
            state = state,
            positions = resolvedPositions,
            scale = scale,
            pan = pan,
            nodeWidthPx = nodeWidthPx,
            nodeHeightPx = nodeHeightPx,
            labelBackground = labelBackground,
            labelTextColor = labelTextColor,
            inactiveColor = inactiveEdgeColor,
            modifier = Modifier.fillMaxSize(),
        )

        // 节点层：卡片在视口屏幕坐标系中精确定位与缩放
        definition.nodes.forEachIndexed { index, node ->
            key(node.id) {
                val run = state?.nodeStates?.get(node.id)
                WorkflowNodeCard(
                    node = node,
                    status = run?.status ?: NodeRunStatus.IDLE,
                    progress = run?.progressMessage.orEmpty(),
                    selected = node.id == selectedNodeId,
                    connecting = node.id == connectionSourceId,
                    onConfigure = { onConfigureNode(node.id) },
                    onBeginConnection = { onBeginConnection(node.id) },
                    onRemove = { onRemoveNode(node.id) },
                    modifier = Modifier
                        .offset {
                            val worldPos = positions[node.id]
                                ?: resolvedPositions[node.id]
                                ?: nodePosition(node, index, density.density)
                            val screenPos = worldPos * scale + pan
                            IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt())
                        }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(
                                (placeable.width * scale).roundToInt(),
                                (placeable.height * scale).roundToInt(),
                            ) {
                                placeable.placeRelativeWithLayer(0, 0) {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                            }
                        }
                        .pointerInput(node.id, editable, scale) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var isDrag = false
                                val pointerId = down.id
                                var totalPan = Offset.Zero
                                val initialWorldPos = positions[node.id]
                                    ?: resolvedPositions[node.id]
                                    ?: nodePosition(node, index, density.density)

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                                    if (change.changedToUpIgnoreConsumed()) {
                                        change.consume()
                                        if (!isDrag) {
                                            val source = connectionSourceId
                                            if (source != null && source != node.id) {
                                                onConnectionRequested(source, node.id)
                                            } else {
                                                onNodeClicked(node.id)
                                            }
                                        } else {
                                            val current = positions[node.id]
                                            if (current != null) {
                                                val snapped = Offset(
                                                    (current.x / gridSizePx).roundToInt() * gridSizePx,
                                                    (current.y / gridSizePx).roundToInt() * gridSizePx,
                                                )
                                                positions[node.id] = snapped
                                                onNodeMoved(node.id, snapped.x / density.density, snapped.y / density.density)
                                            }
                                        }
                                        break
                                    }

                                    if (!editable) {
                                        continue
                                    }

                                    val panDelta = change.positionChange()
                                    if (panDelta != Offset.Zero) {
                                        totalPan += panDelta
                                        if (!isDrag && (totalPan.x * totalPan.x + totalPan.y * totalPan.y) > touchSlop * touchSlop) {
                                            isDrag = true
                                            positions[node.id] = initialWorldPos
                                        }
                                        if (isDrag) {
                                            change.consume()
                                            val current = positions[node.id] ?: initialWorldPos
                                            positions[node.id] = current + panDelta / scale
                                        }
                                    }
                                }
                            }
                        },
                )
            }
        }

        ViewportControls(
            scale = scale,
            onZoomOut = { zoomBy(0.82f) },
            onFit = ::fitToContent,
            onZoomIn = { zoomBy(1.22f) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp),
        )

        Text(
            text = "双指缩放 · 拖动画布 · 双击适配",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun WorkflowEdges(
    definition: WorkflowDefinition,
    state: WorkflowRuntimeState?,
    positions: Map<String, Offset>,
    scale: Float,
    pan: Offset,
    nodeWidthPx: Float,
    nodeHeightPx: Float,
    labelBackground: Color,
    labelTextColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelPaint = remember(labelTextColor, density, scale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelTextColor.toArgb()
            textSize = with(density) { (11.dp.toPx() * scale).coerceIn(8.dp.toPx(), 14.dp.toPx()) }
            textAlign = Paint.Align.CENTER
        }
    }
    val statusColors = NodeRunStatus.entries.associateWith { statusColor(it) }

    Canvas(modifier) {
        definition.edges.forEach { edge ->
            val sourceWorld = positions[edge.fromNodeId] ?: return@forEach
            val targetWorld = positions[edge.toNodeId] ?: return@forEach
            val sourceCenterWorld = sourceWorld + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
            val targetCenterWorld = targetWorld + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
            val deltaWorld = targetCenterWorld - sourceCenterWorld
            val distanceWorld = sqrt(deltaWorld.x * deltaWorld.x + deltaWorld.y * deltaWorld.y)
            if (distanceWorld < 1f) return@forEach
            val direction = deltaWorld / distanceWorld
            val startWorld = rectangleEdge(sourceCenterWorld, direction, nodeWidthPx, nodeHeightPx)
            val endWorld = rectangleEdge(targetCenterWorld, -direction, nodeWidthPx, nodeHeightPx)
            val bendWorld = max(abs(endWorld.x - startWorld.x) * 0.42f, 56.dp.toPx())
            val directionSign = if (endWorld.x >= startWorld.x) 1f else -1f
            val control1World = Offset(startWorld.x + bendWorld * directionSign, startWorld.y)
            val control2World = Offset(endWorld.x - bendWorld * directionSign, endWorld.y)

            // 投影至屏幕视口坐标
            val start = startWorld * scale + pan
            val end = endWorld * scale + pan
            val control1 = control1World * scale + pan
            val control2 = control2World * scale + pan

            val path = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
            }

            val sourceStatus = state?.nodeStates?.get(edge.fromNodeId)?.status ?: NodeRunStatus.IDLE
            val active = sourceStatus in setOf(NodeRunStatus.RUNNING, NodeRunStatus.STREAMING, NodeRunStatus.WAITING_APPROVAL, NodeRunStatus.SUCCESS)
            val color = if (active) (statusColors[sourceStatus] ?: inactiveColor) else inactiveColor
            val dash = if (active) null else PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx() * scale.coerceIn(0.6f, 1.4f), 7.dp.toPx() * scale.coerceIn(0.6f, 1.4f)))
            val strokeWidth = (2.4.dp.toPx() * scale).coerceIn(1.6f, 3.2f)

            drawPath(path, Color.Black.copy(alpha = 0.12f), style = Stroke(strokeWidth + 2f, cap = StrokeCap.Round, pathEffect = dash))
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, pathEffect = dash))
            drawCircle(color, radius = (4.5.dp.toPx() * scale).coerceIn(3f, 6f), center = start)
            drawCircle(labelBackground, radius = (5.5.dp.toPx() * scale).coerceIn(4f, 7.5f), center = end)
            drawCircle(color, radius = (4.2.dp.toPx() * scale).coerceIn(2.8f, 5.5f), center = end)

            val endAngle = atan2(end.y - control2.y, end.x - control2.x)
            val arrowSize = (9.dp.toPx() * scale).coerceIn(6f, 12f)
            val arrow = Path().apply {
                moveTo(end.x, end.y)
                lineTo(end.x - arrowSize * cos(endAngle - 0.48f), end.y - arrowSize * sin(endAngle - 0.48f))
                lineTo(end.x - arrowSize * cos(endAngle + 0.48f), end.y - arrowSize * sin(endAngle + 0.48f))
                close()
            }
            drawPath(arrow, color)

            edgeLabel(edge.fromPort, edge.conditionExpression)?.let { label ->
                val midpoint = cubicPoint(start, control1, control2, end, 0.5f)
                val width = labelPaint.measureText(label) + 14.dp.toPx() * scale.coerceIn(0.7f, 1.2f)
                val height = 22.dp.toPx() * scale.coerceIn(0.7f, 1.2f)
                drawRoundRect(
                    color = labelBackground.copy(alpha = 0.96f),
                    topLeft = Offset(midpoint.x - width / 2f, midpoint.y - height / 2f),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    midpoint.x,
                    midpoint.y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                    labelPaint,
                )
            }
        }
    }
}

data class NodeVisualTheme(
    val accentColor: Color,
    val icon: RuntimeIconName,
    val tag: String,
)

fun WorkflowNodeType.visualTheme(): NodeVisualTheme = when (this) {
    WorkflowNodeType.TRIGGER -> NodeVisualTheme(
        accentColor = Color(0xFF10B981), // 翡翠绿
        icon = RuntimeIconName.Play,
        tag = "触发器",
    )
    WorkflowNodeType.BASH_COMMAND -> NodeVisualTheme(
        accentColor = Color(0xFF06B6D4), // 终端青
        icon = RuntimeIconName.Terminal,
        tag = "Shell 命令",
    )
    WorkflowNodeType.PROCESS_SERVICE -> NodeVisualTheme(
        accentColor = Color(0xFF818CF8), // 守护紫
        icon = RuntimeIconName.Settings,
        tag = "常驻服务",
    )
    WorkflowNodeType.AGENT_INFERENCE -> NodeVisualTheme(
        accentColor = Color(0xFFA855F7), // 智能紫
        icon = RuntimeIconName.Sparkles,
        tag = "AI 推理",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> NodeVisualTheme(
        accentColor = Color(0xFF3B82F6), // 协同蓝
        icon = RuntimeIconName.Hub,
        tag = "子智能体",
    )
    WorkflowNodeType.TAIXU_BUILD -> NodeVisualTheme(
        accentColor = Color(0xFFF59E0B), // 构建琥珀
        icon = RuntimeIconName.Package,
        tag = "离线构建",
    )
    WorkflowNodeType.CONDITION_BRANCH -> NodeVisualTheme(
        accentColor = Color(0xFFEAB308), // 分支黄
        icon = RuntimeIconName.Link,
        tag = "条件分支",
    )
    WorkflowNodeType.HUMAN_APPROVAL -> NodeVisualTheme(
        accentColor = Color(0xFFF43F5E), // 审批红
        icon = RuntimeIconName.Alert,
        tag = "人工把关",
    )
    WorkflowNodeType.HOST_ACTION -> NodeVisualTheme(
        accentColor = Color(0xFF14B8A6), // 宿主青
        icon = RuntimeIconName.Android,
        tag = "宿主系统",
    )
    WorkflowNodeType.DELAY -> NodeVisualTheme(
        accentColor = Color(0xFF94A3B8),
        icon = RuntimeIconName.Speed,
        tag = "延时等待",
    )
    WorkflowNodeType.SET_VARIABLE -> NodeVisualTheme(
        accentColor = Color(0xFF2DD4BF),
        icon = RuntimeIconName.Tune,
        tag = "设置变量",
    )
    WorkflowNodeType.TERMINAL_OUTPUT -> NodeVisualTheme(
        accentColor = Color(0xFF0EA5E9), // 归档天蓝
        icon = RuntimeIconName.Check,
        tag = "结果输出",
    )
}

/**
 * 节点卡片：专属色彩识别系统、左侧色彩状态条、动态代码/分流胶囊预览，
 * 并在选中时呈现 [调参 / 连线 / 删除] 快捷操作条。
 */
@Composable
private fun WorkflowNodeCard(
    node: WorkflowNode,
    status: NodeRunStatus,
    progress: String,
    selected: Boolean,
    connecting: Boolean,
    onConfigure: () -> Unit = {},
    onBeginConnection: () -> Unit = {},
    onRemove: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val theme = node.type.visualTheme()
    val statusClr = statusColor(status)
    val borderColor = when {
        connecting -> MaterialTheme.colorScheme.tertiary
        selected -> theme.accentColor
        status != NodeRunStatus.IDLE -> statusClr
        else -> theme.accentColor.copy(alpha = 0.38f)
    }
    val borderWidth = if (selected || connecting) 2.5.dp else 1.2.dp
    val shape = RoundedCornerShape(14.dp)

    Surface(
        modifier = modifier.size(NodeWidth, NodeHeight),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(borderWidth, borderColor),
        tonalElevation = if (selected) 8.dp else 2.dp,
        shadowElevation = if (selected) 5.dp else 1.5.dp,
    ) {
        Row(Modifier.fillMaxSize()) {
            // 左侧专属颜色状态条
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(theme.accentColor)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶部行：类型图标 + 类型 Tag + 运行状态 Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = theme.accentColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(20.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            RuntimeIcon(theme.icon, Modifier.size(12.dp), tint = theme.accentColor)
                        }
                    }
                    Text(
                        text = theme.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.accentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = statusClr.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = statusLabel(status),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusClr,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }

                // 节点标题
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 底部展示区：选中时展现快捷操作条；未选中时展现动态代码/路由胶囊预览
                if (selected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = onConfigure,
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("⚙ 调参", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            }
                        }
                        Surface(
                            onClick = onBeginConnection,
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("🔗 连线", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            }
                        }
                        Surface(
                            onClick = onRemove,
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("🗑 删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
                            }
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val previewText = when (node.type) {
                            WorkflowNodeType.BASH_COMMAND -> ">_ ${node.config["command"]?.trim() ?: "待配置命令"}"
                            WorkflowNodeType.CONDITION_BRANCH -> "🔀 ${node.config["expression"]?.trim() ?: "exitCode == 0"}"
                            WorkflowNodeType.AGENT_INFERENCE -> "✨ ${node.config["prompt"]?.trim()?.take(20) ?: "AI 提示词"}"
                            WorkflowNodeType.TAIXU_BUILD -> "📦 ${node.config["projectType"] ?: "android"} · ${node.config["task"] ?: "assemble"}"
                            WorkflowNodeType.HUMAN_APPROVAL -> "🛡️ 需要人工确认"
                            WorkflowNodeType.HOST_ACTION -> "📱 ${node.config["action"] ?: "status"}"
                            WorkflowNodeType.DELAY -> "⏱ ${node.config["seconds"] ?: "1"}s"
                            WorkflowNodeType.SET_VARIABLE -> "🔤 ${node.config["variables"]?.lineSequence()?.firstOrNull() ?: "KEY=value"}"
                            else -> progress.ifBlank { node.description.ifBlank { node.id } }
                        }
                        Text(
                            text = previewText,
                            style = if (node.type == WorkflowNodeType.BASH_COMMAND) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewportControls(
    scale: Float,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            RuntimeIconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(32.dp),
                contentDescription = "缩小",
            ) {
                Text("−", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "${(scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            RuntimeIconButton(
                onClick = onFit,
                modifier = Modifier.size(32.dp),
                contentDescription = "适配视口",
            ) {
                Text("适配", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            RuntimeIconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(32.dp),
                contentDescription = "放大",
            ) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(15.dp))
            }
        }
    }
}

internal data class CanvasTransform(val scale: Float, val pan: Offset)

internal data class CanvasContentBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

internal fun zoomAround(
    current: CanvasTransform,
    centroid: Offset,
    zoomFactor: Float,
    minScale: Float,
    maxScale: Float,
): CanvasTransform {
    val newScale = (current.scale * zoomFactor).coerceIn(minScale, maxScale)
    val scaleChange = newScale / current.scale
    return CanvasTransform(newScale, (current.pan - centroid) * scaleChange + centroid)
}

internal fun fitTransform(
    bounds: CanvasContentBounds,
    viewport: IntSize,
    padding: Float,
    minScale: Float,
    maxScale: Float,
): CanvasTransform? {
    if (viewport == IntSize.Zero) return null
    val contentWidth = (bounds.maxX - bounds.minX).coerceAtLeast(1f)
    val contentHeight = (bounds.maxY - bounds.minY).coerceAtLeast(1f)
    val availableWidth = (viewport.width - padding * 2f).coerceAtLeast(1f)
    val availableHeight = (viewport.height - padding * 2f).coerceAtLeast(1f)
    val scale = minOf(availableWidth / contentWidth, availableHeight / contentHeight).coerceIn(minScale, maxScale)
    return CanvasTransform(
        scale = scale,
        pan = Offset(
            x = (viewport.width - contentWidth * scale) / 2f - bounds.minX * scale,
            y = (viewport.height - contentHeight * scale) / 2f - bounds.minY * scale,
        ),
    )
}

private fun contentBounds(positions: Collection<Offset>, nodeWidth: Float, nodeHeight: Float): CanvasContentBounds {
    if (positions.isEmpty()) return CanvasContentBounds(0f, 0f, nodeWidth, nodeHeight)
    return CanvasContentBounds(
        minX = positions.minOf { it.x },
        minY = positions.minOf { it.y },
        maxX = positions.maxOf { it.x + nodeWidth },
        maxY = positions.maxOf { it.y + nodeHeight },
    )
}

private fun rectangleEdge(center: Offset, direction: Offset, width: Float, height: Float): Offset {
    val xDistance = if (abs(direction.x) < 0.0001f) Float.POSITIVE_INFINITY else width / 2f / abs(direction.x)
    val yDistance = if (abs(direction.y) < 0.0001f) Float.POSITIVE_INFINITY else height / 2f / abs(direction.y)
    return center + direction * minOf(xDistance, yDistance)
}

private fun cubicPoint(start: Offset, c1: Offset, c2: Offset, end: Offset, t: Float): Offset {
    val u = 1f - t
    return start * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + end * (t * t * t)
}

private fun edgeLabel(port: String, condition: String?): String? = condition?.takeIf(String::isNotBlank)
    ?: port.takeUnless { it.equals("output", true) || it.equals("success", true) }

private fun positiveModulo(value: Float, modulus: Float): Float = ((value % modulus) + modulus) % modulus

private fun nodePosition(node: WorkflowNode, index: Int, density: Float): Offset = Offset(
    x = (node.canvasX.takeUnless { it == 0f } ?: (48f + index * 248f)) * density,
    y = (node.canvasY.takeUnless { it == 0f } ?: (72f + (index % 2) * 136f)) * density,
)

internal fun resolveNodePositions(
    nodes: List<WorkflowNode>,
    current: Map<String, Offset>,
    density: Float,
): Map<String, Offset> = nodes.mapIndexed { index, node ->
    node.id to (current[node.id] ?: nodePosition(node, index, density))
}.toMap()

private fun WorkflowNodeType.displayName(): String = when (this) {
    WorkflowNodeType.TRIGGER -> "触发器"
    WorkflowNodeType.BASH_COMMAND -> "命令"
    WorkflowNodeType.PROCESS_SERVICE -> "后台服务"
    WorkflowNodeType.AGENT_INFERENCE -> "智能体推理"
    WorkflowNodeType.SUBAGENT_DELEGATE -> "子智能体"
    WorkflowNodeType.TAIXU_BUILD -> "万象构建"
    WorkflowNodeType.CONDITION_BRANCH -> "条件分支"
    WorkflowNodeType.HUMAN_APPROVAL -> "人工审批"
    WorkflowNodeType.HOST_ACTION -> "宿主动作"
    WorkflowNodeType.DELAY -> "延时"
    WorkflowNodeType.SET_VARIABLE -> "变量"
    WorkflowNodeType.TERMINAL_OUTPUT -> "输出"
}
