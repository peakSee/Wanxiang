package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

enum class DoctorStatus {
    HEALTHY,
    WARNING,
    ERROR,
    CHECKING,

    /** 探测拿不到结果（沙箱繁忙/超时）：如实标注，绝不按"异常"误报吓用户。 */
    UNKNOWN,
}

enum class DoctorCategory(val displayName: String) {
    SANDBOX("沙箱与存储"),
    NETWORK_SSL("网络与安全"),
    PACKAGE_MANAGER("包管理与源"),
    DEV_RUNTIMES("核心开发环境"),
}

@Serializable
data class DoctorItem(
    val id: String,
    val category: DoctorCategory,
    val title: String,
    val status: DoctorStatus,
    val summary: String,
    val detail: String? = null,
    val fixable: Boolean = true,
)

@Serializable
data class DoctorReport(
    val items: List<DoctorItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val overallStatus: DoctorStatus = DoctorStatus.HEALTHY,
    val healthyCount: Int = 0,
    val warningCount: Int = 0,
    val errorCount: Int = 0,
) {
    val isAllHealthy: Boolean get() = overallStatus == DoctorStatus.HEALTHY && items.isNotEmpty()
    val needsFix: Boolean get() = warningCount > 0 || errorCount > 0
}

@Serializable
data class RepairProgress(
    val stepTitle: String,
    val stepIndex: Int,
    val totalSteps: Int = 5,
    val progress: Float = 0.0f,
    val logs: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
)
