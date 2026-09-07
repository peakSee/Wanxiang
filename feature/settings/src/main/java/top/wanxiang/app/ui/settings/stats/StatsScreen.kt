package top.wanxiang.app.ui.settings.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.core.model.StatsDateRangePreset
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.settings.stats.widgets.StatsHeatmap
import top.wanxiang.app.ui.settings.stats.widgets.StatsMetricGrid
import top.wanxiang.app.ui.settings.stats.widgets.StatsRankSection
import top.wanxiang.app.ui.settings.stats.widgets.StatsSectionCard
import top.wanxiang.app.ui.settings.stats.widgets.StatsUsageChart

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = "数据统计与用量分析",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 顶部时间范围筛选胶囊
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val presets = listOf(
                    StatsDateRangePreset.ALL_TIME to "全部时间",
                    StatsDateRangePreset.LAST_30_DAYS to "近30天",
                    StatsDateRangePreset.PREVIOUS_MONTH to "上个月",
                    StatsDateRangePreset.PREVIOUS_QUARTER to "上季度",
                )

                for ((preset, label) in presets) {
                    val selected = uiState.range.preset == preset
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setDateRangePreset(preset) },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 加载失败：错误态 + 重试入口（复用模块内 RuntimeCard + 按钮模式）
            uiState.error?.let { errorMessage ->
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            FilterChip(
                                selected = false,
                                onClick = viewModel::refresh,
                                label = { Text("重试") },
                            )
                        }
                    }
                }
            }

            // 加载完成但没有任何数据：给出空态而非整页空白
            if (uiState.error == null && !uiState.isLoading && uiState.snapshot?.heatmap.isNullOrEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂无统计数据。开始使用 Agent 对话与工具后，这里会展示活跃度与 Token 用量。",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val snapshot = uiState.snapshot
            if (snapshot != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 1. 活跃度打卡热力图
                    item {
                        StatsSectionCard(title = "活跃度打卡热力图") {
                            StatsHeatmap(days = snapshot.heatmap)
                        }
                    }

                    // 2. 核心指标概览 6 宫格
                    item {
                        StatsSectionCard(title = "核心指标概览") {
                            StatsMetricGrid(summary = snapshot.summary)
                        }
                    }

                    // 3. Token 用量趋势图表
                    item {
                        StatsSectionCard(title = "Token 用量与消耗趋势") {
                            StatsUsageChart(days = snapshot.trend)
                        }
                    }

                    // 4. 模型使用排行
                    item {
                        StatsRankSection(
                            title = "模型使用排行",
                            leftHeader = "模型名称",
                            rightHeader = "调用次数",
                            items = snapshot.modelRank,
                            icon = RuntimeIconName.Brain,
                        )
                    }

                    // 5. 助手 / 工程工作区排行
                    item {
                        StatsRankSection(
                            title = "助手与工程排行",
                            leftHeader = "工作区 / 助手",
                            rightHeader = "使用频次",
                            items = snapshot.assistantRank,
                            icon = RuntimeIconName.Bot,
                        )
                    }

                    // 6. 热门话题排行
                    item {
                        StatsRankSection(
                            title = "热门话题会话排行",
                            leftHeader = "会话标题",
                            rightHeader = "消息条数",
                            items = snapshot.topicRank,
                            icon = RuntimeIconName.Chat,
                        )
                    }
                }
            }
        }
    }
}
