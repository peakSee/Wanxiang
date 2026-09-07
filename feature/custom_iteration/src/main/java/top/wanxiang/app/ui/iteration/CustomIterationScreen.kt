package top.wanxiang.app.ui.iteration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.ui.components.NoticeBanner
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.components.SectionHeader

@Composable
fun CustomIterationScreen(
    onBack: () -> Unit,
    onNavigateToChat: (prefillPrompt: String) -> Unit,
    viewModel: CustomIterationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar(title = "自定义迭代（实验）", onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头部 Hero 卡片
            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "全面自定义专属的 WanXiangDev",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "万象（WanXiang）是一款致力于构建手机端 Linux 运行时与自主 Agent 的开源基础设施。通过「自定义迭代」，你可以在掌中调用万象 AI 修改万象自身、通过云端 GitHub Actions 自动化编译出独立的 WanXiangDev APK，并与社区开发者共同交流贡献。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }

            // 特性清单
            SectionHeader(title = "核心能力矩阵", subtitle = "一次准备，持续共建")

            IterationFeatureItem(
                title = "隔离迭代工作区",
                description = "独立路径 ~/custom_wanxiang，所有修改与宿主环境完全物理隔离。",
                badgeColor = MaterialTheme.colorScheme.primary
            )

            IterationFeatureItem(
                title = "专属 Agent 规范 Skill",
                description = "内置 wanxiang-custom-iteration Skill，严格遵循多模块、Compose 与安全规则。",
                badgeColor = MaterialTheme.colorScheme.secondary
            )

            IterationFeatureItem(
                title = "GitHub Actions 云端 CI 构建",
                description = "免去手机端繁琐编译，自动化生成 WanXiangDev（top.wanxiang.app.dev）独立 APK。",
                badgeColor = MaterialTheme.colorScheme.tertiary
            )

            IterationFeatureItem(
                title = "双包共存与开源 PR 闭环",
                description = "测试版与正式版完美共存，功能验证完毕后一键提交标准 PR 参与社区共建。",
                badgeColor = MaterialTheme.colorScheme.primary
            )

            // 错误提示：可关闭，不再常驻；重试走下方"开启自定义迭代"按钮
            if (uiState.errorMessage != null) {
                NoticeBanner(
                    text = uiState.errorMessage ?: "",
                    isError = true,
                    onDismiss = viewModel::dismissError
                )
            }

            Spacer(Modifier.height(8.dp))

            // 启动按钮
            RuntimeButton(
                onClick = {
                    viewModel.startCustomIteration { prompt ->
                        onNavigateToChat(prompt)
                    }
                },
                enabled = !uiState.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (uiState.isBusy) {
                    RuntimeCircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在准备迭代工作区…")
                } else {
                    Text(
                        text = "开启自定义迭代",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IterationFeatureItem(
    title: String,
    description: String,
    badgeColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
