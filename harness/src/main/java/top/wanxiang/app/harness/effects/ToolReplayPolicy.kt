package top.wanxiang.app.harness.effects

import top.wanxiang.app.harness.HarnessTool
import top.wanxiang.app.harness.operation.ReplayPolicy

/** Recovery contract for external tool effects. */
object ToolReplayPolicy {
    fun forTool(tool: HarnessTool, rawToolName: String? = null): ReplayPolicy = when {
        rawToolName?.startsWith("mcp__") == true -> ReplayPolicy.NEVER
        tool in SAFE_TO_REPLAY -> ReplayPolicy.SAFE
        else -> ReplayPolicy.NEVER
    }

    private val SAFE_TO_REPLAY = setOf(
        HarnessTool.READ,
        HarnessTool.HISTORY_SEARCH,
        HarnessTool.HISTORY_READ,
    )
}
