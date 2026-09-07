package top.wanxiang.app.ui.browser.snapshot

import top.wanxiang.app.core.browser.PageSnapshot

data class SnapshotSheetState(
    val url: String,
    val title: String,
    val snapshot: PageSnapshot,
)
