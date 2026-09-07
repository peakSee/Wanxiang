package top.wanxiang.app.ui.browser.snapshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wanxiang.app.ui.components.RuntimeCard

@Composable
fun SnapshotSheet(state: SnapshotSheetState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snapshot · ${state.title.ifBlank { state.url }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(state.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn {
                    items(state.snapshot.interactiveRefs) { ref ->
                        RuntimeCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            val parts = buildString {
                                append("[${ref.ref}]")
                                append(" <${ref.tag}")
                                ref.type?.let { append(" type='$it'") }
                                append(">")
                                ref.text?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                            }
                            Text(parts, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
