package top.wanxiang.app.ui.settings

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wanxiang.app.core.database.AndroidAppEntity
import top.wanxiang.app.ui.components.RuntimeButton
import top.wanxiang.app.ui.components.RuntimeCard
import top.wanxiang.app.ui.components.RuntimeCircularProgressIndicator
import top.wanxiang.app.ui.components.RuntimeIcon
import top.wanxiang.app.ui.components.RuntimeIconName
import top.wanxiang.app.ui.components.RuntimeTopBar
import top.wanxiang.app.ui.settings.LocalizedText as Text

@Composable
fun AppManagementScreen(
    onBack: () -> Unit,
    viewModel: AppManagementViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageIsError by viewModel.messageIsError.collectAsStateWithLifecycle()
    var showSystemApps by remember { mutableStateOf(false) }

    // Every entry reconciles the live PackageManager inventory with Room: missing packages are
    // removed and newly installed ones are inserted, without rebuilding the UI's data source.
    LaunchedEffect(Unit) { viewModel.synchronize() }
    val userApps = apps.filterNot { it.isSystemApp }
    val systemApps = apps.filter { it.isSystemApp }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("应用管理", onBack, statusText = if (syncing) "正在同步" else "普通读取 · 特权增强") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RuntimeCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("受控应用索引", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "任何模式均可读取应用并写入 Room；以后进入只增量对比包名。Shizuku/Root 会额外读取冻结和后台联网限制状态，冻结或授权等执行操作在 PRoot 模式会提示权限不足。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RuntimeButton(onClick = viewModel::synchronize, enabled = !syncing) {
                                if (syncing) RuntimeCircularProgressIndicator(Modifier.size(18.dp)) else RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp))
                                Text(if (syncing) "同步中" else "立即同步", modifier = Modifier.padding(start = 8.dp))
                            }
                            Text("共 ${apps.size} 个", style = MaterialTheme.typography.labelLarge)
                        }
                        message?.let {
                            Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 纯计数标签：不可交互，避免伪装成可点击的 FilterChip
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                        Text(
                            "用户应用 (${userApps.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    FilterChip(
                        selected = showSystemApps,
                        onClick = { showSystemApps = !showSystemApps },
                        label = { Text("显示系统应用 (${systemApps.size})") },
                    )
                }
            }
            if (userApps.isEmpty() && !syncing) {
                item { Text("暂无应用数据，请执行一次同步。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(userApps, key = { it.packageName }) { app -> AndroidAppRow(app) }
            if (showSystemApps && systemApps.isNotEmpty()) {
                item {
                    Text(
                        "系统应用",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(systemApps, key = { it.packageName }) { app -> AndroidAppRow(app) }
            }
        }
    }
}

@Composable
private fun AndroidAppRow(app: AndroidAppEntity) {
    RuntimeCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            AndroidAppIcon(app.packageName)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (app.isEnabled) "已开启" else "已冻结",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (app.isNetworkRestricted) {
                    // 「已冻结」已由上方状态文案表达，此处只补充联网受限信息，避免重复
                    Text(
                        "联网受限",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidAppIcon(packageName: String) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable == null) {
            RuntimeIcon(RuntimeIconName.Package, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = packageName
                        setImageDrawable(drawable)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { image -> image.setImageDrawable(drawable) },
            )
        }
    }
}
