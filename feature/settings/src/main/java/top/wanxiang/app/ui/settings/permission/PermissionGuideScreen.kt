package top.wanxiang.app.ui.settings.permission

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.settings.successStatusColor
import top.wanxiang.app.ui.settings.warningStatusColor
import top.wanxiang.app.ui.components.RuntimeOutlinedButton
import top.wanxiang.app.ui.components.RuntimeTopBar

@Composable
fun PermissionGuideScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 品牌/主题选择迁移 rememberSaveable，旋转后不重置
    var selectedBrand by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(OemBrand.detect()) }
    var selectedTopic by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(KeepaliveTopic.AUTOSTART) }
    var brandMenuExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    // 状态刷新
    var statusMap by remember { mutableStateOf(mapOf<KeepaliveTopic, Boolean>()) }
    fun refreshStatuses() {
        statusMap = KeepaliveTopic.entries.associateWith { topic ->
            PermissionGuideRepository.checkStatus(context, topic)
        }
    }

    LaunchedEffect(Unit) {
        refreshStatuses()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshStatuses()
    }

    val steps = remember(selectedBrand, selectedTopic) {
        PermissionGuideRepository.getSteps(selectedBrand, selectedTopic)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "后台保活与权限向导",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 顶部当前品牌选择与提示卡片
            item {
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "当前适配厂商机型",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${selectedBrand.label} (${selectedBrand.osName})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Box {
                                RuntimeOutlinedButton(
                                    onClick = { brandMenuExpanded = true },
                                ) {
                                    Text("切换品牌", fontSize = 13.sp)
                                    Spacer(Modifier.width(4.dp))
                                    RuntimeIcon(RuntimeIconName.ChevronDown, modifier = Modifier.size(14.dp))
                                }
                                DropdownMenu(
                                    expanded = brandMenuExpanded,
                                    onDismissRequest = { brandMenuExpanded = false },
                                ) {
                                    OemBrand.entries.forEach { brand ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "${brand.label} (${brand.osName})",
                                                    fontWeight = if (brand == selectedBrand) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                            onClick = {
                                                selectedBrand = brand
                                                brandMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "国产定制系统后台清理机制较为激进，为了保证 PRoot 沙箱、后台编译任务与 Agent 长时间思考不被冻结杀掉，建议根据指引完成配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            // 2. 权限专题选择滑动条 (Horizontal Tabs)
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(KeepaliveTopic.entries) { topic ->
                        val isSelected = topic == selectedTopic
                        val isGranted = statusMap[topic] == true
                        val hasRealStatus = topic == KeepaliveTopic.BATTERY_UNRESTRICTED ||
                            topic == KeepaliveTopic.NOTIFICATION ||
                            topic == KeepaliveTopic.FLOATING_OVERLAY

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedTopic = topic },
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            shape = RoundedCornerShape(20.dp),
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else null,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                RuntimeIcon(
                                    name = topic.icon,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = topic.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                if (hasRealStatus) {
                                    val granted = isGranted
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (granted) successStatusColor() else warningStatusColor())
                                            .semantics {
                                                contentDescription = if (granted) "权限已授权" else "权限未授权"
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. 当前专题说明与直达系统设置入口
            item {
                val isGranted = statusMap[selectedTopic] == true
                val hasRealStatus = selectedTopic == KeepaliveTopic.BATTERY_UNRESTRICTED ||
                    selectedTopic == KeepaliveTopic.NOTIFICATION ||
                    selectedTopic == KeepaliveTopic.FLOATING_OVERLAY

                RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RuntimeIcon(
                                    name = selectedTopic.icon,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = selectedTopic.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                            }

                            if (hasRealStatus) {
                                Surface(
                                    color = if (isGranted) Color(0xFF1B5E20).copy(alpha = 0.15f) else Color(0xFFE65100).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isGranted) Color(0xFF4CAF50).copy(alpha = 0.5f) else Color(0xFFFF9800).copy(alpha = 0.5f),
                                    ),
                                ) {
                                    Text(
                                        text = if (isGranted) "已放行 / 已就绪" else "待配置 / 未豁免",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isGranted) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    )
                                }
                            }
                        }

                        Text(
                            text = selectedTopic.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                        )

                        RuntimeButton(
                            onClick = {
                                launchSettingIntents(context, selectedBrand, selectedTopic)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RuntimeIcon(RuntimeIconName.OpenInNew, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("直达系统对应设置页")
                        }
                    }
                }
            }

            // 4. 分步指引卡片列表
            item {
                Text(
                    text = "配置步骤详解",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            items(steps.indices.toList()) { index ->
                val step = steps[index]
                RuntimeCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // 序号小圆球
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp,
                            )
                            if (step.tip != null) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "💡 ${step.tip}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun launchSettingIntents(context: Context, brand: OemBrand, topic: KeepaliveTopic) {
    val intents = PermissionGuideRepository.buildLaunchIntents(context, brand, topic)
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Throwable) {
            // 继续尝试降级 intent
        }
    }
    Toast.makeText(context, "无法直接跳转，请在系统设置的应用管理中手动配置", Toast.LENGTH_LONG).show()
}
