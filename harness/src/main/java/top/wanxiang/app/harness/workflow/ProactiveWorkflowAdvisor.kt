package top.wanxiang.app.harness.workflow

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import top.wanxiang.app.core.database.WorkflowRepository
import top.wanxiang.app.core.model.workflow.WorkflowDefinition
import top.wanxiang.app.core.model.workflow.WorkflowTrigger

sealed interface WorkflowSignal {
    val timestamp: Long
    val projectName: String
    val workspacePath: String
    val eventType: String
    val description: String
    val variables: Map<String, String>

    data class BuildFailed(
        override val projectName: String,
        override val workspacePath: String,
        val error: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkflowSignal {
        override val eventType = "BUILD_FAILED"
        override val description = error.ifBlank { "项目构建失败，可运行诊断工作流定位环境或依赖问题。" }
        override val variables = mapOf("BUILD_ERROR" to error, "TARGET_WORKSPACE" to workspacePath)
    }

    data class ApkGenerated(
        override val projectName: String,
        override val workspacePath: String,
        val apkPath: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkflowSignal {
        override val eventType = "APK_GENERATED"
        override val description = "已生成 ${apkPath.substringAfterLast('/')}，可以确认后直接安装。"
        override val variables = mapOf("APK_PATH" to apkPath, "TARGET_WORKSPACE" to workspacePath)
    }
}

@Singleton
class WorkflowSignalBus @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<WorkflowSignal>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = mutableEvents.asSharedFlow()

    fun emit(signal: WorkflowSignal) {
        mutableEvents.tryEmit(signal)
    }
}

data class ProactiveWorkflowSuggestion(
    val workflowId: String,
    val title: String,
    val description: String,
    val projectName: String,
    val initialVariables: Map<String, String>,
    val createdAt: Long,
)

@Singleton
class ProactiveWorkflowAdvisor @Inject constructor(
    private val signalBus: WorkflowSignalBus,
    private val workflows: WorkflowRepository,
) {
    fun observeSuggestions(): Flow<ProactiveWorkflowSuggestion> = signalBus.events
        .map { signal ->
            workflows.ensureBuiltins()
            suggestionsFor(signal, workflows.observeDefinitions().first())
        }
        .transform { suggestions -> suggestions.forEach { emit(it) } }
}

internal fun suggestionsFor(
    signal: WorkflowSignal,
    definitions: List<WorkflowDefinition>,
): List<ProactiveWorkflowSuggestion> = definitions.mapNotNull { definition ->
    val trigger = definition.trigger as? WorkflowTrigger.Proactive ?: return@mapNotNull null
    if (!trigger.eventType.equals(signal.eventType, ignoreCase = true)) return@mapNotNull null
    if (!matchesCondition(trigger.conditionRule, signal)) return@mapNotNull null
    ProactiveWorkflowSuggestion(
        workflowId = definition.id,
        title = trigger.suggestionLabel,
        description = signal.description,
        projectName = signal.projectName,
        initialVariables = signal.variables,
        createdAt = signal.timestamp,
    )
}

private fun matchesCondition(rule: String?, signal: WorkflowSignal): Boolean {
    val expression = rule?.trim().orEmpty()
    if (expression.isEmpty()) return true
    val searchable = buildString {
        append(signal.description)
        signal.variables.forEach { (key, value) -> append('\n').append(key).append('=').append(value) }
    }
    return runCatching { Regex(expression, RegexOption.IGNORE_CASE).containsMatchIn(searchable) }.getOrDefault(false)
}
