package top.wanxiang.app.core.tools

import top.wanxiang.app.runtime.tools.InstallEvent
import kotlinx.coroutines.flow.Flow

/** The install contract shared by every manifest-backed tool adapter. */
interface ToolInstallerAdapter {
    val toolId: String
    fun install(): Flow<InstallEvent>
}
