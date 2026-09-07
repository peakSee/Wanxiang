package top.wanxiang.app.core.browser

import kotlinx.serialization.Serializable

@Serializable
data class SnapshotRef(
    val ref: String,
    val tag: String,
    val type: String? = null,
    val role: String? = null,
    val name: String? = null,
    val text: String? = null,
    val placeholder: String? = null,
    val ariaLabel: String? = null,
    val interactive: Boolean = true
)

@Serializable
data class PageSnapshot(
    val tabId: String,
    val url: String,
    val title: String,
    val refs: Map<String, SnapshotRef>,
    val domFingerprint: String,
    val createdAt: Long
) {
    val interactiveRefs: List<SnapshotRef> get() = refs.values.filter { it.interactive }
    fun refOf(ref: String): SnapshotRef? = refs[ref]
}
