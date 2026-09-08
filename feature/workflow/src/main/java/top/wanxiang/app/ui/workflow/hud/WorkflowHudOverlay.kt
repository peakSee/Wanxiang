package top.wanxiang.app.ui.workflow.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wanxiang.app.runtime.gui.WorkflowGuiHudBridge

@Composable
fun WorkflowHudOverlay(
    session: WorkflowGuiHudBridge.Session,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val phaseColor = when (session.phase) {
        WorkflowGuiHudBridge.Phase.SUCCESS -> MaterialTheme.colorScheme.primary
        WorkflowGuiHudBridge.Phase.FAILED -> MaterialTheme.colorScheme.error
        WorkflowGuiHudBridge.Phase.CANCELLED -> MaterialTheme.colorScheme.outline
        WorkflowGuiHudBridge.Phase.ACTING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val phaseLabel = when (session.phase) {
        WorkflowGuiHudBridge.Phase.THINKING -> "思考中"
        WorkflowGuiHudBridge.Phase.ACTING -> "操作中"
        WorkflowGuiHudBridge.Phase.SUCCESS -> "成功"
        WorkflowGuiHudBridge.Phase.FAILED -> "失败"
        WorkflowGuiHudBridge.Phase.CANCELLED -> "已停止"
        WorkflowGuiHudBridge.Phase.IDLE -> "空闲"
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 340.dp)
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (session.active && session.phase != WorkflowGuiHudBridge.Phase.ACTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = phaseColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = session.workflowName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!session.active) {
                    TextButton(onClick = onDismiss) {
                        Text("×")
                    }
                }
            }

            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelMedium,
                color = phaseColor,
            )

            if (session.nodeTitle.isNotBlank()) {
                Text(
                    text = session.nodeTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = session.stepLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 18.dp),
            )

            if (session.detail.isNotBlank()) {
                Text(
                    text = session.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (session.active) {
                FilledTonalButton(
                    onClick = onStop,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("停止")
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
