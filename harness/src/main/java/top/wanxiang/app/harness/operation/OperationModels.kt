package top.wanxiang.app.harness.operation

import kotlinx.serialization.Serializable

enum class OperationKind(val id: String) {
    RUN("run"), COMPACTION("compaction"), NAVIGATION("navigation")
}

enum class OperationStatus(val id: String) {
    RUNNING("running"), WAITING_APPROVAL("waiting_approval"), SUSPENDED("suspended"), ABORTING("aborting")
}

enum class OperationPhase(val id: String) {
    CHECKPOINT("checkpoint"),
    PROVIDER_INTENT("provider_intent"),
    PROVIDER_SETTLED("provider_settled"),
    TOOL_INTENT("tool_intent"),
    TOOL_SETTLED("tool_settled"),
    WAITING_APPROVAL("waiting_approval"),
}

enum class ReplayPolicy(val id: String) {
    SAFE("safe"), NEVER("never")
}

@Serializable
data class OperationSnapshot(
    val phase: String,
    val round: Int = 0,
    val effectKind: String? = null,
    val effectId: String? = null,
    val effectPayloadJson: String? = null,
    val replayPolicy: String? = null,
    val reservedEntryId: String? = null,
    val attempt: Int = 0,
    val maxAttempts: Int = 1,
    val lastError: String? = null,
)
