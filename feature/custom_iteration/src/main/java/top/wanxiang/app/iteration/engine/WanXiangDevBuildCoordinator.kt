package top.wanxiang.app.iteration.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * WanXiangDevBuildCoordinator — 调度 GitHub Actions 云端构建与本地产物校验。
 */
object WanXiangDevBuildCoordinator {

    sealed interface BuildStatus {
        data object Idle : BuildStatus
        data class Dispathing(val branch: String) : BuildStatus
        data class Running(val runId: String, val statusText: String) : BuildStatus
        data class Downloading(val runId: String, val progress: Int) : BuildStatus
        data class Success(val apkPath: String, val sha256: String) : BuildStatus
        data class Failed(val error: String, val logs: String? = null) : BuildStatus
    }

    /**
     * 构建相关的核心 CLI 指令模板
     */
    object CliCommands {
        fun triggerWorkflow(workflowName: String = "wanxiangdev-build.yml", branch: String = "main"): String =
            "gh workflow run $workflowName --ref $branch"

        fun watchWorkflow(runId: String): String =
            "gh run watch $runId --exit-status"

        fun downloadArtifact(runId: String, outputDir: String = "/storage/emulated/0/Download"): String =
            "gh run download $runId -n wanxiangdev-apk -D $outputDir"

        fun checkFailedLog(runId: String): String =
            "gh run view $runId --log-failed"
    }
}
