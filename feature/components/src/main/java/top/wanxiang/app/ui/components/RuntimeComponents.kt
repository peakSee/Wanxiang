package top.wanxiang.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import top.wanxiang.app.feature.components.R
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.ui.theme.LocalLiquidGlassBackdrop
import top.wanxiang.app.ui.theme.LocalLiquidGlassSurfaceBackdrop
import kotlin.time.Duration.Companion.milliseconds

/**
 * 万象 (WanXiang) 四大核心中枢导航定义：
 * 万象（开辟画布）· 智枢（AI 结对）· 工坊（工作区）· 乾坤（设置与模型）
 */
enum class MainDestination(val labelRes: Int, val subtitleRes: Int, val icon: RuntimeIconName) {
    Home(R.string.components_nav_home, R.string.components_nav_subtitle_home, RuntimeIconName.NavDashboard),
    Agent(R.string.components_nav_agent, R.string.components_nav_subtitle_agent, RuntimeIconName.NavMessage),
    Workspace(R.string.components_nav_workspace, R.string.components_nav_subtitle_workspace, RuntimeIconName.NavRepository),
    Settings(R.string.components_nav_settings, R.string.components_nav_subtitle_settings, RuntimeIconName.NavSettings),
}

/**
 * 万象 · 核心底部中枢导航栏。
 * 默认（玄同主题）为 Material 3 Native NavigationBar；澄明（液态玻璃）主题下渲染为
 * 悬浮磨砂玻璃胶囊：半透明毛玻璃折射底层流光 + 圆角药丸 + 玻璃质感选中项。
 */
@Composable
fun RuntimeBottomBar(
    selected: MainDestination,
    onNavigate: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    if (backdrop != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            LiquidGlassBottomBar(
                selected = selected,
                onNavigate = onNavigate,
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .height(64.dp),
            )
        }
    } else {
        StandardBottomBar(selected, onNavigate, modifier)
    }
}

@Composable
private fun StandardBottomBar(
    selected: MainDestination,
    onNavigate: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        MainDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNavigate(destination)
                },
                icon = { RuntimeIcon(destination.icon, Modifier.size(24.dp)) },
                label = {
                    Text(
                        text = stringResource(destination.labelRes),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * 液态玻璃悬浮底部导航：磨砂玻璃药丸折射底层流光 + 选中项玻璃高亮。
 */
@Composable
private fun LiquidGlassBottomBar(
    selected: MainDestination,
    onNavigate: (MainDestination) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val destinations = MainDestination.entries
    val capsuleShape = RoundedCornerShape(percent = 50)
    val isLightTheme = MaterialTheme.colorScheme.onSurface.luminance() < 0.5f
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(alpha = 0.4f)
    else Color(0xFF121212).copy(alpha = 0.4f)
    val animationScope = rememberCoroutineScope()
    val indicatorAnimation = remember { Animatable(selected.ordinal.toFloat(), 0.001f) }
    val pressAnimation = remember { Animatable(0f, 0.001f) }
    val scaleXAnimation = remember { Animatable(1f, 0.001f) }
    val scaleYAnimation = remember { Animatable(1f, 0.001f) }
    val offsetAnimation = remember { Animatable(0f) }
    var isInteracting by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(selected.ordinal) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val baseContentColor = if (isLightTheme) Color.Black else Color.White
    val currentSelected = rememberUpdatedState(selected)
    fun press() {
        animationScope.launch {
            launch { pressAnimation.animateTo(1f, spring(1f, 1000f, 0.001f)) }
            launch { scaleXAnimation.animateTo(78f / 56f, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnimation.animateTo(78f / 56f, spring(0.7f, 250f, 0.001f)) }
        }
    }
    fun release() {
        animationScope.launch {
            launch { pressAnimation.animateTo(0f, spring(1f, 1000f, 0.001f)) }
            launch { scaleXAnimation.animateTo(1f, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnimation.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
        }
    }
    suspend fun releaseAndAwait() = coroutineScope {
        launch { pressAnimation.animateTo(0f, spring(1f, 1000f, 0.001f)) }
        launch { scaleXAnimation.animateTo(1f, spring(0.6f, 250f, 0.001f)) }
        launch { scaleYAnimation.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
    }
    suspend fun releaseNear(target: Float) {
        val threshold = destinations.lastIndex * 0.025f
        if (abs(indicatorAnimation.value - target) >= threshold) {
            snapshotFlow { abs(indicatorAnimation.value - target) < threshold }.first { it }
        }
        releaseAndAwait()
    }
    fun animateTo(index: Int, notify: Boolean) {
        if (index == selected.ordinal && !isInteracting) return
        animationJob?.cancel()
        isDragging = false
        isInteracting = true
        if (notify && destinations[index] != selected) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onNavigate(destinations[index])
        }
        animationJob = animationScope.launch {
            press()
            delay(70.milliseconds)
            indicatorAnimation.animateTo(index.toFloat(), spring(1f, 1000f, 0.001f))
            releaseAndAwait()
            isInteracting = false
        }
    }
    LaunchedEffect(selected) {
        if (!isDragging && !isInteracting) {
            indicatorAnimation.animateTo(selected.ordinal.toFloat(), spring(1f, 1000f, 0.001f))
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8.dp.toPx()) / destinations.size }
        val panelOffset by remember(offsetAnimation.value, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        val trackModifier = Modifier
            .graphicsLayer { translationX = panelOffset }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { capsuleShape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                layerBlock = {
                    // Deform only the glass track. Scaling the Row itself also scales the
                    // unselected icons/text, while the tinted capture layer stays in place.
                    val width = size.width.coerceAtLeast(1f)
                    val scale = lerp(1f, 1f + 16.dp.toPx() / width, pressAnimation.value)
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = { drawRect(containerColor) },
            )
            .height(64.dp).fillMaxWidth().padding(4.dp)
        Box(trackModifier)

        val indicatorTransform = Modifier
            .align(Alignment.CenterStart)
            .padding(horizontal = 4.dp)
            .graphicsLayer {
                translationX = indicatorAnimation.value * tabWidth + panelOffset
                scaleX = scaleXAnimation.value
                scaleY = scaleYAnimation.value
                val velocity = indicatorAnimation.velocity / 10f
                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
            }

        // The glass pill is below the only copy of the navigation content. It may blur the
        // scene behind the bar, but can never cover or duplicate an icon/label.
        Box(
            indicatorTransform
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { capsuleShape },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(
                            10.dp.toPx() * pressAnimation.value,
                            14.dp.toPx() * pressAnimation.value,
                            chromaticAberration = true,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = pressAnimation.value) },
                    shadow = { Shadow(alpha = pressAnimation.value) },
                    innerShadow = {
                        InnerShadow(
                            radius = 8.dp * pressAnimation.value,
                            alpha = pressAnimation.value,
                        )
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                        drawRect(accentColor.copy(alpha = 0.06f + 0.04f * pressAnimation.value))
                    },
                )
                .height(56.dp)
                .fillMaxWidth(1f / destinations.size),
        )

        Row(
            Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = panelOffset }
                .height(56.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                destinations.forEachIndexed { index, destination ->
                    Column(
                        Modifier
                            .clip(capsuleShape)
                            .fillMaxHeight()
                            .weight(1f)
                            .graphicsLayer {
                                // Draw each icon and label exactly once. Tint follows the moving
                                // indicator on the render layer, avoiding per-frame recomposition
                                // and the coordinate drift caused by a second captured backdrop.
                                val proximity = (1f - abs(indicatorAnimation.value - index))
                                    .fastCoerceIn(0f, 1f)
                                colorFilter = ColorFilter.tint(
                                    lerpColor(baseContentColor, accentColor, proximity),
                                )
                            }
                            .clickable(
                                interactionSource = null,
                                indication = null,
                            ) { animateTo(destination.ordinal, true) },
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RuntimeIcon(destination.icon, Modifier.size(22.dp), tint = baseContentColor)
                        Text(stringResource(destination.labelRes), color = baseContentColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
        }

        // A transparent hit target stays above the content. The gesture inspector does not
        // consume taps, so the tab click targets below continue to receive normal presses.
        Box(
            indicatorTransform
                .height(56.dp)
                .fillMaxWidth(1f / destinations.size)
                .pointerInput(tabWidth) {
                        inspectDragGestures(
                            onDragStart = { _ ->
                                animationJob?.cancel()
                                isDragging = true
                                isInteracting = true
                                dragStartIndex = currentSelected.value.ordinal
                                dragDistancePx = 0f
                                animationScope.launch {
                                    indicatorAnimation.snapTo(dragStartIndex.toFloat())
                                    offsetAnimation.snapTo(0f)
                                    press()
                                }
                            },
                            onDrag = { _, amount ->
                                dragDistancePx += amount.x
                                val next = (dragStartIndex + dragDistancePx / tabWidth)
                                    .coerceIn(0f, destinations.lastIndex.toFloat())
                                animationScope.launch {
                                    indicatorAnimation.snapTo(next)
                                    offsetAnimation.snapTo(dragDistancePx)
                                }
                            },
                            onDragEnd = {
                                val target = (dragStartIndex + dragDistancePx / tabWidth).roundToInt()
                                    .coerceIn(0, destinations.lastIndex)
                            if (destinations[target] != currentSelected.value) {
                                onNavigate(destinations[target])
                            }
                                animationJob?.cancel()
                                animationJob = animationScope.launch {
                                    coroutineScope {
                                        launch { indicatorAnimation.animateTo(target.toFloat(), spring(1f, 1000f, 0.001f)) }
                                        launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                                        launch { releaseNear(target.toFloat()) }
                                    }
                                    isDragging = false
                                    isInteracting = false
                                }
                            },
                            onDragCancel = {
                                if (!isDragging) {
                                    release()
                                } else {
                                    animationJob?.cancel()
                                    animationJob = animationScope.launch {
                                        coroutineScope {
                                            launch { indicatorAnimation.animateTo(currentSelected.value.ordinal.toFloat()) }
                                            launch { offsetAnimation.animateTo(0f) }
                                            launch { releaseAndAwait() }
                                        }
                                        isDragging = false
                                        isInteracting = false
                                    }
                                }
                            },
                        )
                    },
        )
    }
}

/**
 * Mirrors the reference implementation's non-consuming drag inspector. Taps remain available to
 * the Tab click targets underneath the moving glass lens; only actual motion drives the lens.
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (PointerInputChange) -> Unit = {},
    onDragEnd: (PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (PointerInputChange, Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        val down = awaitFirstDown(requireUnconsumed = false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = inspectDragOrUp(initialDown.id) { change ->
            onDrag(change, change.positionChange())
        }
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend inline fun AwaitPointerEventScope.inspectDragOrUp(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitInspectDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitInspectDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) return change
            pointer = otherDown.id
        } else if (change.previousPosition != change.position) {
            return change
        }
    }
}

/** Marks a page-content layer as the safe source for liquid-glass consumers. */
@Composable
fun Modifier.liquidGlassContent(): Modifier {
    val backdrop = LocalLiquidGlassBackdrop.current
    return if (backdrop != null) layerBackdrop(backdrop) else this
}

/** Returns whether the current composition is hosted by the Chengming glass theme. */
@Composable
fun isLiquidGlassThemeActive(): Boolean = LocalLiquidGlassBackdrop.current != null

/**
 * 万象品牌 TopBar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    statusText: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val glassBackdrop = LocalLiquidGlassSurfaceBackdrop.current
    val glassSurfaceColor = MaterialTheme.colorScheme.surface
    val glassShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    val topBar: @Composable () -> Unit = {
        TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onBack == null) WanXiangBrandBadge(30.dp)
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                RuntimeIconButton(onClick = onBack, contentDescription = stringResource(R.string.components_back)) {
                    RuntimeIcon(RuntimeIconName.Back, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (glassBackdrop == null) MaterialTheme.colorScheme.background else Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        )
    }
    if (glassBackdrop == null) {
        Box(modifier = modifier.fillMaxWidth()) { topBar() }
    } else {
        val density = LocalDensity.current
        Box(
            modifier = modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = glassBackdrop,
                    shape = { glassShape },
                    effects = {
                        blur(18.dp.toPx(), edgeTreatment = TileMode.Mirror)
                        lens(10.dp.toPx(), 18.dp.toPx(), chromaticAberration = false)
                        colorControls(brightness = 0.02f, contrast = 1.06f, saturation = 1.04f)
                    },
                    onDrawSurface = {
                        drawRoundRect(glassSurfaceColor.copy(alpha = 0.36f))
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.14f),
                            cornerRadius = CornerRadius(with(density) { 24.dp.toPx() }),
                            style = Stroke(width = with(density) { 1.dp.toPx() }),
                        )
                    },
                ),
        ) { topBar() }
    }
}

/**
 * 万象品牌标志徽章
 */
@Composable
fun WanXiangBrandBadge(size: Dp = 38.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(Color.White)
            .padding(size * 0.10f),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.components_wanxiang_logo),
            contentDescription = stringResource(R.string.components_wanxiang_logo),
            modifier = Modifier.size(size * 0.80f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 万象精制卡片组件：支持自适应表面色与细腻描边
 */
@Composable
fun RuntimeCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glassBackdrop = LocalLiquidGlassSurfaceBackdrop.current
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = borderColor.takeIf { it.alpha > 0f }?.let { BorderStroke(1.dp, it) }
    val shape = RoundedCornerShape(16.dp)

    if (glassBackdrop != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.985f else 1f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "glassCardScale",
        )
        val density = LocalDensity.current
        val glassModifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBackdrop(
                backdrop = glassBackdrop,
                shape = { shape },
                effects = {
                    blur(16.dp.toPx(), edgeTreatment = TileMode.Mirror)
                    lens(8.dp.toPx(), 14.dp.toPx(), chromaticAberration = false)
                    colorControls(brightness = 0.015f, contrast = 1.07f, saturation = 1.05f)
                },
                shadow = { Shadow(radius = 10.dp, alpha = 0.10f) },
                innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.08f) },
                onDrawSurface = {
                    drawRoundRect(containerColor.copy(alpha = minOf(containerColor.alpha, 0.42f)))
                    drawRoundRect(
                        color = if (borderColor.alpha > 0f) borderColor.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.16f),
                        cornerRadius = CornerRadius(with(density) { 16.dp.toPx() }),
                        style = Stroke(width = with(density) { 1.dp.toPx() }),
                    )
                },
            )

            .clip(shape)
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            )
        Column(glassModifier.padding(contentPadding), content = content)
    } else if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * 主题自适应主操作按钮。玄同使用 Material 3；澄明使用参考 Glass 项目的胶囊折射、
 * 按压缩放与半透明染色。
 */
@Composable
fun RuntimeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        if (tonal) {
            FilledTonalButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
                border = border,
                contentPadding = contentPadding,
                content = content,
            )
        } else {
            Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors = colors ?: ButtonDefaults.buttonColors(),
                border = border,
                contentPadding = contentPadding,
                content = content,
            )
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.045f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "glassButtonScale",
    )
    val buttonColors = colors ?: if (tonal) {
        ButtonDefaults.filledTonalButtonColors()
    } else {
        ButtonDefaults.buttonColors()
    }
    val foreground = if (enabled) buttonColors.contentColor else buttonColors.disabledContentColor
    val tint = if (enabled) buttonColors.containerColor else buttonColors.disabledContainerColor
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.48f
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(8.dp.toPx(), edgeTreatment = TileMode.Mirror)
                    lens(12.dp.toPx(), 20.dp.toPx(), chromaticAberration = false)
                    colorControls(brightness = 0.02f, contrast = 1.08f, saturation = 1.10f)
                },
                highlight = { Highlight.Default.copy(alpha = if (pressed) 0.75f else 0.28f) },
                shadow = { Shadow(radius = 8.dp, alpha = if (pressed) 0.16f else 0.10f) },
                innerShadow = { InnerShadow(radius = 4.dp, alpha = if (pressed) 0.16f else 0.08f) },
                onDrawSurface = {
                    drawRoundRect(tint.copy(alpha = if (tonal) 0.42f else 0.72f))
                },
            )
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .height(48.dp)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

/** Glass 风格开关；在玄同主题下保留 Material 3 Switch 的调用方可继续自行使用。 */
@Composable
fun RuntimeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    thumbContent: (@Composable (() -> Unit))? = null,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            thumbContent = thumbContent,
        )
        return
    }
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 28.dp else 2.dp,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "glassSwitchOffset",
    )
    val shape = RoundedCornerShape(18.dp)
    val trackColor = when {
        enabled && checked -> colors.checkedTrackColor
        enabled -> colors.uncheckedTrackColor
        checked -> colors.disabledCheckedTrackColor
        else -> colors.disabledUncheckedTrackColor
    }
    val thumbColor = when {
        enabled && checked -> colors.checkedThumbColor
        enabled -> colors.uncheckedThumbColor
        checked -> colors.disabledCheckedThumbColor
        else -> colors.disabledUncheckedThumbColor
    }
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f }
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(6.dp.toPx(), edgeTreatment = TileMode.Mirror)
                    lens(7.dp.toPx(), 10.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.18f) },
                onDrawSurface = {
                    drawRoundRect(
                        trackColor.copy(alpha = if (trackColor.alpha == 1f) 0.72f else trackColor.alpha),
                    )
                },
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .size(width = 58.dp, height = 32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(28.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        blur(6.dp.toPx(), edgeTreatment = TileMode.Mirror)
                        lens(6.dp.toPx(), 10.dp.toPx(), chromaticAberration = false)
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.46f) },
                    shadow = { Shadow(radius = 4.dp, alpha = 0.16f) },
                    onDrawSurface = { drawCircle(thumbColor.copy(alpha = if (thumbColor.alpha == 1f) 0.86f else thumbColor.alpha)) },
                ),
        ) {
            thumbContent?.invoke()
        }
    }
}

@Composable
fun RuntimeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, border = border, contentPadding = contentPadding, content = content)
    } else {
        RuntimeButton(onClick, modifier, enabled, tonal = true, contentPadding = contentPadding, shape = shape, colors = colors, border = border, content = content)
    }
}

@Composable
fun RuntimeFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) = RuntimeButton(onClick, modifier, enabled, tonal = true, contentPadding = contentPadding, shape = shape, colors = colors, border = border, content = content)

@Composable
fun RuntimeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val foreground = if (enabled) colors.contentColor else colors.disabledContentColor
        Row(
            modifier = modifier
                .graphicsLayer {
                    scaleX = if (pressed) 1.04f else 1f
                    scaleY = if (pressed) 1.04f else 1f
                    alpha = if (enabled) 1f else 0.48f
                }
                .clip(shape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = { blur(4.dp.toPx()); lens(5.dp.toPx(), 7.dp.toPx()) },
                    highlight = { Highlight.Default.copy(alpha = if (pressed) 0.34f else 0.14f) },
                    onDrawSurface = { drawRoundRect(colors.containerColor.copy(alpha = 0.16f)) },
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides foreground) {
                content()
            }
        }
    }
}

@Composable
fun RuntimeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    val accessibilityLabel = contentDescription
    val contentColor = MaterialTheme.colorScheme.onSurface
    val glassSurfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
    if (backdrop == null) {
        // Material fallback：将无障碍标签挂到按钮节点上（Icon 自身 contentDescription 恒为 null）
        IconButton(
            onClick = onClick,
            modifier = modifier.semantics { accessibilityLabel?.let { this.contentDescription = it } },
            enabled = enabled,
            content = content,
        )
    } else {
        Box(
            modifier = modifier
                // 玻璃主题分支同样保证 ≥48dp 最小触摸目标（Material 分支由 IconButton 内部保证）
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .clip(CircleShape)
                .drawBackdrop(backdrop, { CircleShape }, effects = { blur(6.dp.toPx()); lens(7.dp.toPx(), 9.dp.toPx()) }, highlight = { Highlight.Default.copy(alpha = 0.28f) }, onDrawSurface = { drawCircle(glassSurfaceColor) })
                .semantics {
                    accessibilityLabel?.let { this.contentDescription = it }
                }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { CompositionLocalProvider(LocalContentColor provides contentColor, content = content) }
    }
}

/** Theme-aware progress indicators. The Material fallback keeps existing sizing and colors. */
@Composable
fun RuntimeLinearProgressIndicator(
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        if (progress == null) androidx.compose.material3.LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
        else androidx.compose.material3.LinearProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
        return
    }
    val fraction = if (progress != null) {
        progress().coerceIn(0f, 1f)
    } else {
        val indeterminateTransition = rememberInfiniteTransition(label = "glassLinearProgress")
        val indeterminateFraction by indeterminateTransition.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.82f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glassIndeterminateFraction",
        )
        indeterminateFraction
    }
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { blur(6.dp.toPx(), edgeTreatment = TileMode.Mirror); lens(6.dp.toPx(), 8.dp.toPx()) },
                onDrawSurface = { drawRoundRect(trackColor.copy(alpha = 0.38f)) },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(shape)
                .background(color.copy(alpha = 0.82f))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = { lens(4.dp.toPx(), 6.dp.toPx(), chromaticAberration = true) },
                    highlight = { Highlight.Default.copy(alpha = 0.28f) },
                    onDrawSurface = { drawRoundRect(color.copy(alpha = 0.32f)) },
                ),
        )
    }
}

@Composable
fun RuntimeCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent,
    strokeWidth: Dp = 4.dp,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            strokeWidth = strokeWidth,
        )
        return
    }
    val infinite = rememberInfiniteTransition(label = "glassCircularProgress")
    val rotation by infinite.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "glassCircularRotation",
    )
    Canvas(modifier.then(Modifier.size(40.dp))) {
        val stroke = strokeWidth.toPx()
        drawArc(trackColor.copy(alpha = 0.35f), 0f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        val sweep = 115f
        drawArc(color.copy(alpha = 0.92f), rotation, sweep, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun RuntimeCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, colors = colors)
        return
    }
    val primary = when {
        enabled && checked -> colors.checkedBoxColor
        enabled -> colors.uncheckedBorderColor
        checked -> colors.disabledCheckedBoxColor
        else -> colors.disabledUncheckedBorderColor
    }
    val checkmark = if (enabled) colors.checkedCheckmarkColor else colors.disabledCheckedBoxColor
    Box(
        modifier
            .size(24.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(7.dp))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(7.dp) },
                effects = { blur(5.dp.toPx()); lens(5.dp.toPx(), 7.dp.toPx()) },
                highlight = { Highlight.Default.copy(alpha = 0.34f) },
                onDrawSurface = { drawRoundRect(primary.copy(alpha = if (checked) 0.85f else 0.12f)); if (!checked) drawRoundRect(primary.copy(alpha = 0.6f), style = Stroke(1.dp.toPx())) },
            )
            .clickable(enabled = enabled && onCheckedChange != null) { onCheckedChange?.invoke(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = checkmark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RuntimeRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled, colors = colors)
        return
    }
    val primary = when {
        enabled && selected -> colors.selectedColor
        enabled -> colors.unselectedColor
        selected -> colors.disabledSelectedColor
        else -> colors.disabledUnselectedColor
    }
    Box(
        modifier
            .size(24.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(CircleShape)
            .drawBackdrop(backdrop = backdrop, shape = { CircleShape }, effects = { blur(5.dp.toPx()); lens(5.dp.toPx(), 7.dp.toPx()) }, highlight = { Highlight.Default.copy(alpha = 0.3f) }, onDrawSurface = { drawCircle(primary.copy(alpha = 0.1f)); drawCircle(primary.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx())) })
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(11.dp).background(primary, CircleShape))
    }
}

@Composable
fun RuntimeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        androidx.compose.material3.Slider(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished, colors = colors)
        return
    }
    val rangeSize = valueRange.endInclusive - valueRange.start
    fun valueAt(fraction: Float): Float {
        val raw = valueRange.start + fraction.coerceIn(0f, 1f) * rangeSize
        if (steps == 0) return raw
        val stepSize = rangeSize / (steps + 1)
        return (valueRange.start + ((raw - valueRange.start) / stepSize).roundToInt() * stepSize)
            .coerceIn(valueRange.start, valueRange.endInclusive)
    }
    val fraction = ((value - valueRange.start) / rangeSize).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(percent = 50)
    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onValueChange(valueAt(down.position.x / size.width))
                    var pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            onValueChangeFinished?.invoke()
                            break
                        }
                        if (change.positionChange() != Offset.Zero) {
                            onValueChange(valueAt(change.position.x / size.width))
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val track = maxOf(1.dp, maxHeight / 8)
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(track).clip(shape).drawBackdrop(backdrop = backdrop, shape = { shape }, effects = { blur(5.dp.toPx()); lens(5.dp.toPx(), 7.dp.toPx()) }, onDrawSurface = { drawRoundRect(colors.inactiveTrackColor.copy(alpha = 0.22f)) }))
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(track).clip(shape).background(colors.activeTrackColor.copy(alpha = 0.85f)))
        Box(Modifier.align(Alignment.CenterStart).padding(start = (maxWidth - 20.dp) * fraction).size(20.dp).drawBackdrop(backdrop = backdrop, shape = { CircleShape }, effects = { blur(5.dp.toPx()); lens(5.dp.toPx(), 7.dp.toPx(), chromaticAberration = true) }, highlight = { Highlight.Default.copy(alpha = 0.45f) }, onDrawSurface = { drawCircle(colors.thumbColor.copy(alpha = 0.9f)) }))
    }
}

/** Alert surface that stays Material in Xuantong and becomes a backdrop lens in Chengming. */
@Composable
fun RuntimeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val backdrop = LocalLiquidGlassSurfaceBackdrop.current
    if (backdrop == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
            icon = icon,
        )
        return
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
        val shape = RoundedCornerShape(28.dp)
        val dialogSurfaceColor = MaterialTheme.colorScheme.surface
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .wrapContentHeight(Alignment.Top)
                .imePadding()
                .heightIn(max = 560.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(16.dp.toPx(), edgeTreatment = TileMode.Mirror)
                        lens(24.dp.toPx(), 48.dp.toPx(), chromaticAberration = true)
                    },
                    highlight = { Highlight.Plain },
                    shadow = { Shadow(radius = 18.dp, alpha = 0.18f) },
                    innerShadow = { InnerShadow(radius = 8.dp, alpha = 0.10f) },
                    onDrawSurface = {
                        drawRoundRect(dialogSurfaceColor.copy(alpha = 0.58f))
                        drawRoundRect(
                            Color.White.copy(alpha = 0.22f),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    },
                )
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null || title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    icon?.invoke()
                    title?.invoke()
                }
            }
            Box(modifier = Modifier.weight(1f, fill = false)) {
                text?.invoke()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/**
 * 呼吸状态指示药丸
 */
@Composable
fun StatusBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val alphaAnim = if (pulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        ).value
    } else 1f

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alphaAnim)),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = color,
            )
        }
    }
}

@Composable
fun IconTile(
    icon: RuntimeIconName,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 42.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        RuntimeIcon(icon, Modifier.size(size * 0.48f), tint = color)
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isCode: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.62f, fill = false),
        )
    }
}

@Composable
fun CodeBlockRow(
    label: String,
    code: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun EmptyPanel(
    icon: RuntimeIconName,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    RuntimeCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconTile(icon, color = MaterialTheme.colorScheme.primary, size = 48.dp)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuntimeIcon(if (isError) RuntimeIconName.Alert else RuntimeIconName.Check, Modifier.size(17.dp), color)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            RuntimeIconButton(
                onClick = onCopy,
                modifier = Modifier.size(24.dp),
                contentDescription = "复制",
            ) {
                RuntimeIcon(RuntimeIconName.Copy, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDismiss != null) {
            val dismissLabel = stringResource(R.string.components_close)
            RuntimeIconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp),
                contentDescription = dismissLabel,
            ) {
                RuntimeIcon(RuntimeIconName.Close, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 🌟 滚动列表平滑渐隐遮罩 (Scroll Fading Edge Mask)
 * 为列表顶部和底部提供平滑的 Alpha 渐隐过渡，消除与 TopBar / BottomBar 之间的生硬切边。
 */
fun Modifier.scrollFadingEdge(
    top: Dp = 14.dp,
    bottom: Dp = 14.dp,
): Modifier = this.graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val topPx = top.toPx()
    val bottomPx = bottom.toPx()
    val h = size.height

    if (topPx > 0f && h > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = topPx.coerceAtMost(h),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
    if (bottomPx > 0f && h > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = (h - bottomPx).coerceAtLeast(0f),
                endY = h,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}
