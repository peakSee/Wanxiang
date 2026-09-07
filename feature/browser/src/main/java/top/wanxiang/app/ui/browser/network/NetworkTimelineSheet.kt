package top.wanxiang.app.ui.browser.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.runtime.browser.CapturedRequest
import top.wanxiang.app.runtime.browser.hook.HookHitRecord

/**
 * 网络时间线抽屉：请求列表（方法/状态/耗时/URL）+ Hook 命中记录 两个页签；
 * 点击单条请求展开引擎侧完整详情（请求/响应头与 body）。
 */
@Composable
fun NetworkTimelineSheet(
    requests: List<CapturedRequest>,
    hookHits: List<HookHitRecord>,
    detail: String?,
    onLoadDetail: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // 详情视图：非空时替换列表展示（返回列表按钮回到时间线）
    var detailId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (detailId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { detailId = null }) { Text("← 返回") }
                    Text(
                        "请求详情",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            } else {
                Text(
                    "网络时间线",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (detailId != null) {
                    NetworkDetailBody(detail = detail)
                } else {
                    SecondaryTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        divider = {},
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("请求 (${requests.size})", style = MaterialTheme.typography.labelMedium) },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Hook 命中 (${hookHits.size})", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                    if (selectedTab == 0) {
                        RequestList(requests) { id ->
                            detailId = id
                            onLoadDetail(id)
                        }
                    } else {
                        HookHitList(hookHits)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 请求列表：最新在下；空态给引导文案（native 恒在，只有 js 条目才带状态/body）。 */
@Composable
private fun RequestList(requests: List<CapturedRequest>, onClick: (String) -> Unit) {
    if (requests.isEmpty()) {
        Text(
            "暂无捕获记录。\n导航页面后这里会显示资源请求；安装 hook 规则（browser.hook_create）可捕获 fetch/XHR 的状态码与 body。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        items(requests.asReversed(), key = { it.id + it.startedAt }) { req ->
            RequestRow(req = req, onClick = { onClick(req.id) })
        }
    }
}

@Composable
private fun RequestRow(req: CapturedRequest, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                req.method,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = methodColor(req.method),
                modifier = Modifier.widthIn(44.dp),
                maxLines = 1,
            )
            Text(
                statusText(req),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = statusColor(req),
                modifier = Modifier.widthIn(32.dp),
                maxLines = 1,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    req.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        listOfNotNull(
                            req.durationMs.takeIf { it > 0 }?.let { "${it}ms" },
                            req.responseSize.takeIf { it > 0 }?.let { "${formatBytes(it)}" },
                            req.actionTaken.takeIf { it.isNotBlank() },
                            req.source,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 单条请求详情：引擎 networkDetail 输出的等宽文本（含请求/响应头与 body）。 */
@Composable
private fun NetworkDetailBody(detail: String?) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = detail ?: "加载中…",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = Color(0xFFD4D4D4),
            modifier = Modifier
                .padding(10.dp)
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** Hook 命中列表：类型徽标 + 目标 + 摘要（函数/属性/WebSocket/网络规则命中）。 */
@Composable
private fun HookHitList(hits: List<HookHitRecord>) {
    if (hits.isEmpty()) {
        Text(
            "暂无 hook 命中。\n安装 hook 规则（browser.hook_create）后，函数调用、属性访问、WebSocket 与网络规则命中会实时出现在这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        items(hits.asReversed(), key = { it.hookId + it.at.toString() + it.target }) { hit ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                hit.type.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                maxLines = 1,
                            )
                        }
                        Text(
                            hit.phase,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        hit.target,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hit.summary.isNotBlank()) {
                        Text(
                            hit.summary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ===== 颜色与格式化 =====

private fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> Color(0xFF3F8FFF)
    "POST" -> Color(0xFF3FA66F)
    "PUT", "PATCH" -> Color(0xFFC98A2B)
    "DELETE" -> Color(0xFFD9534F)
    else -> Color(0xFF8A8A9A)
}

private fun statusText(req: CapturedRequest): String = when {
    req.statusCode in 200..299 || req.statusCode in 300..399 || req.statusCode >= 400 -> req.statusCode.toString()
    req.source == "native" -> "—"
    else -> "✕"
}

private fun statusColor(req: CapturedRequest): Color = when {
    req.statusCode in 200..299 -> Color(0xFF3FA66F)
    req.statusCode in 300..399 -> Color(0xFFC98A2B)
    req.statusCode >= 400 -> Color(0xFFD9534F)
    req.statusCode == 0 && req.source != "native" -> Color(0xFFD9534F)
    else -> Color(0xFF8A8A9A)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fM".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1fK".format(bytes / 1024f)
    else -> "${bytes}B"
}
