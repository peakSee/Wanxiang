package top.wanxiang.app.ui.workspace

import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import top.wanxiang.app.feature.workspace.R
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.wanxiang.app.core.tools.ToolNotificationNotifier
import top.wanxiang.app.runtime.WorkspaceProject
import top.wanxiang.app.runtime.BackgroundTaskRegistry
import top.wanxiang.app.runtime.build.BuildRunProgress
import top.wanxiang.app.runtime.build.WorkspaceBuildRunner

data class WorkspaceBuildTaskState(
    val project: WorkspaceProject,
    val progress: BuildRunProgress,
)

/** Keeps a workspace build alive while the workspace destination is recreated. */
@Singleton
class WorkspaceBuildTaskCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: WorkspaceBuildRunner,
    private val notifier: ToolNotificationNotifier,
    private val backgroundTaskRegistry: BackgroundTaskRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<WorkspaceBuildTaskState?>(null)
    val state: StateFlow<WorkspaceBuildTaskState?> = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(
        project: WorkspaceProject,
        buildType: top.wanxiang.app.runtime.build.WorkshopBuildType = top.wanxiang.app.runtime.build.WorkshopBuildType.DEBUG,
        keystore: top.wanxiang.app.core.datastore.WorkshopKeystore? = null,
    ): Boolean {
        if (job?.isActive == true || _state.value?.progress?.isRunning == true) return false
        val initial = BuildRunProgress(step = context.getString(R.string.workspace_prepare_build))
        _state.value = WorkspaceBuildTaskState(project, initial)
        backgroundTaskRegistry.start(BUILD_TASK_ID)
        notifier.showBuildProgress(project.name, initial.step)
        job = scope.launch {
            try {
                // 终态保护：终态（isRunning=false）一旦到达，后续任何 isRunning=true
                // 的消息都是心跳协程在 cancel 前挤进通道的孤儿，直接丢弃——绝不允许
                // 把已呈现的“运行就绪/编译失败”覆盖回“正在构建”转圈态。
                var finished = false
                runner.runProject(project, buildType, keystore).collect { progress ->
                    if (finished && progress.isRunning) return@collect
                    if (!progress.isRunning) finished = true
                    _state.value = WorkspaceBuildTaskState(project, progress)
                    if (progress.isRunning) {
                        notifier.showBuildProgress(project.name, progress.step)
                    } else {
                        if (progress.isSuccess == true) {
                            notifier.showBuildSuccess(project.name, progress.apkPath)
                        } else {
                            notifier.showBuildFailed(project.name, progress.message ?: context.getString(R.string.workspace_unknown_error))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failed = BuildRunProgress(
                    step = context.getString(R.string.workspace_build_interrupted),
                    isRunning = false,
                    isSuccess = false,
                    message = error.message ?: context.getString(R.string.workspace_build_exception),
                )
                _state.value = WorkspaceBuildTaskState(project, failed)
                notifier.showBuildFailed(project.name, failed.message ?: context.getString(R.string.workspace_unknown_exception))
            } finally {
                backgroundTaskRegistry.finish(BUILD_TASK_ID)
                job = null
            }
        }
        return true
    }

    @Synchronized
    fun cancel() {
        val current = _state.value ?: return
        job?.cancel()
        job = null
        val stopped = BuildRunProgress(
            step = context.getString(R.string.workspace_build_stopped),
            isRunning = false,
            isSuccess = false,
            message = context.getString(R.string.workspace_build_stopped_message),
            logOutput = current.progress.logOutput,
        )
        _state.value = current.copy(progress = stopped)
        notifier.showBuildFailed(current.project.name, stopped.message ?: context.getString(R.string.workspace_build_stopped_short))
    }

    fun dismiss() {
        if (_state.value?.progress?.isRunning != true) _state.value = null
    }

    fun launchPackageInstaller(apkPath: String) {
        runner.launchPackageInstaller(java.io.File(apkPath))
    }

    private companion object {
        const val BUILD_TASK_ID = "workspace-build"
    }
}
